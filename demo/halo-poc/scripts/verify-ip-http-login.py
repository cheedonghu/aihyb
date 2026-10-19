"""Verify the tunnel-only admin path: normal login over http://127.0.0.1:18090, read-only.

The public IP entry is plain HTTP, so console/login are restricted to the SSH tunnel. This script
must run on the server (or through the tunnel) and never prints credentials or tokens.
"""
import json
from datetime import datetime, timezone

from poc_client import BASE, ROOT, Client

assert BASE == 'http://127.0.0.1:18090', 'Run this through the SSH tunnel or on the server itself'
c = Client()
current = c.request('/apis/api.console.halo.run/v1alpha1/users/-')
assert current.get('user', current)['metadata']['name'] == 'aihyb-admin'
session = [cookie for cookie in c.cookies
           if cookie.domain.lstrip('.') in ('127.0.0.1', 'localhost') and cookie.name.upper() == 'SESSION']
assert session, 'Loopback login must set a session cookie'
assert all(any(key.lower() == 'httponly' for key in cookie._rest) for cookie in session), \
    'Session cookie must stay HttpOnly'
detail = c.request('/apis/console.api.aihyb.run/v1alpha1/entries/poc-review')
assert 'THEME_DRAFT_review' in detail['draft_json']
report = {'timestamp_utc': datetime.now(timezone.utc).isoformat(),
    'tunnel_origin': BASE, 'normal_login': True, 'session_cookie_httponly': True,
    'session_cookie_secure': False,
    'secure_cookie_expected_on_plain_http_loopback': False,
    'authenticated_draft_read_only': True,
    'note': 'Secure flag is absent because the tunnel entry is http://127.0.0.1:18090; '
            'traffic stays inside the SSH channel and never crosses the plain-HTTP public entry.',
    'complete': True}
(ROOT / 'evidence/ip-http-login.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print('Tunnel admin login / HttpOnly session / read-only draft access PASS', flush=True)
