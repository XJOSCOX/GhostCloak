"""Disposable Linux validator. Run ONLY in the documented network-none container.

Creates synthetic TLS material, a fake loopback backend, and container-only nft rules.
Never opens a Tunnel, reads owner credentials, modifies host rules, or publishes ports.
"""
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time

ROOT = Path('/fixtures')
assert os.environ.get('GHOSTCLOAK_LOCAL_VALIDATOR') == 'network-none-container'
assert Path('/.dockerenv').exists(), 'Must run in the disposable container'
assert {name for _, name in socket.if_nameindex()} == {'lo'}, 'Network must be disabled before container-only firewall tests'

def run(args, ok=True):
    result = subprocess.run(args, capture_output=True, text=True, timeout=30)
    if ok and result.returncode:
        raise AssertionError(f'{args[0]} failed: {result.stdout} {result.stderr}')
    return result

for directory in ['/run/ghostcloak-origin', '/run/ghostcloak-tunnel', '/etc/ghostcloak-origin', '/etc/cloudflared']:
    Path(directory).mkdir(parents=True, exist_ok=True)
run(['chown', 'root:ghostcloak-tunnel', '/run/ghostcloak-tunnel'])
os.chmod('/run/ghostcloak-tunnel', 0o750)
cert = Path('/etc/letsencrypt/live/api.ghostcloak.org')
cert.mkdir(parents=True)
run(['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
     '-nodes', '-days', '1', '-subj', '/CN=api.ghostcloak.org', '-addext', 'subjectAltName=DNS:api.ghostcloak.org',
     '-keyout', str(cert / 'privkey.pem'), '-out', str(cert / 'fullchain.pem')])
os.chmod(cert / 'privkey.pem', 0o600)
shutil.copy(ROOT / 'nginx-origin.conf.template', '/etc/ghostcloak-origin/nginx.conf')
shutil.copy(ROOT / 'origin-guard.nft', '/etc/ghostcloak-origin/origin-guard.nft')
run(['nft', '-c', '-f', '/etc/ghostcloak-origin/origin-guard.nft'])
run(['nft', '-f', '/etc/ghostcloak-origin/origin-guard.nft'])
run(['nft', '-f', '/etc/ghostcloak-origin/origin-guard.nft'])
assert run(['nft', 'list', 'table', 'inet', 'ghostcloak_origin_guard']).stdout.count('reject') == 1
run(['nginx', '-t', '-c', '/etc/ghostcloak-origin/nginx.conf'])

captured = []
class Backend(http.server.BaseHTTPRequestHandler):
    def do_GET(self): self.respond()
    def do_POST(self): self.respond()
    def respond(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        captured.append((self.path, dict(self.headers), body))
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')
    def log_message(self, *args): pass

backend = http.server.ThreadingHTTPServer(('127.0.0.1', 8787), Backend)
threading.Thread(target=backend.serve_forever, daemon=True).start()
nginx = subprocess.Popen(['nginx', '-c', '/etc/ghostcloak-origin/nginx.conf', '-g', 'daemon off;'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    sock = Path('/run/ghostcloak-tunnel/origin.sock')
    for _ in range(100):
        if sock.exists(): break
        time.sleep(.05)
    assert sock.exists()
    ip = '203.0.113.42'
    headers = ['CF-Connecting-IP', 'X-Forwarded-For', 'True-Client-IP', 'Forwarded', 'X-Real-IP',
               'CF-Connecting-IPv6', 'CF-IPCountry', 'CF-Ray', 'X-Future-Address-Header']
    def request(path='/health', method='GET', user='cloudflared', extra=None):
        args = ['runuser', '-u', user, '--', 'curl', '--silent', '--show-error', '--max-time', '3',
                '--unix-socket', str(sock), '--cacert', str(cert / 'fullchain.pem'),
                '-X', method, '-w', '\n%{http_code}', 'https://api.ghostcloak.org' + path]
        return run(args + (extra or []), ok=False)
    health = request()
    assert health.returncode == 0 and health.stdout == '{"status":"ok"}\n200', health
    for path in ['/v1/auth/challenge', '/v1/auth/verify', '/v1/auth/revoke', '/v1/accounts', '/v1/accounts/username',
                 '/v1/directory/lookup', '/v1/devices/prekeys', '/v1/messages', '/v1/messages/fetch', '/v1/messages/ack']:
        extra = ['-H', 'Content-Type: application/cbor', '-H', 'Authorization: Bearer synthetic-fixture', '--data-binary', 'abc']
        for header in headers: extra += ['-H', f'{header}: {ip}']
        response = request(path, 'POST', extra=extra)
        assert response.returncode == 0 and response.stdout.endswith('\n200'), response
        _, received, body = captured[-1]
        lower = {k.lower(): v for k, v in received.items()}
        assert all(header.lower() not in lower for header in headers), lower
        assert ip not in json.dumps(received)
        assert lower['authorization'] == 'Bearer synthetic-fixture' and lower['content-type'] == 'application/cbor'
        assert lower['x-forwarded-proto'] == 'https' and body == b'abc'
    for path, method, status in [('/admin', 'GET', 404), ('/metrics', 'GET', 404), ('/v1/messages/extra', 'POST', 404),
                                 ('/v1/messages', 'GET', 405), ('/health', 'POST', 405), ('/health?ip='+ip, 'GET', 400)]:
        assert request(path, method).stdout.endswith('\n'+str(status))
    assert request(extra=['-H', 'Host: unexpected.invalid']).stdout.endswith('\n421')
    large = Path('/tmp/oversized-body'); large.write_bytes(b'x' * 196609)
    assert request('/v1/messages', 'POST', extra=['--data-binary', '@'+str(large)]).stdout.endswith('\n413')
    assert request(extra=['-H', 'X-Oversized: '+('x' * 10000)]).stdout.endswith('\n400')
    before = len(captured)
    assert request(user='nobody').returncode != 0, 'Unprivileged user reached private socket'
    assert len(captured) == before
    # The socket restriction alone would not protect the backend TCP listener.
    for user in ['nobody', 'cloudflared']:
        assert run(['runuser', '-u', user, '--', 'curl', '--silent', '--max-time', '2',
                    '-H', 'X-Forwarded-Proto: https', 'http://127.0.0.1:8787/health'], ok=False).returncode != 0
    assert run(['runuser', '-u', 'ghostcloak-origin', '--', 'curl', '--silent', '--max-time', '2',
                'http://127.0.0.1:8787/health']).stdout == '{"status":"ok"}'
    listeners = '\n'.join(line.split()[3] for line in run(['ss', '-H', '-lnt']).stdout.splitlines())
    assert '0.0.0.0:' not in listeners and '[::]:' not in listeners, listeners
    assert ':443' not in listeners and ':80' not in listeners, listeners
    for log in Path('/var/log/nginx').glob('*.log'):
        assert ip not in log.read_text(), 'Visitor address logged'
finally:
    nginx.terminate(); nginx.wait(timeout=10); backend.shutdown(); backend.server_close()

# Pure local cloudflared parsing: network is disabled and no credentials file exists.
shutil.copy('/tool/cloudflared', '/usr/local/bin/cloudflared')
os.chmod('/usr/local/bin/cloudflared', 0o755)
config = Path('/etc/cloudflared/ghostcloak.yml')
config.write_text((ROOT / 'cloudflared.yml.template').read_text().replace('REPLACE_WITH_TUNNEL_UUID', '00000000-0000-0000-0000-000000000001'))
assert not Path('/etc/cloudflared/tunnel-credentials.json').exists()
run(['cloudflared', '--config', str(config), 'tunnel', 'ingress', 'validate'])
run(['cloudflared', '--config', str(config), 'tunnel', '--protocol', 'http2', '--loglevel', 'error',
     '--metrics', '127.0.0.1:20241', '--management-diagnostics=false', 'ingress', 'validate'])
for url, match in [('https://api.ghostcloak.org/health', 'rule #0'), ('https://unexpected.invalid/health', 'rule #1')]:
    result = run(['cloudflared', '--config', str(config), 'tunnel', 'ingress', 'rule', url])
    assert match in result.stdout + result.stderr

# Unit parser checks; fake backend launcher satisfies file-existence validation only.
units = Path('/tmp/units'); units.mkdir()
for unit in ROOT.glob('*.service'): shutil.copy(unit, units / unit.name)
shutil.copy('/parent/ghostcloak.service', units / 'ghostcloak.service')
shutil.copytree(ROOT / 'ghostcloak.service.d', units / 'ghostcloak.service.d')
launcher = Path('/opt/ghostcloak/current/bin/backend'); launcher.parent.mkdir(parents=True)
launcher.write_text('#!/bin/sh\nexit 0\n'); launcher.chmod(0o755)
(units / 'postgresql.service').write_text('[Service]\nExecStart=/bin/true\n')
run(['systemd-analyze', 'verify', *map(str, units.glob('*.service'))])
print('PASS: Linux nginx TLS Unix socket, all API routes, header removal, UID bypass protection, private listeners, cloudflared offline rules, systemd syntax')
