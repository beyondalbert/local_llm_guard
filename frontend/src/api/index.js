import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/',
  timeout: 120000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') {
      return response
    }
    const r = response.data
    if (r && typeof r === 'object' && 'code' in r && r.code !== 0) {
      ElMessage.error(r.message || '请求失败')
      if (r.code === 401) {
        localStorage.removeItem('admin_token')
        router.push('/login')
      }
      return Promise.reject(new Error(r.message || '请求失败'))
    }
    return r
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      localStorage.removeItem('admin_token')
      router.push('/login')
      ElMessage.error('登录已过期，请重新登录')
    } else {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request
