<template>
  <div>
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6" v-for="card in cards" :key="card.label">
        <el-card class="stat-card" shadow="never">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value" :class="{ 'money': card.label.includes('费用') }">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="charts">
      <el-col :xs="24" :sm="16">
        <el-card v-loading="dailyLoading" shadow="never">
          <div class="chart-header">
            <div class="chart-title">每日统计</div>
            <div class="chart-controls">
              <el-select
                v-model="dailyKeys"
                multiple
                collapse-tags
                collapse-tags-tooltip
                clearable
                placeholder="全部 Key"
                style="width: 220px"
                @change="loadDaily"
              >
                <el-option v-for="k in keys" :key="k.id" :label="k.remark || `Key #${k.id}`" :value="k.id" />
              </el-select>
              <el-radio-group v-model="dailyDays" @change="loadDaily">
                <el-radio-button :label="7">近一周</el-radio-button>
                <el-radio-button :label="30">近一个月</el-radio-button>
              </el-radio-group>
            </div>
          </div>
          <div ref="dailyRef" class="chart"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="never">
          <div class="chart-title">模型分布</div>
          <div ref="modelRef" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="charts">
      <el-col :span="24">
        <el-card shadow="never">
          <div class="chart-title">各 API Key 请求与 Token 统计</div>
          <div ref="keyRef" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="detail-card">
      <div class="chart-title">API Key 使用明细</div>
      <el-table :data="keyUsage" v-loading="keyLoading" border stripe size="small">
        <el-table-column label="API Key" min-width="200">
          <template #default="{ row }">
            <span v-if="row.apiKeyId == null" class="no-key">未关联 Key</span>
            <span v-else>{{ keyLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="totalRequests" label="请求数" width="100" align="right" />
        <el-table-column prop="successRequests" label="成功" width="90" align="right" />
        <el-table-column prop="failedRequests" label="失败" width="90" align="right" />
        <el-table-column prop="promptTokens" label="Prompt Tokens" width="140" align="right" />
        <el-table-column prop="completionTokens" label="Completion Tokens" width="170" align="right" />
        <el-table-column prop="totalTokens" label="总 Tokens" width="120" align="right" />
        <el-table-column label="费用" width="120" align="right">
          <template #default="{ row }">{{ formatMoney(row.totalCost) }}</template>
        </el-table-column>
        <el-table-column prop="lastRequestTime" label="最近请求时间" width="180" />
      </el-table>
    </el-card>

    <el-card class="detail-card">
      <div class="toolbar">
        <div class="chart-title">请求明细</div>
        <div class="filters">
          <el-input v-model="filters.model" placeholder="模型" clearable style="width: 140px" />
          <el-select v-model="filters.apiKeyId" placeholder="API Key" clearable style="width: 150px">
            <el-option v-for="k in keys" :key="k.id" :label="k.remark || `Key #${k.id}`" :value="k.id" />
          </el-select>
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 110px">
            <el-option label="成功" :value="1" />
            <el-option label="失败" :value="0" />
          </el-select>
          <el-date-picker
            v-model="filters.range"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 340px"
          />
          <el-button type="primary" @click="loadDetail(1)">查询</el-button>
          <el-button @click="exportCsv">导出 CSV</el-button>
        </div>
      </div>

      <el-table :data="records" v-loading="detailLoading" border stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="model" label="模型" min-width="140" />
        <el-table-column label="API Key" min-width="150">
          <template #default="{ row }">
            <span v-if="row.apiKeyId == null" class="no-key">未关联 Key</span>
            <span v-else>{{ keyIdLabel(row.apiKeyId) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="promptTokens" label="Prompt Tokens" width="130" />
        <el-table-column prop="completionTokens" label="Completion Tokens" width="160" />
        <el-table-column prop="requestIp" label="IP" width="130" />
        <el-table-column prop="costMs" label="耗时(ms)" width="100" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误信息" min-width="160" show-overflow-tooltip />
        <el-table-column prop="requestTime" label="请求时间" width="180" />
      </el-table>

      <el-pagination
        class="pagination"
        background
        layout="total, prev, pager, next, sizes"
        :total="total"
        :page-size="filters.size"
        :current-page="filters.page"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="loadDetail"
        @size-change="onSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import request from '../api'

const cards = reactive([
  { label: '总请求数', value: 0 },
  { label: '总 Tokens', value: 0 },
  { label: '总费用', value: '¥0.00' },
  { label: '今日请求', value: 0 }
])

const dailyRef = ref(null)
const modelRef = ref(null)
const keyRef = ref(null)
let dailyChart = null
let modelChart = null
let keyChart = null

const keys = ref([])
const records = ref([])
const total = ref(0)
const detailLoading = ref(false)
const keyUsage = ref([])
const keyLoading = ref(false)
const dailyKeys = ref([])
const dailyDays = ref(7)
const dailyLoading = ref(false)
const filters = reactive({ page: 1, size: 20, model: '', apiKeyId: null, status: null, range: [] })

async function loadDashboard() {
  keyLoading.value = true
  try {
    const r = await request.get('/api/admin/stat/dashboard')
    const d = r.data || {}
    cards[0].value = d.totalRequests ?? 0
    cards[1].value = formatTokens(d.totalTokens ?? 0)
    cards[2].value = formatMoney(d.totalCost ?? 0)
    cards[3].value = d.todayRequests ?? 0
    if (d.todayCost != null) {
      todayCost = d.todayCost
    }
    inputPrice = d.inputPricePerM ?? 0
    outputPrice = d.outputPricePerM ?? 0
    keyUsage.value = d.keyUsage || []
    renderModel(d.modelDistribution || [])
    renderKey(keyUsage.value)
  } finally {
    keyLoading.value = false
  }
}

async function loadDaily() {
  dailyLoading.value = true
  try {
    const params = { days: dailyDays.value }
    if (dailyKeys.value && dailyKeys.value.length) {
      params.apiKeyIds = dailyKeys.value.join(',')
    }
    const r = await request.get('/api/admin/stat/daily', { params })
    renderDaily(r.data || [])
  } finally {
    dailyLoading.value = false
  }
}

function renderDaily(rows) {
  if (!dailyChart) return
  dailyChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        if (!params || !params.length) return ''
        const i = params[0].dataIndex
        const row = rows[i] || {}
        const lines = []
        if (row.date) lines.push(row.date)
        params.forEach((p) => {
          lines.push(`${p.marker}${p.seriesName}：${fmtNum(p.value)}`)
        })
        lines.push(`费用：${formatMoney(row.totalCost)}`)
        return lines.join('<br/>')
      }
    },
    legend: { bottom: 0 },
    grid: { left: 20, right: 20, top: 50, bottom: 60, containLabel: true },
    xAxis: { type: 'category', data: rows.map((i) => (i.date || '').slice(5)) },
    yAxis: [
      { type: 'value', name: '请求数', minInterval: 1 },
      { type: 'value', name: 'Tokens' }
    ],
    series: [
      { name: '请求数', type: 'bar', data: rows.map((i) => Number(i.requests) || 0) },
      { name: '总 Tokens', type: 'line', smooth: true, yAxisIndex: 1, data: rows.map((i) => Number(i.totalTokens) || 0) }
    ]
  })
}

function renderModel(dist) {
  if (!modelChart) return
  modelChart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '65%'],
      data: dist.map((i) => ({ name: i.name || 'unknown', value: Number(i.value) || 0 }))
    }]
  })
}

function keyLabel(row) {
  if (row.remark) return row.remark
  if (row.keyPrefix) return row.keyPrefix + '…'
  return 'Key #' + row.apiKeyId
}

// 根据 apiKeyId 在 keys 列表里查备注；查不到则退回前缀/Key #id
function keyIdLabel(apiKeyId) {
  if (apiKeyId == null) return '未关联 Key'
  const k = keys.value.find((x) => x.id === apiKeyId)
  if (k && k.remark) return k.remark
  if (k && k.keyPrefix) return k.keyPrefix + '…'
  return 'Key #' + apiKeyId
}

function renderKey(usage) {
  if (!keyChart) return
  const rows = usage || []
  keyChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const i = params[0] ? params[0].dataIndex : 0
        const row = rows[i] || {}
        let s = (row.apiKeyId == null ? '未关联 Key' : keyLabel(row)) + '<br/>'
        params.forEach((p) => { s += `${p.marker}${p.seriesName}：${fmtNum(p.value)}<br/>` })
        s += `费用：${formatMoney(row.totalCost)}`
        return s
      }
    },
    legend: { bottom: 0 },
    grid: { left: 20, right: 20, top: 50, bottom: 60, containLabel: true },
    xAxis: { type: 'category', data: rows.map((i) => (i.apiKeyId == null ? '未关联 Key' : keyLabel(i))), axisLabel: { interval: 0 } },
    yAxis: [
      { type: 'value', name: '请求数', minInterval: 1 },
      { type: 'value', name: 'Tokens' }
    ],
    series: [
      { name: '成功', type: 'bar', stack: 'total', data: rows.map((i) => Number(i.successRequests) || 0) },
      { name: '失败', type: 'bar', stack: 'total', data: rows.map((i) => Number(i.failedRequests) || 0) },
      { name: '总 Tokens', type: 'line', yAxisIndex: 1, data: rows.map((i) => Number(i.totalTokens) || 0) }
    ]
  })
}

function formatTokens(n) {
  return fmtNum(n)
}

function fmtNum(n) {
  const num = Number(n) || 0
  if (num >= 1e8) return (num / 1e8).toFixed(2) + '亿'
  if (num >= 1e4) return (num / 1e4).toFixed(2) + '万'
  return String(num)
}

function formatMoney(n) {
  return '¥' + (Number(n) || 0).toFixed(inputPrice > 0 || outputPrice > 0 ? 4 : 2)
}

let todayCost = 0
let inputPrice = 0
let outputPrice = 0

async function loadKeys() {
  const r = await request.get('/api/admin/apikey/list')
  keys.value = r.data || []
}

async function loadDetail(page) {
  detailLoading.value = true
  filters.page = page
  try {
    const params = {
      page: filters.page,
      size: filters.size,
      model: filters.model || undefined,
      apiKeyId: filters.apiKeyId || undefined,
      status: filters.status,
      startTime: filters.range && filters.range[0] ? filters.range[0] : undefined,
      endTime: filters.range && filters.range[1] ? filters.range[1] : undefined
    }
    const r = await request.get('/api/admin/stat/detail', { params })
    records.value = r.data.records || []
    total.value = r.data.total || 0
  } finally {
    detailLoading.value = false
  }
}

function onSizeChange(size) {
  filters.size = size
  loadDetail(1)
}

async function exportCsv() {
  const params = {
    model: filters.model || undefined,
    apiKeyId: filters.apiKeyId || undefined,
    status: filters.status,
    startTime: filters.range && filters.range[0] ? filters.range[0] : undefined,
    endTime: filters.range && filters.range[1] ? filters.range[1] : undefined
  }
  const response = await request.get('/api/admin/stat/exportCsv', { params, responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  const a = document.createElement('a')
  a.href = url
  a.download = 'request_logs.csv'
  a.click()
  URL.revokeObjectURL(url)
}

function resizeCharts() {
  dailyChart && dailyChart.resize()
  modelChart && modelChart.resize()
  keyChart && keyChart.resize()
}

onMounted(() => {
  dailyChart = echarts.init(dailyRef.value)
  modelChart = echarts.init(modelRef.value)
  keyChart = echarts.init(keyRef.value)
  window.addEventListener('resize', resizeCharts)
  loadDashboard()
  loadKeys()
  loadDetail(1)
  loadDaily()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  dailyChart && dailyChart.dispose()
  modelChart && modelChart.dispose()
  keyChart && keyChart.dispose()
})
</script>

<style scoped>
.stat-card {
  text-align: center;
}
.stat-label {
  color: #909399;
  font-size: 13px;
}
.stat-value {
  font-size: 28px;
  font-weight: 600;
  margin-top: 8px;
}
.stat-value.money {
  color: #e67e22;
}
@media (max-width: 768px) {
  .stat-value {
    font-size: 20px;
  }
  .stat-card :deep(.el-card__body) {
    padding: 12px 8px;
  }
}
.charts {
  margin-top: 16px;
}
.chart-title {
  font-weight: 600;
  margin-bottom: 12px;
}
.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.chart-header .chart-title {
  margin-bottom: 0;
}
.chart-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.chart {
  height: 340px;
}
.detail-card {
  margin-top: 16px;
}
.no-key {
  color: #909399;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 8px;
}
.filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.pagination {
  margin-top: 12px;
  justify-content: flex-end;
  flex-wrap: wrap;
}
@media (max-width: 768px) {
  .charts {
    margin-top: 12px;
  }
  .chart {
    height: 260px;
  }
  .chart-controls {
    width: 100%;
  }
  .chart-controls .el-select {
    width: 100% !important;
  }
  .detail-card {
    margin-top: 12px;
  }
  .detail-card :deep(.el-card__body) {
    padding: 12px;
  }
  .filters {
    width: 100%;
  }
  .filters :deep(.el-input),
  .filters :deep(.el-select),
  .filters :deep(.el-date-editor) {
    width: 100% !important;
  }
  /* 表格在移动端变成横向滚动，触屏可滑动 */
  .detail-card :deep(.el-table) {
    min-width: 0;
  }
}
</style>
