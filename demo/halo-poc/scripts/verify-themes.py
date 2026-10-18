"""Theme A/B/A validation. Switches only named PoC themes, never changes site content during checks."""
import copy
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from poc_client import Client, ROOT, BASE

c = Client(); admin = '/apis/console.api.aihyb.run/v1alpha1'
theme_api = '/apis/api.console.halo.run/v1alpha1/themes'
run = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
def native_name(id): return 'ap-' + hashlib.sha256(id.encode()).hexdigest()
def publish(package):
    r = c.request(admin + '/content-packages', 'POST', package)
    c.request(admin + '/entries/' + package['content_id'] + '/review', 'POST', {'revision': r['revision'], 'decision': 'approve'})
    c.request(admin + '/native-posts/' + native_name(package['content_id']) + '/publish?headSnapshot=as-' + hashlib.sha256(r['revision'].encode()).hexdigest(), 'PUT')
    return r
def seed():
    package = json.loads((ROOT / 'package-fixtures/tutorial.json').read_text())
    package['content_id'] = 'poc-theme-render'; package['submission_id'] = 'theme-render-' + run
    try: package['base_revision'] = c.request(admin + '/entries/poc-theme-render')['entry']['draft_revision']
    except urllib.error.HTTPError as error:
        if error.code != 404: raise
    package['content']['title'] = '主题验证：代码、提示词、图片与长表格'
    package['content']['body_markdown'] = '## Markdown 渲染样例\n\n```javascript\nconst price = 49;\nconsole.log("核对原始资料", price);\n```\n\n## 提示词\n\n```text\n请指出资料中的未知项，并保留原文链接。\n```\n\n![自建 PoC 图片](/aihyb/poc-sample.svg)\n\n| 字段 | 说明 | 来源 | 核验 | 限制 | 适用范围 |\n|---|---|---|---|---|---|\n| 中文长字段 | Markdown 与结构化数据共用版本 | 本站虚构数据 | pending | 人工核对 | PoC |\n'
    publish(package)
    # Freeze genuine unpublished updates to all four modules across the theme switch.
    for kind in ('review', 'tutorial', 'leaderboard', 'pricing'):
        id = 'poc-' + kind; detail = c.request(admin + '/entries/' + id)
        draft = json.loads(detail['draft_json']); draft['submission_id'] = 'theme-draft-' + kind + '-' + run
        draft['base_revision'] = detail['entry']['draft_revision']
        draft['content']['title'] = 'THEME_DRAFT_' + kind
        draft['data']['task'] = 'theme-private-' + kind
        if kind == 'pricing': draft['data']['plans'][0]['amount'] = '59.91'
        elif kind == 'leaderboard': draft['data']['entries'][0]['score'] = '99.91'
        elif kind == 'tutorial': draft['data']['tool_version'] = 'theme-private-v2'
        else: draft['data']['conclusions'][0]['text'] = 'THEME_DRAFT_review conclusion'
        c.request(admin + '/content-packages', 'POST', draft)
    time.sleep(1)
    (ROOT / 'evidence/theme-content-baseline.json').write_text(json.dumps(state(), ensure_ascii=False, indent=2))
    print('Published render fixture and froze four separate private drafts')
def all_items(path):
    page = c.request(path + '?page=1&size=1000')
    assert len(page['items']) == page['total'], ('Unexpected pagination', path)
    return page['items']
def state():
    entries = all_items('/apis/content.aihyb.run/v1alpha1/contententries')
    revisions = all_items('/apis/content.aihyb.run/v1alpha1/contentrevisions')
    snapshots = all_items('/apis/content.halo.run/v1alpha1/snapshots')
    result = {'entries': {}, 'public': {}, 'revisions': {}, 'snapshots': {}}
    for entry in entries:
        id = entry['spec']['contentId']
        detail = c.request(admin + '/entries/' + id)
        post = c.request('/apis/content.halo.run/v1alpha1/posts/' + native_name(id))
        result['entries'][id] = {'detail': detail, 'post_spec': post['spec'],
            'post_annotations': {key:value for key,value in post['metadata']['annotations'].items() if key.startswith('aihyb.run/')}}
        if detail['entry'].get('published_revision'):
            result['public'][id] = c.request('/apis/api.aihyb.run/v1alpha1/contents/' + id)
    for revision in revisions:
        result['revisions'][revision['metadata']['name']] = hashlib.sha256(json.dumps(revision['spec'], sort_keys=True).encode()).hexdigest()
    for snapshot in snapshots:
        if snapshot['metadata']['name'].startswith('as-'):
            result['snapshots'][snapshot['metadata']['name']] = hashlib.sha256(json.dumps(snapshot['spec'], sort_keys=True).encode()).hexdigest()
    return result
class Page(HTMLParser):
    def __init__(self, body):
        super().__init__(); self.canonical=[]; self.links=[]; self.assets=[]; self.feed(body)
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        if tag=='link' and a.get('rel')=='canonical': self.canonical.append(a.get('href'))
        if tag=='a': self.links.append(a.get('href'))
        if tag=='script' and a.get('src'): self.assets.append(a['src'])
        if tag=='link' and a.get('rel')=='stylesheet': self.assets.append(a['href'])
def html(path):
    response = urllib.request.urlopen(BASE + path, timeout=20)
    assert response.status==200
    return response.read().decode()
def validate(label, theme):
    baseline = json.loads((ROOT / 'evidence/theme-content-baseline.json').read_text())
    # Halo's visit statistics change by design on reads; compare all business state, not counters.
    for entry in baseline['entries'].values():
        entry['post_annotations']={key:value for key,value in entry['post_annotations'].items() if key.startswith('aihyb.run/')}
    c.request(theme_api + '/' + theme + '/activation', 'PUT')
    c.request(theme_api + '/' + theme + '/invalidate-cache', 'PUT')
    report = {'theme': theme, 'pages': {}, 'state_unchanged': False}
    paths=['/', '/aihyb']+['/aihyb/'+kind for kind in ('review','tutorial','leaderboard','pricing')]
    paths += ['/archives/aihyb-poc-'+kind for kind in ('review','tutorial','leaderboard','pricing')]+['/archives/aihyb-poc-theme-render','/aihyb/fallback','/aihyb?task=spreadsheet&size=1']
    for path in paths:
        body=html(path); page=Page(body)
        assert 'THEME_DRAFT_' not in body and 'theme-private-' not in body, path
        if path!='/aihyb/fallback':
            for kind in ('review','tutorial','leaderboard','pricing'): assert '/aihyb/'+kind in page.links, (path,kind)
        if path.startswith('/archives/'):
            id=path.split('aihyb-',1)[1]; public=baseline['public'][id]
            assert public['content']['title'] in body and '查看完整原文' in body, path
            assert len(page.canonical)==1 and page.canonical[0].endswith(path), (path,page.canonical)
        if path.startswith('/aihyb') and path!='/aihyb/fallback': assert len(page.canonical)==1, (path,page.canonical)
        if path.endswith('poc-theme-render'): assert 'language-javascript' in body and '/aihyb/poc-sample.svg' in body
        if 'size=1' in path:
            assert any('task=spreadsheet' in (link or '') and 'page=2' in link for link in page.links), page.links
        for asset in set(page.assets):
            if asset.startswith('/'):
                try: assert urllib.request.urlopen(BASE+asset,timeout=20).status==200, asset
                except urllib.error.HTTPError as error: raise AssertionError((path, asset, error.code)) from error
        report['pages'][path]={'status':200,'canonical':page.canonical,'asset_count':len(set(page.assets))}
    for kind in ('review','tutorial','leaderboard','pricing'):
        id='poc-'+kind; detail=baseline['entries'][id]['detail']; rev=detail['entry']['draft_revision']
        assert 'THEME_DRAFT_'+kind in c.request(admin+'/entries/'+id+'/preview?revision='+rev)
        try: urllib.request.urlopen(urllib.request.Request(BASE+admin+'/entries/'+id+'/preview?revision='+rev, headers={'Accept':'application/json','X-Requested-With':'XMLHttpRequest'}),timeout=10)
        except urllib.error.HTTPError as error: assert error.code in (401,403)
        else: raise AssertionError('Anonymous draft preview was readable')
    actual=state()
    if actual != baseline:
        (ROOT/'evidence'/('theme-'+label+'-state-actual.json')).write_text(json.dumps(actual,ensure_ascii=False,indent=2))
        differences=[]
        def diff(a,b,path=''):
            if isinstance(a,dict) and isinstance(b,dict):
                for key in set(a)|set(b): diff(a.get(key),b.get(key),path+'/'+key)
            elif a!=b: differences.append(path)
        diff(baseline,actual)
        raise AssertionError('Content baseline mismatch at: '+', '.join(differences[:12]))
    report['state_unchanged']=True; report['complete']=True
    (ROOT/'evidence'/('theme-'+label+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(label,theme,'four modules / assets / canonical / previews / unchanged state PASS')
if __name__=='__main__':
    if sys.argv[1]=='seed': seed()
    else: validate(sys.argv[1], sys.argv[2])
