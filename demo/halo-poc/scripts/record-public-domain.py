"""Record only non-secret domain deployment and private backup integrity facts."""
import hashlib,json,subprocess,tarfile
from datetime import datetime,timezone
from pathlib import Path
from poc_client import Client,ROOT
def command(*args):return subprocess.check_output(args,cwd=ROOT,text=True).strip()
report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'origin':'https://aihyb.com','complete':False}
preflight=json.loads((ROOT/'evidence/public-domain-preflight.json').read_text())
expected=command('docker','image','inspect',preflight['caddy_image'],'--format','{{.Id}}')
actual=command('docker','inspect','aihyb-halo-poc-caddy-1','--format','{{.Image}}');assert actual==expected
report['caddy_image']=preflight['caddy_image'];report['caddy_version']=command('docker','compose','exec','-T','caddy','caddy','version')
report['container_mounts']={}
for service in ('halo','db','caddy'):
    name='aihyb-halo-poc-'+service+'-1'
    mounts=json.loads(command('docker','inspect',name,'--format','{{json .Mounts}}'))
    report['container_mounts'][service]=[{'source':m['Source'],'destination':m['Destination'],'rw':m['RW']} for m in mounts]
assert command('docker','inspect','aihyb-halo-poc-caddy-1','--format','{{.HostConfig.NetworkMode}}')=='host'
assert command('docker','inspect','aihyb-halo-poc-caddy-1','--format','{{.HostConfig.ReadonlyRootfs}}')=='true'
report['caddy_host_network_readonly_rootfs']=True
report['caddy_limits']=json.loads(command('docker','inspect','aihyb-halo-poc-caddy-1','--format','{"memory":{{.HostConfig.Memory}},"nano_cpus":{{.HostConfig.NanoCpus}},"pids":{{.HostConfig.PidsLimit}}}'))
backup=max((p for p in (ROOT/'backups').iterdir() if p.name.startswith('20261017T') and p.is_dir()),key=lambda p:p.name)
assert backup.resolve().is_relative_to((ROOT/'backups').resolve())
for line in (backup/'checksums.txt').read_text().splitlines():
    checksum,path=line.split(maxsplit=1);file=Path(path);assert file.resolve().is_relative_to(backup.resolve())
    assert hashlib.sha256(file.read_bytes()).hexdigest()==checksum
subprocess.run(['docker','compose','exec','-T','db','pg_restore','--list'],cwd=ROOT,input=(backup/'halo.dump').read_bytes(),stdout=subprocess.DEVNULL,check=True)
for name in ('compose.yaml','compose.media.yaml','compose.public.yaml','public/Caddyfile','secrets/cos-media.json'):assert (backup/name).is_file()
with tarfile.open(backup/'caddy-files.tar.gz') as archive:
    names=archive.getnames();assert any(n.endswith('.crt') for n in names) and any(n.endswith('.key') for n in names)
config=Client().request('/api/v1alpha1/configmaps/system');assert json.loads(config['data']['basic'])['externalUrl']=='https://aihyb.com'
report['halo_system_external_url']='https://aihyb.com'
report['private_backup']={'path':str(backup),'all_archive_checksums_valid':True,'postgres_archive_valid':True,
    'public_compose_caddyfile_and_private_cos_configuration_included':True,'certificate_and_private_key_persisted_in_private_backup':True,
    'cos_objects_excluded_from_server_backup':True,'cos_independent_backup_verified':False}
report['ip_forward']=command('sysctl','-n','net.ipv4.ip_forward');assert report['ip_forward']=='0'
report['listening_ports']=command('ss','-lntp')
report['complete']=True;(ROOT/'evidence/public-domain-artifacts.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('Public Compose / fixed image / host mounts / private DB+Halo+Caddy backup / certificate persistence / forwarding=0 PASS',flush=True)
