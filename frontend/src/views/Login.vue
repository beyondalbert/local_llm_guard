<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="title">Local LLM Guard</h2>
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item>
          <div class="captcha-row">
            <el-input
              v-model="form.captchaCode"
              placeholder="验证码"
              size="large"
              maxlength="4"
              class="captcha-input"
              @keyup.enter="submit"
            />
            <img
              v-if="captchaImg"
              :src="captchaImg"
              class="captcha-img"
              alt="验证码"
              title="点击刷新"
              @click="loadCaptcha"
            />
            <a v-else class="captcha-refresh" @click="loadCaptcha">获取验证码</a>
          </div>
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'

const router = useRouter()
const loading = ref(false)
const captchaImg = ref('')
const form = reactive({ username: 'admin', password: '', captchaId: '', captchaCode: '' })

async function loadCaptcha() {
  form.captchaCode = ''
  try {
    const r = await request.get('/api/captcha/generate')
    form.captchaId = r.data.captchaId
    captchaImg.value = r.data.imageBase64
  } catch (e) {
    ElMessage.error('验证码获取失败')
  }
}

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  if (!form.captchaCode) {
    ElMessage.warning('请输入验证码')
    return
  }
  loading.value = true
  try {
    const r = await request.post('/api/admin/login', form)
    localStorage.setItem('admin_token', r.data.token)
    localStorage.setItem('admin_username', r.data.username)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (e) {
    // 验证码/登录失败时刷新验证码
    loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(loadCaptcha)
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.login-card {
  width: 380px;
  max-width: 100%;
}
.title {
  text-align: center;
  margin: 0 0 24px;
}
.submit {
  width: 100%;
}
.captcha-row {
  display: flex;
  gap: 10px;
  width: 100%;
  align-items: center;
}
.captcha-input {
  flex: 1;
}
.captcha-img {
  width: 120px;
  height: 44px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid #e2e8f0;
  flex-shrink: 0;
}
.captcha-refresh {
  color: #6366f1;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}
@media (max-width: 768px) {
  .login-card {
    width: 100%;
  }
  .login-card :deep(.el-card__body) {
    padding: 20px 16px;
  }
}
</style>
