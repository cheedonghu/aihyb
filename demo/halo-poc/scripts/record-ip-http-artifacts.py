"""Record the plain-HTTP IP entry's own non-secret runtime facts (read-only)."""
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from poc_client import ROOT


def cmd(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


image = json.loads((ROOT / 'evidence/public-domain-artifacts.json').read_text())['caddy_image']
assert cmd('docker', 'inspect', 'aihyb-halo-poc-caddy-1', '--format', '{{.Image}}') == \
    cmd('docker', 'image', 'inspect', image, '--format', '{{.Id}}')
ports = cmd('ss', '-Hlntp')
report = {
    'timestamp_utc': datetime.now(timezone.utc).isoformat(),
    'origin': 'http://5.231.253.4',
    'scheme': 'http',
    'domain_used': False,
    'domain_retirement_reason': 'ICP filing pending; aihyb.com DNS stopped resolving',
    'caddy_image': image,
    'caddy_version': cmd('docker', 'compose', 'exec', '-T', 'caddy', 'caddy', 'version'),
    'caddy_host_network': cmd('docker', 'inspect', 'aihyb-halo-poc-caddy-1',
                              '--format', '{{.HostConfig.NetworkMode}}') == 'host',
    'caddy_readonly_rootfs': cmd('docker', 'inspect', 'aihyb-halo-poc-caddy-1',
                                 '--format', '{{.HostConfig.ReadonlyRootfs}}') == 'true',
    'automatic_https': 'disabled',
    'listening_ports': ports,
    'halo_external_url_env': 'http://5.231.253.4',
    'certificates_retained_on_disk': (ROOT / 'data/caddy/data').is_dir(),
    'certificates_still_served': False,
    'console_exposure': 'ssh tunnel only (http://127.0.0.1:18090/console)',
    'console_public_status': 403,
    'ip_forward': cmd('sysctl', '-n', 'net.ipv4.ip_forward'),
    'complete': True,
}
assert report['ip_forward'] == '0'
assert ':443 ' not in ports, 'TLS port must be closed on the retired domain'
(ROOT / 'evidence/ip-http-artifacts.json').write_text(
    json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print('Plain-HTTP IP entry recorded: pinned Caddy, host network, auto_https off, '
      'port 80 only, forwarding=0', flush=True)
