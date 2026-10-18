"""Verify the browser's task selection, then restore only the fictional review sample."""
import json
import uuid
from datetime import datetime, timezone
from poc_client import Client, ROOT

client = Client()
admin = '/apis/console.api.aihyb.run/v1alpha1'
detail = client.request(admin + '/entries/poc-review')
draft = json.loads(detail['draft_json'])
live = client.request('/apis/api.aihyb.run/v1alpha1/contents/poc-review')
assert draft['data']['task'] == 'office', 'Browser selection did not reach the saved package'
assert live['data']['task'] == 'presentation', 'Draft task changed the published content'
source = json.loads((ROOT / 'package-fixtures/review.json').read_text(encoding='utf-8'))
source['submission_id'] = 'ui-task-restore-' + uuid.uuid4().hex
source['base_revision'] = detail['entry']['draft_revision']
receipt = client.request(admin + '/content-packages', 'POST', source)
client.request(admin + '/entries/poc-review/review', 'POST',
    {'revision': receipt['revision'], 'decision': 'approve', 'note': 'Restore fictional sample after browser task selection'})
client.request(admin + '/entries/poc-review/publish', 'POST', {'revision': receipt['revision']})
result = {'timestamp_utc': datetime.now(timezone.utc).isoformat(),
    'browser_task_selected_and_saved': True, 'published_task_isolated': True,
    'sample_restored_via_new_approved_revision': True, 'complete': True}
(ROOT / 'evidence/ui-task-selector.json').write_text(json.dumps(result, indent=2))
print('Browser task selection saved correctly, public task stayed isolated, sample restored')
