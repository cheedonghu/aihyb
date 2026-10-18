"""Initialize only the isolated Halo PoC and seed four clearly labelled samples."""
import html
import base64
import http.cookiejar
import json
from pathlib import Path
import re
import secrets
import subprocess
import textwrap
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path('/opt/aihyb-halo-poc')
BASE = 'http://127.0.0.1:18090'
PRIVATE = ROOT / 'secrets'
PRIVATE.mkdir(mode=0o700, exist_ok=True)
PW = PRIVATE / 'admin-password.txt'
if not PW.exists():
    PW.write_text(secrets.token_urlsafe(24), encoding='utf-8')
    PW.chmod(0o600)
password = PW.read_text(encoding='utf-8').strip()
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
csrf = None


def request(path, method='GET', data=None, form=False, authenticated=True):
    headers = {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'}
    body = None
    if data is not None:
        if form:
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
            body = urllib.parse.urlencode(data).encode()
        else:
            headers['Content-Type'] = 'application/json'
            body = json.dumps(data, ensure_ascii=False).encode()
    if csrf and authenticated and method != 'GET':
        headers['X-CSRF-TOKEN'] = csrf
    response = client.open(urllib.request.Request(BASE + path, body, headers, method=method), timeout=90)
    raw = response.read().decode()
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw


def token(page):
    match = re.search(r'name="_csrf"\s+value="([^"]+)"', page)
    if not match:
        raise RuntimeError('No CSRF field found; inspect target Halo login contract.')
    return html.unescape(match.group(1))


setup = client.open(BASE + '/system/setup', timeout=30)
setup_page = setup.read().decode()
if '/system/setup' in setup.url:
    csrf = token(setup_page)
    request('/system/setup', 'POST', {
        '_csrf': csrf, 'username': 'aihyb-admin', 'password': password,
        'email': '', 'siteTitle': 'AI好用榜 · PoC 测试', 'language': 'zh-CN',
        'externalUrl': BASE,
    }, form=True)
    print('INITIALIZED (credentials saved privately, not printed)')

login_page = client.open(BASE + '/login', timeout=30).read().decode()
csrf = token(login_page)
public_key_match = re.search(r'const publicKey = ("[^"]+")', login_page)
if not public_key_match:
    raise RuntimeError('Expected Halo RSA login key not found.')
public_key = json.loads(public_key_match.group(1))
key_path = PRIVATE / 'login-public-key.pem'
key_path.write_text('-----BEGIN PUBLIC KEY-----\n' + '\n'.join(textwrap.wrap(public_key, 64)) + '\n-----END PUBLIC KEY-----\n')
encrypted = subprocess.run(['openssl', 'pkeyutl', '-encrypt', '-pubin', '-inkey', str(key_path),
                            '-pkeyopt', 'rsa_padding_mode:pkcs1'], input=password.encode(),
                           capture_output=True, check=True).stdout
request('/login', 'POST', {'username': 'aihyb-admin',
                          'password': base64.b64encode(encrypted).decode(), '_csrf': csrf}, form=True)
# Refresh the masked CSRF token after authentication (security rotates it).
login_after = client.open(BASE + '/login', timeout=30).read().decode()
found = re.search(r'name="_csrf"\s+value="([^"]+)"', login_after)
if found:
    csrf = html.unescape(found.group(1))
else:
    # Console requests use the raw token supplied by the CSRF cookie.
    for cookie in cookies:
        if cookie.name == 'XSRF-TOKEN':
            csrf = urllib.parse.unquote(cookie.value)
            break

samples = [
    ('reviews', '能力测评', '测试工具的办公任务测评', '<p>这是虚构的 PoC 样例，不代表真实工具结论。</p><h2>任务与条件</h2><p>将一份会议记录整理为待办事项。工具版本、输入条件和核验日期需记录。</p><h2>结果与依据</h2><p>样例能提取负责人和截止时间，缺失的信息明确标为未知。</p><p>来源：本站模拟实测，核验日期 2026-10-13。</p>'),
    ('leaderboards', '评分与榜单', '会议整理任务榜单示例', '<p>这是编辑维护的虚构榜单，不能视为真实排名。</p><table><thead><tr><th>顺序</th><th>工具</th><th>推荐理由</th></tr></thead><tbody><tr><td>1</td><td>测试工具 A</td><td>待办提取完整</td></tr><tr><td>2</td><td>测试工具 B</td><td>格式清晰，需补全负责人</td></tr></tbody></table><p>方法版本：poc-v1；榜单日期：2026-10-13；来源：模拟测评。</p>'),
    ('prices', '价格对比', '订阅与免费额度对比示例', '<p>所有金额均为测试数据，不是产品报价。</p><table><thead><tr><th>工具</th><th>月订阅</th><th>免费额度</th><th>限制</th></tr></thead><tbody><tr><td>测试工具 A</td><td>USD 10/月</td><td>每天 10 次</td><td>文件上传有大小限制</td></tr><tr><td>测试工具 B</td><td>USD 20/月</td><td>未知</td><td>高峰时段限速</td></tr></tbody></table><p>币种和周期相同才可直接比较；未知额度不能用 0 代替。核验日期：2026-10-13。</p>'),
    ('tutorials', '使用技巧与教程', '把会议记录变成待办事项', '<p>PoC 教程样例。</p><ol><li>移除记录中的敏感信息。</li><li>输入下面的提示词。</li><li>核对负责人、截止时间与原文。</li></ol><pre><code>请把会议记录整理成待办表：事项、负责人、截止时间、依据。原文没写的信息标为未知。</code></pre><h2>常见失败</h2><p>模型可能猜测负责人。请保留原始依据并人工确认。</p>'),
]
results = []
for slug, label, title, content in samples:
    category_name = 'poc-' + slug
    cat_path = '/apis/content.halo.run/v1alpha1/categories'
    try:
        request(cat_path + '/' + category_name)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        request(cat_path, 'POST', {
            'apiVersion': 'content.halo.run/v1alpha1', 'kind': 'Category',
            'metadata': {'name': category_name},
            'spec': {'displayName': label, 'slug': slug, 'description': '四模块 PoC 示例', 'priority': 0},
        })
    post_name = 'poc-' + slug
    post_path = '/apis/content.halo.run/v1alpha1/posts/' + post_name
    try:
        post = request(post_path)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        post = request('/apis/api.console.halo.run/v1alpha1/posts', 'POST', {
            'post': {'apiVersion': 'content.halo.run/v1alpha1', 'kind': 'Post',
                     'metadata': {'name': post_name},
                     'spec': {'title': title, 'slug': slug + '-sample', 'owner': 'aihyb-admin',
                              'deleted': False, 'publish': False, 'pinned': False,
                              'allowComment': False, 'visible': 'PUBLIC', 'priority': 0,
                              'excerpt': {'autoGenerate': True}, 'categories': [category_name], 'tags': []}},
            'content': {'raw': content, 'content': content, 'rawType': 'HTML'},
        })
    post = request('/apis/api.console.halo.run/v1alpha1/posts/' + post_name + '/publish', 'PUT')
    results.append({'module': label, 'postName': post_name, 'slug': slug + '-sample',
                    'status': post.get('status', {}), 'category': category_name})
    print('SEEDED:', label)
(ROOT / 'evidence' / 'four-module-samples.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print('Four native content samples created. Structured business CRUD/filtering remains to implement.')
