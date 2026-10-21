<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">
        <span class="logo-mark">L</span>
        <span>Local LLM Guard</span>
      </div>
      <el-menu :default-active="$route.path" router background-color="#0f172a" text-color="#94a3b8" active-text-color="#ffffff">
        <el-menu-item index="/dashboard">
          <span class="menu-icon">▦</span>
          <span>数据看板</span>
        </el-menu-item>
        <el-menu-item index="/keys">
          <span class="menu-icon">⚿</span>
          <span>API Key 管理</span>
        </el-menu-item>
        <el-menu-item index="/chat">
          <span class="menu-icon">✉</span>
          <span>对话测试</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <span class="menu-icon">⚙</span>
          <span>模型配置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="body">
      <el-header class="header">
        <div class="header-title">{{ routeTitle }}</div>
        <div class="header-right">
          <el-tooltip content="模型配置" placement="bottom">
            <el-button link class="icon-btn" @click="router.push('/settings')">⚙</el-button>
          </el-tooltip>
          <el-dropdown trigger="click" @command="onCommand">
            <div class="user">
              <span class="avatar">{{ (username || 'A')[0].toUpperCase() }}</span>
              <span class="name">{{ username }}</span>
              <span class="caret">▾</span>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout">
                  <span style="color: #f56c6c">退出登录</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <el-dialog v-model="pwdVisible" title="修改密码" width="420px" :close-on-click-modal="false">
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="88px">
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 8 位，含字母和数字" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'

const route = useRoute()
const router = useRouter()
const username = localStorage.getItem('admin_username') || 'admin'

const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdFormRef = ref(null)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '新密码长度不能少于 8 位', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value && (!/[A-Za-z]/.test(value) || !/\d/.test(value))) {
          callback(new Error('新密码需同时包含字母和数字'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== pwdForm.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const routeTitle = computed(() => {
  const map = { dashboard: '数据看板', keys: 'API Key 管理', chat: '对话测试', settings: '模型配置' }
  return map[route.name] || 'Local LLM Guard'
})

function onCommand(command) {
  if (command === 'logout') {
    localStorage.removeItem('admin_token')
    localStorage.removeItem('admin_username')
    router.push('/login')
  } else if (command === 'password') {
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
    pwdVisible.value = true
  }
}

async function submitPassword() {
  if (!pwdFormRef.value) return
  try {
    await pwdFormRef.value.validate()
  } catch (e) {
    return
  }
  pwdSaving.value = true
  try {
    await request.post('/api/admin/password', {
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword
    })
    ElMessage.success('密码修改成功，请重新登录')
    pwdVisible.value = false
    localStorage.removeItem('admin_token')
    localStorage.removeItem('admin_username')
    router.push('/login')
  } finally {
    pwdSaving.value = false
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  background: #0f172a;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  padding: 18px 20px;
}
.logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  font-size: 15px;
}
.aside :deep(.el-menu) {
  border-right: none;
}
.aside :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
}
.aside :deep(.el-menu-item.is-active) {
  background: rgba(99, 102, 241, 0.18);
}
.menu-icon {
  margin-right: 10px;
  font-size: 16px;
}
.body {
  min-width: 0;
}
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  z-index: 1;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.icon-btn {
  font-size: 18px;
  padding: 6px;
}
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 8px;
  transition: background 0.2s;
}
.user:hover {
  background: #f1f5f9;
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}
.name {
  color: #334155;
  font-size: 14px;
}
.caret {
  color: #94a3b8;
  font-size: 12px;
}
.main {
  overflow: auto;
  background: #f5f7fa;
  padding: 0;
}
</style>
