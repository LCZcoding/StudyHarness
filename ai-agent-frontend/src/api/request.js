import axios from 'axios'

// Axios 实例：用于普通 REST 请求
// baseURL 固定 /api，配合 Vite dev proxy 转发到 http://localhost:8123
const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
  // 后端识别身份靠 HttpSession（Cookie 里的 JSESSIONID）承载，必须让请求带上 Cookie。
  // dev 走 Vite proxy 时前后端同源，Cookie 本来就会带；一旦前端构建后部署到别的域，
  // 少了这一行就会全部 401。
  // 跨域带上token
  withCredentials: true,
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
