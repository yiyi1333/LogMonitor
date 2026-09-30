#!/usr/bin/env python3
"""Disposable Docker release smoke; never uses an existing deployment or credentials."""
import argparse
import base64
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import uuid

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'performance'))
from benchmark import Client


def command(args, input=None, timeout=300):
    result = subprocess.run(args, input=input, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        # Commands contain only temporary paths, never passwords or tokens.
        raise RuntimeError('Command failed: ' + ' '.join(args) + '\n' + result.stderr[-2000:])
    return result.stdout


def eventually(check, timeout=90):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except Exception as error:
            last = error
        time.sleep(1)
    raise AssertionError('Condition timed out') from last


def run(args):
    if not args.isolated:
        raise SystemExit('Require --isolated: creates and removes only a unique test project')
    project = 'logmonitor-smoke-' + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix='logmonitor-docker-smoke-') as temp:
        root = Path(temp)
        with tarfile.open(args.package) as archive:
            archive.extractall(root, filter='data')
        packages = list(root.glob('logmonitor-docker-*'))
        assert len(packages) == 1
        package = packages[0]
        version = (package / 'VERSION').read_text().strip()
        platform = (package / 'PLATFORM').read_text().strip()
        print('Loading offline images', flush=True)
        command(['docker', 'load', '-i', str(package / 'images.tar')])
        for component in ['backend', 'frontend', 'agent']:
            actual = json.loads(command(['docker', 'image', 'inspect', 'logmonitor/' + component + ':' + version]))[0]
            assert actual['Os'] + '/' + actual['Architecture'] == platform
            assert actual['Config']['Labels']['org.opencontainers.image.version'] == version
        logs = root / 'logs'; app = logs / 'app'; app.mkdir(parents=True)
        config = root / 'agent-config'; config.mkdir()
        log = app / 'application.log'; log.touch()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        password = secrets.token_urlsafe(24)
        env = root / '.env'
        env.write_text('\n'.join([
            'LOGMONITOR_VERSION=' + version, 'DB_HOST=mysql', 'DB_USERNAME=logmonitor',
            'DB_PASSWORD=' + secrets.token_urlsafe(24), 'MYSQL_ROOT_PASSWORD=' + secrets.token_urlsafe(24),
            'LOG_MONITOR_ADMIN_USERNAME=smoke', 'LOG_MONITOR_ADMIN_PASSWORD=' + password,
            'LLM_CONFIG_MASTER_KEY=' + base64.b64encode(secrets.token_bytes(32)).decode(),
            'SESSION_COOKIE_SECURE=false', 'FRONTEND_PORT=' + str(port),
            'CENTER_LOG_DIR=' + str(logs), 'AGENT_LOG_DIR=' + str(logs), 'AGENT_CONFIG_DIR=' + str(config),
        ]) + '\n')
        env.chmod(0o600)
        compose = ['docker', 'compose', '--env-file', str(env), '-p', project,
                   '-f', str(package / 'compose.yaml'), '-f', str(package / 'compose.mysql.yaml'),
                   '-f', str(package / 'compose.agent.yaml')]
        try:
            command(compose + ['config', '--quiet'])
            print('Starting isolated MySQL, backend and frontend', flush=True)
            command(compose + ['up', '-d', '--wait', '--wait-timeout', '240', 'mysql', 'backend', 'frontend'])
            url = 'http://127.0.0.1:' + str(port)
            with urllib.request.urlopen(url + '/agents') as response:
                assert b'<html' in response.read()
            with urllib.request.urlopen(url + '/api/health') as response:
                assert response.status == 200
            os.environ['BENCH_USER'] = 'smoke'; os.environ['BENCH_PASSWORD'] = password
            client = Client(url); client.login()
            roots = client.request('/sources/directories')
            assert [entry['path'] for entry in roots['directories']] == ['/logs']
            command(compose + ['run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'sh', 'agent', '-c', 'chown 10001:10001 /config'])
            print('Registering and starting real container Agent', flush=True)
            command(compose + ['run', '--rm', '--no-deps', '-T', 'agent', 'configure', '--config', '/config/agent.json', '--allow-http'],
                    input='http://frontend:8080\nsmoke\n' + password + '\ncontainer-smoke\n127.0.0.1\n/logs\n')
            command(compose + ['run', '--rm', '--no-deps', 'agent', 'check', '--config', '/config/agent.json', '--data', '/data'])
            enrolled = client.request('/agents')[0]
            assert enrolled['version'] == version
            source = client.request('/sources', {'name': 'container-smoke', 'applicationNamespace': 'container-smoke',
                      'collectorType': 'AGENT', 'agentId': enrolled['id'], 'path': '/logs/app',
                      'include': '*.log', 'exclude': '*.error_*.log', 'startMode': 'HISTORY_180D'})
            command(compose + ['up', '-d', 'agent'])
            start = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=1)
            def summary():
                from urllib.parse import urlencode
                return client.request('/dashboard/summary?' + urlencode({'service': 'container-smoke', 'from': start.isoformat(),
                                      'to': (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=1)).isoformat()}))['totalAccess']
            stamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S.000')
            with log.open('a') as output:
                for n in range(20):
                    output.write(stamp + ' INFO 1 --- [reader] bench.OncePerRequest : 当前请求URL:http://localhost/docker/' + str(n) + '\n')
                output.write(stamp + ' INFO 1 --- [flush] bench.Marker : flush\n')
            eventually(lambda: summary() == 20)
            endpoint = '/sources/directories?agentId=' + str(enrolled['id']) + '&path=/logs'
            listing = eventually(lambda: client.request(endpoint))
            assert [entry['name'] for entry in listing['directories']] == ['app']
            for service in ['backend', 'agent']:
                assert command(compose + ['exec', '-T', service, 'id', '-u']).strip() == '10001'
                command(compose + ['exec', '-T', service, 'sh', '-c', '! touch /logs/forbidden 2>/dev/null'])
            heartbeat = client.request('/agents')[0]['lastSeenAt']
            eventually(lambda: client.request('/agents')[0]['lastSeenAt'] > heartbeat, timeout=50)
            print('Checking restarts preserve schema, registration and cursor', flush=True)
            command(compose + ['restart', 'backend', 'agent'])
            command(compose + ['up', '-d', '--wait', '--wait-timeout', '120', 'backend', 'frontend'])
            client = Client(url); client.login()
            assert summary() == 20
            assert client.request('/agents')[0]['uuid'] == enrolled['uuid']
            assert client.request('/sources/status')[0]['id'] == source['id']
            with log.open('a') as output:
                output.write(stamp + ' INFO 1 --- [reader] bench.OncePerRequest : 当前请求URL:http://localhost/docker/20\n')
                output.write(stamp + ' INFO 1 --- [flush] bench.Marker : flush\n')
            eventually(lambda: summary() == 21)
            result = {'version': version, 'platform': platform, 'offline_load': True, 'mysql_init': True,
                      'health_and_spa_proxy': True, 'agent_configure_check': True, 'nonroot_and_readonly_logs': True,
                      'local_and_remote_directories': True, 'heartbeat_advanced': True, 'restart_persistence': True,
                      'accesses_expected': 21, 'accesses_actual': summary()}
            print(json.dumps(result), flush=True)
        finally:
            # This project was generated above and owns only temporary test volumes.
            if (config / 'agent.json').exists():
                command(compose + ['run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'sh', 'agent', '-c',
                        'rm -f /config/agent.json; chown ' + str(os.getuid()) + ':' + str(os.getgid()) + ' /config'])
            command(compose + ['down', '-v', '--remove-orphans'])
            os.environ.pop('BENCH_PASSWORD', None)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--package', type=Path, required=True)
    run(parser.parse_args())
