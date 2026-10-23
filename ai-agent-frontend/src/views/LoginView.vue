<template>
  <div class="login">
    <header class="topbar">
      <span class="brand">AI AGENT STUDY</span>
      <RouterLink to="/" class="back">← 返回应用中心</RouterLink>
    </header>

    <main class="card">
      <h1>{{ isRegister ? '注册' : '登录' }}</h1>
      <p class="sub">
        {{ isRegister ? '注册后自动登录，数据归属于这个新账号' : '登录后，会话与消息才会归属到你的账号' }}
      </p>

      <!-- @submit.prevent：阻止表单默认的"整页刷新"提交，改由 submit() 接管 -->
      <form @submit.prevent="submit">
        <label class="field">
          <span>用户名</span>
          <!-- v-model：输入框的值与 username 这个 ref 双向同步 -->
          <input
            v-model.trim="username"
            type="text"
            autocomplete="username"
            placeholder="请输入用户名"
          />
        </label>

        <label class="field">
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            placeholder="请输入密码"
          />
        </label>

        <label v-if="isRegister" class="field">
          <span>确认密码</span>
          <input
            v-model="confirm"
            type="password"
            autocomplete="new-password"
            placeholder="再输入一次密码"
          />
        </label>

        <p v-if="error" class="error">{{ error }}</p>

        <button type="submit" :disabled="loading || !canSubmit">
          {{ submitText }}
        </button>
      </form>

      <p class="switch">
        {{ isRegister ? '已有账号？' : '还没有账号？' }}
        <a href="#" @click.prevent="toggleMode">{{ isRegister ? '去登录' : '去注册' }}</a>
      </p>
    </main>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, register } from '../api/auth.js'
import { setAuth } from '../stores/auth.js'

const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const confirm = ref('')
const loading = ref(false)
const error = ref('')
// 'login' | 'register'：同一个表单两种用途，避免再建一个页面
const mode = ref('login')

const isRegister = computed(() => mode.value === 'register')

const canSubmit = computed(() => {
  if (!username.value || !password.value) return false
  return isRegister.value ? password.value === confirm.value : true
})

const submitText = computed(() => {
  if (loading.value) return isRegister.value ? '注册中…' : '登录中…'
  return isRegister.value ? '注册并登录' : '登录'
})

function toggleMode() {
  mode.value = isRegister.value ? 'login' : 'register'
  error.value = ''
  confirm.value = ''
}

async function submit() {
  error.value = ''
  if (isRegister.value && password.value !== confirm.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    if (isRegister.value) {
      // 注册接口成功即写入 session，不需要再调一次登录
      await register(username.value, password.value)
    } else {
      await login(username.value, password.value)
    }
    // 登录态在服务端 session 里，前端不需要存 token
    // 传入 username 只是为了让首页顶栏能显示"我是谁"，不参与鉴权
    setAuth(username.value)
    // 原来写死 router.push('/')；改成回到"被打断前想去的地方"，没有就回首页
    router.push(safeRedirect(route.query.redirect))
  } catch (e) {
    // 后端所有错误响应体的形状统一为 { message }，所以一句f话就能兜住
    error.value = e.response?.data?.message || (isRegister.value ? '注册失败，请稍后重试' : '登录失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
// redirect 是用户可改的参数，必须只放行站内路径：
// 否则 ?redirect=//evil.com 会把用户跳出站（open redirect）
function safeRedirect(target) {
  if (typeof target !== 'string') return '/'      // 同名参数多次出现时 query 会是数组
  if (!target.startsWith('/') || target.startsWith('//')) return '/'
  return target
}

</script>

<style scoped>
.login {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #0e1116;
}

.topbar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 30px;
  color: #e8edf2;
}
.brand {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 14px;
  letter-spacing: 3px;
}
.back {
  font-size: 12px;
  color: #6b7f92;
  letter-spacing: 1px;
}
.back:hover {
  color: #22d3ee;
}

.card {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: min(400px, 90vw);
  margin: 0 auto;
}
.card h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 800;
  color: #e8edf2;
}
.sub {
  margin: 8px 0 26px;
  font-size: 13px;
  color: #6b7f92;
}

form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field span {
  font-size: 12px;
  color: #6b7f92;
  letter-spacing: 1px;
}
.field input {
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid rgba(34, 211, 238, 0.2);
  background: #111b27;
  color: #e8edf2;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s ease;
}
.field input:focus {
  border-color: #22d3ee;
}
.field input::placeholder {
  color: #55677a;
}

.error {
  margin: 0;
  font-size: 13px;
  color: #f87171;
}

.switch {
  margin: 18px 0 0;
  font-size: 13px;
  color: #6b7f92;
  text-align: center;
}
.switch a {
  color: #22d3ee;
  margin-left: 4px;
}

button {
  margin-top: 6px;
  padding: 12px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  color: #06202a;
  background: linear-gradient(135deg, #22d3ee, #0891b2);
  transition: transform 0.15s ease, opacity 0.15s ease;
}
button:not(:disabled):hover {
  transform: translateY(-1px);
}
button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
