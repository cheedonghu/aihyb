"""Inspect only theme metadata and theme-specific API descriptions."""
import json
from poc_client import Client, ROOT
client = Client()
themes = client.request('/apis/theme.halo.run/v1alpha1/themes')
(ROOT / 'evidence/themes-before.json').write_text(json.dumps(themes, ensure_ascii=False, indent=2))
for theme in themes.get('items', []):
    print(theme['metadata']['name'], 'version', theme['spec'].get('version'), 'status', theme.get('status'))
doc = client.request('/v3/api-docs')
paths = {key: value for key, value in doc.get('paths', {}).items() if '/themes' in key or '/menus' in key}
(ROOT / 'evidence/theme-api.json').write_text(json.dumps({'paths': paths}, ensure_ascii=False, indent=2))
print('Theme and menu API paths:', list(paths))
