import json
import urllib.request
import urllib.error
import secrets
from poc_client import Client, ROOT, BASE

client = Client()
admin = '/apis/console.api.aihyb.run/v1alpha1'
public = '/apis/api.aihyb.run/v1alpha1/contents'
for kind in ('review', 'tutorial', 'leaderboard', 'pricing'):
    package = json.loads((ROOT / 'package-fixtures' / (kind + '.json')).read_text(encoding='utf-8'))
    try:
        try:
            current = client.request(admin + '/entries/' + package['content_id'])
            package['base_revision'] = current['entry']['draft_revision']
            package['submission_id'] = 'smoke-' + kind + '-' + secrets.token_hex(6)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        receipt = client.request(admin + '/content-packages', 'POST', package)
        print(kind, 'received:', receipt['revision'], 'replayed:', receipt['replayed'])
        preview = client.request(admin + '/entries/' + package['content_id'] + '/preview')
        assert package['content']['title'] in preview
        client.request(admin + '/entries/' + package['content_id'] + '/review', 'POST',
                       {'revision': receipt['revision'], 'decision': 'approve', 'note': '虚构 PoC 样例审核'})
        client.request(admin + '/entries/' + package['content_id'] + '/publish', 'POST',
                       {'revision': receipt['revision']})
        response = urllib.request.urlopen(urllib.request.Request(BASE + public + '/' + package['content_id'],
                    headers={'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'}), timeout=20)
        data = json.load(response)
        assert data['content']['title'] == package['content']['title']
        print(kind, 'published HTTP', response.status)
    except urllib.error.HTTPError as error:
        print(kind, 'HTTP', error.code, error.read().decode()[:1800])
        raise
client.save_headers(ROOT / 'secrets/openapi-headers.json')
