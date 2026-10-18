"""Small two-worker reproducer: page rendering interleaved with search JS/CSS and byte ranges."""
import concurrent.futures
import hashlib
import json
import sys
from datetime import datetime,timezone
import time
import urllib.error
import urllib.request
import zipfile
from poc_client import ROOT,BASE
data={}
with zipfile.ZipFile(ROOT/'data/halo/plugins/PluginSearchWidget-1.7.1.jar') as archive:
    for path in ('static/style.css','static/search-widget.iife.js'): data[path]=archive.read(path)
tasks=[]
for i in range(30):
    tasks.append(('/archives/aihyb-poc-review',{},None))
    tasks.append(('/aihyb/pricing',{},None))
    for path,body in data.items():
        tasks.append(('/plugins/PluginSearchWidget/assets/'+path+'?version=1.7.1',{},body))
        tasks.append(('/plugins/PluginSearchWidget/assets/'+path+'?version=1.7.1',{'Range':'bytes=0-127'},body[:128]))
def probe(task):
    path,headers,expected=task; start=time.monotonic()
    try:
        response=urllib.request.urlopen(urllib.request.Request(BASE+path,headers=headers),timeout=15)
        body=response.read(); status=response.status
        ok=(expected is None or body==expected) and status in (200,206)
    except urllib.error.HTTPError as error:
        error.read();status=error.code;ok=False
    return {'path':path,'range':headers.get('Range'),'status':status,'matches':ok,'seconds':time.monotonic()-start}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: results=list(pool.map(probe,tasks))
(ROOT/'evidence'/('search-asset-mixed-after.json' if '--after' in sys.argv else 'search-asset-mixed-before.json')).write_text(json.dumps({'timestamp_utc':datetime.now(timezone.utc).isoformat(),'workers':2,'requests':len(results),'failures':sum(not r['matches'] for r in results),'results':results},indent=2))
print('Mixed page/full/range requests:',len(results),'failures:',sum(not r['matches'] for r in results))
