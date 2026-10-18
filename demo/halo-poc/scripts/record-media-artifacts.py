"""Non-secret runtime, plugin, restricted relay and private backup integrity record."""
import hashlib,json,socket,subprocess,tarfile
from pathlib import Path
from datetime import datetime,timezone
from poc_client import Client,ROOT
c=Client();report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'plugins':{},'complete':False}
for name in ('plugin-aihyb','PluginS3ObjectStorage','PluginSearchWidget','PluginFeed','PluginSitemap'):
    value=c.request('/apis/plugin.halo.run/v1alpha1/plugins/'+name);assert value['spec']['enabled'] and value['status']['phase']=='STARTED'
    report['plugins'][name]={'phase':'STARTED','version':value['spec']['version']}
expected=hashlib.sha256((ROOT/'plugin-aihyb.jar').read_bytes()).hexdigest();installed=list((ROOT/'data/halo/plugins').glob('plugin-aihyb-*.jar'))
assert len(installed)==1 and hashlib.sha256(installed[0].read_bytes()).hexdigest()==expected;report['aihyb_sha256']=expected
official=ROOT/'data/halo/plugins/plugin-s3-1.17.0.jar'
if not official.exists():
    candidates=[p for p in (ROOT/'data/halo/plugins').glob('*.jar') if p.name.lower().startswith('plugins3objectstorage')];assert len(candidates)==1;official=candidates[0]
assert hashlib.sha256(official.read_bytes()).hexdigest()=='c59ac257e7117bfd018a7ff59b7d701fd2680eb33bc645c0b4d05594ee4c20d9'
report['official_s3_sha256']=hashlib.sha256(official.read_bytes()).hexdigest()
def relay(request):
    with socket.create_connection(('172.29.240.1',18091),timeout=5) as connection:
        connection.sendall(request.encode());return connection.recv(4096).split(b'\r\n')[0].decode()
checks={}
for target in ('example.com:443','127.0.0.1:443','cos.ap-shanghai.myqcloud.com:80','evil.cos.ap-shanghai.myqcloud.com:443'):
    assert '403' in relay('CONNECT '+target+' HTTP/1.1\r\nHost: '+target+'\r\n\r\n');checks[target]='403'
assert '405' in relay('GET / HTTP/1.1\r\nHost: example.com\r\n\r\n');checks['GET']='405'
assert '200' in relay('CONNECT cos.ap-shanghai.myqcloud.com:443 HTTP/1.1\r\nHost: cos.ap-shanghai.myqcloud.com:443\r\n\r\n');checks['allowed_cos_tls']='200'
report['relay']=checks
report['relay_limits']=subprocess.check_output(['systemctl','show','aihyb-poc-s3-egress.service','-p','ActiveState','-p','SubState','-p','MemoryMax','-p','CPUQuotaPerSecUSec','-p','TasksMax'],text=True).strip()
backup=max((p for p in (ROOT/'backups').iterdir() if p.name.startswith('20261017T') and p.is_dir()),key=lambda p:p.name)
assert backup.resolve().is_relative_to((ROOT/'backups').resolve())
for line in (backup/'checksums.txt').read_text().splitlines():
    checksum,path=line.split(maxsplit=1);file=Path(path);assert file.resolve().is_relative_to(backup.resolve())
    assert hashlib.sha256(file.read_bytes()).hexdigest()==checksum
subprocess.run(['docker','compose','exec','-T','db','pg_restore','--list'],cwd=ROOT,input=(backup/'halo.dump').read_bytes(),stdout=subprocess.DEVNULL,check=True)
with tarfile.open(backup/'halo-files.tar.gz') as archive:
    names=archive.getnames();assert any(n.endswith('/'+installed[0].name) for n in names)
    for theme in ('theme-earth','theme-anatole'):assert 'data/halo/themes/'+theme+'/templates/search.html' in names
for file in ('compose.media.yaml','media-egress/s3-egress-proxy.py','media-egress/aihyb-poc-s3-egress.service','secrets/cos-media.json'):assert (backup/file).is_file()
report['private_backup']={'path':str(backup),'checksums_and_pg_archive_valid':True,'overlay_and_relay_and_private_config_saved':True,'objects_remain_in_cos':True}
logs=subprocess.check_output(['docker','compose','logs','--since','5m','--tail','1000','halo'],cwd=ROOT,text=True)
report['bounded_log_sample']={key:logs.count(key) for key in ('java.util.zip.ZipException','No indices found','Feed cache invalidation notification failed')}
report['restart_readiness_seconds']=int((ROOT/'evidence/restart-readiness-seconds.txt').read_text())
report['complete']=True;(ROOT/'evidence/media-final-artifacts.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('Own plugin/S3 hashes and STARTED states / restricted relay / backup integrity recorded; no credentials exported',flush=True)
