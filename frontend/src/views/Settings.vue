<template>
  <div class="settings-page">
    <el-card class="settings-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span>本地大模型参数配置</span>
          <span class="sub">保存后立即生效，供对话测试与网关默认使用</span>
        </div>
      </template>

      <el-form :model="form" label-width="140px" class="settings-form">
        <el-form-item label="上游地址">
          <el-input v-model="form.upstreamUrl" placeholder="http://127.0.0.1:8000/v1">
            <template #prepend>LLM</template>
          </el-input>
          <div class="tip">OpenAI 兼容服务的 /v1 根地址，网关请求将转发到该地址。</div>
        </el-form-item>

        <el-form-item label="上游 API Key">
          <el-input v-model="form.upstreamApiKey" type="password" show-password placeholder="留空则透传客户端 Key">
            <template #prepend>Key</template>
          </el-input>
          <div class="tip">
            上游服务（vLLM 的 <code>--api-key</code> 或另一个网关）自己的 Key。
            留空时网关会把调用方 <code>Authorization</code> 头原样透传给上游，避免上游返回 invalid_api_key。
          </div>
        </el-form-item>

        <el-form-item label="默认模型">
          <el-input v-model="form.defaultModel" placeholder="例如 qwen2.5-7b-instruct">
            <template #append>
              <el-button :loading="loadingModels" @click="loadModels">检测可用模型</el-button>
            </template>
          </el-input>
          <div class="tip" v-if="models.length">
            可用模型：
            <el-tag v-for="m in models" :key="m" size="small" class="model-tag" @click="form.defaultModel = m">{{ m }}</el-tag>
          </div>
        </el-form-item>

        <el-divider content-position="left">默认采样参数</el-divider>

        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="Temperature">
              <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" show-input :show-input-controls="false" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Top P">
              <el-slider v-model="form.topP" :min="0" :max="1" :step="0.05" show-input :show-input-controls="false" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="Max Tokens">
              <el-input-number v-model="form.maxTokens" :min="1" :max="32768" :step="256" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Presence Penalty">
              <el-input-number v-model="form.presencePenalty" :min="-2" :max="2" :step="0.1" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="Frequency Penalty">
          <el-input-number v-model="form.frequencyPenalty" :min="-2" :max="2" :step="0.1" />
        </el-form-item>

        <el-divider content-position="left">费用计价（元 / 百万 Token）</el-divider>
        <div class="tip price-tip">在下方设置后，数据看板会按各 Key 的输入/输出 Token 量实时估算费用。</div>
        <el-row :gutter="24">
          <el-col :xs="24" :span="12">
            <el-form-item label="输入单价">
              <el-input-number v-model="form.inputPricePerM" :min="0" :precision="4" :step="0.1" style="width:100%" />
              <span class="hint">元 / 百万 tokens</span>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :span="12">
            <el-form-item label="输出单价">
              <el-input-number v-model="form.outputPricePerM" :min="0" :precision="4" :step="0.1" style="width:100%" />
              <span class="hint">元 / 百万 tokens</span>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
          <el-button @click="loadConfig">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api'

const loading = ref(false)
const saving = ref(false)
const loadingModels = ref(false)
const models = ref([])

const form = reactive({
  upstreamUrl: '',
  upstreamApiKey: '',
  defaultModel: '',
  temperature: 0.7,
  maxTokens: 2048,
  topP: 1.0,
  presencePenalty: 0,
  frequencyPenalty: 0,
  inputPricePerM: 0,
  outputPricePerM: 0
})

async function loadConfig() {
  loading.value = true
  try {
    const r = await request.get('/api/admin/config')
    Object.assign(form, r.data || {})
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await request.put('/api/admin/config', form)
    ElMessage.success('配置已保存并生效')
  } finally {
    saving.value = false
  }
}

async function loadModels() {
  if (!form.upstreamUrl) {
    ElMessage.warning('请先填写上游地址')
    return
  }
  loadingModels.value = true
  try {
    const r = await request.get('/api/admin/chat/getModels')
    models.value = r.data || []
    if (models.value.length && !form.defaultModel) {
      form.defaultModel = models.value[0]
    }
    if (!models.value.length) {
      ElMessage.warning('未检测到可用模型，请检查上游服务与地址')
    }
  } finally {
    loadingModels.value = false
  }
}

onMounted(loadConfig)
</script>

<style scoped>
.settings-page {
  padding: 16px;
}
.settings-card {
  max-width: 960px;
}
.card-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.card-header .sub {
  font-size: 12px;
  color: #909399;
  font-weight: 400;
}
.settings-form {
  max-width: 860px;
}
.tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-top: 4px;
}
.model-tag {
  margin: 2px 4px 2px 0;
  cursor: pointer;
}
.price-tip {
  margin-left: 140px;
  margin-bottom: 12px;
}
.hint {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
@media (max-width: 768px) {
  .settings-page {
    padding: 12px;
  }
  .settings-card :deep(.el-card__body) {
    padding: 16px 12px;
  }
  .settings-form :deep(.el-form-item) {
    margin-bottom: 16px;
  }
  .settings-form :deep(.el-form-item__label) {
    float: none;
    display: block;
    text-align: left;
    width: auto !important;
    padding: 0 0 4px;
  }
  .settings-form :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }
  /* 上游地址/Key 前后的 prepend/append 在小屏收缩 */
  .settings-form :deep(.el-input-group__prepend),
  .settings-form :deep(.el-input-group__append) {
    padding: 0 8px;
  }
  .price-tip {
    margin-left: 0;
  }
}
</style>
