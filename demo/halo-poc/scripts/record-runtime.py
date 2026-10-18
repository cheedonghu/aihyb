"""Record non-secret host checks; never inspect environment variables or process arguments."""
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from poc_client import ROOT

def command(*args):
    return subprocess.check_output(args, text=True).strip()

baseline = (ROOT / 'evidence/services-before-plugin.txt').read_text().splitlines()
processes = dict(line.split(None, 1) for line in command('ps', '-eo', 'pid=,comm=').splitlines())
protected = {}
for line in baseline:
    fields = line.split(None, 1)
    if len(fields) == 2 and fields[1] in {'sshd', 'stat_client', 'dockerd', 'containerd', 'systemd-network',
                                          'systemd-journal', 'systemd-timesyn', 'systemd-resolve', 'qemu-ga'}:
        protected[fields[1]] = {'baseline_pid': int(fields[0]),
                                'same_pid_and_name': processes.get(fields[0]) == fields[1]}
assert all(value['same_pid_and_name'] for value in protected.values())
forwarding = command('sysctl', '-n', 'net.ipv4.ip_forward')
assert forwarding == '0'
bindings = json.loads(command('docker', 'inspect', '--format', '{{json .HostConfig.PortBindings}}', 'aihyb-halo-poc-halo-1'))
assert bindings == {'8090/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '18090'}]}
db_bindings = json.loads(command('docker', 'inspect', '--format', '{{json .HostConfig.PortBindings}}', 'aihyb-halo-poc-db-1'))
assert not db_bindings
result = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'protected_services': protected,
    'ip_forward': forwarding, 'halo_bindings': bindings, 'db_bindings': db_bindings,
    'compose': command('docker', 'compose', 'ps', '--format', 'json'),
    'stats_single_idle_sample': command('docker', 'stats', '--no-stream', '--format', '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'),
    'listening_ports': command('ss', '-lntup'), 'complete': True}
(ROOT / 'evidence/runtime-after-plugin.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
print('Protected service PIDs unchanged; forwarding=0; Halo loopback only; database unexposed')
