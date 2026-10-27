import { definePlugin } from '@halo-dev/ui-shared'
import { VDropdownItem } from '@halo-dev/components'
import { markRaw } from 'vue'
import NativeMarkdownEditor from './views/NativeMarkdownEditor.vue'

export default definePlugin({
  components: {},
  routes: [
    { parentName: 'Root', route: { path: '/aihyb-site', name: 'AIHYBSite', component: () => import('./views/SiteSettingsView.vue'),
      meta: { title: 'AI好用榜站点配置', searchable: true, permissions: ['plugin:aihyb:site-manage'], menu: {name: 'AI好用榜站点配置', group: 'content', priority: 30} } } },
    { parentName: 'Root', route: { path: '/aihyb-new-leaderboard', name: 'AIHYBNewLeaderboard', component: () => import('./views/NewLeaderboardView.vue'),
      meta: { title: '新建综合榜', searchable: true, permissions: ['plugin:aihyb:view'], menu: {name: '新建综合榜', group: 'content', priority: 31} } } },
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
