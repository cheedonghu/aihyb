from poc_client import Client, ROOT
import urllib.error
admin = Client()
print('Admin session reused:', admin.used_cache)
for role in ('plugin-aihyb-importer', 'plugin-aihyb-editor', 'plugin-aihyb-reviewer', 'plugin-aihyb-publisher'):
    data = admin.request('/api/v1alpha1/roles/' + role)
    print(role, 'rules:', data.get('rules'))
for suffix in ('importer', 'editor', 'reviewer', 'publisher'):
    name = 'poc-package-' + suffix
    user = Client(name, ROOT / 'secrets' / (name + '.txt'))
    print(suffix, 'session reused:', user.used_cache)
    try:
        current = user.request('/apis/api.console.halo.run/v1alpha1/users/-')
        identity = current.get('user', current).get('metadata', {}).get('name')
        print(suffix, 'authenticated identity:', identity)
        result = user.request('/apis/console.api.aihyb.run/v1alpha1/entries?size=1')
        print(suffix, 'list permitted')
    except urllib.error.HTTPError as error:
        print(suffix, 'inspection HTTP:', error.code)
