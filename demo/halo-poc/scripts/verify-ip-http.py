"""Verify the plain-HTTP IP entry: public pages, origin rewrite, admin lockdown and media."""
import hashlib
import json
import socket
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from html.parser import HTMLParser

from poc_client import ROOT

IP = '5.231.253.4'
BASE = 'http://' + IP
OLD = 'aihyb.com'
report = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'origin': BASE, 'pages': {}, 'complete': False}


def checkpoint():
    (ROOT / 'evidence/ip-http-verification.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')


def get(path, headers=None, data=None):
    request = urllib.request.Request(
        BASE + path,
        json.dumps(data).encode() if data is not None else None,
        {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest',
         **(headers or {}), **({'Content-Type': 'application/json'} if data is not None else {})})
    response = urllib.request.urlopen(request, timeout=45)
    assert urllib.parse.urlsplit(response.url).hostname == IP, 'Unexpected redirect to another host'
    return response.status, response.headers, response.read()


def denied(path, codes):
    try:
        get(path)
    except urllib.error.HTTPError as error:
        error.read()
        assert error.code in codes, (path, error.code)
        return error.code
    raise AssertionError('Route unexpectedly reachable on the public entry: ' + path)


class Page(HTMLParser):
    def __init__(self, raw):
        super().__init__()
        self.canonical = []
        self.assets = []
        self.feed(raw)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'link' and a.get('rel') == 'canonical':
            self.canonical.append(a.get('href'))
        if tag == 'link' and a.get('rel') == 'stylesheet' and a.get('href'):
            self.assets.append(a['href'])
        if tag == 'script' and a.get('src'):
            self.assets.append(a['src'])


with socket.create_connection((IP, 80), timeout=10):
    report['http_reachable'] = True
try:
    socket.create_connection((IP, 443), timeout=3).close()
except OSError:
    report['tls_port_closed'] = True
else:
    report['tls_port_closed'] = False

baseline = json.loads((ROOT / 'secrets/ip-http-baseline.json').read_text())
private = [json.loads(baseline['entries']['poc-' + kind]['detail']['draft_json'])['content']['title']
           for kind in ('review', 'tutorial', 'leaderboard', 'pricing')]
private += ['PRIVATE_ASSET_NAME_20261017', 'PRIVATE_MEDIA_DISCOVERY_']
assets = set()
paths = ['/', '/aihyb'] + ['/aihyb/' + kind for kind in ('review', 'tutorial', 'leaderboard', 'pricing')]
paths += ['/archives/aihyb-poc-' + kind for kind in (
    'review', 'tutorial', 'leaderboard', 'pricing', 'media-20261017')]
for path in paths:
    status, headers, raw = get(path, {'Accept': 'text/html'})
    body = raw.decode()
    parsed = Page(body)
    assert status == 200 and all(marker not in body for marker in private), path
    assert '127.0.0.1:18090' not in body and OLD not in body and 'https://' + IP not in body, (path, 'stale origin')
    if path != '/' or parsed.canonical:
        assert len(parsed.canonical) == 1, (path, parsed.canonical)
        canonical = urllib.parse.urljoin(BASE + path, parsed.canonical[0])
        assert canonical == BASE + path, (path, canonical)
    assert all('/aihyb/' + kind in body for kind in ('review', 'tutorial', 'leaderboard', 'pricing'))
    for asset in parsed.assets:
        if asset.startswith('/') or asset.startswith(BASE + '/'):
            assets.add(asset.removeprefix(BASE))
    report['pages'][path] = {'status': status, 'canonical': parsed.canonical[0] if parsed.canonical else None}
    checkpoint()
for asset in assets:
    assert get(asset)[0] == 200
report['local_css_js_assets'] = len(assets)

for marker in private:
    value = json.loads(get('/apis/api.halo.run/v1alpha1/indices/-/search', data={
        'keyword': marker, 'limit': 1000, 'filterPublished': False,
        'filterExposed': False, 'filterRecycled': True})[2])
    assert marker not in json.dumps(value['hits'], ensure_ascii=False)
search = json.loads(get('/apis/api.halo.run/v1alpha1/indices/-/search', data={
    'keyword': '价格', 'limit': 10, 'filterPublished': True, 'filterExposed': True,
    'filterRecycled': False})[2])
report['search_hits_for_keyword'] = len(search['hits'])
assert report['search_hits_for_keyword'] >= 1

feeds = (['/rss.xml', '/feed.xml', '/feed/authors/aihyb-admin.xml', '/feed/tags/halo.xml']
         + ['/feed/categories/' + kind + '.xml' for kind in ('reviews', 'tutorials', 'leaderboards', 'prices')]
         + ['/sitemap.xml'])
for path in feeds:
    raw = get(path, {'Accept': 'application/rss+xml,text/xml'})[2].decode()
    ET.fromstring(raw)
    assert all(marker not in raw for marker in private) and '127.0.0.1:18090' not in raw, path
    assert OLD not in raw, (path, 'stale domain')
    assert BASE in raw, (path, 'feed still uses another origin')
report['search_feed_sitemap_private_excluded'] = True

report['blocked_internal_routes'] = {path: denied(path, {404}) for path in (
    '/actuator', '/actuator/health/readiness', '/v3/api-docs/aihybApis', '/swagger-ui.html')}
# Console, login and account routes are tunnel-only on plain HTTP.
report['tunnel_only_routes'] = {path: denied(path, {403}) for path in (
    '/console', '/console/', '/console/posts', '/login', '/logout', '/signup', '/uc',
    '/uc/profile', '/password-reset',
    '/apis/uc.api.halo.run/v1alpha1/users/-',
    '/apis/console.api.aihyb.run/v1alpha1/entries/poc-review',
    '/apis/console.api.aihyb.run/v1alpha1/entries/poc-media-20261017/operations')}
# Routes outside the console prefixes keep Halo's own authentication, as before.
report['anonymous_private_routes'] = {path: denied(path, {401, 403}) for path in (
    '/apis/content.aihyb.run/v1alpha1/contentrevisions?size=1',
    '/apis/content.aihyb.run/v1alpha1/contententries?size=1')}
# Public theme widgets call these two anonymous self-lookup endpoints; they must stay reachable.
for path in ('/apis/api.console.halo.run/v1alpha1/users/-',
             '/apis/api.console.halo.run/v1alpha1/users/-/permissions'):
    assert get(path)[0] == 200, path
report['anonymous_widget_endpoints_open'] = True

view = json.loads(get('/apis/api.aihyb.run/v1alpha1/contents/poc-media-20261017')[2])
assert len(view['assets']) == 3
expected = json.loads((ROOT / 'evidence/media-post-restart.json').read_text())['public_media_hashes']
media = {}
for asset in view['assets']:
    status, headers, raw = get(asset['url'].removeprefix(BASE))
    assert status == 200 and hashlib.sha256(raw).hexdigest() == expected[asset['asset_id']]
    assert 'no-store' in headers.get('Cache-Control', '') and not headers.get('Location')
    media[asset['asset_id']] = {'bytes': len(raw), 'sha256_matches': True}
    if asset['asset_id'] == 'video':
        code, ranged, body = get(asset['url'].removeprefix(BASE), {'Range': 'bytes=0-127'})
        assert code == 206 and body == raw[:128]
        media['video']['range_status'] = code
report['media'] = media
report['archived_entry_denied'] = denied('/archives/aihyb-poc-operations-20261017', {404})
report['complete'] = True
checkpoint()
print('Plain-HTTP IP entry / four modules / origin rewrite / console lockdown / media Range / '
      'discovery PASS', flush=True)
