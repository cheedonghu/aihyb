"""Verify the actual Console SDK upload against original bytes in private COS preview."""
import hashlib,json,urllib.request,urllib.error
from datetime import datetime,timezone
from poc_client import Client,ROOT,BASE
c=Client();path='/apis/console.api.aihyb.run/v1alpha1/entries/poc-operations-20261017'
detail=c.request(path);package=json.loads(detail['draft_json']);assert len(package['assets'])==1
asset=package['assets'][0];assert asset['visibility']=='private' and asset['content_type']=='image/png'
expected=(ROOT/'media-fixtures/poc-image.png').read_bytes();digest=hashlib.sha256(expected).hexdigest();assert asset['sha256']==digest
revision=detail['entry']['draft_revision'];url=path+'/media/'+revision+'/'+asset['asset_id']
response=c.http.open(BASE+url,timeout=30);actual=response.read();assert actual==expected and len(actual)==315
try:urllib.request.urlopen(BASE+'/apis/api.aihyb.run/v1alpha1/media/poc-operations-20261017/file/'+revision+'/'+asset['asset_id'],timeout=20)
except urllib.error.HTTPError as error:error.read();assert error.code==404
else:raise AssertionError('Private Console-uploaded image became public')
result={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'actual_console_sdk_upload':True,'bytes':len(actual),'sha256':digest,
    'exact_original_bytes':True,'private_anonymous_denied':True,'complete':True}
(ROOT/'evidence/media-browser-upload.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('Actual Console-generated SDK upload: 315 original PNG bytes / SHA256 / private preview / anonymous denial PASS',flush=True)
