"""Read-only discovery checks against a frozen business state; optionally switch two named PoC themes."""
import hashlib
import importlib.util
import json
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime,timezone
from html.parser import HTMLParser
from poc_client import ROOT,BASE

spec=importlib.util.spec_from_file_location('theme_validation',ROOT/'verify-themes.py')
theme=importlib.util.module_from_spec(spec);spec.loader.exec_module(theme)
c=theme.c
baseline_path=ROOT/'secrets/discovery-persistence-baseline.json'
if sys.argv[1]=='freeze':
    baseline_path.write_text(json.dumps(theme.state(),ensure_ascii=False))
    baseline_path.chmod(0o600)
    print('Private business-state baseline frozen; no content changes')
    raise SystemExit(0)
label=sys.argv[1]
if len(sys.argv)>2:
    selected=sys.argv[2]
    assert selected in ('theme-earth','theme-anatole')
    c.request(theme.theme_api+'/'+selected+'/activation','PUT')
    c.request(theme.theme_api+'/'+selected+'/invalidate-cache','PUT')
baseline=json.loads(baseline_path.read_text())
private=[];unpublished=[]
for id,entry in baseline['entries'].items():
    draft=json.loads(entry['detail']['draft_json'])
    if id.startswith('poc-discovery-unpublished-'):
        unpublished.append(id);private.append(draft['content']['title'])
    if id in ('poc-review','poc-tutorial','poc-leaderboard','poc-pricing'):
        private.append(draft['content']['title'])
        kind=draft['kind'];data=draft['data']
        private.append(data['conclusions'][0]['text'] if kind=='review' else data['tool_version'] if kind=='tutorial' else str(data['entries'][0]['score']) if kind=='leaderboard' else str(data['plans'][0]['amount']))
def read(path,accept='*/*',data=None):
    headers={'Accept':accept}
    if data is not None:headers['Content-Type']='application/json'
    response=urllib.request.urlopen(urllib.request.Request(BASE+path,json.dumps(data).encode() if data is not None else None,headers),timeout=25)
    assert response.status==200
    return response.read().decode()
def search(word):return json.loads(read('/apis/api.halo.run/v1alpha1/indices/-/search','application/json',{'keyword':word,'limit':1000}))
class SearchHits(HTMLParser):
    def __init__(self,body):
        super().__init__();self.active=False;self.hits=[];self.current=[];self.feed(body)
    def handle_starttag(self,tag,attrs):
        if tag=='article' and 'search-hit' in dict(attrs).get('class','').split():self.active=True;self.current=[]
    def handle_data(self,data):
        if self.active:self.current.append(data)
    def handle_endtag(self,tag):
        if tag=='article' and self.active:self.hits.append(''.join(self.current));self.active=False
for marker in private:
    # Numeric strings may tokenize into public matches; assert their text absent, rather than all hits empty.
    hits=search(marker)['hits'];assert marker not in json.dumps(hits,ensure_ascii=False),(label,marker,'private index content')
    page=read('/search?keyword='+urllib.parse.quote(marker),'text/html')
    assert marker not in ''.join(SearchHits(page).hits),(label,'private standalone result',marker)
paths=['/rss.xml','/feed.xml','/feed/authors/aihyb-admin.xml','/feed/tags/halo.xml']
paths+=['/feed/categories/'+name+'.xml' for name in ('reviews','tutorials','leaderboards','prices')]
for path in paths:
    raw=read(path,'application/rss+xml,text/xml');tree=ET.fromstring(raw)
    assert all(value not in raw for value in private+unpublished),(label,path,'private content')
    if path in ('/rss.xml','/feed.xml','/feed/authors/aihyb-admin.xml'):
        for kind in ('review','tutorial','leaderboard','pricing'):
            id='poc-'+kind;published=baseline['public'][id]
            assert any(item.findtext('title')==published['content']['title'] and urllib.parse.urlsplit(item.findtext('link') or '').path=='/archives/aihyb-'+id for item in tree.findall('.//item')),(label,path,id,'missing public version')
raw=read('/sitemap.xml','text/xml');links=[node.text for node in ET.fromstring(raw).findall('.//{*}loc')]
assert all(value not in raw for value in private+unpublished)
for kind in ('review','tutorial','leaderboard','pricing'):
    id='poc-'+kind;published=baseline['public'][id]
    hits=search(published['content']['title'])['hits']
    assert any(hit.get('metadataName')==theme.native_name(id) for hit in hits)
    page=read('/search?keyword='+urllib.parse.quote(published['content']['title']),'text/html')
    assert 'class="search-hit"' in page and '/archives/aihyb-'+id in page
    assert any(urllib.parse.urlsplit(link).path=='/archives/aihyb-'+id for link in links)
manifest=json.loads((ROOT/'search-resourcefix-manifest.json').read_text())
artifact=ROOT/'data/halo/plugins/PluginSearchWidget-1.7.1.jar'
assert hashlib.sha256(artifact.read_bytes()).hexdigest()==manifest['fixed_sha256']
for path in ('static/style.css','static/search-widget.iife.js'):assert len(read('/plugins/PluginSearchWidget/assets/'+path))>100
actual=theme.state();assert actual==baseline,'Business state changed across read-only validation/restart/theme switch'
result={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'label':label,'complete':True,'business_state_unchanged':True,'entries':len(actual['entries']),'public_entries':len(actual['public']),'revisions':len(actual['revisions']),'managed_snapshots':len(actual['snapshots']),'private_queries':len(private),'feed_routes':paths,'sitemap_urls':len(links),'search_artifact_sha256':manifest['fixed_sha256']}
(ROOT/'evidence'/('discovery-'+label+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(label,'search / feeds / sitemap / unchanged business state PASS',flush=True)
