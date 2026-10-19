"""
Serve the PoC over plain HTTP on the public IP; the domain is retired while ICP filing is pending.

The public entry is read-only. Console/login/account routes stay on the SSH tunnel because plain
HTTP would carry the admin password in clear text. Business data is untouched: the only stored
change is Halo's derived ``system.basic.externalUrl``.

Replaces deploy-public-domain.py, which must not be re-run now that aihyb.com does not resolve.
"""
import hashlib
import json
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from poc_client import Client, ROOT

IP = '5.231.253.4'
ORIGIN = 'http://' + IP
OLD_ORIGIN = 'https://aihyb.com'
COMPOSE = 'compose.yaml:compose.media.yaml:compose.public.yaml'
BASELINE = ROOT / 'secrets/ip-http-baseline.json'
ORIGINAL_SYSTEM = ROOT / 'secrets/ip-http-original-system.json'
BACKUP = ROOT / 'backups/ip-http-switch'


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def run(*args):
    subprocess.run(args, cwd=ROOT, check=True)


def all_items(client, path):
    value = client.request(path + '?size=1000')
    assert len(value['items']) == value['total']
    return value['items']


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def state():
    """Counter-independent projection of every business version this PoC owns."""
    client = Client()
    result = {'entries': {}, 'revisions': {}, 'snapshots': {}}
    for entry in all_items(client, '/apis/content.aihyb.run/v1alpha1/contententries'):
        id = entry['spec']['contentId']
        name = 'ap-' + hashlib.sha256(id.encode()).hexdigest()
        post = client.request('/apis/content.halo.run/v1alpha1/posts/' + name)
        detail = client.request('/apis/console.api.aihyb.run/v1alpha1/entries/' + id)
        result['entries'][id] = {'detail': detail, 'post_spec': post['spec'],
            'annotations': {k: v for k, v in post['metadata']['annotations'].items()
                            if k.startswith('aihyb.run/')}}
    for rev in all_items(client, '/apis/content.aihyb.run/v1alpha1/contentrevisions'):
        result['revisions'][rev['metadata']['name']] = digest(rev['spec'])
    for snap in all_items(client, '/apis/content.halo.run/v1alpha1/snapshots'):
        if snap['metadata']['name'].startswith('as-'):
            result['snapshots'][snap['metadata']['name']] = digest(snap['spec'])
    return result


def prepare():
    """Save the current domain entry and business baseline; never overwrite a saved copy."""
    assert ROOT.resolve() == Path('/opt/aihyb-halo-poc')
    assert command('sysctl', '-n', 'net.ipv4.ip_forward') == '0'
    # Port 80 is already held by our own Caddy; the switch replaces it, so prove ownership instead
    # of asking for a free port. Nothing else on the host may be listening there.
    owner = command('ss', '-Hlntp', 'sport', '=', ':80')
    assert 'caddy' in owner, owner
    assert command('docker', 'inspect', 'aihyb-halo-poc-caddy-1',
                   '--format', '{{.HostConfig.NetworkMode}}') == 'host'
    assert not BASELINE.exists(), 'IP HTTP baseline already frozen; refused automatic overwrite'
    assert not ORIGINAL_SYSTEM.exists(), 'Original system settings already saved'
    assert not BACKUP.exists(), 'Backup directory already exists'
    recorded = json.loads((ROOT / 'evidence/public-domain-artifacts.json').read_text())
    assert recorded['halo_system_external_url'] == OLD_ORIGIN
    BACKUP.mkdir(parents=True, mode=0o700)
    for name in ('compose.public.yaml', 'public/Caddyfile', '.env'):
        target = BACKUP / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        target.write_bytes((ROOT / name).read_bytes())
        target.chmod(0o600)
    config = Client().request('/api/v1alpha1/configmaps/system')
    ORIGINAL_SYSTEM.write_text(json.dumps(config, ensure_ascii=False))
    ORIGINAL_SYSTEM.chmod(0o600)
    BASELINE.write_text(json.dumps(state(), ensure_ascii=False))
    BASELINE.chmod(0o600)
    print('Saved private baseline, original system settings and pre-switch entry files', flush=True)


def origin_setting():
    """Point Halo's derived origin at the IP; Halo reads this before the process property."""
    client = Client()
    path = '/api/v1alpha1/configmaps/system'
    config = client.request(path)
    basic = json.loads(config['data'].get('basic', '{}'))
    current = basic.get('externalUrl')
    assert current in (OLD_ORIGIN, ORIGIN), 'Refused replacing an unfamiliar public origin'
    if current == ORIGIN:
        print('Halo system origin already points at the IP', flush=True)
        return
    assert ORIGINAL_SYSTEM.exists()
    saved = json.loads(ORIGINAL_SYSTEM.read_text())
    assert json.loads(saved['data']['basic'])['externalUrl'] == current
    basic['externalUrl'] = ORIGIN
    config['data']['basic'] = json.dumps(basic, ensure_ascii=False)
    client.request(path, 'PUT', config)
    seen = client.request(path)
    assert json.loads(seen['data']['basic'])['externalUrl'] == ORIGIN
    print('Only Halo system.basic.externalUrl switched to the IP by native ConfigMap CAS', flush=True)


def validate():
    run('docker', 'compose', 'config', '--quiet')
    run('docker', 'compose', 'run', '--rm', '--no-deps', '--entrypoint', 'caddy', 'caddy',
        'validate', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile')


def deploy():
    assert BACKUP.exists() and BASELINE.exists()
    assert b'aihyb.com' not in (ROOT / 'public/Caddyfile').read_bytes(), 'Caddyfile still names the domain'
    run('docker', 'compose', 'up', '-d', '--no-deps', 'halo')
    run('python3', 'wait-halo.py')
    run('docker', 'compose', 'up', '-d', '--no-deps', 'caddy')
    print('Own Halo recreated for the new origin and own Caddy restarted on plain HTTP', flush=True)


def compare():
    """Entry public_url values are relative paths, so the origin switch must not touch this state."""
    previous = json.loads(BASELINE.read_text())
    current = state()
    assert current == previous, 'Business state changed during the origin switch'
    report = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'entries': len(current['entries']),
        'revisions': len(current['revisions']), 'snapshots': len(current['snapshots']),
        'business_unchanged': True, 'entry_public_urls_stay_relative': True, 'complete': True}
    (ROOT / 'evidence/ip-http-persistence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('All drafts / approvals / publications / immutable revisions / snapshots preserved', flush=True)


if __name__ == '__main__':
    {'prepare': prepare, 'origin': origin_setting, 'validate': validate,
     'deploy': deploy, 'compare': compare}[sys.argv[1]]()
