"""Own restricted egress service plus Halo-only Compose override; no global forwarding/firewall changes."""
import pathlib
import shutil
import socket
import subprocess
from poc_client import ROOT

assert pathlib.Path('/proc/sys/net/ipv4/ip_forward').read_text().strip()=='0'
current_jvm=subprocess.check_output(['docker','compose','exec','-T','halo','printenv','JVM_OPTS'],cwd=ROOT,text=True).strip()
original_jvm='-Xms128m -Xmx768m -XX:ActiveProcessorCount=1'
expected_jvm=original_jvm+' -Dhttp.proxyHost=172.29.240.1 -Dhttp.proxyPort=18091 -Dhttps.proxyHost=172.29.240.1 -Dhttps.proxyPort=18091'
assert current_jvm in (original_jvm,expected_jvm),'Refused replacing unfamiliar JVM options'
destination=pathlib.Path('/usr/local/lib/aihyb-halo-poc/s3-egress-proxy.py')
unit_path=pathlib.Path('/etc/systemd/system/aihyb-poc-s3-egress.service')
unit='''[Unit]
Description=AIHYB PoC COS-only TLS egress
After=network-online.target docker.service
[Service]
Type=simple
DynamicUser=yes
ExecStart=/usr/bin/python3 /usr/local/lib/aihyb-halo-poc/s3-egress-proxy.py
Restart=on-failure
RestartSec=3
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_INET AF_INET6
MemoryMax=64M
CPUQuota=15%
TasksMax=16
[Install]
WantedBy=multi-user.target
'''
if unit_path.exists():
    assert unit_path.read_text()==unit,'Refused overwrite of an unfamiliar service definition'
else:
    with socket.socket() as check:check.bind(('172.29.240.1',18091))
destination.parent.mkdir(mode=0o755,parents=True,exist_ok=True)
source=ROOT/'s3-egress-proxy.py'
if destination.exists():assert destination.read_bytes()==source.read_bytes(),'Refused changing an existing relay during setup'
shutil.copy2(source,destination);destination.chmod(0o644);destination.parent.chmod(0o755)
unit_path.write_text(unit);unit_path.chmod(0o644)
subprocess.run(['systemctl','daemon-reload'],check=True)
subprocess.run(['systemctl','enable','--now',unit_path.name],check=True)
subprocess.run(['docker','compose','-f','compose.yaml','-f','compose.media.yaml','up','-d','--no-deps','halo'],cwd=ROOT,check=True)
print('COS-only bridge relay installed; only PoC Halo recreated; global forwarding remains zero')
