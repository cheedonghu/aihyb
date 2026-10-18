"""Read real anonymous discovery surfaces; modify only the dedicated media fixture and reversible Feed setting."""
import copy, json, time, urllib.request, urllib.parse, xml.etree.ElementTree as ET
from datetime import datetime,timezone
from poc_client import Client,ROOT,BASE
c=Client();admin='/apis/console.api.aihyb.run/v1alpha1';id='poc-media-20261017';path=admin+'/entries/'+id
run=datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S');marker='PRIVATE_MEDIA_DISCOVERY_'+run
original={k:c.request(admin+'/entries/poc-'+k) for k in ('review','tutorial','leaderboard','pricing')}
current=c.request(path);package=json.loads(current['draft_json']);package['base_revision']=current['entry']['draft_revision'];package['submission_id']='media-discovery-'+run
package['content']['title']=marker;package['content']['body_markdown']+='\n\n'+marker+'_BODY';package['data']['tool_version']=marker+'_DATA'
c.request(admin+'/content-packages','POST',package)
plugin=c.request('/apis/plugin.halo.run/v1alpha1/plugins/PluginFeed');config='/api/v1alpha1/configmaps/'+plugin['spec']['configMapName']
before=c.request(config);original_data=copy.deepcopy(before.get('data',{}));owned=None
report={'run':run,'checks':{},'complete':False,'feed_config_restored':False}
def checkpoint():(ROOT/'evidence/media-discovery-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
def get(path,data=None,accept='application/json'):
    request=urllib.request.Request(BASE+path,json.dumps(data).encode() if data else None,{'Accept':accept,'Content-Type':'application/json'})
    response=urllib.request.urlopen(request,timeout=30)
    assert '/login' not in urllib.parse.urlsplit(response.url).path,'Unexpected login redirect'
    return response.read().decode()
private=[marker,'PRIVATE_ASSET_NAME_20261017']+[ 'THEME_DRAFT_'+k for k in original ]
def check(mode):
    count=0
    for token in private:
        for override in (False,True):
            body={'keyword':token,'limit':1000}
            if override:body.update(filterPublished=False,filterExposed=False,filterRecycled=True)
            assert json.loads(get('/apis/api.halo.run/v1alpha1/indices/-/search',body))['hits']==[]
    feeds=['/rss.xml','/feed.xml','/feed/authors/aihyb-admin.xml','/feed/tags/halo.xml']+['/feed/categories/'+k+'.xml' for k in ('reviews','tutorials','leaderboards','prices')]
    for route in feeds:
        raw=get(route,accept='application/rss+xml,text/xml');xml=ET.fromstring(raw)
        assert all(token not in raw for token in private) and 'X-Amz-' not in raw and 'myqcloud.com' not in raw
        if mode=='content' and route in ('/rss.xml','/feed.xml','/feed/categories/tutorials.xml'):
            matching=[item for item in xml.findall('.//item') if '/archives/aihyb-'+id==(urllib.parse.urlsplit(item.findtext('link') or '').path)]
            assert len(matching)==1
            assert '/file/' in (matching[0].findtext('description') or ''),'Full Feed did not contain published media'
        count+=1
    for route in ('/sitemap.xml','/archives/aihyb-'+id,'/apis/api.aihyb.run/v1alpha1/contents/'+id):
        raw=get(route,accept='*/*');assert all(token not in raw for token in private)
        assert 'X-Amz-' not in raw and 'myqcloud.com' not in raw
    report['checks'][mode]={'private_queries_with_overrides':len(private)*2,'feed_routes':count,'page_dto_sitemap_private_excluded':True};checkpoint()
def set_config(expected,target):
    value=c.request(config);assert value.get('data',{})==expected,'Feed changed by another operator; refused overwrite'
    value['data']=copy.deepcopy(target);c.request(config,'PUT',value)
    target_mode=json.loads(target.get('basic','{}')).get('descriptionType','excerpt')
    for attempt in range(40):
        seen=c.request('/apis/api.console.halo.run/v1alpha1/plugins/PluginFeed/json-config')
        if isinstance(seen,str):seen=json.loads(seen)
        if seen.get('basic',{}).get('descriptionType','excerpt')==target_mode:return
        time.sleep(.1)
    raise AssertionError('Feed setting watcher did not converge')
try:
    check('default')
    owned=copy.deepcopy(original_data);basic=json.loads(owned.get('basic','{}'));basic['descriptionType']='content';owned['basic']=json.dumps(basic,ensure_ascii=False)
    set_config(original_data,owned);check('content')
finally:
    if owned is not None:set_config(owned,original_data)
    report['feed_config_restored']=True;checkpoint()
assert original=={k:c.request(admin+'/entries/poc-'+k) for k in original}
report['four_base_modules_unchanged']=True;report['complete']=True;checkpoint()
print('Anonymous search overrides / eight Feed routes in default+full modes / sitemap / media draft isolation PASS; Feed setting restored',flush=True)
