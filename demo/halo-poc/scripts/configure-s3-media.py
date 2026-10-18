"""Create the dedicated PoC S3 policy from an explicitly supplied private file. Never print secrets."""
import json
import sys
import urllib.error
from pathlib import Path
from poc_client import Client,ROOT

source=Path(sys.argv[1]);value=json.loads(source.read_text(encoding='utf-8-sig'))
required=('bucket','region','endpoint','accessKey','accessSecret')
assert all(isinstance(value.get(k),str) and value[k].strip() and '请填写' not in value[k] for k in required),'S3 configuration is incomplete'
assert value['bucket']=='pt-1305414590' and value['region']=='ap-shanghai','Refused unexpected PoC destination'
assert value['endpoint']=='cos.ap-shanghai.myqcloud.com','Refused unexpected S3 endpoint'
value.update(endpointProtocol='https',protocol='https',enablePathStyleAccess=False,randomFilenameMode='uuid',duplicateFilenameHandling='exception',location='aihyb-poc')
c=Client();config_name='aihyb-media-s3-config';policy_name='aihyb-media-private'
config_path='/api/v1alpha1/configmaps/'+config_name
policy_path='/apis/storage.halo.run/v1alpha1/policies/'+policy_name
def existing(path):
    try:return c.request(path)
    except urllib.error.HTTPError as error:
        if error.code!=404:raise
        return None
if existing(config_path) is not None or existing(policy_path) is not None:
    raise RuntimeError('Dedicated configuration already exists; refused overwriting credentials or storage mapping')
config={'apiVersion':'v1alpha1','kind':'ConfigMap','metadata':{'name':config_name},'data':{'default':json.dumps(value)}}
policy={'apiVersion':'storage.halo.run/v1alpha1','kind':'Policy','metadata':{'name':policy_name},'spec':{'displayName':'AI好用榜私有媒体（S3兼容）','templateName':'s3os','configMapName':config_name}}
c.request('/api/v1alpha1/configmaps','POST',config)
c.request('/apis/storage.halo.run/v1alpha1/policies','POST',policy)
print('Dedicated private-media S3 policy configured; native defaults unchanged')
