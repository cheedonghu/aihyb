"""Existing isolated role identities; mutations restricted to the archived operations fixture."""
import json,sys,urllib.error,urllib.parse,urllib.request
from poc_client import Client,ROOT,BASE
admin='/apis/console.api.aihyb.run/v1alpha1';id='poc-operations-20261017';path=admin+'/entries/'+id
report_path=ROOT/'evidence/operations-role-verification.json';report=json.loads(report_path.read_text()) if report_path.exists() else {}
def deny(action):
    try:action()
    except urllib.error.HTTPError as error:error.read();assert error.code==403,error.code;return True
    raise AssertionError('Expected 403')
for role in sys.argv[1:]:
    user='poc-package-'+role;c=Client(user,ROOT/'secrets'/(user+'.txt'));result={}
    if role=='importer':
        # Upload must reach domain validation rather than RBAC: fixture is archived.
        try:c.request(path+'/assets?filename=role.txt','POST',raw=b'role-only fictional test',content_type='text/plain')
        except urllib.error.HTTPError as error:error.read();assert error.code==409,error.code;result['upload_permission_reaches_archive_guard']=True
        for endpoint in ('operations','history','preview','history/not-a-revision/preview','media/not-a-revision/not-an-asset'):
            result[endpoint+'_denied']=deny(lambda ep=endpoint:c.request(path+'/'+ep))
    else:
        state=c.request(path+'/operations');assert state['archived'];history=c.request(path+'/history?size=1');assert history['items']
        revision=history['items'][0]['revision'];assert isinstance(c.request(path+'/history/'+revision+'/preview'),str)
        result['read_status_history_preview']=True
        # An editor is allowed to restore, but the archived-entry domain guard must reject it.
        try:c.request(path+'/restore','POST',{'version':state['version'],'revision':revision})
        except urllib.error.HTTPError as error:error.read();assert error.code==409,error.code;result['restore_permission_reaches_archive_guard']=True
    if role!='publisher':
        result['operations_write_denied']=deny(lambda:c.request(path+'/operations','POST',{'version':'stale','action':'unarchive'}))
        result['publish_denied']=deny(lambda:c.request(path+'/publish','POST',{'revision':'stale'}))
    else:
        state=c.request(path+'/operations');c.request(path+'/operations','POST',{'version':state['version'],'action':'unarchive'})
        assert not c.request(path+'/operations')['published']
        state=c.request(path+'/operations');c.request(path+'/operations','POST',{'version':state['version'],'action':'archive'})
        assert c.request(path+'/operations')['archived'];result['unarchive_no_publish_and_rearchive']=True
    report[role]=result;report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(role,'operations/media RBAC PASS',flush=True)
