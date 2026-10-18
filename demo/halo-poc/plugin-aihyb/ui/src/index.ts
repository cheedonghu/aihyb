import { definePlugin } from '@halo-dev/ui-shared'
import { VDropdownItem } from '@halo-dev/components'
import { markRaw } from 'vue'
import NativeMarkdownEditor from './views/NativeMarkdownEditor.vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/aihyb-packages',
        name: 'AIHYBPackages',
        component: () => import(/* webpackChunkName: "HomeView" */ './views/HomeView.vue'),
        meta: {
          title: '稿件数据与审核',
          searchable: false,
          permissions: ['plugin:aihyb:view'],
        },
      },
    },
  ],
  extensionPoints: {
    'editor:create': () => [{ name: 'aihyb-markdown', displayName: '稿件 Markdown', rawType: 'MARKDOWN', component: markRaw(NativeMarkdownEditor) }],
    'post:list-item:operation:create': (post) => {
      const id = post.value.post.metadata.annotations?.['aihyb.run/content-id']
      if (!id) return []
      return [{ priority: 15, component: markRaw(VDropdownItem), label: '稿件数据与审核', permissions: ['plugin:aihyb:view'],
        action: () => { window.location.href = `/console/aihyb-packages?content=${encodeURIComponent(id)}&post=${encodeURIComponent(post.value.post.metadata.name)}` } }]
    },
  },
})
