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
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'

const router = useRouter()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
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
    // error toast already shown by interceptor
  } finally {
    loading.value = false
  }
}
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
@media (max-width: 768px) {
  .login-card {
    width: 100%;
  }
}
@media (max-width: 768px) {
  .login-card :deep(.el-card__body) {
    padding: 20px 16px;
  }
}
.title {
  text-align: center;
  margin: 0 0 24px;
}
.submit {
  width: 100%;
}
</style>
