"""Real public TLS, four modules, media, discovery and anonymous access checks; no content writes."""
import hashlib,json,socket,ssl,time,urllib.error,urllib.parse,urllib.request,xml.etree.ElementTree as ET
from datetime import datetime,timezone
from html.parser import HTMLParser
from poc_client import ROOT

DOMAIN='aihyb.com';BASE='https://'+DOMAIN
report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'origin':BASE,'pages':{},'complete':False}
def checkpoint():(ROOT/'evidence/public-domain-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
def get(path,headers=None,data=None):
    request=urllib.request.Request(BASE+path,json.dumps(data).encode() if data is not None else None,
        {'Accept':'application/json','X-Requested-With':'XMLHttpRequest',**(headers or {}),**({'Content-Type':'application/json'} if data is not None else {})})
    response=urllib.request.urlopen(request,timeout=45)
    assert urllib.parse.urlsplit(response.url).hostname==DOMAIN,'Unexpected redirect to another host'
    return response.status,response.headers,response.read()
def denied(path,codes):
    try:get(path)
    except urllib.error.HTTPError as error:error.read();assert error.code in codes,(path,error.code);return error.code
    raise AssertionError('Anonymous or internal route unexpectedly readable: '+path)
class Page(HTMLParser):
    def __init__(self,raw):super().__init__();self.canonical=[];self.assets=[];self.feed(raw)
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=='link' and a.get('rel')=='canonical':self.canonical.append(a.get('href'))
        if tag=='link' and a.get('rel')=='stylesheet' and a.get('href'):self.assets.append(a['href'])
        if tag=='script' and a.get('src'):self.assets.append(a['src'])

started=time.monotonic()
for attempt in range(30):
    try:
        with socket.create_connection(('5.231.253.4',443),timeout=5) as connection:
            with ssl.create_default_context().wrap_socket(connection,server_hostname=DOMAIN) as tls:
                cert=tls.getpeercert();report['tls']={'protocol':tls.version(),'issuer':cert['issuer'],'expires':cert['notAfter'],'hostname_and_chain_valid':True}
        break
    except (OSError,ssl.SSLError):
        if attempt==29:raise
        time.sleep(2)
report['tls_wait_seconds']=round(time.monotonic()-started,2);checkpoint()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None
try:urllib.request.build_opener(NoRedirect()).open('http://'+DOMAIN+'/',timeout=15)
except urllib.error.HTTPError as error:
    error.read();assert error.code in (301,308) and error.headers['Location']=='https://'+DOMAIN+'/'
    report['http_to_https']=error.code
else:raise AssertionError('HTTP did not redirect to HTTPS')
baseline=json.loads((ROOT/'secrets/public-domain-baseline.json').read_text())
private=[json.loads(baseline['entries']['poc-'+kind]['detail']['draft_json'])['content']['title'] for kind in ('review','tutorial','leaderboard','pricing')]
private+=['PRIVATE_ASSET_NAME_20261017','PRIVATE_MEDIA_DISCOVERY_']
assets=set()
paths=['/','/aihyb']+['/aihyb/'+kind for kind in ('review','tutorial','leaderboard','pricing')]
paths+=['/archives/aihyb-poc-'+kind for kind in ('review','tutorial','leaderboard','pricing','media-20261017')]
for path in paths:
    status,headers,raw=get(path,{'Accept':'text/html'});body=raw.decode();parsed=Page(body)
    assert status==200 and all(marker not in body for marker in private),path
    assert '127.0.0.1:18090' not in body and 'http://aihyb.com' not in body,(path,'old origin')
    if path!='/' or parsed.canonical:
        assert len(parsed.canonical)==1 and urllib.parse.urljoin(BASE+path,parsed.canonical[0])==BASE+path,(path,parsed.canonical)
    assert all('/aihyb/'+kind in body for kind in ('review','tutorial','leaderboard','pricing'))
    for asset in parsed.assets:
        if asset.startswith('/') or asset.startswith(BASE+'/'):assets.add(asset.removeprefix(BASE))
    report['pages'][path]={'status':status,'canonical':parsed.canonical[0] if parsed.canonical else None};checkpoint()
for asset in assets:assert get(asset)[0]==200
report['local_css_js_assets']=len(assets)
for marker in private:
    value=json.loads(get('/apis/api.halo.run/v1alpha1/indices/-/search',data={'keyword':marker,'limit':1000,'filterPublished':False,'filterExposed':False,'filterRecycled':True})[2])
    assert marker not in json.dumps(value['hits'],ensure_ascii=False)
for path in ['/rss.xml','/feed.xml','/feed/authors/aihyb-admin.xml','/feed/tags/halo.xml']+['/feed/categories/'+kind+'.xml' for kind in ('reviews','tutorials','leaderboards','prices')]+['/sitemap.xml']:
    raw=get(path,{'Accept':'application/rss+xml,text/xml'})[2].decode();ET.fromstring(raw)
    assert all(marker not in raw for marker in private) and '127.0.0.1:18090' not in raw and 'aihyb-poc-operations-20261017' not in raw,path
report['search_feed_sitemap_private_excluded']=True
report['blocked_internal_routes']={path:denied(path,{404}) for path in ('/actuator','/actuator/health/readiness','/v3/api-docs/aihybApis','/swagger-ui.html')}
admin='/apis/console.api.aihyb.run/v1alpha1'
report['anonymous_private_routes']={path:denied(path,{401,403}) for path in (admin+'/entries/poc-review',admin+'/entries/poc-media-20261017/operations','/apis/content.aihyb.run/v1alpha1/contentrevisions?size=1')}
view=json.loads(get('/apis/api.aihyb.run/v1alpha1/contents/poc-media-20261017')[2]);assert len(view['assets'])==3
expected=json.loads((ROOT/'evidence/media-post-restart.json').read_text())['public_media_hashes'];media={}
for asset in view['assets']:
    status,headers,raw=get(asset['url'].removeprefix(BASE));assert status==200 and hashlib.sha256(raw).hexdigest()==expected[asset['asset_id']]
    assert 'no-store' in headers.get('Cache-Control','') and not headers.get('Location')
    media[asset['asset_id']]={'bytes':len(raw),'sha256_matches':True}
    if asset['asset_id']=='video':
        code,ranged,body=get(asset['url'].removeprefix(BASE),{'Range':'bytes=0-127'});assert code==206 and body==raw[:128]
        media['video']['range_status']=code
report['media']=media
report['archived_entry_denied']=denied('/archives/aihyb-poc-operations-20261017',{404})
report['complete']=True;checkpoint()
print('Public trusted HTTPS / HTTP redirect / four modules / assets / media Range / discovery / private route protections PASS',flush=True)
