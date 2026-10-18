"""Install second theme and apply only hash-checked PoC theme overlays; preserve original files."""
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
import urllib.error
from poc_client import Client, ROOT

client = Client()
api = '/apis/api.console.halo.run/v1alpha1/themes'
try:
    client.request('/apis/theme.halo.run/v1alpha1/themes/theme-anatole')
except urllib.error.HTTPError as error:
    if error.code != 404: raise
    client.upload(api + '/install', ROOT / 'anatole-v2.1.0.zip', 'application/zip')
    print('Installed Anatole 2.1.0 via native theme API')

base = (ROOT / 'data/halo/themes').resolve()
overlay = ROOT / 'theme-adapters'
backup = ROOT / 'backups' / ('theme-adapters-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
manifest = json.loads((overlay / 'manifest.json').read_text())
for item in manifest:
    target = (base / item['theme'] / item['path']).resolve()
    assert target.is_relative_to(base / item['theme']), 'Theme path escaped workspace'
    content = (overlay / item['theme'] / item['path']).read_bytes()
    assert hashlib.sha256(content).hexdigest() == item['adapted_sha256']
    current = hashlib.sha256(target.read_bytes()).hexdigest() if target.exists() else None
    if current == item['adapted_sha256']: continue
    assert current in {item['original_sha256'],item.get('previous_adapted_sha256')}, ('Unexpected theme file change; refused overwrite', str(target))
    if target.exists():
        saved = backup / item['theme'] / item['path']
        saved.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(target, saved)
    target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(content)
    print('Adapted', item['theme'], item['path'])
for theme in ('theme-earth', 'theme-anatole'):
    client.request(api + '/' + theme + '/reload', 'PUT')
    client.request(api + '/' + theme + '/invalidate-cache', 'PUT')
    state = client.request('/apis/theme.halo.run/v1alpha1/themes/' + theme)
    print(theme, state.get('status'))
(ROOT / 'evidence/theme-adapters.json').write_text(json.dumps({'backup': str(backup), 'files': manifest}, ensure_ascii=False, indent=2))
