import { axiosInstance } from '@halo-dev/api-client'
import { AIHYBAdminApi, AIHYBMediaApi, Configuration } from './generated'

export const packageApi = new AIHYBAdminApi(undefined, '', axiosInstance)
export const mediaApi = new AIHYBMediaApi(new Configuration(), '', axiosInstance)
