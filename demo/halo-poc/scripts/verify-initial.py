"""First-stage evidence. It deliberately does not claim all eight PoC gates pass."""
import contextlib
import io
import json
from pathlib import Path
import runpy
import time
import urllib.error
import urllib.request

ROOT = Path('/opt/aihyb-halo-poc')
BASE = 'http://127.0.0.1:18090'
with contextlib.redirect_stdout(io.StringIO()):
    ns = runpy.run_path(str(ROOT / 'initialize.py'))
admin = ns['request']
results = {}
locations = {}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


anonymous_client = urllib.request.build_opener(NoRedirect())


def anonymous(path, method='GET', data=None):
    headers = {'Accept': 'application/json' if path.startswith('/apis/') else 'text/html',
               'X-Requested-With': 'XMLHttpRequest'}
    payload = None
    if data is not None:
        payload = json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
    start = time.perf_counter()
    try:
        response = anonymous_client.open(urllib.request.Request(BASE + path, payload, headers, method=method), timeout=20)
        raw = response.read()
        return response.status, raw.decode(), len(raw), round((time.perf_counter() - start) * 1000, 2)
    except urllib.error.HTTPError as error:
        locations[path] = error.headers.get('Location', '')
        raw = error.read()
        return error.code, raw.decode(), len(raw), round((time.perf_counter() - start) * 1000, 2)


results['module_pages'] = {}
html_dir = ROOT / 'evidence' / 'four-module-html'
html_dir.mkdir(exist_ok=True)
for slug in ['reviews', 'leaderboards', 'prices', 'tutorials']:
    status, text, size, duration = anonymous('/archives/' + slug + '-sample')
    (html_dir / (slug + '.html')).write_text(text, encoding='utf-8')
    results['module_pages'][slug] = {'http': status, 'htmlBytes': size, 'serverLocalMs': duration}
    assert status == 200, (slug, status)


def create_draft(name, title, content):
    try:
        existing = admin('/apis/content.halo.run/v1alpha1/posts/' + name)
        admin('/apis/api.console.halo.run/v1alpha1/posts/' + name + '/content', 'PUT',
              {'raw': content, 'content': content, 'rawType': 'HTML'})
        return existing
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    return admin('/apis/api.console.halo.run/v1alpha1/posts', 'POST', {
        'post': {'apiVersion': 'content.halo.run/v1alpha1', 'kind': 'Post',
                 'metadata': {'name': name}, 'spec': {'title': title, 'slug': name, 'owner': 'aihyb-admin',
                 'deleted': False, 'publish': False, 'pinned': False, 'allowComment': False,
                 'visible': 'PUBLIC', 'priority': 0, 'excerpt': {'autoGenerate': True}, 'categories': [], 'tags': []}},
        'content': {'raw': content, 'content': content, 'rawType': 'HTML'},
    })


name = 'poc-version-probe'
resource = '/apis/content.halo.run/v1alpha1/posts/' + name
console = '/apis/api.console.halo.run/v1alpha1/posts/' + name
draft = create_draft(name, 'PoC 已发布标题 A', '<p>AIHYB_PUBLISHED_A</p>')
published = admin(console + '/publish', 'PUT')
snapshot_a = published['spec']['releaseSnapshot']
admin(console + '/content', 'PUT', {'raw': '<p>AIHYB_DRAFT_B</p>', 'content': '<p>AIHYB_DRAFT_B</p>', 'rawType': 'HTML'})
code, text, _, _ = anonymous('/archives/' + name)
head = admin(console + '/head-content')
results['draft_body_isolated'] = code == 200 and 'AIHYB_PUBLISHED_A' in text and 'AIHYB_DRAFT_B' not in text
results['admin_head_contains_draft'] = 'AIHYB_DRAFT_B' in json.dumps(head)
assert results['draft_body_isolated'] and results['admin_head_contains_draft']

def update_title(title):
    for attempt in range(6):
        updated = admin(resource)
        updated['spec']['title'] = title
        try:
            return admin(resource, 'PUT', updated)
        except urllib.error.HTTPError as error:
            if error.code != 409 or attempt == 5:
                raise
            time.sleep(0.25)


update_title('AIHYB_METADATA_DRAFT_TITLE_B')
_, title_view, _, _ = anonymous('/archives/' + name)
results['native_title_edit_isolated'] = 'AIHYB_METADATA_DRAFT_TITLE_B' not in title_view
update_title('PoC 已发布标题 A')

admin(console + '/publish', 'PUT')
_, published_b, _, _ = anonymous('/archives/' + name)
results['publish_switches_body'] = 'AIHYB_DRAFT_B' in published_b and 'AIHYB_PUBLISHED_A' not in published_b
assert results['publish_switches_body']
admin(console + '/revert-content', 'PUT', {'snapshotName': snapshot_a})
admin(console + '/publish', 'PUT')
_, reverted, _, _ = anonymous('/archives/' + name)
results['restore_snapshot_body'] = 'AIHYB_PUBLISHED_A' in reverted and 'AIHYB_DRAFT_B' not in reverted
assert results['restore_snapshot_body']
admin(console + '/unpublish', 'PUT')

private = 'poc-unpublished-probe'
create_draft(private, 'PoC 未发布文章', '<p>AIHYB_UNPUBLISHED_SECRET_MARKER</p>')
public_status, public_body, _, _ = anonymous('/archives/' + private)
core_status, core_body, _, _ = anonymous('/apis/content.halo.run/v1alpha1/posts/' + private)
private_resource_path = '/apis/content.halo.run/v1alpha1/posts/' + private
before_write = admin(private_resource_path)
before_title = before_write['spec']['title']
before_write['spec']['title'] = 'AIHYB_ANON_WRITE_MARKER'
write_status, _, _, _ = anonymous(private_resource_path, 'PUT', before_write)
write_unchanged = admin(private_resource_path)['spec']['title'] == before_title
list_status, list_body, _, _ = anonymous('/apis/api.content.halo.run/v1alpha1/posts')
results['anonymous_permissions'] = {
    'unpublishedPageStatus': public_status,
    'coreResourceStatus': core_status,
    'writeStatus': write_status,
    'writeRedirect': locations.get(private_resource_path, ''),
    'writeLeftDataUnchanged': write_unchanged,
    'publishedListStatus': list_status,
    'draftNotInPage': 'AIHYB_UNPUBLISHED_SECRET_MARKER' not in public_body,
    'draftNotInResource': 'AIHYB_UNPUBLISHED_SECRET_MARKER' not in core_body,
    'draftAbsentFromPublicList': private not in list_body,
}
assert write_status in (401, 403, 404) or (write_status == 302 and '/login' in locations.get(private_resource_path, ''))
assert write_unchanged
assert results['anonymous_permissions']['draftNotInPage']
assert results['anonymous_permissions']['draftNotInResource']
assert results['anonymous_permissions']['draftAbsentFromPublicList']
results['scope'] = 'Native content samples only; business plugin, role matrix, two themes, recovery and load tests remain.'
(ROOT / 'evidence' / 'initial-verification.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))
