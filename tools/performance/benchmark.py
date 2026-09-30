#!/usr/bin/env python3
"""Open-arrival, real-Agent benchmark. Use only an isolated disposable center."""
import argparse
import concurrent.futures
import datetime as dt
import http.cookiejar
import json
import os
import pathlib
import platform
import random
import shutil
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def environment():
    commands = {'java': ['java', '-version'], 'agent_java': [os.environ.get('BENCH_AGENT_JAVA', 'java'), '-version'],
                'memory': ['sysctl', '-n', 'hw.memsize'], 'cpu': ['sysctl', '-n', 'machdep.cpu.brand_string'],
                'disk': ['df', '-k', '.'], 'network': ['netstat', '-ib']}
    result = {'platform': platform.platform(), 'cpu_count': os.cpu_count(), 'python': platform.python_version()}
    for name, command in commands.items():
        try:
            run = subprocess.run(command, capture_output=True, text=True, timeout=5)
            result[name] = (run.stdout + run.stderr).strip()
        except (OSError, subprocess.TimeoutExpired):
            result[name] = 'unavailable; record manually'
    return result


class Client:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None

    def request(self, path, data=None, token=None):
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if self.csrf:
            headers['X-XSRF-TOKEN'] = self.csrf
        body = None if data is None else json.dumps(data).encode()
        if body is not None:
            headers['Content-Type'] = 'application/json'
        with self.opener.open(urllib.request.Request(self.base + '/api' + path, body, headers), timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def login(self):
        self.csrf = self.request('/auth/csrf')['token']
        self.request('/auth/login', {'username': os.environ['BENCH_USER'], 'password': os.environ['BENCH_PASSWORD']})
        self.csrf = self.request('/auth/csrf')['token']


def percentile(values, p=0.95):
    if not values:
        return None
    return sorted(values)[min(len(values)-1, int(len(values)*p))]


def run(args):
    if not args.isolated:
        raise SystemExit('Require --isolated: run only against an independent test center/database')
    out = pathlib.Path(args.output).resolve()
    if out.exists():
        raise SystemExit('Output must be a new directory; preserve each run independently')
    out.mkdir(parents=True, mode=0o700)
    (out/'environment.json').write_text(json.dumps(environment(), ensure_ascii=False, indent=2))
    client = Client(args.url)
    client.login()
    namespace = 'bench-' + uuid.uuid4().hex[:12]
    agents, sources, handles, processes = [], [], [], []
    rng = random.Random(args.seed)
    expected = {'accesses': 0, 'errors': 0, 'bytes': 0, 'groups': {}}
    metrics = {'query_seconds': [], 'query_failures': 0, 'visibility_seconds': [], 'check_failures': 0, 'queue': [], 'producer_lag_seconds': []}
    stop = threading.Event()
    started = dt.datetime.now(dt.timezone.utc)
    query_threads = []
    measurements = threading.Lock()
    try:
        for n in range(args.agents):
            home = out/('agent-%03d' % n)
            root = home/'logs'; root.mkdir(parents=True)
            enroll = client.request('/agent/v1/enroll', {'username': os.environ['BENCH_USER'],
                'password': os.environ['BENCH_PASSWORD'], 'name': namespace+'-'+str(n), 'hostName': 'benchmark',
                'version': args.version, 'roots': [{'path': str(root), 'realPath': str(root)}]})
            config = dict(serverUrl=args.url, allowHttp=args.url.startswith('http:'), agentId=enroll['agentId'],
                agentUuid=enroll['agentUuid'], token=enroll['token'], allowedRoots=[str(root)],
                maxBatchBytes=enroll['maxBatchBytes'], spoolLimitBytes=enroll['spoolLimitBytes'],
                agentName=namespace+'-'+str(n), hostName='benchmark')
            config_path = home/'agent.json'; config_path.write_text(json.dumps(config)); config_path.chmod(0o600)
            for index in range(2):
                directory = root/str(index); directory.mkdir()
                file = directory/'application.log'; file.touch()
                source = client.request('/sources', {'name': namespace+'-'+str(n)+'-'+str(index),
                    'applicationNamespace': namespace, 'collectorType': 'AGENT', 'agentId': enroll['agentId'],
                    'path': str(directory), 'include': '*.log', 'charset': 'UTF-8', 'startMode': 'HISTORY_180D'})
                sources.append((file, source['id']))
            log = open(home/'agent.log', 'wb'); handles.append(log)
            processes.append(subprocess.Popen([args.agent_java, '-jar', str(pathlib.Path(args.agent_jar).resolve()),
                'run', '--config', str(config_path), '--data', str(home/'data')], stdout=log, stderr=log, env=dict(os.environ, AGENT_UPLOAD_WORKERS=str(args.upload_workers))))
            agents.append(home)
        time.sleep(args.warmup)
        if any(p.poll() is not None for p in processes):
            raise RuntimeError('Agent exited during startup; inspect restricted local Agent logs')
        query_from = started.replace(second=0, microsecond=0).isoformat()
        def query_worker():
            reader = Client(args.url); reader.login(); turn = 0
            while not stop.is_set():
                before = time.monotonic()
                try:
                    params = urllib.parse.urlencode({'applicationNamespace': namespace, 'service': namespace,
                        'from': query_from, 'to': dt.datetime.now(dt.timezone.utc).isoformat()})
                    path = ['/dashboard/summary', '/errors/groups', '/errors/occurrences'][turn % 3]
                    data = reader.request(path+'?'+params)
                    if path == '/errors/occurrences' and data['items']:
                        reader.request('/errors/occurrences/'+str(data['items'][0]['id']))
                    with measurements: metrics['query_seconds'].append(time.monotonic()-before)
                except Exception:
                    with measurements: metrics['query_failures'] += 1
                turn += 1
                stop.wait(max(0, 10-(time.monotonic()-before)))
        for _ in range(args.query_users):
            thread = threading.Thread(target=query_worker, daemon=True); thread.start(); query_threads.append(thread)
        # Producer emits one second of arrivals at a time, never waits for an Agent ACK.
        begin = time.monotonic(); produced = 0; sequence = 0; last_sample = -1
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as probes:
            probe_jobs = []
            def visibility(uri, created):
                deadline = time.monotonic()+args.visibility_timeout
                while time.monotonic() < deadline:
                    try:
                        params = urllib.parse.urlencode({'applicationNamespace': namespace, 'from': query_from,
                            'to': dt.datetime.now(dt.timezone.utc).isoformat(), 'keyword': uri})
                        if client_for_probe().request('/endpoints?'+params)['items']:
                            with measurements: metrics['visibility_seconds'].append(time.monotonic()-created)
                            return
                    except Exception:
                        pass
                    stop.wait(1)
                with measurements: metrics['check_failures'] += 1
            probe_local = threading.local()
            def client_for_probe():
                if not hasattr(probe_local, 'client'):
                    probe_local.client = Client(args.url); probe_local.client.login()
                return probe_local.client
            files = [open(file, 'ab', buffering=0) for file, _ in sources]
            try:
                for second in range(args.seconds):
                    until = begin + second
                    time.sleep(max(0, until-time.monotonic()))
                    metrics['producer_lag_seconds'].append(max(0, time.monotonic()-until))
                    multiplier = args.burst_multiplier if args.burst_start <= second < args.burst_start+args.burst_seconds else 1
                    target = args.gb_per_day*1e9/86400*multiplier
                    payloads = [bytearray() for _ in files]; emitted = 0; probe_uri = None
                    while emitted < target:
                        timestamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8)))
                        header = timestamp.strftime('%Y-%m-%d %H:%M:%S.')+('%03d' % (timestamp.microsecond//1000))
                        is_error = rng.random() < args.error_ratio
                        fingerprint_name = 'Bench'+str(sequence % args.fingerprints)+'Exception'
                        uri = '/bench/u'+str(sequence % args.uris)
                        if second % 10 == 0 and probe_uri is None and not is_error:
                            uri = '/bench/probe/'+str(second); probe_uri = uri
                        if is_error:
                            text = header+' ERROR 1 --- [e-'+str(sequence)+'] bench.Logger : failure\nbench.'+fingerprint_name+': synthetic failure\n'
                            text += '\tat bench.Work.run(Work.java:42)\n'*args.stack_lines
                            expected['errors'] += 1
                            group = expected['groups'].setdefault(fingerprint_name, {'count': 0, 'first': timestamp.isoformat(), 'last': None})
                            group['count'] += 1; group['last'] = timestamp.isoformat()
                        else:
                            text = header+' INFO 1 --- [r-'+str(sequence)+'] bench.OncePerRequest : 当前请求URL:http://localhost'+uri+'\n'
                            expected['accesses'] += 1
                        raw = text.encode(); index = sequence % len(files)
                        if args.hot_source and rng.random() < 0.5: index = 0
                        payloads[index].extend(raw); emitted += len(raw); sequence += 1
                    for file, raw in zip(files, payloads): file.write(raw)
                    expected['bytes'] += emitted; produced += emitted
                    if probe_uri: probe_jobs.append(probes.submit(visibility, probe_uri, time.monotonic()))
                    if second-last_sample >= 10:
                        qbytes = sum(sum(p.stat().st_size for p in (h/'data'/'spool').glob('*') if p.is_file()) for h in agents)
                        metadata = [p for h in agents for p in (h/'data'/'spool').glob('*.json')]
                        ages = []
                        for path in metadata:
                            try: ages.append(max(0, time.time()-json.loads(path.read_text())['createdAt']/1000))
                            except (OSError, ValueError, KeyError): pass
                        metrics['queue'].append({'second': second, 'bytes': qbytes, 'oldest_seconds': max(ages, default=0)})
                        last_sample = second
                # Flush parser tail using ordinary INFO; not an access or an error.
                for file in files:
                    stamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S.000')
                    file.write((stamp+' INFO 1 --- [flush] bench.Marker : flush\n').encode())
                deadline = time.monotonic()+args.drain_seconds
                summary = None
                while time.monotonic() < deadline:
                    params = urllib.parse.urlencode({'applicationNamespace': namespace, 'from': query_from,
                        'to': dt.datetime.now(dt.timezone.utc).isoformat()})
                    summary = client.request('/dashboard/summary?'+params)
                    if summary['totalAccess'] == expected['accesses'] and summary['systemErrors'] == expected['errors']: break
                    time.sleep(1)
                metrics['actual'] = summary
                metrics['counts_match'] = bool(summary and summary['totalAccess'] == expected['accesses'] and summary['systemErrors'] == expected['errors'])
            finally:
                for file in files: file.close()
            for job in probe_jobs: job.result()
    finally:
        stop.set()
        for thread in query_threads: thread.join(timeout=35)
        for p in processes:
            p.terminate()
        for p in processes:
            try: p.wait(timeout=15)
            except subprocess.TimeoutExpired: p.kill(); p.wait()
        for h in handles: h.close()
        (out/'expected.json').write_text(json.dumps(expected, indent=2))
        metrics['query_p95_seconds'] = percentile(metrics['query_seconds'])
        metrics['visibility_p95_seconds'] = percentile(metrics['visibility_seconds'])
        metrics['scenario'] = {k: v for k, v in vars(args).items() if k not in ('url', 'agent_jar', 'output')}
        (out/'report.json').write_text(json.dumps(metrics, indent=2))
    print(json.dumps({'counts_match': metrics['counts_match'], 'query_p95_seconds': metrics['query_p95_seconds'],
        'visibility_p95_seconds': metrics['visibility_p95_seconds'], 'output': str(out)}))
    return 0 if metrics['counts_match'] and not metrics['check_failures'] and not metrics['query_failures'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--url', required=True); parser.add_argument('--agent-jar', required=True)
    parser.add_argument('--agent-java', default=os.environ.get('BENCH_AGENT_JAVA', 'java'))
    parser.add_argument('--output', required=True); parser.add_argument('--version', default='benchmark')
    parser.add_argument('--agents', type=int, default=100); parser.add_argument('--upload-workers', type=int, default=2)
    parser.add_argument('--gb-per-day', type=float, default=100); parser.add_argument('--seconds', type=int, default=7200)
    parser.add_argument('--error-ratio', type=float, default=.01); parser.add_argument('--stack-lines', type=int, default=8)
    parser.add_argument('--fingerprints', type=int, default=100); parser.add_argument('--uris', type=int, default=1000)
    parser.add_argument('--hot-source', action='store_true'); parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--query-users', type=int, default=10); parser.add_argument('--warmup', type=int, default=15)
    parser.add_argument('--drain-seconds', type=int, default=1800); parser.add_argument('--visibility-timeout', type=int, default=120)
    parser.add_argument('--burst-start', type=int, default=3600); parser.add_argument('--burst-seconds', type=int, default=0)
    parser.add_argument('--burst-multiplier', type=float, default=10)
    args = parser.parse_args()
    if args.agents < 1 or args.gb_per_day <= 0 or args.seconds < 1 or not 0 <= args.error_ratio <= 1 or min(args.fingerprints,args.uris)<1:
        parser.error('Invalid workload bounds')
    return run(args)


if __name__ == '__main__':
    raise SystemExit(main())
