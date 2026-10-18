"""Assert the exact changes made by the real native editor browser workflow."""
import json
from datetime import datetime, timezone
from poc_client import Client, ROOT
c = Client()
id = 'poc-pricing'
detail = c.request('/apis/console.api.aihyb.run/v1alpha1/entries/' + id)
live = c.request('/apis/api.aihyb.run/v1alpha1/contents/' + id)
draft = json.loads(detail['draft_json'])
assert draft['content']['title'] == '原生入口验证：办公助手 A 价格'
assert '原生标题和正文同时修改' in draft['content']['body_markdown']
assert draft['data']['plans'][0]['amount'] == '59.00'
assert live['content'] == draft['content'] and live['data'] == draft['data']
assert live['revision'] == detail['entry']['draft_revision'] == detail['entry']['reviewed_revision']
(ROOT / 'evidence/native-browser-workflow.json').write_text(json.dumps({'timestamp_utc': datetime.now(timezone.utc).isoformat(),
    'native_editor_title_and_body_saved_together': True, 'structured_price_saved_and_reviewed': True,
    'native_publish_button_switched_complete_revision': True, 'complete': True}, indent=2))
print('Real native editor save, review and publish buttons passed')
