"""Real native URLs, metadata/body saves, review and publish, all four fictional modules."""
import copy
import hashlib
import json
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from poc_client import Client, ROOT, BASE

c = Client()
admin = '/apis/console.api.aihyb.run/v1alpha1'
core = '/apis/content.halo.run/v1alpha1'
console = '/apis/api.console.halo.run/v1alpha1'
uc = '/apis/uc.api.content.halo.run/v1alpha1'
run = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
results = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'modules': {}, 'checks': {}}
def native(path, method='GET', data=None):
    if path.endswith('/publish') and method == 'PUT':
        name = path.split('/')[-2]
        head = native(console + '/posts/' + name + '/head-content')
        path += '?headSnapshot=' + head['snapshotName']
    try: return c.request(path, method, data)
    except urllib.error.HTTPError as error:
        if error.code != 307: raise
        location = error.headers['Location']
        assert location.startswith(admin + '/'), location
        error.read()
        return c.request(location, method, data)
def rejected(action, code):
    try: action()
    except urllib.error.HTTPError as error:
        body = error.read().decode()
        assert error.code == code, (error.code, body[:250])
        return error.code
    raise AssertionError('Expected rejection')
def public(id): return c.request('/apis/api.aihyb.run/v1alpha1/contents/' + id)
def public_html(id):
    for attempt in range(20):
        try: return urllib.request.urlopen(BASE + '/archives/aihyb-' + id, timeout=15).read().decode()
        except urllib.error.HTTPError as error:
            if error.code != 404: raise
            time.sleep(.3)
    raise AssertionError('Native published page did not become available')
def save_checkpoint():
    (ROOT / 'evidence/native-verification.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
for kind in ('review', 'tutorial', 'leaderboard', 'pricing'):
    id = 'poc-' + kind
    name = 'ap-' + hashlib.sha256(id.encode()).hexdigest()
    original = public(id)
    original_html = public_html(id)
    assert original['content']['title'] in original_html
    detail = c.request(admin + '/entries/' + id)
    saved = native(core + '/posts/' + name)
    head = native(console + '/posts/' + name + '/head-content')
    assert head['version'] > 1000000 and saved['spec']['title'] == json.loads(detail['draft_json'])['content']['title']
    marker = 'NATIVE_DRAFT_' + kind + '_' + run
    pending = copy.deepcopy(saved); pending['spec']['title'] = marker
    pending['spec']['excerpt']['raw'] = marker + ' summary'
    meta = native(core + '/posts/' + name, 'PUT', pending)
    assert meta['spec']['title'] == marker and public(id) == original
    new_head = native(console + '/posts/' + name + '/head-content')
    body = {'version': head['version'], 'raw': head['raw'] + '\n\n' + marker, 'content': '<script>ignored</script>', 'rawType': 'MARKDOWN'}
    rejected(lambda: native(console + '/posts/' + name + '/content', 'PUT', body), 409)
    # Mirrors the scoped frontend bridge for the metadata+body two-step native save.
    body['version'] = int(meta['metadata']['annotations']['aihyb.run/editor-version'])
    updated = native(console + '/posts/' + name + '/content', 'PUT', body)
    assert updated['spec']['title'] == marker
    full = c.request(admin + '/entries/' + id)
    package = json.loads(full['draft_json'])
    package['submission_id'] = 'native-data-' + kind + '-' + run
    package['base_revision'] = full['entry']['draft_revision']
    package['data']['task'] = 'draft-native-task-' + kind
    if kind == 'pricing': package['data']['plans'][0]['amount'] = '69.91'
    elif kind == 'leaderboard': package['data']['entries'][0]['score'] = '98.71'
    elif kind == 'review': package['data']['conclusions'][0]['text'] = marker + ' conclusion'
    else: package['data']['tool_version'] = marker + ' version'
    receipt = c.request(admin + '/content-packages', 'POST', package)
    assert public(id) == original
    html = public_html(id)
    assert marker not in html and original['content']['title'] in html
    preview = c.request(admin + '/entries/' + id + '/preview?revision=' + receipt['revision'])
    assert marker in preview
    rejected(lambda: native(console + '/posts/' + name + '/publish', 'PUT'), 409)
    c.request(admin + '/entries/' + id + '/review', 'POST', {'revision': receipt['revision'], 'decision': 'approve', 'note': 'Native workflow PoC'})
    native(console + '/posts/' + name + '/publish', 'PUT')
    live = public(id)
    assert live['content'] == package['content'] and live['data'] == package['data']
    assert marker in public_html(id)
    raw = c.request('/apis/api.content.halo.run/v1alpha1/posts/' + name)
    # The native public metadata must now match the very same complete package.
    native_post = raw.get('post', raw)
    assert native_post['spec']['title'] == marker
    rejected(lambda: native(core + '/posts/' + name, 'PUT', pending), 409)
    for suffix in ('', '/draft', '/publish', '/unpublish'):
        rejected(lambda suffix=suffix: c.request(uc + '/posts/' + name + suffix, 'PUT', pending), 403)
    snapshot = updated['spec']['headSnapshot']
    rejected(lambda: c.request(core + '/snapshots/' + snapshot, 'PUT', {'metadata': {'name': snapshot}}), 403)
    restored = json.loads((ROOT / 'package-fixtures' / (kind + '.json')).read_text(encoding='utf-8'))
    restored['submission_id'] = 'native-restore-' + kind + '-' + run
    restored['base_revision'] = receipt['revision']
    restore = c.request(admin + '/content-packages', 'POST', restored)
    rejected(lambda: native(console + '/posts/' + name + '/publish', 'PUT'), 409)
    c.request(admin + '/entries/' + id + '/review', 'POST', {'revision': restore['revision'], 'decision': 'approve'})
    native(console + '/posts/' + name + '/publish', 'PUT')
    assert public(id)['content'] == restored['content'] and public(id)['data'] == restored['data']
    results['modules'][kind] = {'native_metadata_save_isolated': True, 'native_body_save_isolated': True,
        'structured_data_save_isolated': True, 'expired_native_metadata_and_body_http': 409,
        'unapproved_native_publish_http': 409, 'native_publish_switched_complete_package': True,
        'native_public_title_matches_package': True, 'uc_write_bypass_http': 403, 'snapshot_write_http': 403,
        'new_draft_invalidated_approval': True, 'restored': True}
    save_checkpoint()
    print(kind, 'native save / preview / review / publish / bypass protection PASS')
results['complete'] = True
save_checkpoint()
print('All four native article workflows passed')
