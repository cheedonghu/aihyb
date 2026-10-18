"""Create native Post projections without changing existing package states."""
import json
from poc_client import Client, ROOT
c = Client()
print(c.request('/apis/console.api.aihyb.run/v1alpha1/native-migrate', 'POST'))
before = json.loads((ROOT / 'evidence/native-before.json').read_text())
after = {'entries': {}, 'public': {}}
for id in before['entries']:
    detail = c.request('/apis/console.api.aihyb.run/v1alpha1/entries/' + id)
    after['entries'][id] = {'entry': detail['entry'], 'draft': json.loads(detail['draft_json'])}
    after['public'][id] = c.request('/apis/api.aihyb.run/v1alpha1/contents/' + id)
assert after['entries'] == before['entries'], 'Migration changed editable or approval state'
assert after['public'] == before['public'], 'Migration changed published payloads'
(ROOT / 'evidence/native-migration.json').write_text(json.dumps({'four_module_drafts_and_approvals_unchanged': True,
    'four_module_public_payloads_unchanged': True, 'complete': True}, indent=2))
print('Native migration preserved all four draft, approval and publication states')
