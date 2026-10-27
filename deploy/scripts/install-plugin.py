"""Install the selected plugin artifact into an explicitly targeted Halo instance."""
import json
from pathlib import Path
import urllib.error
import time
import os
import argparse
from site_client import Client

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--artifact', type=Path, required=True, help='Built plugin JAR to install')
args = parser.parse_args()
if not args.artifact.is_file():
    parser.error('Plugin artifact does not exist')

client = Client()
base = '/apis/api.console.halo.run/v1alpha1/plugins'
try:
    client.request('/apis/plugin.halo.run/v1alpha1/plugins/plugin-aihyb')
    action = '/plugin-aihyb/upgrade'
except urllib.error.HTTPError as error:
    if error.code != 404:
        raise
    action = '/install'
result = client.upload(base + action, args.artifact)
print('Plugin upload accepted:', action)
if isinstance(result, dict):
    print('Plugin:', result.get('metadata', {}).get('name', 'plugin-aihyb'))
if os.environ.get('AIHYB_UPLOAD_ONLY') == '1':
    raise SystemExit(0)
# spec.enabled is true. Do not issue a competing start while install starts it.
# The immediately returned STARTED can still describe the old instance during upgrade.
time.sleep(3)
for attempt in range(30):
    state = client.request('/apis/plugin.halo.run/v1alpha1/plugins/plugin-aihyb')
    phase = state.get('status', {}).get('phase')
    if phase == 'STARTED':
        print('Plugin phase:', phase)
        break
    if phase == 'FAILED':
        raise RuntimeError('Plugin failed to start; inspect task-specific logs before retrying.')
    time.sleep(1)
else:
    raise RuntimeError('Plugin start did not reach STARTED within 30 seconds.')
