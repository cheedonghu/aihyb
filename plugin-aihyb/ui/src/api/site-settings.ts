import { axiosInstance } from '@halo-dev/api-client'
export type TaskTag = { id: string; label: string; enabled: boolean; scenes: string[] }
export type QrSlot = { id: string; label: string; description: string; expiresOn: string; image: string }
export type LinkRule = { url: string; relation: string }
export type SiteSettings = { version: string; tags: TaskTag[]; qrs: QrSlot[]; links: LinkRule[] }
export const siteApi = {
  get: () => axiosInstance.get<SiteSettings>('/apis/console.api.aihyb.run/v1alpha1/site-settings'),
  save: (data: SiteSettings) => axiosInstance.put<SiteSettings>('/apis/console.api.aihyb.run/v1alpha1/site-settings', data),
  taxonomy: () => axiosInstance.get<{tags: TaskTag[]; scenes: Record<string,string>}>('/apis/console.api.aihyb.run/v1alpha1/taxonomy'),
}
export const sceneLabels: Record<string,string> = { programming:'编程', research:'科研', office:'专业办公', qa:'知识问答', image:'生图', video:'视频' }
export function siteError(error: unknown): string {
  const data = (error as {response?: {data?: {message?: string; errors?: string[]}}}).response?.data
  return data?.errors?.join('\n') || data?.message || (error instanceof Error ? error.message : '操作失败，请重试。')
}
