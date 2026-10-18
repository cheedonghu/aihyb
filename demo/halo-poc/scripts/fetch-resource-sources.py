"""Fetch Halo's exact 2.26.1 static-resource implementation for diagnosis."""
import json
import urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'evidence/discovery-sources'
def get(url):
    return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'AIHYB-PoC-source-audit'}),timeout=40).read()
tree=json.loads(get('https://api.github.com/repos/halo-dev/halo/git/trees/v2.26.1?recursive=1'))
paths=[item['path'] for item in tree['tree'] if item['path'].endswith('.java') and '/test/' not in item['path'] and any(word in item['path'] for word in ('ReverseProxy','FileSystem','ResourceWeb','ResourceHandler','PluginAsset'))]
for path in paths: (root/Path(path).name).write_bytes(get('https://raw.githubusercontent.com/halo-dev/halo/v2.26.1/'+path))
(root/'resource-source-paths.json').write_text(json.dumps(paths,indent=2))
print('Downloaded',len(paths),'static-resource implementation files')
