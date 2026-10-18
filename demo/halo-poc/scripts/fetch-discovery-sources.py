"""Fetch exact upstream source tags and bounded relevant Halo files from official repositories."""
import json
import urllib.request
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]/'evidence/discovery-sources'
ROOT.mkdir(parents=True,exist_ok=True)
def get(url):
    return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'AIHYB-PoC-source-audit'}),timeout=45).read()
for repo,tag in [('plugin-search-widget','v1.7.1'),('plugin-feed','v1.5.0'),('plugin-sitemap','v1.3.0')]:
    path=ROOT/(repo+'.zip')
    if not path.exists(): path.write_bytes(get('https://api.github.com/repos/halo-dev/'+repo+'/zipball/'+tag))
    target=(ROOT/repo).resolve(); target.mkdir(exist_ok=True)
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            relative=Path(*Path(info.filename).parts[1:])
            destination=(target/relative).resolve()
            assert destination.is_relative_to(target)
            if info.is_dir(): destination.mkdir(parents=True,exist_ok=True)
            else:
                destination.parent.mkdir(parents=True,exist_ok=True); destination.write_bytes(archive.read(info))
    print(repo,tag,'source saved')
tree=json.loads(get('https://api.github.com/repos/halo-dev/halo/git/trees/v2.26.1?recursive=1'))
names=('DefaultPluginRouterFunctionRegistry.java','PluginResourceUtils.java','SearchServiceImpl.java','PostSearchService.java','PostPublishedEvent.java')
paths=[item['path'] for item in tree['tree'] if item['path'].endswith(names) or ('search' in item['path'].lower() and item['path'].endswith('.java'))]
(ROOT/'halo-source-paths.json').write_text(json.dumps(paths,indent=2))
for path in paths:
    if '/test/' in path: continue
    content=get('https://raw.githubusercontent.com/halo-dev/halo/v2.26.1/'+path)
    (ROOT/Path(path).name).write_bytes(content)
print('Halo 2.26.1 source:',len(paths),'relevant paths')
