"""Native endpoint RBAC using existing isolated PoC identities; saves only no-op versions."""
import hashlib
import json
import sys
import urllib.error
from poc_client import Client, ROOT
admin='/apis/console.api.aihyb.run/v1alpha1'; core='/apis/content.halo.run/v1alpha1'
console='/apis/api.console.halo.run/v1alpha1'
def native(c,path,method='GET',data=None):
    try: return c.request(path,method,data)
    except urllib.error.HTTPError as error:
        if error.code!=307: raise
        location=error.headers['Location']; assert location.startswith(admin+'/')
        return c.request(location,method,data)
def deny(action,codes=(401,403)):
    try: action()
    except urllib.error.HTTPError as error:
        error.read(); assert error.code in codes,error.code; return error.code
    raise AssertionError('Expected authorization refusal')
result={}
for role in sys.argv[1:]:
    username='poc-package-'+role; c=Client(username,ROOT/'secrets'/(username+'.txt'))
    id='poc-theme-render'; name='ap-'+hashlib.sha256(id.encode()).hexdigest()
    if role=='importer':
        result[role]={'native_editor_read_denied':deny(lambda:native(c,core+'/posts/'+name)),
            'native_publish_denied':deny(lambda:native(c,console+'/posts/'+name+'/publish','PUT'))}
        continue
    post=native(c,core+'/posts/'+name)
    saved=native(c,core+'/posts/'+name,'PUT',post)
    revision=saved['metadata']['annotations']['aihyb.run/draft-revision']
    head=native(c,console+'/posts/'+name+'/head-content')
    body=native(c,console+'/posts/'+name+'/content','PUT',{'raw':head['raw'],'version':head['version'],'rawType':'MARKDOWN'})
    assert body['metadata']['annotations']['aihyb.run/draft-revision']==revision
    publish_path=console+'/posts/'+name+'/publish?headSnapshot='+saved['spec']['headSnapshot']
    result[role]={'native_read_and_noop_metadata_body_save':True}
    if role=='editor':
        result[role]['review_denied']=deny(lambda:c.request(admin+'/entries/'+id+'/review','POST',{'revision':revision,'decision':'approve'}))
        result[role]['publish_denied']=deny(lambda:native(c,publish_path,'PUT'))
    elif role=='reviewer':
        c.request(admin+'/entries/'+id+'/review','POST',{'revision':revision,'decision':'approve','note':'Native-role PoC'})
        result[role]['review_allowed']=True
        result[role]['publish_denied']=deny(lambda:native(c,publish_path,'PUT'))
    else:
        result[role]['publish_without_explicit_version']=deny(lambda:native(c,console+'/posts/'+name+'/publish','PUT'),(409,))
        result[role]['stale_snapshot_publish']=deny(lambda:native(c,console+'/posts/'+name+'/publish?headSnapshot=as-stale','PUT'),(409,))
        native(c,publish_path,'PUT'); result[role]['approved_exact_snapshot_publish']=True
path=ROOT/'evidence/native-role-verification.json'
combined=json.loads(path.read_text()) if path.exists() else {}
combined.update(result); path.write_text(json.dumps(combined,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False))
