"""Check local paths, canonical contracts and theme hashes without network or writes."""
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
errors = []


def check(condition, message):
    if not condition:
        errors.append(message)


for name in ('demo', 'video', 'evidence', 'materials', 'archive'):
    check(not (ROOT / name).exists(), 'Out-of-scope directory in repository: ' + name)

canonical = ROOT / 'contracts/content-package-v1.schema.json'
resource = ROOT / 'plugin-aihyb/src/main/resources/contracts/content-package-v1.schema.json'
check(canonical.read_bytes() == resource.read_bytes(), 'Plugin content schema differs from canonical schema')
json.loads(canonical.read_text(encoding='utf-8'))
spec = json.loads((ROOT / 'contracts/openapi/aihybApis.json').read_text(encoding='utf-8'))
check(bool(spec.get('paths')), 'OpenAPI snapshot has no paths')
for example in (ROOT / 'contracts/examples').glob('*.json'):
    data = json.loads(example.read_text(encoding='utf-8'))
    check(data.get('schema_version') == '1.0', 'Unrecognized example schema: ' + example.name)

for source in (ROOT / 'plugin-aihyb/src/test').rglob('*.java'):
    check(not re.search(r'\.\./(?:package-fixtures|media-fixtures)/', source.read_text(encoding='utf-8')),
          'Stale fixture path in ' + str(source.relative_to(ROOT)))

manifest = json.loads((ROOT / 'themes/adapters/manifest.json').read_text(encoding='utf-8'))
seen = set()
for entry in manifest:
    key = entry['theme'], entry['path']
    check(key not in seen, 'Duplicate adapter path: ' + str(key))
    seen.add(key)
    path = (ROOT / 'themes/adapters' / entry['theme'] / entry['path']).resolve()
    if not path.is_relative_to((ROOT / 'themes/adapters' / entry['theme']).resolve()):
        errors.append('Adapter path escapes theme directory: ' + str(key))
        continue
    if not path.is_file():
        errors.append('Missing adapter: ' + str(key))
        continue
    check(hashlib.sha256(path.read_bytes()).hexdigest() == entry['adapted_sha256'],
          'Adapter hash mismatch: ' + str(key))

for baseline in (ROOT / 'themes/baselines').glob('*.json'):
    for entry in json.loads(baseline.read_text(encoding='utf-8'))['files']:
        check((entry['theme'], entry['path']) in seen, 'Unknown baseline adapter: ' + entry['path'])
        check(bool(re.fullmatch(r'[a-f0-9]{64}', entry['adapted_sha256'])), 'Invalid deployment hash')

feed = json.loads((ROOT / 'plugin-aihyb/libs/feed-api-extraction.json').read_text(encoding='utf-8'))
check(hashlib.sha256((ROOT / 'plugin-aihyb/libs/feed-api-1.5.0.jar').read_bytes()).hexdigest()
      == feed['api_jar_sha256'], 'Feed API dependency hash mismatch')

documents = [ROOT / name for name in ('README.md', 'AGENTS.md', 'CONTEXT.md',
                                      'contracts/README.md', 'plugin-aihyb/README.md',
                                      'plugin-aihyb/libs/README.md', 'themes/README.md', 'deploy/README.md')]
documents.extend((ROOT / 'docs').rglob('*.md'))
for document in documents:
    text = document.read_text(encoding='utf-8')
    for target in re.findall(r'!?\[[^\]]*\]\(([^\s)]+)(?:\s+[^)]*)?\)', text):
        target = unquote(target.strip('<>').split('#')[0])
        if not target or re.match(r'[a-zA-Z][a-zA-Z0-9+.-]*:', target):
            continue
        check((document.parent / target).exists(),
              str(document.relative_to(ROOT)) + ': broken local link: ' + target)

if errors:
    for error in errors:
        print('ERROR: ' + error, file=sys.stderr)
    raise SystemExit(1)
print('Repository paths, schemas, examples, dependency provenance and ' + str(len(manifest)) + ' theme hashes passed')
