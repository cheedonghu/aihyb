"""Upgrade only the PoC search widget after exact-source checks and a recoverable binary backup."""
import hashlib
import json
import shutil
import time
import zipfile
from datetime import datetime,timezone
from poc_client import Client,ROOT
manifest=json.loads((ROOT/'search-resourcefix-manifest.json').read_text())
current=ROOT/'data/halo/plugins/PluginSearchWidget-1.7.1.jar'
fixed=ROOT/'PluginSearchWidget-1.7.1-resourcefix.jar'
assert hashlib.sha256(current.read_bytes()).hexdigest()==manifest['original_sha256'],'Installed artifact differs from inspected original'
assert hashlib.sha256(fixed.read_bytes()).hexdigest()==manifest['fixed_sha256']
with zipfile.ZipFile(current) as a,zipfile.ZipFile(fixed) as b:
    assert a.namelist()==b.namelist() and all(a.read(path)==b.read(path) for path in a.namelist())
backup=ROOT/'backups'/('search-resourcefix-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
backup.mkdir(parents=True);shutil.copy2(current,backup/current.name)
c=Client();c.upload('/apis/api.console.halo.run/v1alpha1/plugins/PluginSearchWidget/upgrade',fixed)
time.sleep(3)
plugin=c.request('/apis/plugin.halo.run/v1alpha1/plugins/PluginSearchWidget')
assert plugin['spec']['enabled'] is True
report={'backup':str(backup),'original_sha256':manifest['original_sha256'],'fixed_sha256':manifest['fixed_sha256'],'all_entry_bytes_identical':True}
(ROOT/'evidence/search-resourcefix-installed.json').write_text(json.dumps(report,indent=2))
print('Only search widget artifact upgraded; original saved at',backup)
