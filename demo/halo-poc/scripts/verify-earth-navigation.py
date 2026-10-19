"""Read-only content checks around an Earth theme deployment; never publishes or switches themes."""
import argparse
import hashlib
from html.parser import HTMLParser
import json
import runpy
import urllib.request
from datetime import datetime, timezone
from poc_client import ROOT, BASE, Client

BASELINE = ROOT / 'secrets/earth-navigation-baseline.json'
MODULES = ['/aihyb/' + name for name in ('review', 'leaderboard', 'pricing', 'tutorial')]

def fingerprint():
    # Reuse the existing read-only, counter-independent business state projection.
    # deploy-ip-http.py carries the same projection now that the domain entry is retired.
    state = runpy.run_path(str(ROOT / 'deploy-ip-http.py'))['state']()
    digest = hashlib.sha256(json.dumps(state, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return {'sha256': digest, 'counts': {key: len(value) for key, value in state.items()}}

class Page(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.headers = 0
        self.primary = False
        self.nav_count = 0
        self.links = []
        self.canonical = []
        self.styles = []
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'header' and attrs.get('id') == 'header-menu':
            self.headers += 1
        if tag == 'nav' and 'aihyb-primary-nav' in attrs.get('class', '').split():
            self.nav_count += 1
            self.primary = True
        if tag == 'a' and self.primary:
            self.links.append(attrs.get('href'))
        if tag == 'link' and attrs.get('rel') == 'canonical':
            self.canonical.append(attrs.get('href'))
        if tag == 'link' and attrs.get('rel') == 'stylesheet':
            self.styles.append(attrs.get('href'))

    def handle_endtag(self, tag):
        if tag == 'nav':
            self.primary = False

def check():
    assert fingerprint() == json.loads(BASELINE.read_text()), 'Business content changed during theme deployment'
    report = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'business_unchanged': True, 'pages': {}}
    paths = ['/', '/page/2', '/aihyb', *MODULES, '/archives', '/categories/reviews', '/categories', '/tags',
             '/search?keyword=%E4%BB%B7%E6%A0%BC']
    paths += ['/archives/aihyb-poc-' + name for name in ('review', 'leaderboard', 'pricing', 'tutorial', 'theme-render')]
    for path in paths:
        with urllib.request.urlopen(BASE + path, timeout=30) as response:
            assert response.status == 200
            text = response.read().decode()
        page = Page(text)
        assert page.headers == 1 and page.nav_count == 1 and page.links == MODULES, (path, page.links)
        assert any('/assets/aihyb-navigation.css?' in (style or '') for style in page.styles), path
        assert 'THEME_DRAFT_' not in text and 'theme-private-' not in text, path
        if path.startswith('/archives/aihyb-poc-') or path.startswith('/aihyb'):
            assert len(page.canonical) == 1, (path, page.canonical)
        if path in ('/', '/page/2'):
            assert '帮你选对 AI，用好 AI。' in text and 'id="filters"' not in text, path
            assert 'default-background' not in text and 'id="hero"' not in text, path
        report['pages'][path] = {'status': 200, 'one_primary_navigation': True}
    report['complete'] = True
    (ROOT / 'evidence/earth-navigation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print('PASS:', len(paths), 'public pages; shared navigation; business state unchanged')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('freeze', 'check'))
    args = parser.parse_args()
    if args.mode == 'freeze':
        assert Client().request('/apis/theme.halo.run/v1alpha1/themes/theme-earth')['spec']['version'] == '1.15.3'
        BASELINE.write_text(json.dumps(fingerprint()))
        BASELINE.chmod(0o600)
        print('Saved private business fingerprint before theme deployment')
    else:
        check()
