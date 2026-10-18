"""Private PoC login client. Never prints passwords, cookies, or CSRF tokens."""
import base64
import html
import http.cookiejar
import json
import os
from pathlib import Path
import re
import subprocess
import textwrap
import urllib.parse
import urllib.request

ROOT = Path(os.environ.get('AIHYB_POC_ROOT', '/opt/aihyb-halo-poc'))
BASE = os.environ.get('AIHYB_POC_URL', 'http://127.0.0.1:18090')

class Client:
    def __init__(self, username='aihyb-admin', password_file=None, basic=False):
        password_file = Path(password_file) if password_file else ROOT / 'secrets/admin-password.txt'
        password = password_file.read_text(encoding='utf-8').strip()
        cookie_path = ROOT / 'secrets' / ('session-' + username + '.txt')
        self.cookies = http.cookiejar.MozillaCookieJar(str(cookie_path))
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.authorization = None
        self.used_cache = False
        self.csrf = None
        if not basic and cookie_path.exists():
            try:
                self.cookies.load(ignore_discard=True)
                current = self.request('/apis/api.console.halo.run/v1alpha1/users/-')
                actual = current.get('user', current).get('metadata', {}).get('name')
                if actual == username:
                    self.used_cache = True
                    self.csrf = next((urllib.parse.unquote(cookie.value) for cookie in self.cookies
                                      if cookie.name == 'XSRF-TOKEN'), None)
                    return
            except (OSError, urllib.error.HTTPError):
                pass
            self.cookies.clear()
        if basic:
            self.csrf = None
            self.authorization = 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode()
            current = self.request('/apis/api.console.halo.run/v1alpha1/users/-')
            actual = current.get('user', current).get('metadata', {}).get('name')
            if actual != username:
                raise RuntimeError('PoC Basic identity did not match ' + username)
            return
        page = self.http.open(BASE + '/login', timeout=30).read().decode()
        self.csrf = html.unescape(re.search(r'name="_csrf"\s+value="([^"]+)"', page).group(1))
        key = json.loads(re.search(r'const publicKey = ("[^"]+")', page).group(1))
        key_path = ROOT / 'secrets/login-public-key.pem'
        key_path.write_text('-----BEGIN PUBLIC KEY-----\n' + '\n'.join(textwrap.wrap(key, 64)) + '\n-----END PUBLIC KEY-----\n')
        key_path.chmod(0o600)
        encrypted = subprocess.run(['openssl', 'pkeyutl', '-encrypt', '-pubin', '-inkey', str(key_path),
                                    '-pkeyopt', 'rsa_padding_mode:pkcs1'], input=password.encode(),
                                   capture_output=True, check=True).stdout
        body = urllib.parse.urlencode({'username': username, 'password': base64.b64encode(encrypted).decode(),
                                       '_csrf': self.csrf}).encode()
        login_result = self.http.open(urllib.request.Request(BASE + '/login', body,
                       {'Content-Type': 'application/x-www-form-urlencoded'}, method='POST'), timeout=30)
        login_html = login_result.read().decode()
        if urllib.parse.urlsplit(login_result.url).path == '/login':
            error_text = re.search(r'<[^>]+class="[^"]*error[^"]*"[^>]*>(.*?)</[^>]+>', login_html, re.S)
            reason = re.sub('<[^>]+>', '', error_text.group(1)).strip()[:200] if error_text else 'authentication rejected'
            raise RuntimeError('PoC login failed for ' + username + ': ' + reason)
        after = self.http.open(BASE + '/login', timeout=30).read().decode()
        token = re.search(r'name="_csrf"\s+value="([^"]+)"', after)
        self.csrf = html.unescape(token.group(1)) if token else next(
            urllib.parse.unquote(cookie.value) for cookie in self.cookies if cookie.name == 'XSRF-TOKEN')
        current = self.request('/apis/api.console.halo.run/v1alpha1/users/-')
        actual = current.get('user', current).get('metadata', {}).get('name')
        if actual != username:
            raise RuntimeError('PoC login identity did not match ' + username)
        self.cookies.save(ignore_discard=True)
        cookie_path.chmod(0o600)

    def request(self, path, method='GET', data=None, raw=None, content_type='application/json'):
        headers = {'Accept': 'text/html' if path.endswith('/preview') else 'application/json',
                   'X-Requested-With': 'XMLHttpRequest'}
        if self.authorization:
            headers['Authorization'] = self.authorization
        body = raw if raw is not None else json.dumps(data, ensure_ascii=False).encode() if data is not None else None
        if body is not None:
            headers['Content-Type'] = content_type
        if self.csrf and method not in ('GET', 'HEAD'):
            headers['X-CSRF-TOKEN'] = self.csrf
        response = self.http.open(urllib.request.Request(BASE + path, body, headers, method=method), timeout=45)
        value = response.read().decode()
        self.cookies.save(ignore_discard=True)
        Path(self.cookies.filename).chmod(0o600)
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value

    def upload(self, path, file_path, file_type='application/java-archive'):
        boundary = 'aihyb-poc-upload-boundary'
        file_path = Path(file_path)
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'
                f'Content-Type: {file_type}\r\n\r\n').encode() + file_path.read_bytes()
        body += f'\r\n--{boundary}--\r\n'.encode()
        return self.request(path, 'POST', raw=body, content_type='multipart/form-data; boundary=' + boundary)

    def save_headers(self, destination):
        destination = Path(destination)
        values = {'Authorization': self.authorization} if self.authorization else {
            'Cookie': '; '.join(cookie.name + '=' + cookie.value for cookie in self.cookies),
            'X-CSRF-TOKEN': self.csrf}
        destination.write_text(json.dumps(values), encoding='utf-8')
        destination.chmod(0o600)
