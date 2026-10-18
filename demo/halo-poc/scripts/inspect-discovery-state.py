"""Read only discovery-related config shape and category identities; omit account/secrets data."""
import json
import urllib.request
from poc_client import Client,ROOT,BASE
c=Client()
categories=c.request('/apis/content.halo.run/v1alpha1/categories?size=1000')['items']
print('Category identity/slug pairs:',[(item['metadata']['name'],item['spec']['slug']) for item in categories])
posts=c.request('/apis/content.halo.run/v1alpha1/posts?size=1000')['items']
print('Managed categories:',[(item['metadata']['annotations'].get('aihyb.run/content-id'),item['spec'].get('categories')) for item in posts if item['metadata'].get('annotations',{}).get('aihyb.run/content-id')])
config=c.request('/apis/api.console.halo.run/v1alpha1/plugins/PluginFeed/json-config')
print('Feed config type:',type(config).__name__,'value:',config)
api=c.request('/v3/api-docs')
operation=api['paths']['/apis/api.console.halo.run/v1alpha1/plugins/{name}/json-config']['put']
print('Config body schema:',operation.get('requestBody'))
for path in ['/rss.xml','/feed/categories/reviews.xml']:
    raw=urllib.request.urlopen(BASE+path,timeout=15).read().decode()
    (ROOT/'evidence'/('discovery-initial-'+path.replace('/','_'))).write_text(raw)
print('Feed diagnostic XML saved')
