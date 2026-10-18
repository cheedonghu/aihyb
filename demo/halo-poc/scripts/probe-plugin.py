from poc_client import Client
client = Client()
plugin = client.request('/apis/plugin.halo.run/v1alpha1/plugins/plugin-aihyb')
print({'enabled': plugin['spec']['enabled'], 'phase': plugin.get('status', {}).get('phase')})
try:
    config = client.request('/v3/api-docs/swagger-config')
    print('OpenAPI groups:', config.get('urls', []))
except Exception as error:
    print('OpenAPI probe:', type(error).__name__)
