"""Real HTTP regressions for complete draft isolation, retry safety, and PoC roles."""
import copy
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import secrets
import string
import time
import urllib.error
import urllib.request
from poc_client import Client, ROOT, BASE

ADMIN = '/apis/console.api.aihyb.run/v1alpha1'
PUBLIC = '/apis/api.aihyb.run/v1alpha1/contents'
RESOURCE = '/apis/content.aihyb.run/v1alpha1'
client = Client()
run_id = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
results = {'run': run_id, 'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'modules': {}, 'checks': {}}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

anonymous_http = urllib.request.build_opener(NoRedirect())
def anonymous(path, method='GET', data=None):
    body = json.dumps(data).encode() if data is not None else None
    headers = {'Accept': 'text/html' if path.startswith('/aihyb') or path.endswith('/preview') else 'application/json',
               'X-Requested-With': 'XMLHttpRequest'}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    started = time.perf_counter()
    try:
        response = anonymous_http.open(urllib.request.Request(BASE + path, body, headers, method=method), timeout=20)
        text = response.read().decode()
        return response.status, text, dict(response.headers), round((time.perf_counter() - started) * 1000, 2)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(), dict(error.headers), round((time.perf_counter() - started) * 1000, 2)

def public_data(content_id):
    code, body, _, _ = anonymous(PUBLIC + '/' + content_id)
    assert code == 200, (code, body[:200])
    return json.loads(body)

def rejected(operation, expected):
    try:
        operation()
    except urllib.error.HTTPError as error:
        body = error.read().decode()
        assert error.code in expected, (error.code, body[:400])
        return error.code
    raise AssertionError('Expected rejection, request succeeded')

def publish(content_id, revision):
    client.request(ADMIN + '/entries/' + content_id + '/review', 'POST',
                   {'revision': revision, 'decision': 'approve', 'note': 'PoC 明确修订核验'})
    return client.request(ADMIN + '/entries/' + content_id + '/publish', 'POST', {'revision': revision})

for kind in ('review', 'tutorial', 'leaderboard', 'pricing'):
    source = json.loads((ROOT / 'package-fixtures' / (kind + '.json')).read_text(encoding='utf-8'))
    content_id = source['content_id']
    original = public_data(content_id)
    detail = client.request(ADMIN + '/entries/' + content_id)
    draft = copy.deepcopy(source)
    draft['submission_id'] = kind + '-b-' + run_id
    draft['base_revision'] = detail['entry']['draft_revision']
    marker = 'AIHYB_DRAFT_' + kind.upper() + '_' + run_id
    draft['content']['title'] = marker
    draft['content']['summary'] = marker + ' summary'
    draft['content']['body_markdown'] += '\n\n' + marker + '\n\n<script>alert(1)</script>\n'
    draft['content']['tags'] = [marker]
    draft['data']['task'] = 'unpublished-task-' + kind
    if kind == 'review':
        draft['data']['conclusions'][0]['text'] = marker
    elif kind == 'tutorial':
        draft['data']['tool_version'] = marker
    elif kind == 'leaderboard':
        draft['data']['entries'][0]['score'] = '99.91'
    else:
        draft['data']['plans'][0]['amount'] = '89.91'
    receipt = client.request(ADMIN + '/content-packages', 'POST', draft)
    again = client.request(ADMIN + '/content-packages', 'POST', draft)
    assert again['replayed'] and again['revision'] == receipt['revision']
    after = public_data(content_id)
    assert after == original, kind + ': complete published DTO changed on draft save'
    status, html, _, duration = anonymous('/aihyb/content/' + content_id)
    assert status == 200 and marker not in html and original['content']['title'] in html
    preview = client.request(ADMIN + '/entries/' + content_id + '/preview')
    assert marker in preview and '<script>alert(1)</script>' not in preview
    code, hidden_list, _, _ = anonymous(PUBLIC + '?task=unpublished-task-' + kind)
    assert code == 200 and json.loads(hidden_list)['total'] == 0
    metrics = {'draft_title_body_data_isolated': True, 'replayed': True,
               'published_task_index_isolated': True, 'preview_sanitized': True,
               'htmlBytes': len(html.encode()), 'serverLocalMs': duration}
    metrics['publish_without_review_http'] = rejected(
        lambda: client.request(ADMIN + '/entries/' + content_id + '/publish', 'POST',
                               {'revision': receipt['revision']}), {409})
    # Approval must not carry over to a subsequent edit.
    client.request(ADMIN + '/entries/' + content_id + '/review', 'POST',
                   {'revision': receipt['revision'], 'decision': 'approve', 'note': 'PoC 第一修订'})
    revised = copy.deepcopy(draft)
    revised['submission_id'] = kind + '-c-' + run_id
    revised['base_revision'] = receipt['revision']
    revised['content']['summary'] += ' edited after review'
    newest = client.request(ADMIN + '/content-packages', 'POST', revised)
    metrics['stale_approval_http'] = rejected(
        lambda: client.request(ADMIN + '/entries/' + content_id + '/publish', 'POST',
                               {'revision': newest['revision']}), {409})
    metrics['stale_publish_http'] = rejected(
        lambda: client.request(ADMIN + '/entries/' + content_id + '/publish', 'POST',
                               {'revision': receipt['revision']}), {409})
    publish(content_id, newest['revision'])
    live = public_data(content_id)
    assert live['content'] == revised['content'] and live['data'] == revised['data']
    assert live['revision'] == newest['revision']
    metrics['approved_complete_version_switched'] = True
    # Raw write APIs must be blocked even for the PoC super administrator.
    raw_path = RESOURCE + '/contentrevisions/' + newest['revision']
    raw = client.request(raw_path)
    tampered = copy.deepcopy(raw)
    tampered['spec']['renderedHtml'] = '<script>alert(9)</script>'
    metrics['raw_revision_write_http'] = rejected(lambda: client.request(raw_path, 'PUT', tampered), {403})
    metrics['raw_revision_delete_http'] = rejected(lambda: client.request(raw_path, 'DELETE'), {403})
    assert client.request(raw_path)['spec']['renderedHtml'] == raw['spec']['renderedHtml']
    # Replay a much older accepted request: it must not roll back current draft or public pointers.
    old_replay = client.request(ADMIN + '/content-packages', 'POST', draft)
    assert old_replay['replayed'] and client.request(ADMIN + '/entries/' + content_id)['entry']['draft_revision'] == newest['revision']
    assert public_data(content_id) == live
    metrics['old_replay_does_not_roll_back'] = True
    # Restore the original sample through a NEW reviewed revision.
    restored = copy.deepcopy(source)
    restored['submission_id'] = kind + '-restore-' + run_id
    restored['base_revision'] = newest['revision']
    restore_receipt = client.request(ADMIN + '/content-packages', 'POST', restored)
    publish(content_id, restore_receipt['revision'])
    final = public_data(content_id)
    assert final['content'] == source['content'] and final['data'] == source['data']
    metrics['restore_complete_payload'] = True
    results['modules'][kind] = metrics
    (ROOT / 'evidence/package-verification.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(kind, 'complete draft / approval / publish / replay / restore PASS')

# Two independent updates start from the same version; only one can commit.
sample = json.loads((ROOT / 'package-fixtures/pricing.json').read_text(encoding='utf-8'))
sample['base_revision'] = client.request(ADMIN + '/entries/poc-pricing')['entry']['draft_revision']
left, right = copy.deepcopy(sample), copy.deepcopy(sample)
left['submission_id'], right['submission_id'] = 'race-left-' + run_id, 'race-right-' + run_id
left['content']['title'], right['content']['title'] = 'RACE_LEFT_' + run_id, 'RACE_RIGHT_' + run_id
def submit_race(package):
    try:
        return 200, client.request(ADMIN + '/content-packages', 'POST', package)
    except urllib.error.HTTPError as error:
        error.read()
        return error.code, None
with ThreadPoolExecutor(max_workers=2) as pool:
    race = list(pool.map(submit_race, [left, right]))
assert sorted(code for code, _ in race) == [200, 409], race
results['checks']['concurrent_update_http'] = [code for code, _ in race]
winner = next(value for code, value in race if code == 200)
reset = copy.deepcopy(sample)
reset['base_revision'] = winner['revision']
reset['submission_id'] = 'race-reset-' + run_id
reset_receipt = client.request(ADMIN + '/content-packages', 'POST', reset)
publish('poc-pricing', reset_receipt['revision'])

invalid = copy.deepcopy(sample)
invalid['submission_id'] = 'invalid-' + run_id
invalid['published'] = True
results['checks']['forged_publish_field_http'] = rejected(
    lambda: client.request(ADMIN + '/content-packages', 'POST', invalid), {422})
trailing = json.dumps(sample).encode() + b' {}'
results['checks']['trailing_json_http'] = rejected(
    lambda: client.request(ADMIN + '/content-packages', 'POST', raw=trailing), {422})
bad_rank = json.loads((ROOT / 'package-fixtures/leaderboard.json').read_text(encoding='utf-8'))
bad_rank['data']['entries'][0]['rank'] = 4294967297
results['checks']['overflow_rank_http'] = rejected(
    lambda: client.request(ADMIN + '/content-packages', 'POST', bad_rank), {422})

# Isolated test identities. Passwords only go into the existing private directory.
role_clients = {}
for suffix, role in [('importer', 'plugin-aihyb-importer'), ('editor', 'plugin-aihyb-editor'),
                     ('reviewer', 'plugin-aihyb-reviewer'), ('publisher', 'plugin-aihyb-publisher')]:
    name = 'poc-package-' + suffix
    password_path = ROOT / 'secrets' / (name + '.txt')
    try:
        client.request('/apis/api.console.halo.run/v1alpha1/users/' + name)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        password = 'PoC9!' + ''.join(secrets.choice(string.ascii_letters + string.digits + '!@#$%^&*.?') for _ in range(27))
        client.request('/apis/api.console.halo.run/v1alpha1/users', 'POST',
                       {'name': name, 'email': name + '@example.invalid', 'displayName': 'PoC ' + suffix,
                        'password': password, 'roles': [role]})
        password_path.write_text(password, encoding='utf-8')
        password_path.chmod(0o600)
    for attempt in range(1):
        try:
            role_clients[suffix] = Client(name, password_path)
            break
        except Exception:
            if attempt == 5:
                raise
            time.sleep(1)

restricted = copy.deepcopy(sample)
restricted.pop('base_revision')
restricted['content_id'] = 'poc-restricted-' + run_id
restricted['submission_id'] = 'restricted-' + run_id
importer = role_clients['importer']
imported = importer.request(ADMIN + '/content-packages', 'POST', restricted)
path = ADMIN + '/entries/' + restricted['content_id']
results['checks']['importer_can_submit_draft'] = True
for suffix in ['importer', 'editor']:
    active = role_clients[suffix]
    results['checks'][suffix + '_cannot_review_http'] = rejected(
        lambda: active.request(path + '/review', 'POST', {'revision': imported['revision'], 'decision': 'approve'}), {401, 403})
    results['checks'][suffix + '_cannot_publish_http'] = rejected(
        lambda: active.request(path + '/publish', 'POST', {'revision': imported['revision']}), {401, 403})
role_clients['editor'].request(path)
role_clients['reviewer'].request(path + '/review', 'POST', {'revision': imported['revision'], 'decision': 'approve'})
results['checks']['reviewer_cannot_publish_http'] = rejected(
    lambda: role_clients['reviewer'].request(path + '/publish', 'POST', {'revision': imported['revision']}), {401, 403})

for check, target, method, data in [
    ('anonymous_draft_preview', path + '/preview', 'GET', None),
    ('anonymous_raw_resource', RESOURCE + '/contentrevisions/' + imported['revision'], 'GET', None),
    ('anonymous_submit', ADMIN + '/content-packages', 'POST', restricted),
    ('anonymous_publish', path + '/publish', 'POST', {'revision': imported['revision']}),
    ('anonymous_unpublished', PUBLIC + '/' + restricted['content_id'], 'GET', None),
]:
    code, _, _, _ = anonymous(target, method, data)
    assert code in {401, 403, 404}, (check, code)
    results['checks'][check + '_http'] = code
code, published_list, _, _ = anonymous(PUBLIC + '?size=100')
assert code == 200 and restricted['content_id'] not in published_list
role_clients['publisher'].request(path + '/publish', 'POST', {'revision': imported['revision']})
assert public_data(restricted['content_id'])['content']['title'] == restricted['content']['title']
results['checks']['publisher_can_publish_approved_revision'] = True

results['complete'] = True
(ROOT / 'evidence/package-verification.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
client.save_headers(ROOT / 'secrets/openapi-headers.json')
print('Package workflow HTTP verification PASS; evidence saved.')
