"""Apply hash-checked theme overlays to an explicitly selected instance; preserve backups."""
import hashlib
import json
import shutil
import argparse
from datetime import datetime, timezone
from pathlib import Path
import urllib.error
from site_client import Client, ROOT

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--theme', action='append', choices=('theme-earth', 'theme-anatole'), help='Apply only named themes')
parser.add_argument('--check', action='store_true', help='Validate hashes without changing theme files')
parser.add_argument('--overlay', type=Path,
                    default=Path(__file__).resolve().parents[2] / 'themes/adapters',
                    help='Directory containing the adapter manifest and files')
parser.add_argument('--anatole-archive', type=Path, help='Official Anatole 2.1.0 archive, if installation is needed')
args = parser.parse_args()
selected = tuple(dict.fromkeys(args.theme or ('theme-earth',)))
client = Client()
api = '/apis/api.console.halo.run/v1alpha1/themes'
if 'theme-anatole' in selected:
    try:
        client.request('/apis/theme.halo.run/v1alpha1/themes/theme-anatole')
    except urllib.error.HTTPError as error:
        if error.code != 404: raise
        if args.check:
            raise RuntimeError('Anatole is not installed; --check does not install themes') from error
        if args.anatole_archive is None:
            raise RuntimeError('Supply --anatole-archive to install the optional theme') from error
        client.upload(api + '/install', args.anatole_archive, 'application/zip')
        print('Installed Anatole 2.1.0 via native theme API')

base = (ROOT / 'data/halo/themes').resolve()
overlay = args.overlay.resolve()
backup = ROOT / 'backups' / ('theme-adapters-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
manifest = [item for item in json.loads((overlay / 'manifest.json').read_text()) if item['theme'] in selected]
changes = []
for item in manifest:
    target = (base / item['theme'] / item['path']).resolve()
    assert target.is_relative_to(base / item['theme']), 'Theme path escaped workspace'
    content = (overlay / item['theme'] / item['path']).read_bytes()
    assert hashlib.sha256(content).hexdigest() == item['adapted_sha256']
    current = hashlib.sha256(target.read_bytes()).hexdigest() if target.exists() else None
    if current == item['adapted_sha256']: continue
    assert current in {item['original_sha256'],item.get('previous_adapted_sha256')}, ('Unexpected theme file change; refused overwrite', str(target))
    changes.append((item, target, content, target.read_bytes() if target.exists() else None))
# Validate the entire set before touching any theme file.
if args.check:
    print('Validated', len(manifest), 'files;', len(changes), 'pending changes for', ', '.join(selected))
    raise SystemExit(0)
for item, target, content, original in changes:
    if original is not None:
        saved = backup / item['theme'] / item['path']
        saved.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(target, saved)
try:
    for item, target, content, original in changes:
        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(content)
        print('Adapted', item['theme'], item['path'])
    for theme in selected:
        client.request(api + '/' + theme + '/reload', 'PUT')
        client.request(api + '/' + theme + '/invalidate-cache', 'PUT')
        state = client.request('/apis/theme.halo.run/v1alpha1/themes/' + theme)
        print(theme, state.get('status'))
except Exception:
    for item, target, content, original in reversed(changes):
        if original is None:
            target.unlink(missing_ok=True)
        else:
            target.write_bytes(original)
    for theme in selected:
        client.request(api + '/' + theme + '/reload', 'PUT')
        client.request(api + '/' + theme + '/invalidate-cache', 'PUT')
    raise
state = ROOT / 'runtime/theme-adapters.json'
state.parent.mkdir(parents=True, exist_ok=True)
state.write_text(json.dumps({'backup': str(backup), 'files': manifest, 'selected_themes': selected}, ensure_ascii=False, indent=2))
