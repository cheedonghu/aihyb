"""Generate bounded, reproducible overlays from the exact downloaded theme versions."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
themes = ROOT / 'themes'
nav = '''<nav class="aihyb-module-nav" aria-label="四模块导航"><a href="/aihyb/review">能力测评</a><a href="/aihyb/leaderboard">评分与榜单</a><a href="/aihyb/pricing">价格对比</a><a href="/aihyb/tutorial">使用技巧与教程</a></nav>'''
manifest = []
manifest_path=themes/'adapters/manifest.json'
previous={ (item['theme'],item['path']):item['adapted_sha256'] for item in json.loads(manifest_path.read_text()) } if manifest_path.exists() else {}
def write(theme, relative, content, original=None):
    target = themes / 'adapters' / theme / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8', newline='\n')
    manifest.append({'theme': theme, 'path': relative,
        'original_sha256': hashlib.sha256(original.encode()).hexdigest() if original is not None else None,
        'previous_adapted_sha256': previous.get((theme,relative)),
        'adapted_sha256': hashlib.sha256(content.encode()).hexdigest()})

earth = themes / 'theme-earth'
source = (earth / 'templates/index.html').read_text(encoding='utf-8')
header_end = source.index('</header>') + len('</header>')
footer_start = source.index('<footer')
layout = source[:header_end] + nav + '\n<main style="max-width:1080px;margin:auto;min-width:0"><th:block th:replace="${content}"/></main>\n' + source[footer_start:]
layout = layout.replace('<html th:lang="${#locale.toLanguageTag}">', '<html th:lang="${#locale.toLanguageTag}" th:fragment="html(head,content)">')
layout = layout.replace('<title th:text="${site.title}"></title>', '<th:block th:replace="${head}"/>')
layout=layout.replace('</head>', '<style>#aihyb-portal > .aihyb-module-nav{display:none}</style></head>')
write('theme-earth', 'templates/layout.html', layout)
for relative in ('templates/index.html', 'templates/post.html'):
    original = (earth / relative).read_text(encoding='utf-8')
    write('theme-earth', relative, original.replace('</header>', '</header>\n' + nav, 1), original)

anatole = themes / 'anatole-v2.1.0'
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
