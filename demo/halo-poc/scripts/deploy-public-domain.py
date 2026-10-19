"""Expose only this Halo through a dedicated HTTPS entry; preserve other services and private data."""
import hashlib,json,os,socket,subprocess,sys,time,urllib.request
from datetime import datetime,timezone
from pathlib import Path
from poc_client import Client,ROOT

DOMAIN='aihyb.com';ADDRESS='5.231.253.4';ORIGIN='https://'+DOMAIN
COMPOSE='compose.yaml:compose.media.yaml:compose.public.yaml'
BASELINE=ROOT/'secrets/public-domain-baseline.json'
def command(*args):return subprocess.check_output(args,cwd=ROOT,text=True).strip()
def run(*args):subprocess.run(args,cwd=ROOT,check=True)
def all_items(client,path):
    value=client.request(path+'?size=1000');assert len(value['items'])==value['total'];return value['items']
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
def state():
    client=Client();result={'entries':{},'revisions':{},'snapshots':{}}
    for entry in all_items(client,'/apis/content.aihyb.run/v1alpha1/contententries'):
        id=entry['spec']['contentId'];name='ap-'+hashlib.sha256(id.encode()).hexdigest()
        post=client.request('/apis/content.halo.run/v1alpha1/posts/'+name)
        detail=client.request('/apis/console.api.aihyb.run/v1alpha1/entries/'+id)
        result['entries'][id]={'detail':detail,'post_spec':post['spec'],
            'annotations':{k:v for k,v in post['metadata']['annotations'].items() if k.startswith('aihyb.run/')}}
    for rev in all_items(client,'/apis/content.aihyb.run/v1alpha1/contentrevisions'):result['revisions'][rev['metadata']['name']]=digest(rev['spec'])
    for snap in all_items(client,'/apis/content.halo.run/v1alpha1/snapshots'):
        if snap['metadata']['name'].startswith('as-'):result['snapshots'][snap['metadata']['name']]=digest(snap['spec'])
    return result
def prepare():
    assert ROOT.resolve()==Path('/opt/aihyb-halo-poc')
    assert command('sysctl','-n','net.ipv4.ip_forward')=='0'
    for port in (80,443):
        with socket.socket() as probe:probe.bind(('0.0.0.0',port))
    dns=json.load(urllib.request.urlopen('https://dns.google/resolve?name='+DOMAIN+'&type=A',timeout=20))
    answers=[a['data'] for a in dns.get('Answer',[]) if a['type']==1];assert answers==[ADDRESS],answers
    BASELINE.write_text(json.dumps(state(),ensure_ascii=False));BASELINE.chmod(0o600)
    run('docker','pull','caddy:2-alpine')
    image=json.loads(command('docker','image','inspect','caddy:2-alpine','--format','{{json .RepoDigests}}'))[0]
    assert image.startswith('caddy@sha256:'),image
    env=ROOT/'.env';raw=env.read_text();values={}
    for line in raw.splitlines():
        if '=' in line and not line.startswith('#'):
            key,value=line.split('=',1);values[key]=value
    assert values.get('COMPOSE_FILE',COMPOSE)==COMPOSE,'Refused changing an unfamiliar Compose file set'
    assert 'CADDY_IMAGE' not in values,'Public deployment already prepared; refused automatic overwrite'
    original_env=ROOT/'secrets/public-domain-original.env'
    assert not original_env.exists(),'Existing private pre-deployment configuration must not be overwritten'
    original_env.write_text(raw);original_env.chmod(0o600)
    raw=raw.rstrip()+'\nCADDY_IMAGE='+image+'\n'
    if 'COMPOSE_FILE' not in values:raw+='COMPOSE_FILE='+COMPOSE+'\n'
    env.write_text(raw);env.chmod(0o600)
    for name in ('data/caddy/data','data/caddy/config'):(ROOT/name).mkdir(parents=True,exist_ok=True,mode=0o700)
    run('docker','compose','config','--quiet')
    run('docker','compose','run','--rm','--no-deps','--entrypoint','caddy','caddy','validate','--config','/etc/caddy/Caddyfile','--adapter','caddyfile')
    report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'domain':DOMAIN,'dns_a':answers,'caddy_image':image,
        'ports_previously_free':[80,443],'global_forwarding':'0','prepared':True}
    (ROOT/'evidence/public-domain-preflight.json').write_text(json.dumps(report,indent=2))
    print('Public HTTPS configuration validated; image pinned; private business baseline saved',flush=True)
def deploy():
    assert BASELINE.exists()
    run('docker','compose','up','-d','--no-deps','halo')
    run('python3','wait-halo.py')
    run('docker','compose','up','-d','--no-deps','caddy')
    print('Only own Halo recreated and own Caddy started; waiting for normal public TLS issuance',flush=True)
def origin_setting():
    # Halo 2.26 uses database system.basic.externalUrl before the process property.
    client=Client();path='/api/v1alpha1/configmaps/system';config=client.request(path)
    basic=json.loads(config['data'].get('basic','{}'));current=basic.get('externalUrl')
    assert current in ('http://127.0.0.1:18090',ORIGIN),'Refused replacing an unfamiliar public origin'
    if current==ORIGIN:
        print('Halo system public origin already matches the domain',flush=True);return
    saved=ROOT/'secrets/public-domain-original-system.json';assert not saved.exists(),'Existing original configuration must not be overwritten'
    saved.write_text(json.dumps(config,ensure_ascii=False));saved.chmod(0o600)
    basic['externalUrl']=ORIGIN;config['data']['basic']=json.dumps(basic,ensure_ascii=False)
    client.request(path,'PUT',config)
    seen=client.request(path);assert json.loads(seen['data']['basic'])['externalUrl']==ORIGIN
    print('Only Halo system.basic.externalUrl updated by native ConfigMap CAS',flush=True)
def compare():
    previous=json.loads(BASELINE.read_text());current=state()
    for id,entry in previous['entries'].items():
        url=entry['detail']['entry'].get('public_url')
        if url:entry['detail']['entry']['public_url']=url.replace('http://127.0.0.1:18090',ORIGIN)
    assert current==previous,'Business state changed beyond expected public URL replacement'
    report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'entries':len(current['entries']),
        'revisions':len(current['revisions']),'snapshots':len(current['snapshots']),'business_unchanged_except_derived_public_origin':True,'complete':True}
    (ROOT/'evidence/public-domain-persistence.json').write_text(json.dumps(report,indent=2))
    print('All package drafts / approvals / publications / immutable revisions / snapshots preserved',flush=True)
if __name__=='__main__':
    # SUPERSEDED 2026-10-18: aihyb.com stopped resolving (ICP filing); the entry is now plain HTTP
    # on the IP. Re-running this would point Halo back at a dead domain. Use deploy-ip-http.py.
    raise SystemExit('Superseded: aihyb.com is retired. Use deploy-ip-http.py for the IP entry.')
