"""Generate bounded, reproducible overlays from the exact downloaded theme versions."""
import hashlib
import json
import re
from pathlib import Path

themes = Path(__file__).resolve().parents[1]
nav = '''<nav class="aihyb-module-nav" aria-label="四模块导航"><a href="/aihyb/review">能力测评</a><a href="/aihyb/leaderboard">评分与榜单</a><a href="/aihyb/pricing">价格对比</a><a href="/aihyb/tutorial">使用技巧与教程</a></nav>'''
manifest = []
manifest_path=themes/'adapters/manifest.json'
previous={ (item['theme'],item['path']):item for item in json.loads(manifest_path.read_text()) } if manifest_path.exists() else {}
def write(theme, relative, content, original=None):
    target = themes / 'adapters' / theme / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8', newline='\n')
    digest = hashlib.sha256(content.encode()).hexdigest()
    prior = previous.get((theme, relative), {})
    previous_digest = prior.get('previous_adapted_sha256') if prior.get('adapted_sha256') == digest else prior.get('adapted_sha256')
    manifest.append({'theme': theme, 'path': relative,
        'original_sha256': hashlib.sha256(original.encode()).hexdigest() if original is not None else None,
        'previous_adapted_sha256': previous_digest,
        'adapted_sha256': digest})

earth = themes / 'upstream/theme-earth'
source = (earth / 'templates/index.html').read_text(encoding='utf-8')
header_end = source.index('</header>') + len('</header>')
footer_start = source.index('<footer')
layout = source[:header_end] + '\n<main style="max-width:1080px;margin:auto;min-width:0"><th:block th:replace="${content}"/></main>\n' + source[footer_start:]
layout = layout.replace('<html th:lang="${#locale.toLanguageTag}">', '<html th:lang="${#locale.toLanguageTag}" th:fragment="html(head,content)">')
layout = layout.replace('<title th:text="${site.title}"></title>', '<th:block th:replace="${head}"/>')
def earth_navigation(content):
    content, count = re.subn(r'<header\s+id="header-menu"[\s\S]*?</header>',
                            '<th:block th:replace="~{modules/aihyb-header :: header}" />', content)
    assert count == 1, 'Expected exactly one original Earth header'
    return content.replace('</head>', '<link rel="stylesheet" href="/themes/theme-earth/assets/aihyb-navigation.css?v=20261022-polish5">\n<link rel="stylesheet" href="/themes/theme-earth/assets/aihyb-editorial.css?v=20261022-polish5">\n</head>', 1)

write('theme-earth', 'templates/modules/aihyb-header.html', (themes/'shared/earth-header.html').read_text(encoding='utf-8'))
write('theme-earth', 'templates/assets/aihyb-navigation.css', (themes/'shared/earth-navigation.css').read_text(encoding='utf-8'))
write('theme-earth', 'templates/assets/aihyb-editorial.css', (themes/'shared/earth-editorial.css').read_text(encoding='utf-8'))
write('theme-earth', 'templates/layout.html', (themes/'shared/earth-portal-layout.html').read_text(encoding='utf-8'))
for template in sorted((earth/'templates').rglob('*.html')):
    original = template.read_text(encoding='utf-8')
    if 'id="header-menu"' not in original:
        continue
    adapted = earth_navigation(original)
    if template.name == 'index.html':
        adapted = (themes/'shared/earth-portal-index.html').read_text(encoding='utf-8')
    if template.name == 'post.html':
        adapted = (themes/'shared/earth-post.html').read_text(encoding='utf-8')
    write('theme-earth', template.relative_to(earth).as_posix(), adapted, original)

anatole = themes / 'upstream/anatole-v2.1.0'
original = (anatole / 'templates/module/layout.html').read_text(encoding='utf-8')
style='''<style>#aihyb-portal > .aihyb-module-nav{display:none}.main .content{min-width:0}.main .aihyb-module-nav{margin:12px 10px}@media(max-width:960px){.sidebar{height:140px!important}.sidebar .logo-title{padding-top:78px!important}.sidebar .logo-title .title a{font-size:22px}.main .content{margin-top:140px!important}}</style>'''
adapted=original.replace('<div class="content">','<div class="content">'+nav).replace('</head>',style+'</head>')
write('theme-anatole', 'templates/module/layout.html', adapted, original)
layout = adapted.replace('html (title,content)', 'html(head,content)').replace('<title th:text="${title}"></title>', '<th:block th:replace="${head}"/>')
write('theme-anatole', 'templates/layout.html', layout)
original = (anatole / 'templates/module/page-top.html').read_text(encoding='utf-8')
safe = original.replace('<div class="page-top animated fadeInDown">', '<div class="page-top animated fadeInDown" th:with="primary=${menuFinder.getPrimary()}">')
safe = safe.replace('${menuFinder.getPrimary().menuItems}', '${primary == null ? {} : primary.menuItems}')
write('theme-anatole', 'templates/module/page-top.html', safe, original)
original=(anatole/'templates/module/sidebar.html').read_text(encoding='utf-8')
write('theme-anatole','templates/module/sidebar.html',original.replace('alt="Logo"','alt="Logo" th:if="${not #strings.isEmpty(site.logo)}"'),original)
original=(anatole/'templates/module/page-heading.html').read_text(encoding='utf-8')
write('theme-anatole','templates/module/page-heading.html',original.replace('<h3','<h1').replace('</h3>','</h1>'),original)
search=(themes/'shared/search.html').read_text(encoding='utf-8')
write('theme-earth','templates/search.html',search)
original=(anatole/'templates/search.html').read_text(encoding='utf-8')
write('theme-anatole','templates/search.html',search,original)
(themes / 'adapters/manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print('Prepared', len(manifest), 'bounded theme overlays')
