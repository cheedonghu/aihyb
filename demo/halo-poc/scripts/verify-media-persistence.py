"""Freeze/check full business state across own-Halo restart; private baseline never exported."""
import hashlib,json,sys,urllib.request
from datetime import datetime,timezone
from poc_client import Client,ROOT,BASE
c=Client();admin='/apis/console.api.aihyb.run/v1alpha1';baseline=ROOT/'secrets/media-persistence-baseline.json'
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
def items(path):
    value=c.request(path+'?size=1000');assert len(value['items'])==value['total'];return value['items']
def state():
    result={'entries':{},'revisions':{},'snapshots':{},'media':{}}
    for entry in items('/apis/content.aihyb.run/v1alpha1/contententries'):
        id=entry['spec']['contentId'];post=c.request('/apis/content.halo.run/v1alpha1/posts/ap-'+hashlib.sha256(id.encode()).hexdigest())
        result['entries'][id]={'detail':c.request(admin+'/entries/'+id),'post_spec':post['spec'],
            'annotations':{k:v for k,v in post['metadata']['annotations'].items() if k.startswith('aihyb.run/')}}
    for rev in items('/apis/content.aihyb.run/v1alpha1/contentrevisions'):result['revisions'][rev['metadata']['name']]=digest(rev['spec'])
    for snap in items('/apis/content.halo.run/v1alpha1/snapshots'):
        if snap['metadata']['name'].startswith('as-'):result['snapshots'][snap['metadata']['name']]=digest(snap['spec'])
    public=c.request('/apis/api.aihyb.run/v1alpha1/contents/poc-media-20261017')
    for asset in public['assets']:
        url=asset['url'];url=BASE+url if url.startswith('/') else url
        response=urllib.request.urlopen(url,timeout=30);assert response.status==200 and 'no-store' in response.headers.get('Cache-Control','')
        result['media'][asset['asset_id']]=hashlib.sha256(response.read()).hexdigest()
    return result
actual=state()
if sys.argv[1]=='freeze':
    baseline.write_text(json.dumps(actual,ensure_ascii=False));baseline.chmod(0o600);print('Private complete business/media baseline frozen',flush=True)
else:
    assert actual==json.loads(baseline.read_text()),'Business/media state changed across restart'
    report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'entries':len(actual['entries']),'revisions':len(actual['revisions']),
        'snapshots':len(actual['snapshots']),'public_media_hashes':actual['media'],'all_business_state_unchanged':True,'complete':True}
    (ROOT/'evidence/media-post-restart.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print('All business state / immutable revisions / native snapshots / real COS bytes unchanged across restart',flush=True)
