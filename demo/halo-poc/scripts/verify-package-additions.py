"""Read-only checks of filtered pagination, revision-bound preview and persistence snapshot."""
import hashlib
import json
import urllib.error
from datetime import datetime, timezone
from poc_client import Client, ROOT

client = Client()
admin = '/apis/console.api.aihyb.run/v1alpha1'
results = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'modules': {}, 'checks': {}}
for kind in ('review', 'tutorial', 'leaderboard', 'pricing'):
    page = client.request(admin + '/entries?kind=' + kind + '&page=1&size=1')
    assert len(page['items']) == 1 and page['total'] >= 1
    assert all(item['kind'] == kind for item in page['items'])
    all_items = client.request(admin + '/entries?kind=' + kind + '&page=1&size=100')
    assert len(all_items['items']) == all_items['total']
    assert all(item['kind'] == kind for item in all_items['items'])
    item = client.request(admin + '/entries/poc-' + kind)
    revision = item['entry']['draft_revision']
    preview = client.request(admin + '/entries/poc-' + kind + '/preview?revision=' + revision)
    title = json.loads(item['draft_json'])['content']['title']
    assert title in preview
    try:
        client.request(admin + '/entries/poc-' + kind + '/preview?revision=r-obsolete')
        raise AssertionError('Stale preview was accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 409
    results['modules'][kind] = {'filtered_total': all_items['total'], 'draft_revision': revision,
        'draft_sha256': hashlib.sha256(item['draft_json'].encode()).hexdigest(),
        'published_revision': item['entry']['published_revision'], 'stale_preview_http': 409}
results['checks']['kind_filter_before_pagination'] = True
results['checks']['revision_bound_preview'] = True
results['complete'] = True
(ROOT / 'evidence/package-additions.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
print('Filtered pagination and revision-bound preview passed for all four modules')
