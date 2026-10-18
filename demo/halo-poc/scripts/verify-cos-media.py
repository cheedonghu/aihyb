"""Real COS smoke/integration check with <4 KiB of generated fictional image/video/files."""
import copy
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime,timezone
from poc_client import Client,ROOT,BASE

c=Client();admin='/apis/console.api.aihyb.run/v1alpha1';id='poc-media-20261017';path=admin+'/entries/'+id
name='ap-'+hashlib.sha256(id.encode()).hexdigest();run=datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S');sequence=0
report={'run':run,'entry':id,'uploads':[],'checks':{},'complete':False}
def checkpoint():(ROOT/'evidence/cos-media-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
def status():return c.request(path+'/operations')
def detail():return c.request(path)
def deny(action,codes):
    try:action()
    except urllib.error.HTTPError as error:
        error.read();assert error.code in codes,error.code;return error.code
    raise AssertionError('Expected refusal')
def public_get(url,headers=None):
    if url.startswith('/'):url=BASE+url
    response=urllib.request.urlopen(urllib.request.Request(url,headers=headers or {}),timeout=30)
    return response.status,response.headers,response.read()
def submit(payload):
    global sequence
    value=copy.deepcopy(payload);sequence+=1;value['content_id']=id;value['submission_id']='media-'+run+'-'+str(sequence)
    try:value['base_revision']=detail()['entry']['draft_revision']
    except urllib.error.HTTPError as error:
        if error.code!=404:raise
        value.pop('base_revision',None)
    return c.request(admin+'/content-packages','POST',value)['revision']
def publish():
    state=status();revision=state['draft_revision']
    c.request(path+'/review','POST',{'revision':revision,'decision':'approve','note':'Real COS integration PoC','operation_epoch':state['operation_epoch']})
    c.request(admin+'/native-posts/'+name+'/publish?headSnapshot=as-'+hashlib.sha256(revision.encode()).hexdigest()+'&operationEpoch='+state['operation_epoch'],'PUT')
    return revision
def operate(action):return c.request(path+'/operations','POST',{'version':status()['version'],'action':action})
def baseline():return {kind:c.request(admin+'/entries/poc-'+kind) for kind in ('review','tutorial','leaderboard','pricing')}

original=baseline();assert c.request(admin+'/media-configuration')['configured']
try:
    if status()['archived']:operate('unarchive')
except urllib.error.HTTPError as error:
    if error.code!=404:raise
package=json.loads((ROOT/'package-fixtures/tutorial.json').read_text())
package['content']['title']='媒体验证：图片、附件、视频与审核隔离'
package['content']['body_markdown']='## S3 私有存储验证\n\n本站生成的虚构媒体样例。\n\n[video:bilibili:BV1xx411c7mD]\n'
submit(package)
files=[('picture','poc-image.png','image/png','public'),('download','poc-attachment.txt','text/plain','public'),
       ('video','poc-video.mp4','video/mp4','public'),('internal','poc-private.txt','text/plain','private')]
uploaded={}
for asset_id,filename,mime,visibility in files:
    file=ROOT/'media-fixtures'/filename;raw=file.read_bytes()
    receipt=c.request(path+'/assets?filename='+urllib.parse.quote(filename),'POST',raw=raw,content_type=mime)
    assert receipt['sha256']==hashlib.sha256(raw).hexdigest() and receipt['bytes']==len(raw)
    asset={'asset_id':asset_id,'object_key':receipt['object_key'],'content_type':mime,'sha256':receipt['sha256'],
           'alt':'PRIVATE_ASSET_NAME_20261017' if visibility=='private' else filename,'visibility':visibility}
    uploaded[asset_id]=(asset,raw)
    report['uploads'].append({'asset_id':asset_id,'bytes':len(raw),'sha256_matches':True,'visibility':visibility});checkpoint()
    native=c.request('/apis/storage.halo.run/v1alpha1/attachments/'+receipt['object_key'].split('/',1)[1])
    # The unsigned object address stays private, irrespective of package visibility.
    permalink=native.get('status',{}).get('permalink')
    if permalink:
        deny(lambda:public_get(permalink),{401,403})
    deny(lambda:c.request('/apis/storage.halo.run/v1alpha1/attachments/'+native['metadata']['name'],'DELETE'),{403})
    print('Real COS upload/private-object/hash/native-delete guard PASS:',asset_id,flush=True)
package['assets']=[value[0] for value in uploaded.values()]
package['content']['body_markdown']+='\n![图片](asset://picture)\n\n[播放视频](asset://video)\n\n[下载附件](asset://download)\n'
a=submit(package)
for asset_id,(asset,raw) in uploaded.items():
    private_url=path+'/media/'+a+'/'+asset_id
    assert c.http.open(BASE+private_url,timeout=30).read()==raw
    deny(lambda:public_get(private_url,{'Accept':'application/json','X-Requested-With':'XMLHttpRequest'}),{401,403})
    deny(lambda:public_get('/apis/api.aihyb.run/v1alpha1/media/'+id+'/file/' +a+'/'+asset_id),{404})
report['checks']['draft_all_media_private']=True;checkpoint()
publish()
for asset_id,(asset,raw) in uploaded.items():
    url='/apis/api.aihyb.run/v1alpha1/media/'+id+'/file/' +a+'/'+asset_id
    if asset['visibility']=='private':deny(lambda:public_get(url),{404});continue
    code,headers,body=public_get(url);assert code==200 and body==raw
    assert headers.get('Location') is None and 'no-store' in headers.get('Cache-Control','')
    if asset_id=='download':assert headers.get('Content-Disposition','').startswith('attachment;')
    if asset_id=='video':
        code,headers,body=public_get(url,{'Range':'bytes=0-127'});assert code==206 and body==raw[:128] and headers.get('Content-Range','').startswith('bytes 0-127/')
        deny(lambda:public_get(url,{'Range':'bytes=99999999-'}),{416})
view=c.request('/apis/api.aihyb.run/v1alpha1/contents/'+id);serialized=json.dumps(view,ensure_ascii=False)
assert len(view['assets'])==3 and 'PRIVATE_ASSET_NAME_20261017' not in serialized and 'attachments/' not in serialized and 'X-Amz-' not in serialized
for url in ('/archives/aihyb-'+id,'/rss.xml','/feed.xml','/feed/categories/tutorials.xml'):
    accept={'Accept':'application/rss+xml,text/xml'} if url.endswith('.xml') else {}
    raw=public_get(url,accept)[2].decode()
    assert 'PRIVATE_ASSET_NAME_20261017' not in raw and 'X-Amz-' not in raw and 'myqcloud.com' not in raw
report['checks']['published_image_attachment_video_range_and_private_exclusion']=True;checkpoint()
newer=copy.deepcopy(package);newer['content']['title']='PRIVATE_MEDIA_DRAFT_B_'+run
b=submit(newer)
deny(lambda:public_get('/apis/api.aihyb.run/v1alpha1/media/'+id+'/file/' +b+'/picture'),{404})
assert public_get('/apis/api.aihyb.run/v1alpha1/media/'+id+'/file/' +a+'/picture')[2]==uploaded['picture'][1]
approve_state=status();c.request(path+'/review','POST',{'revision':b,'decision':'approve','note':'Media version isolation','operation_epoch':approve_state['operation_epoch']})
deny(lambda:public_get('/apis/api.aihyb.run/v1alpha1/media/'+id+'/file/' +b+'/picture'),{404})
publish();deny(lambda:public_get('/apis/api.aihyb.run/v1alpha1/media/'+id+'/file/' +a+'/picture'),{404})
assert public_get('/apis/api.aihyb.run/v1alpha1/media/'+id+'/file/' +b+'/picture')[2]==uploaded['picture'][1]
operate('withdraw')
for asset_id in uploaded:deny(lambda:public_get('/apis/api.aihyb.run/v1alpha1/media/'+id+'/file/' +b+'/'+asset_id),{404})
assert c.http.open(BASE+path+'/media/'+b+'/internal',timeout=30).read()==uploaded['internal'][1]
report['checks']['new_draft_approval_old_revision_and_withdrawal_isolation']=True
state=status();restored=c.request(path+'/restore','POST',{'version':state['version'],'revision':a})
assert json.loads(restored['draft_json'])['assets']==package['assets'] and restored['entry']['reviewed_revision'] is None
package['content']['title']='媒体验证：图片、附件、视频与审核隔离';submit(package);final=publish()
assert baseline()==original
report['four_base_modules_unchanged']=True;report['final_public_revision']=final;report['final_status']=status();report['complete']=True;checkpoint()
print('Real COS image / download / MP4 Range / private preview / old revision denial / withdrawal / complete restore PASS',flush=True)
