#!/usr/bin/env python3
"""Real-Agent directory/upload/heartbeat smoke. Disposable center only; credentials from environment."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import uuid
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'performance'))
from benchmark import Client


def run(args):
    if not args.isolated:
        raise SystemExit('Require --isolated and a disposable center')
    client = Client(args.url)
    client.login()
    roots = client.request('/sources/directories')
    assert roots['directories'], 'Center has no configured roots'
    center_path = roots['directories'][0]['path']
    client.request('/sources/directories?' + urllib.parse.urlencode({'path': center_path}))
    with tempfile.TemporaryDirectory(prefix='directory-agent-') as tmp:
        home = Path(tmp).resolve()
        root = home / 'logs'; app = root / 'app'; app.mkdir(parents=True)
        outside = home / 'outside'; outside.mkdir()
        (root / 'escape').symlink_to(outside, target_is_directory=True)
        (root / 'file.log').touch()
        name = 'directory-smoke-' + uuid.uuid4().hex[:12]
        enrolled = client.request('/agent/v1/enroll', {'username': os.environ['BENCH_USER'], 'password': os.environ['BENCH_PASSWORD'],
            'name': name, 'hostName': 'isolated-directory-test', 'version': args.version, 'roots': [{'path': str(root), 'realPath': str(root)}]})
        config = dict(serverUrl=args.url, allowHttp=args.url.startswith('http:'), agentId=enrolled['agentId'], agentUuid=enrolled['agentUuid'],
            token=enrolled['token'], allowedRoots=[str(root)], agentName=name, hostName='isolated-directory-test')
        config_path = home / 'agent.json'; config_path.write_text(json.dumps(config)); config_path.chmod(0o600)
        source = client.request('/sources', {'name': name, 'applicationNamespace': name, 'collectorType': 'AGENT', 'agentId': enrolled['agentId'],
            'path': str(app), 'include': '*.log', 'exclude': '*.error_*.log', 'startMode': 'HISTORY_180D'})
        log_path = app / 'application.log'; log_path.touch()
        with open(home / 'agent.log', 'wb') as log:
            process = subprocess.Popen([args.agent_java, '-jar', str(Path(args.agent_jar).resolve()), 'run', '--config', str(config_path), '--data', str(home / 'data')], stdout=log, stderr=log)
            try:
                endpoint = '/sources/directories?' + urllib.parse.urlencode({'agentId': enrolled['agentId'], 'path': str(root)})
                ready_until = time.monotonic() + 20
                while True:
                    try: listing = client.request(endpoint); break
                    except urllib.error.HTTPError as e:
                        if e.code != 409 or time.monotonic() > ready_until: raise
                        time.sleep(.5)
                assert [e['name'] for e in listing['directories']] == ['app'], 'Remote files or escaped links exposed'
                agent = lambda: next(a for a in client.request('/agents') if a['id'] == enrolled['agentId'])
                heartbeat_before = agent()['lastSeenAt']
                started = time.monotonic(); count = 0; latencies = []
                query_from = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0) - dt.timedelta(minutes=1)
                while time.monotonic() - started < 35:
                    assert process.poll() is None, 'Agent exited'
                    stamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]
                    with open(log_path, 'a') as output:
                        for _ in range(5):
                            output.write(stamp + ' INFO 1 --- [reader] bench.OncePerRequest : 当前请求URL:http://localhost/directory/' + str(count) + '\n'); count += 1
                        output.write(stamp + ' INFO 1 --- [flush] bench.Worker : anonymous marker\n')
                    tick = time.monotonic(); response = client.request(endpoint); latencies.append(time.monotonic() - tick)
                    assert response['directories'][0]['name'] == 'app'
                    time.sleep(.7)
                heartbeat_after = agent()['lastSeenAt']
                assert heartbeat_after > heartbeat_before, 'Heartbeat did not advance during directory queries'
                until = time.monotonic() + 45
                while True:
                    dashboard = client.request('/dashboard/summary?' + urllib.parse.urlencode({'service': name, 'from': query_from.isoformat(), 'to': (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=1)).isoformat()}))
                    if dashboard['totalAccess'] == count: break
                    assert time.monotonic() < until, 'Uploads did not converge to generated events'
                    time.sleep(.5)
                print(json.dumps({'center_directory': 'passed', 'remote_directory': 'passed', 'symlink_boundary': 'passed',
                    'queries': len(latencies), 'maximum_directory_seconds': max(latencies), 'accesses_expected': count,
                    'accesses_actual': dashboard['totalAccess'], 'heartbeat_advanced': True}))
            finally:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--url', required=True)
    parser.add_argument('--agent-jar', required=True)
    parser.add_argument('--agent-java', default=os.environ.get('BENCH_AGENT_JAVA', 'java'))
    parser.add_argument('--version', default='1.1.2')
    run(parser.parse_args())
