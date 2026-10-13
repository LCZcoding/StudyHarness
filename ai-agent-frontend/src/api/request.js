import axios from 'axios'

// Axios 实例：用于普通 REST 请求
// baseURL 固定 /api，配合 Vite dev proxy 转发到 http://localhost:8123
const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    console.error('[request error]', error?.message || error)
    return Promise.reject(error)
  }
)

export default request
