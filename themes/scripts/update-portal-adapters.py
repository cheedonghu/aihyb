"""Refresh only the portal overlays and their deployment hashes from retained shared sources."""
import hashlib
import json
import re
import argparse
from pathlib import Path

themes = Path(__file__).resolve().parents[1]
overlay = themes / 'adapters'
manifest_path = overlay / 'manifest.json'
manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', required=True, type=Path,
                    help='Verified deployment hash baseline for the intended environment')
args = parser.parse_args()
deployment = args.baseline
deployed = {(entry['theme'], entry['path']): entry for entry in
            json.loads(deployment.read_text(encoding='utf-8'))['files']}
sources = {
    'templates/index.html': 'earth-portal-index.html',
    'templates/layout.html': 'earth-portal-layout.html',
    'templates/modules/aihyb-header.html': 'earth-header.html',
    'templates/assets/aihyb-navigation.css': 'earth-navigation.css',
    'templates/assets/aihyb-editorial.css': 'earth-editorial.css',
    'templates/post.html': 'earth-post.html',
    'templates/modules/aihyb-footer.html': 'earth-footer.html',
}
if not any(entry['theme'] == 'theme-earth' and entry['path'] == 'templates/assets/aihyb-editorial.css' for entry in manifest):
    manifest.append({'theme': 'theme-earth', 'path': 'templates/assets/aihyb-editorial.css',
                     'original_sha256': None, 'previous_adapted_sha256': None, 'adapted_sha256': None})
for path in sources:
    if not any(entry['theme']=='theme-earth' and entry['path']==path for entry in manifest):
        manifest.append({'theme':'theme-earth','path':path,'original_sha256':None,'previous_adapted_sha256':None,'adapted_sha256':None})
changes = 0
pending = []
for entry in manifest:
    if entry['theme'] != 'theme-earth':
        continue
    target = overlay / entry['theme'] / entry['path']
    current = target.read_bytes() if target.exists() else None
    desired = ((themes / 'shared' / sources[entry['path']]).read_bytes() if entry['path'] in sources
               else current.replace(b'v=20261018', b'v=20261021')).replace(b'\r\n', b'\n')
    # Refresh the shared navigation stylesheet on native pages as well as portal pages.
    for version in (b'20261021', b'20261022', b'20261022-polish', b'20261022-polish2', b'20261022-polish3', b'20261022-polish4'):
        desired = desired.replace(b'aihyb-navigation.css?v=' + version + b'"', b'aihyb-navigation.css?v=20261022-polish5"')
        desired = desired.replace(b'aihyb-editorial.css?v=' + version + b'"', b'aihyb-editorial.css?v=20261022-polish5"')
    if entry['path'].endswith('.html') and b'aihyb-navigation.css?' in desired and b'aihyb-editorial.css?' not in desired:
        desired = desired.replace(b'</head>', b'<link rel="stylesheet" href="/themes/theme-earth/assets/aihyb-editorial.css?v=20261022-polish5">\n</head>', 1)
    if entry['path'].endswith('.html') and entry['path'] != 'templates/modules/aihyb-footer.html':
        count = [0]
        def shared_footer(match):
            count[0] += 1
            return b'<th:block th:replace="~{modules/aihyb-footer :: footer}" />' if count[0] == 1 else b''
        desired = re.sub(rb'<footer\b[\s\S]*?</footer>', shared_footer, desired)
    digest = hashlib.sha256(desired).hexdigest()
    assert current is None or current == desired or hashlib.sha256(current).hexdigest() == entry['adapted_sha256'], ('Unexpected local overlay change', str(target))
    if digest == entry['adapted_sha256']:
        continue
    # Repeated local revisions must keep accepting the last verified remote version.
    prior = deployed.get((entry['theme'], entry['path']))
    if prior is None and (entry['original_sha256'] is not None or entry.get('previous_adapted_sha256') is not None):
        raise RuntimeError('Missing verified deployment baseline for ' + entry['path'])
    entry['previous_adapted_sha256'] = prior['adapted_sha256'] if prior else None
    entry['adapted_sha256'] = digest
    pending.append((target, desired))
    changes += 1
for target, desired in pending:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(desired)
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print('Updated', changes, 'portal adapter hashes; remote deployment remains separate')
