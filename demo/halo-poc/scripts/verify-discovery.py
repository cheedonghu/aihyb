"""Real anonymous discovery routes, draft/approval/publication/rebuild boundaries, and reversible fictional data writes."""
import copy
import hashlib
import html
import json
import time
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime,timezone
from html.parser import HTMLParser
from poc_client import Client,ROOT,BASE

c=Client();admin='/apis/console.api.aihyb.run/v1alpha1'
run=datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
backup=ROOT/'backups'/('discovery-audit-'+run);backup.mkdir(mode=0o700)
report={'run':run,'private_backup':str(backup),'stages':{},'restored':False,'complete':False}
original={};owned={};sequence=0;config_changed=False
def checkpoint():
    (ROOT/'evidence/discovery-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
def detail(id):return c.request(admin+'/entries/'+id)
def public(id):return c.request('/apis/api.aihyb.run/v1alpha1/contents/'+id)
def native_name(id):return 'ap-'+hashlib.sha256(id.encode()).hexdigest()
def get(path,accept='*/*',data=None):
    headers={'Accept':accept}
    if data is not None:headers['Content-Type']='application/json'
    request=urllib.request.Request(BASE+path,json.dumps(data).encode() if data is not None else None,headers)
    response=urllib.request.urlopen(request,timeout=30)
    assert not urllib.parse.urlsplit(response.url).path.startswith('/login'),'Unexpected login redirect'
    return response.read().decode()
def search(keyword,override=False):
    payload={'keyword':keyword,'limit':1000}
    if override:payload.update(filterPublished=False,filterExposed=False,filterRecycled=True)
    return json.loads(get('/apis/api.halo.run/v1alpha1/indices/-/search','application/json',payload))
def ingest(id,payload,initial=False):
    global sequence
    value=copy.deepcopy(payload);value['content_id']=id;sequence+=1
    value['submission_id']='discovery-'+run+'-'+str(sequence)
    if not initial:
        current=detail(id);revision=current['entry']['draft_revision']
        allowed=owned[id] if id in owned else {original[id]['detail']['entry']['draft_revision']}
        assert revision in allowed,'Another editor changed '+id+'; refused overwrite'
        value['base_revision']=revision
    else:value.pop('base_revision',None)
    receipt=c.request(admin+'/content-packages','POST',value)
    owned.setdefault(id,set()).add(receipt['revision'])
    return receipt['revision']
def approve(id):
    revision=detail(id)['entry']['draft_revision']
    assert revision in owned[id]
    c.request(admin+'/entries/'+id+'/review','POST',{'revision':revision,'decision':'approve','note':'Discovery isolation PoC '+run})
    return revision
def publish(id):
    revision=approve(id)
    head='as-'+hashlib.sha256(revision.encode()).hexdigest()
    c.request(admin+'/native-posts/'+native_name(id)+'/publish?headSnapshot='+head,'PUT')
def wait_public_index(expected):
    started=time.monotonic()
    for attempt in range(40):
        if all(any(hit.get('metadataName')==native_name(id) for hit in search(package['content']['title'])['hits']) for id,package in expected.items()):
            return time.monotonic()-started
        time.sleep(.25)
    raise AssertionError('Published titles were not discoverable within ten seconds')
def marker_package(kind,package,prefix):
    value=copy.deepcopy(package)
    markers=[prefix+'title'+kind+run,prefix+'body'+kind+run,prefix+'data'+kind+run,prefix+'summary'+kind+run]
    value['content']['title']=markers[0];value['content']['summary']=markers[3]
    value['content']['body_markdown']+='\n\n'+markers[1]
    value['data']['task']=prefix+'task'+kind+run
    if kind=='review':value['data']['conclusions'][0]['text']=markers[2]
    elif kind=='tutorial':value['data']['tool_version']=markers[2]
    elif kind=='leaderboard':value['data']['entries'][0]['reason']=markers[2]
    else:
        value['data']['plans'][0]['name']=markers[2]
        value['data']['plans'][0]['amount']='876543.21' if prefix=='draft' else '765432.12'
    return value,markers
class SearchPage(HTMLParser):
    def __init__(self,body):
        super().__init__();self.active=False;self.hits=[];self.current=[];self.feed(body)
    def handle_starttag(self,tag,attrs):
        values=dict(attrs)
        if tag=='article' and 'search-hit' in values.get('class','').split():self.active=True;self.current=[]
        if self.active and tag=='a':self.current.append(values.get('href',''))
    def handle_data(self,value):
        if self.active:self.current.append(value)
    def handle_endtag(self,tag):
        if tag=='article' and self.active:self.hits.append(''.join(self.current));self.active=False
def check(stage,expected,private,unpublished_id):
    result={'search':{},'feeds':{},'sitemap':{}}
    # Public search ignores caller attempts to disable publication/privacy filters.
    for marker in private:
        for override in (False,True):
            value=search(marker,override)
            assert value['hits']==[],(stage,'private search hit',marker,value['hits'])
        body=get('/search?keyword='+urllib.parse.quote(marker),'text/html')
        assert SearchPage(body).hits==[],(stage,'private standalone result',marker)
    result['search']['private_marker_queries']=len(private)
    result['search']['anonymous_filter_override_rejected']=True
    for id,package in expected.items():
        value=search(package['content']['title'])
        assert any(hit.get('metadataName')==native_name(id) for hit in value['hits']),(stage,'missing public search',id)
        text=json.dumps(value['hits'],ensure_ascii=False)
        assert all(marker not in text for marker in private)
        body=get('/search?keyword='+urllib.parse.quote(package['content']['title']),'text/html')
        hits=SearchPage(body).hits
        assert any('/archives/aihyb-'+id in hit for hit in hits),(stage,'missing public search page',id)
        assert all(marker not in ''.join(hits) for marker in private)
        assert public(id)['content']==package['content'] and public(id)['data']==package['data']
    result['search']['four_public_titles_found']=True
    paths=['/rss.xml','/feed.xml','/feed/authors/aihyb-admin.xml','/feed/tags/halo.xml']
    paths+=['/feed/categories/'+category+'.xml' for category in ('reviews','tutorials','leaderboards','prices')]
    for path in paths:
        raw=get(path,'application/rss+xml,text/xml');xml=ET.fromstring(raw)
        assert all(marker not in html.unescape(raw) for marker in private),(stage,path,'private marker')
        assert unpublished_id not in raw,(stage,path,'unpublished URL')
        items=xml.findall('.//item');found={}
        for item in items:
            link=item.findtext('link') or ''
            for id,package in expected.items():
                if urllib.parse.urlsplit(link).path=='/archives/aihyb-'+id:
                    assert item.findtext('title')==package['content']['title'],(stage,path,id,'wrong feed title')
                    description=item.findtext('description') or ''
                    if report['feed_mode']=='content':
                        data=package['data'];kind=package['kind']
                        needle=(data['conclusions'][0]['text'] if kind=='review' else data['tool_version'] if kind=='tutorial' else data['entries'][0]['reason'] if kind=='leaderboard' else data['plans'][0]['name'])
                        assert needle in html.unescape(description),(stage,path,id,'wrong structured feed data')
                        if kind=='pricing':assert data['plans'][0]['amount']+' CNY' in html.unescape(description)
                    else:assert package['content']['summary'] in html.unescape(description)
                    found[id]=True
        if path in ('/rss.xml','/feed.xml','/feed/authors/aihyb-admin.xml'):
            assert len(found)==4,(stage,path,'missing public items',list(found))
        if path.startswith('/feed/categories/'):
            target={'reviews':'review','tutorials':'tutorial','leaderboards':'leaderboard','prices':'pricing'}[path.split('/')[-1][:-4]]
            assert 'poc-'+target in found,(stage,path,'missing module item')
        result['feeds'][path]={'status':200,'items':len(items),'managed_public_items':list(found),'mode':report['feed_mode']}
    raw=get('/sitemap.xml','text/xml');xml=ET.fromstring(raw)
    links=[element.text for element in xml.findall('.//{*}loc')]
    assert all(marker not in raw for marker in private) and unpublished_id not in raw
    for id in expected:assert any(urllib.parse.urlsplit(link).path=='/archives/aihyb-'+id for link in links),(stage,'sitemap missing published id',id)
    assert not any('/console/' in link or 'preview=' in link for link in links)
    result['sitemap']={'status':200,'urls':len(links),'unpublished_excluded':True,'four_public_urls_present':True}
    report['stages'][stage]=result;checkpoint();print(stage,'anonymous search / eight feed routes / sitemap PASS',flush=True)

manifest=json.loads((ROOT/'search-resourcefix-manifest.json').read_text())
installed=ROOT/'data/halo/plugins/PluginSearchWidget-1.7.1.jar'
assert hashlib.sha256(installed.read_bytes()).hexdigest()==manifest['fixed_sha256']
plugin=c.request('/apis/plugin.halo.run/v1alpha1/plugins/PluginSearchWidget')
assert plugin['spec']['enabled'] and plugin['status']['phase']=='STARTED'
with zipfile.ZipFile(installed) as archive:
    for name in ('static/style.css','static/search-widget.iife.js'):
        assert archive.getinfo(name).compress_type==zipfile.ZIP_STORED
        response=urllib.request.urlopen(BASE+'/plugins/PluginSearchWidget/assets/'+name+'?version=1.7.1',timeout=15)
        assert response.read()==archive.read(name)
report['search_artifact']={'installed_sha256_matches':True,'phase':'STARTED','css_js_stored_and_byte_identical':True}
config_path='/apis/api.console.halo.run/v1alpha1/plugins/PluginFeed/json-config'
feed_config=c.request(config_path)
if isinstance(feed_config,str):feed_config=json.loads(feed_config)
feed_plugin=c.request('/apis/plugin.halo.run/v1alpha1/plugins/PluginFeed')
config_map_path='/api/v1alpha1/configmaps/'+feed_plugin['spec']['configMapName']
original_config_map=c.request(config_map_path)
original_config_data=copy.deepcopy(original_config_map.get('data',{}))
temporary_config_data=copy.deepcopy(original_config_data)
basic=json.loads(temporary_config_data.get('basic','{}'));basic['descriptionType']='content'
temporary_config_data['basic']=json.dumps(basic,ensure_ascii=False)
def change_feed_config(expected,target):
    # Native ConfigMap CAS preserves the resource version and prevents overwriting an operator.
    current=c.request(config_map_path)
    if current.get('data',{})!=expected:
        raise RuntimeError('Feed configuration changed by another editor; refused overwrite')
    current['data']=copy.deepcopy(target)
    c.request(config_map_path,'PUT',current)
    # The official setting watcher updates its snapshot and emits cache-clear configuration events.
    for attempt in range(40):
        observed=c.request(config_path)
        if isinstance(observed,str):observed=json.loads(observed)
        if observed.get('basic',{}).get('descriptionType','excerpt')==json.loads(target['basic']).get('descriptionType','excerpt'):return
        time.sleep(.1)
    raise RuntimeError('Feed setting watcher did not apply the committed configuration')
for kind in ('review','tutorial','leaderboard','pricing'):
    id='poc-'+kind;record=detail(id);live=public(id)
    assert record['entry']['reviewed_revision'] is None,'PoC requires the original draft to be unapproved; refused mutation'
    revision=c.request('/apis/content.aihyb.run/v1alpha1/contentrevisions/'+record['entry']['published_revision'])
    original[id]={'detail':record,'draft':json.loads(record['draft_json']),'published':json.loads(revision['spec']['rawJson']),'public_dto':live}
snapshot=backup/'originals.json';snapshot.write_text(json.dumps({'entries':original,'feed_config':feed_config,'feed_config_map':original_config_map},ensure_ascii=False,indent=2));snapshot.chmod(0o600)
expected={id:value['published'] for id,value in original.items()}
drafts={};private=[]
entries=c.request('/apis/content.aihyb.run/v1alpha1/contententries?size=1000')['items']
candidates=sorted(item['spec']['contentId'] for item in entries if item['spec']['contentId'].startswith('poc-discovery-unpublished-'))
unpublished_id=candidates[0] if candidates else 'poc-discovery-unpublished-'+run
if candidates:
    existing=detail(unpublished_id)
    assert existing['entry']['published_revision'] is None,'Diagnostic control unexpectedly published'
    owned[unpublished_id]={existing['entry']['draft_revision']}
try:
    report['feed_mode']=feed_config.get('basic',{}).get('descriptionType','excerpt').lower()
    report['index_rebuild_status']=c.request('/apis/api.console.halo.run/v1alpha1/indices/-/rebuild','POST')
    report['initial_index_seconds']=wait_public_index(expected)
    for id,record in original.items():
        value,markers=marker_package(record['published']['kind'],record['draft'],'draft')
        drafts[id]=value;private+=markers;ingest(id,value)
    value,markers=marker_package('tutorial',original['poc-tutorial']['published'],'unpublished')
    private+=markers;ingest(unpublished_id,value,not candidates)
    check('saved-drafts-excerpt',expected,private,unpublished_id)
    for id in drafts:approve(id)
    check('approved-drafts-still-private',expected,private,unpublished_id)
    c.request('/apis/api.console.halo.run/v1alpha1/indices/-/rebuild','POST');report['rebuild_with_drafts_seconds']=wait_public_index(expected)
    check('rebuilt-index-with-drafts',expected,private,unpublished_id)
    # Mark ownership before writing so an interrupted acknowledgement still permits a safe restore.
    config_changed=True
    change_feed_config(original_config_data,temporary_config_data);report['feed_mode']='content'
    # Normal PostUpdated events clear existing RSS caches; no CMS-private cache API is invented.
    for id in drafts:
        ingest(id,drafts[id]);approve(id)
    check('full-feed-with-approved-drafts',expected,private,unpublished_id)
    for id in drafts:publish(id)
    report['publication_search_seconds']=wait_public_index(drafts)
    newer_private=markers[:] # never-published control remains private in all stages
    for id,package in drafts.items():
        value,more=marker_package(package['kind'],package,'third');newer_private+=more;ingest(id,value)
    check('published-B-with-private-C',drafts,newer_private,unpublished_id)
finally:
    errors=[]
    if config_changed:
        try:
            observed=c.request(config_map_path).get('data',{})
            if observed!=original_config_data:change_feed_config(temporary_config_data,original_config_data)
            report['feed_restore_used_versioned_ownership_check']=True
        except Exception as error:errors.append('feed configuration restore: '+str(error))
    for id,record in original.items():
        if id not in owned:continue
        try:
            if public(id)!=record['public_dto']:
                ingest(id,record['published']);publish(id)
            ingest(id,record['draft'])
        except Exception as error:errors.append(id+' restore: '+str(error))
    report['restore_errors']=errors;report['restored']=not errors;checkpoint()
    assert not errors,errors

report['feed_mode']=feed_config.get('basic',{}).get('descriptionType','excerpt').lower()
report['restored_index_seconds']=wait_public_index(expected)
private=[]
for id,record in original.items():
    current=detail(id);draft=json.loads(current['draft_json'])
    for key in ('schema_version','content_id','kind','content','data','sources','assets','provenance'):assert draft.get(key)==record['draft'].get(key),(id,key,'draft restoration mismatch')
    assert public(id)['content']==record['public_dto']['content'] and public(id)['data']==record['public_dto']['data'] and public(id)['sources']==record['public_dto']['sources']
    assert current['entry']['reviewed_revision'] is None
    private.append(record['draft']['content']['title'])
assert c.request(config_path)==feed_config or c.request(config_path)==json.dumps(feed_config),'Feed config not restored'
unpublished=detail(unpublished_id);private.append(json.loads(unpublished['draft_json'])['content']['title'])
check('restored-original-A-and-draft-B',expected,private,unpublished_id)
report['unpublished_control']=unpublished_id;report['complete']=True;checkpoint()
print('Discovery verification complete; all four original public/draft contents and Feed settings restored',flush=True)
