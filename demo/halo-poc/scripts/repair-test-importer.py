"""Repair only the credential of this task's existing, isolated importer test identity."""
from poc_client import Client, ROOT
import urllib.error
import json
import secrets
import string
name = 'poc-package-importer'
password = 'PoC9!' + ''.join(secrets.choice(string.ascii_letters + string.digits + '!@#$%^&*.?') for _ in range(27))
try:
    Client().request('/apis/api.console.halo.run/v1alpha1/users/' + name + '/password', 'PUT', {'password': password})
except urllib.error.HTTPError as error:
    problem = json.loads(error.read())
    print('Credential repair HTTP', error.code, str(problem.get('detail', problem.get('title', ''))).replace(password, '[redacted]'))
    raise SystemExit(1)
(ROOT / 'secrets' / (name + '.txt')).write_text(password, encoding='utf-8')
(ROOT / 'secrets' / (name + '.txt')).chmod(0o600)
print('Isolated importer test credential reconciled; no credential printed.')
