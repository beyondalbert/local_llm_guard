<template>
  <div>
    <el-card>
      <div class="toolbar">
        <div class="title">API Key 列表</div>
        <div>
          <el-button @click="openVerify">验证 Key</el-button>
          <el-button type="primary" @click="openCreate">创建 API Key</el-button>
        </div>
      </div>

      <el-table :data="list" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="Key" min-width="200">
          <template #default="{ row }">
            <el-tooltip content="完整 Key 仅创建时展示一次，列表仅显示脱敏前缀" placement="top">
              <code class="key-mask">{{ row.key || '********************' }}</code>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'">
              {{ row.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="expireTime" label="过期时间" width="180">
          <template #default="{ row }">{{ row.expireTime || '永久' }}</template>
        </el-table-column>
        <el-table-column prop="allowModels" label="允许模型" min-width="160">
          <template #default="{ row }">{{ row.allowModels || '全部' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="创建 API Key" width="480px">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" placeholder="例如：开发环境" />
        </el-form-item>
        <el-form-item label="有效天数">
          <el-input-number v-model="createForm.expireDays" :min="0" :max="3650" />
          <span class="hint">0 或留空表示永久有效</span>
        </el-form-item>
        <el-form-item label="允许模型">
          <el-input v-model="createForm.allowModels" placeholder="逗号分隔，留空表示全部模型" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="createForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="keyVisible" title="API Key 创建成功" width="520px">
      <el-alert type="warning" :closable="false" show-icon title="请立即复制保存，关闭后将无法再次查看完整 Key" />
      <div class="key-box">
        <code>{{ newKey }}</code>
        <el-button size="small" @click="copyKey">复制</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="keyVisible = false">我已保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="verifyVisible" title="验证 API Key" width="560px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="粘贴调用方使用的完整 Key，可立即确认当前实例的数据库里能否找到它。"
      />
      <el-input
        v-model="verifyKey"
        type="textarea"
        :rows="3"
        placeholder="sk-..."
        style="margin-top: 14px"
      />
      <div v-if="verifyResult" class="verify-result">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="结果">
            <el-tag :type="verifyResult.valid ? 'success' : 'danger'">
              {{ verifyResult.valid ? '可用' : '不可用' }}
            </el-tag>
            <span class="verify-reason">{{ verifyResult.reason }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="Key 前缀">
            {{ verifyResult.keyPrefix }}（长度 {{ verifyResult.keyLength }}）
          </el-descriptions-item>
          <el-descriptions-item label="匹配记录">
            {{ verifyResult.id ? `#${verifyResult.id} ${verifyResult.remark || ''}` : '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="当前数据库">
            {{ verifyResult.database }}（共 {{ verifyResult.totalKeys }} 条）
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="verifyVisible = false">关闭</el-button>
        <el-button type="primary" :loading="verifying" @click="doVerify">验证</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑 API Key" width="480px">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" />
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker
            v-model="editForm.expireTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="留空表示永久有效"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="允许模型">
          <el-input v-model="editForm.allowModels" placeholder="逗号分隔，留空表示全部模型" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="editForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="update">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const list = ref([])
const loading = ref(false)
const createVisible = ref(false)
const keyVisible = ref(false)
const editVisible = ref(false)
const creating = ref(false)
const saving = ref(false)
const newKey = ref('')
const verifyVisible = ref(false)
const verifyKey = ref('')
const verifyResult = ref(null)
const verifying = ref(false)

const createForm = reactive({ remark: '', expireDays: 0, allowModels: '', enabled: 1 })
const editForm = reactive({ id: null, remark: '', enabled: 1, expireTime: '', allowModels: '' })

async function load() {
  loading.value = true
  try {
    const r = await request.get('/api/admin/apikey/list')
    list.value = r.data || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(createForm, { remark: '', expireDays: 0, allowModels: '', enabled: 1 })
  createVisible.value = true
}

async function create() {
  creating.value = true
  try {
    const r = await request.post('/api/admin/apikey/create', createForm)
    newKey.value = r.data.key
    createVisible.value = false
    keyVisible.value = true
    load()
  } finally {
    creating.value = false
  }
}

function openEdit(row) {  Object.assign(editForm, {
    id: row.id,
    remark: row.remark,
    enabled: row.enabled,
    expireTime: row.expireTime || '',
    allowModels: row.allowModels || ''
  })
  editVisible.value = true
}

async function update() {
  saving.value = true
  try {
    await request.put('/api/admin/apikey/update', editForm)
    ElMessage.success('保存成功')
    editVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function doVerify() {
  if (!verifyKey.value.trim()) {
    ElMessage.warning('请先粘贴要验证的 Key')
    return
  }
  verifying.value = true
  try {
    const r = await request.post('/api/admin/apikey/verify', { key: verifyKey.value })
    verifyResult.value = r.data
  } finally {
    verifying.value = false
  }
}

function openVerify() {
  verifyKey.value = ''
  verifyResult.value = null
  verifyVisible.value = true
}

async function remove(row) {
  await ElMessageBox.confirm('删除后使用该 Key 的请求将立即失效，确定删除？', '提示', { type: 'warning' })
  await request.delete('/api/admin/apikey/delete', { params: { id: row.id } })
  ElMessage.success('删除成功')
  load()
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(newKey.value)
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.warning('复制失败，请手动复制')
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.title {
  font-size: 16px;
  font-weight: 600;
}
@media (max-width: 768px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .toolbar .el-button {
    width: 100%;
    margin-left: 0 !important;
  }
  /* 移动端卡片横向滚动 */
  :deep(.el-card__body) {
    overflow-x: auto;
    padding: 12px;
  }
  :deep(.el-dialog) {
    width: 92% !important;
    max-width: 480px;
  }
}
.hint {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
.key-mask {
  background: #f1f5f9;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #475569;
}
.key-box {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 16px;
}
.key-box code {
  flex: 1;
  word-break: break-all;
  background: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
}
.verify-result {
  margin-top: 14px;
}
.verify-reason {
  margin-left: 8px;
  color: #606266;
  font-size: 13px;
}
</style>
