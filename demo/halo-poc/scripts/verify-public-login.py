"""Use the existing private account to verify normal HTTPS login; no content writes or credential output.

SUPERSEDED 2026-10-18: console/login are now tunnel-only on plain HTTP. Use verify-ip-http-login.py.
"""
import json
from datetime import datetime,timezone
from poc_client import Client,ROOT,BASE
raise SystemExit('Superseded: the domain entry is gone. Run verify-ip-http-login.py instead.')
assert BASE=='https://aihyb.com'
c=Client();current=c.request('/apis/api.console.halo.run/v1alpha1/users/-')
assert current.get('user',current)['metadata']['name']=='aihyb-admin'
session=[cookie for cookie in c.cookies if cookie.domain.lstrip('.')=='aihyb.com' and cookie.name.upper() in ('SESSION','JSESSIONID')]
assert session and all(cookie.secure for cookie in session),'HTTPS session cookie must be Secure'
assert all(any(key.lower()=='httponly' for key in cookie._rest) for cookie in session),'Session cookie must be HttpOnly'
detail=c.request('/apis/console.api.aihyb.run/v1alpha1/entries/poc-review');assert 'THEME_DRAFT_review' in detail['draft_json']
report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'normal_https_login':True,'secure_session_cookie':True,
    'httponly_session_cookie':True,'authenticated_draft_read_only':True,'complete':True}
(ROOT/'evidence/public-domain-login.json').write_text(json.dumps(report,indent=2))
print('Normal HTTPS admin login / Secure+HttpOnly session / read-only draft access PASS',flush=True)
