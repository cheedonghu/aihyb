import json
from poc_client import Client, ROOT
client = Client()
document = client.request('/v3/api-docs/aihybApis')
print('Selected group paths:', list(document['paths'])[:12])
assert any('/console.api.aihyb.run/' in path for path in document['paths'])
(ROOT / 'evidence/aihyb-openapi.json').write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding='utf-8')
client.save_headers(ROOT / 'secrets/openapi-headers.json')
print('API document paths:', len(document['paths']))
