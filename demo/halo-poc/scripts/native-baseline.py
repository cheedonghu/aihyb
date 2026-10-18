"""Read-only baseline before and after adapting native Posts."""
import json
import sys
from datetime import datetime, timezone
from poc_client import Client, ROOT
c = Client()
out = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'entries': {}, 'public': {}}
for kind in ('review', 'tutorial', 'leaderboard', 'pricing'):
    id = 'poc-' + kind
    detail = c.request('/apis/console.api.aihyb.run/v1alpha1/entries/' + id)
    out['entries'][id] = {'entry': detail['entry'], 'draft': json.loads(detail['draft_json'])}
    out['public'][id] = c.request('/apis/api.aihyb.run/v1alpha1/contents/' + id)
file = ROOT / 'evidence' / (sys.argv[1] if len(sys.argv) > 1 else 'native-before.json')
file.write_text(json.dumps(out, ensure_ascii=False, indent=2))
print('Four module draft, approval and public baseline recorded:', file.name)
