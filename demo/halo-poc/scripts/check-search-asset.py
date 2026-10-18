"""Inspect only the PoC search plugin artifact and its asset response."""
import hashlib
import json
import zipfile
import urllib.error
import time
from poc_client import ROOT, Client
file=ROOT/'data/halo/plugins/PluginSearchWidget-1.7.1.jar'
with zipfile.ZipFile(file) as archive:
    failed=archive.testzip()
print('Search archive integrity:', failed or 'PASS')
c=Client(); plugin=c.request('/apis/plugin.halo.run/v1alpha1/plugins/PluginSearchWidget')
doc=c.request('/v3/api-docs')
paths={path:list(value) for path,value in doc['paths'].items() if '/plugins/{name}/' in path}
print('Native plugin actions:', paths)
print('Search plugin phase:',plugin.get('status',{}).get('phase'))
operation=doc['paths']['/apis/api.console.halo.run/v1alpha1/plugins/{name}/plugin-state']['put']
print('Native state operation:',json.dumps(operation))
ref=operation.get('requestBody',{}).get('content',{}).get('application/json',{}).get('schema',{}).get('$ref')
if ref: print('Native state schema:',json.dumps(doc['components']['schemas'][ref.split('/')[-1]]))
c.request('/apis/api.console.halo.run/v1alpha1/plugins/PluginSearchWidget/reload','PUT')
time.sleep(3)
asset=c.request('/plugins/PluginSearchWidget/assets/static/style.css?version=1.7.1')
assert isinstance(asset,str) and len(asset)>20
print('Search stylesheet after native plugin reload: PASS')
(ROOT/'evidence/search-plugin-check.json').write_text(json.dumps({'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'failed_archive_member':failed,'phase':plugin.get('status',{}).get('phase')},indent=2))
