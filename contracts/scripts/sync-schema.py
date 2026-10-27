"""Copy the canonical content schema into the plugin resource directory."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = root / 'contracts/content-package-v1.schema.json'
target = root / 'plugin-aihyb/src/main/resources/contracts/content-package-v1.schema.json'
target.write_bytes(source.read_bytes())
print('Synchronized the plugin content schema')
