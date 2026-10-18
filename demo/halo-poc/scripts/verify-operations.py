"""Fictional dedicated entry only: native publish, withdrawal, archive and complete history restore."""
import copy
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime,timezone
from poc_client import Client,ROOT,BASE

c=Client();admin='/apis/console.api.aihyb.run/v1alpha1';id='poc-operations-20261017'
name='ap-'+hashlib.sha256(id.encode()).hexdigest();path=admin+'/entries/'+id
run=datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S');counter=0
report={'run':run,'entry':id,'checks':{},'complete':False}
def checkpoint():(ROOT/'evidence/operations-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
def read(path,accept='*/*',data=None):
    headers={'Accept':accept}
    if data is not None:headers['Content-Type']='application/json'
    response=urllib.request.urlopen(urllib.request.Request(BASE+path,json.dumps(data).encode() if data is not None else None,headers),timeout=25)
    return response.read().decode()
def detail():return c.request(path)
def status():return c.request(path+'/operations')
def denied(action,codes):
    try:action()
    except urllib.error.HTTPError as error:
        error.read();assert error.code in codes,error.code;return error.code
    raise AssertionError('Expected refusal')
def submit(package):
    global counter
    package=copy.deepcopy(package);counter+=1;package['content_id']=id;package['submission_id']='ops-'+run+'-'+str(counter)
    try:package['base_revision']=detail()['entry']['draft_revision']
    except urllib.error.HTTPError as error:
        if error.code!=404:raise
        package.pop('base_revision',None)
    return c.request(admin+'/content-packages','POST',package)['revision']
def approve():
    state=status();revision=state['draft_revision']
    c.request(path+'/review','POST',{'revision':revision,'decision':'approve','note':'Operations PoC '+run,'operation_epoch':state['operation_epoch']})
    return revision
def native_publish(revision=None,epoch=None):
    state=status();revision=revision or state['draft_revision'];epoch=epoch or state['operation_epoch']
    head='as-'+hashlib.sha256(revision.encode()).hexdigest()
    return c.request(admin+'/native-posts/'+name+'/publish?headSnapshot='+head+'&operationEpoch='+urllib.parse.quote(epoch),'PUT')
def operate(action):
    value=status();return c.request(path+'/operations','POST',{'version':value['version'],'action':action})
def restore_owned(revision):
    for attempt in range(4):
        before=status()
        try:return c.request(path+'/restore','POST',{'version':before['version'],'revision':revision})
        except urllib.error.HTTPError as error:
            if error.code!=409:raise
            error.read();after=status()
            assert after['draft_revision']==before['draft_revision'] and after['operation_epoch']==before['operation_epoch'],'Another editor changed fixture; refused retry'
            time.sleep(.2)
    raise AssertionError('Fixture restore still conflicted after four unchanged-business-state attempts')
def search(word):return json.loads(read('/apis/api.halo.run/v1alpha1/indices/-/search','application/json',{'keyword':word,'limit':1000}))
feed_paths=['/rss.xml','/feed.xml','/feed/authors/aihyb-admin.xml','/feed/tags/halo.xml','/feed/categories/tutorials.xml']
def excluded(label,marker):
    started=time.monotonic()
    denied(lambda:read('/archives/aihyb-'+id),{404})
    denied(lambda:read('/apis/api.aihyb.run/v1alpha1/contents/'+id,'application/json'),{404})
    for attempt in range(70):
        hits=search(marker)['hits'];feeds=[read(p,'application/rss+xml,text/xml') for p in feed_paths]
        sitemap=read('/sitemap.xml','text/xml')
        if not any(hit.get('metadataName')==name for hit in hits) and all('/archives/aihyb-'+id not in body for body in feeds) and '/archives/aihyb-'+id not in sitemap:
            report['checks'][label]={'body_and_json_404':True,'search_and_all_feed_aliases_excluded':True,'sitemap_excluded':True,'seconds':time.monotonic()-started};checkpoint();return
        time.sleep(.5)
    raise AssertionError('Discovery did not withdraw within 35 seconds')
def visible(marker):
    for attempt in range(40):
        if any(hit.get('metadataName')==name for hit in search(marker)['hits']):return
        time.sleep(.1)
    raise AssertionError('Published fixture not in index')
def baseline():
    return {kind:c.request(admin+'/entries/poc-'+kind) for kind in ('review','tutorial','leaderboard','pricing')}

original=baseline()
try:
    existing=status()
    if existing['archived']:operate('unarchive')
except urllib.error.HTTPError as error:
    if error.code!=404:raise
package=json.loads((ROOT/'package-fixtures/tutorial.json').read_text())
package['content']['title']='运营验证公开A'+run
package['content']['body_markdown']='## 完整运营验证\n\n公开版本A。\n\n[video:bilibili:BV1xx411c7mD]\n'
package['data']['tool_version']='operations-public-a-'+run
a=submit(package);approve();native_publish();visible(package['content']['title'])
live=c.request('/apis/api.aihyb.run/v1alpha1/contents/'+id)
for p in feed_paths:read(p,'application/rss+xml,text/xml')
read('/sitemap.xml','text/xml') # warm every discovery cache before withdrawing
draft=copy.deepcopy(package);draft['content']['title']='operationsprivatetitle'+run;draft['data']['tool_version']='operationsprivatedata'+run
b=submit(draft)
assert c.request('/apis/api.aihyb.run/v1alpha1/contents/'+id)==live
assert search(draft['content']['title'])['hits']==[] and search(draft['data']['tool_version'])['hits']==[]
history=c.request(path+'/history');assert {a,b}.issubset({item['revision'] for item in history['items']})
assert package['content']['title'] in c.request(path+'/history/'+a+'/preview')
assert denied(lambda:c.request(path+'/restore','POST',{'version':status()['version'],'revision':'r-'+'0'*64}),{404})==404
restored=restore_owned(a)
new=restored['entry']['draft_revision'];assert new not in (a,b)
assert json.loads(restored['draft_json'])['content']==package['content']
assert restored['entry']['reviewed_revision'] is None
assert c.request('/apis/api.aihyb.run/v1alpha1/contents/'+id)==live
report['checks']['history_restore']={'new_draft_revision':True,'public_unchanged':True,'approval_cleared':True,'orphan_denied':True}
approve();stale=status();operate('withdraw');excluded('withdrawn',package['content']['title'])
approve() # even a new explicit approval cannot make the old operation epoch valid
denied(lambda:native_publish(new,stale['operation_epoch']),{409})
native_publish();visible(package['content']['title'])
old_state=status();operate('archive');excluded('archived',package['content']['title'])
archived=status();assert archived['archived'] and not archived['published'] and archived['draft_revision']==new
assert archived['reviewed_revision'] is None
denied(lambda:submit(draft),{409});denied(lambda:approve(),{409});denied(lambda:native_publish(),{409})
denied(lambda:c.request(path+'/restore','POST',{'version':status()['version'],'revision':a}),{409})
denied(lambda:c.request(path+'/operations','POST',{'version':old_state['version'],'action':'unarchive'}),{409})
unarchived=operate('unarchive');assert not unarchived['archived'] and not unarchived['published'] and unarchived['reviewed_revision'] is None
denied(lambda:read('/archives/aihyb-'+id),{404})
restored=restore_owned(b)
assert json.loads(restored['draft_json'])['data']['tool_version']==draft['data']['tool_version']
assert restored['entry']['draft_revision'] not in (a,b,new)
approve();native_publish();visible(draft['content']['title'])
assert c.request('/apis/api.aihyb.run/v1alpha1/contents/'+id)['data']['tool_version']==draft['data']['tool_version']
report['checks']['archive_and_republish']={'archive_blocks_save_review_publish_restore':True,'stale_operation_denied':True,'unarchive_does_not_publish':True,'restored_b_requires_new_approval':True,'old_publication_epoch_denied':True}
operate('archive');excluded('final_fixture_archived',draft['content']['title'])
assert baseline()==original,'A four-module baseline draft/public state changed'
report['four_base_modules_unchanged']=True
report['media_configuration']=c.request(admin+'/media-configuration')
report['final_status']=status();report['complete']=True;checkpoint()
print('Operations: native publish / withdrawal / discovery caches / archive / history restore / stale intent PASS; four baselines unchanged',flush=True)
