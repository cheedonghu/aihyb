"""Non-secret artifact, runtime and private-backup integrity evidence for this PoC only."""
import hashlib
import json
import subprocess
import tarfile
import zipfile
from datetime import datetime,timezone
from poc_client import Client,ROOT

c=Client();backup=ROOT/'backups/20261014T162611Z'
assert backup.is_dir()
result={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'backup':str(backup),'plugins':{}}
for name in ('plugin-aihyb','PluginSearchWidget','PluginFeed','PluginSitemap'):
    state=c.request('/apis/plugin.halo.run/v1alpha1/plugins/'+name)
    assert state['spec']['enabled'] and state['status']['phase']=='STARTED',name
    result['plugins'][name]={'enabled':True,'phase':'STARTED','version':state['spec']['version']}
expected=hashlib.sha256((ROOT/'plugin-aihyb.jar').read_bytes()).hexdigest()
installed=list((ROOT/'data/halo/plugins').glob('plugin-aihyb-*.jar'));assert len(installed)==1
assert hashlib.sha256(installed[0].read_bytes()).hexdigest()==expected
result['aihyb_sha256']=expected
with zipfile.ZipFile(installed[0]) as jar:
    manifest=jar.read('plugin.yaml').decode('utf-8')
    assert 'AI好用榜' in manifest and '\ufffd' not in manifest and 'PluginFeed' in manifest
    assert not any(path.startswith('run/halo/feed/') or 'feed-api' in path for path in jar.namelist())
result['manifest_utf8_and_unbundled_feed_api']=True
for line in (backup/'checksums.txt').read_text().splitlines():
    digest,path=line.split(maxsplit=1);path=__import__('pathlib').Path(path)
    assert path.resolve().is_relative_to(backup.resolve())
    assert hashlib.sha256(path.read_bytes()).hexdigest()==digest
subprocess.run(['docker','compose','exec','-T','db','pg_restore','--list'],cwd=ROOT,input=(backup/'halo.dump').read_bytes(),stdout=subprocess.DEVNULL,check=True)
with tarfile.open(backup/'halo-files.tar.gz') as archive:
    names=archive.getnames()
    for theme_name in ('theme-earth','theme-anatole'):
        assert 'data/halo/themes/'+theme_name+'/templates/search.html' in names
    assert any(name.endswith('/PluginSearchWidget-1.7.1.jar') for name in names)
    assert any(name.endswith('/'+installed[0].name) for name in names)
result['backup_checksums_pg_archive_and_required_files_valid']=True
result['both_theme_search_templates_in_backup']=True
logs=subprocess.check_output(['docker','compose','logs','--since','20m','--tail','2000','halo'],cwd=ROOT,text=True)
result['bounded_log_sample']={'since':'20m','tail_limit':2000,'zip_exception_count':logs.count('java.util.zip.ZipException'),'feed_notification_failure_count':logs.count('Feed cache invalidation notification failed')}
result['restart_readiness_seconds']=int((ROOT/'evidence/restart-readiness-seconds.txt').read_text())
result['complete']=True
(ROOT/'evidence/discovery-final-artifacts.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('Plugin hashes/states, UTF-8 manifest, private backup integrity and bounded log sample recorded')
