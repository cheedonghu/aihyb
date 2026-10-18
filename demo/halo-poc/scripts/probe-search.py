"""Enable only the existing PoC search widget, then reproduce static asset serving errors."""
import hashlib
import sys
import json
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime,timezone
from poc_client import Client,ROOT,BASE
c=Client(); name='PluginSearchWidget'
file=ROOT/'data/halo/plugins/PluginSearchWidget-1.7.1.jar'
expected={}
with zipfile.ZipFile(file) as archive:
    assert archive.testzip() is None
    for item in archive.namelist():
        if item.startswith('static/') and item.endswith(('.css','.js')):
            expected['/plugins/'+name+'/assets/'+item]=hashlib.sha256(archive.read(item)).hexdigest()
c.request('/apis/api.console.halo.run/v1alpha1/plugins/'+name+'/plugin-state','PUT',{'enable':True,'async':False})
records=[]
for round in range(40):
    for path,wanted in expected.items():
        url=path+('?version=1.7.1' if round%2 else '')
        start=time.monotonic()
        try:
            response=urllib.request.urlopen(BASE+url,timeout=10); body=response.read()
            record={'round':round,'path':url,'status':response.status,'hash_matches':hashlib.sha256(body).hexdigest()==wanted,'bytes':len(body)}
        except urllib.error.HTTPError as error:
            error.read(); record={'round':round,'path':url,'status':error.code,'hash_matches':False}
        record['seconds']=time.monotonic()-start
        records.append(record)
print('Search asset probes:',len(records),'failures:',sum(r['status']!=200 or not r['hash_matches'] for r in records))
doc=c.request('/v3/api-docs')
paths={path:value for path,value in doc['paths'].items() if 'search' in path.lower() or 'feed' in path.lower() or 'sitemap' in path.lower()}
(ROOT/'evidence/discovery-api.json').write_text(json.dumps({'paths':paths,'schemas':doc['components']['schemas']},ensure_ascii=False))
(ROOT/'evidence'/('search-asset-after.json' if '--after' in sys.argv else 'search-asset-before.json')).write_text(json.dumps({'timestamp_utc':datetime.now(timezone.utc).isoformat(),'jar_sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'expected':expected,'records':records},ensure_ascii=False,indent=2))
logs=__import__('subprocess').check_output(['docker','compose','logs','--since','3m','--tail','500','halo'],cwd=ROOT,text=True)
(ROOT/'evidence/search-error-log.txt').write_text(logs)
print('Discovery endpoints:',list(paths))
