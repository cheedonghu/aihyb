"""Disable only the optional search widget inside this PoC; keep artifact and settings for rollback."""
import json
from datetime import datetime, timezone
from poc_client import Client, ROOT
c=Client()
path='/apis/plugin.halo.run/v1alpha1/plugins/PluginSearchWidget'
before=c.request(path)
c.request('/apis/api.console.halo.run/v1alpha1/plugins/PluginSearchWidget/plugin-state','PUT',{'enable':False,'async':False})
after=c.request(path)
assert after['spec']['enabled'] is False
(ROOT/'evidence/search-plugin-disabled.json').write_text(json.dumps({'timestamp_utc':datetime.now(timezone.utc).isoformat(),'name':'PluginSearchWidget','before_enabled':before['spec']['enabled'],'after_enabled':after['spec']['enabled'],'reason':'Repeatable intermittent ZipException in stylesheet serving; unrelated optional PoC search widget. Settings/artifact retained.','rollback':{'enable':True,'async':False}},ensure_ascii=False,indent=2))
print('Optional PoC search widget disabled; settings/artifact retained')
