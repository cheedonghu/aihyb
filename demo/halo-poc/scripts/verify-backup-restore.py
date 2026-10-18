"""Restore the newest PoC database dump into a fresh, isolated test database only.

This does not restore files into the running Halo instance or claim full P7 acceptance.
"""
import json
import subprocess
from datetime import datetime, timezone
from poc_client import ROOT

db_name = 'poc_restore_check_20261014'
assert db_name == 'poc_restore_check_20261014'
backup = sorted((ROOT / 'backups').glob('*/halo.dump'))[-1]
base = ['docker', 'compose', 'exec', '-T', 'db']

def sql(database, query):
    return subprocess.check_output(base + ['psql', '-U', 'halo', '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-c', query], text=True).strip()

exists = sql('halo', "SELECT 1 FROM pg_database WHERE datname = 'poc_restore_check_20261014'")
if exists:
    raise RuntimeError('Isolated restore database already exists; inspect it instead of overwriting')
query = "SELECT count(*), md5(coalesce(string_agg(name || ':' || md5(data), ',' ORDER BY name), '')) FROM extensions WHERE name LIKE '%content.aihyb.run%'"
live_before = sql('halo', query)
subprocess.run(base + ['createdb', '-U', 'halo', db_name], check=True)
with backup.open('rb') as dump:
    subprocess.run(base + ['pg_restore', '-U', 'halo', '-d', db_name, '--exit-on-error'], stdin=dump, check=True)
restored = sql(db_name, query)
assert int(restored.split('|')[0]) >= 8, 'Plugin resources were not restored'
assert restored == live_before, 'Restored content resources differ from live backup checkpoint'
results = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'backup': str(backup.parent),
    'restored_database': db_name, 'plugin_resource_count_and_digest': restored,
    'matches_live_plugin_resources': True, 'scope': 'PostgreSQL dump restored to fresh test database; full Halo filesystem restore not exercised',
    'complete': True}
(ROOT / 'evidence/database-restore.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
print('Fresh isolated PostgreSQL restore matches all plugin resource bytes')
# Keep the tiny isolated database as reviewable PoC evidence; never touch the running halo database.
