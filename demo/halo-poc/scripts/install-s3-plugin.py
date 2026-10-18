"""Install only the fixed official Halo S3 plugin; no bucket credentials or defaults are changed."""
import hashlib
import time
import urllib.error
from poc_client import Client,ROOT

c=Client();name='PluginS3ObjectStorage';file=ROOT/'plugin-s3-1.17.0.jar'
assert hashlib.sha256(file.read_bytes()).hexdigest()=='c59ac257e7117bfd018a7ff59b7d701fd2680eb33bc645c0b4d05594ee4c20d9'
try:
    plugin=c.request('/apis/plugin.halo.run/v1alpha1/plugins/'+name)
    assert plugin['spec']['version']=='1.17.0','Existing S3 version differs; refused replacement'
except urllib.error.HTTPError as error:
    if error.code!=404:raise
    c.upload('/apis/api.console.halo.run/v1alpha1/plugins/install',file)
    time.sleep(3)
plugin=c.request('/apis/plugin.halo.run/v1alpha1/plugins/'+name)
if not plugin['spec']['enabled']:
    c.request('/apis/api.console.halo.run/v1alpha1/plugins/'+name+'/plugin-state','PUT',{'enable':True,'async':False})
for attempt in range(30):
    plugin=c.request('/apis/plugin.halo.run/v1alpha1/plugins/'+name)
    if plugin['status']['phase']=='STARTED':break
    if plugin['status']['phase']=='FAILED':raise RuntimeError('Official S3 plugin failed to start')
    time.sleep(1)
else:raise RuntimeError('S3 startup timeout')
print('Official S3 plugin 1.17.0 STARTED; no other plugins or storage defaults changed')
