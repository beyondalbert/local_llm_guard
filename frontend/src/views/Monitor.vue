<template>
  <div class="monitor-page">
    <div class="toolbar">
      <div class="title">系统监测</div>
      <div class="actions">
        <span class="updated">上次刷新：{{ lastUpdate || '—' }}</span>
        <el-button size="small" @click="load" :loading="loading">刷新</el-button>
        <el-switch v-model="auto" active-text="自动刷新" @change="toggleAuto" />
      </div>
    </div>

    <div v-loading="loading">
      <!-- CPU / 内存 / 磁盘 / JVM 概览卡 -->
      <el-row :gutter="12">
        <el-col :xs="12" :sm="12" :md="6" v-for="card in overview" :key="card.label">
          <el-card class="ov-card" shadow="never">
            <div class="ov-label">{{ card.label }}</div>
            <div class="ov-value">
              <el-progress type="dashboard" :percentage="card.percent" :width="86" :stroke-width="8">
                <template #default>
                  <span class="ov-percent">{{ card.percent }}%</span>
                </template>
              </el-progress>
            </div>
            <div class="ov-sub">{{ card.sub }}</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 详情 -->
      <el-row :gutter="12" class="det-row">
        <el-col :xs="24" :md="12">
          <el-card shadow="never">
            <template #header>CPU / 内存</template>
            <div class="det-item"><span>CPU 核心数</span><strong>{{ detail.cpu?.cores }}</strong></div>
            <div class="det-item"><span>CPU 负载 (1min)</span><strong>{{ fmtNum(detail.cpu?.load) }}</strong></div>
            <div class="det-item"><span>进程 CPU</span><strong>{{ fmtNum(detail.cpu?.processCpu) }}%</strong></div>
            <el-divider />
            <div class="det-item"><span>总内存</span><strong>{{ fmtSize(detail.memory?.total) }}</strong></div>
            <div class="det-item"><span>已用内存</span><strong>{{ fmtSize(detail.memory?.used) }}</strong></div>
            <div class="det-item"><span>可用内存</span><strong>{{ fmtSize(detail.memory?.free) }}</strong></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-card shadow="never">
            <template #header>JVM / 磁盘</template>
            <div class="det-item"><span>JVM 堆上限</span><strong>{{ fmtSize(detail.jvm?.heapMax) }}</strong></div>
            <div class="det-item"><span>JVM 已用堆</span><strong>{{ fmtSize(detail.jvm?.heapUsed) }}</strong></div>
            <div class="det-item"><span>运行时长</span><strong>{{ fmtUptime(detail.jvm?.uptime) }}</strong></div>
            <el-divider />
            <div class="det-item"><span>磁盘路径</span><strong class="ellipsis">{{ detail.disk?.path }}</strong></div>
            <div class="det-item"><span>磁盘容量</span><strong>{{ fmtSize(detail.disk?.total) }}</strong></div>
            <div class="det-item"><span>已用</span><strong>{{ fmtSize(detail.disk?.used) }}（{{ fmtNum(detail.disk?.usagePercent) }}%）</strong></div>
          </el-card>
        </el-col>
      </el-row>

      <!-- GPU -->
      <el-card shadow="never" class="gpu-card">
        <template #header>显卡（GPU）</template>
        <template v-if="gpus.length">
          <el-row :gutter="12" v-for="(g, i) in gpus" :key="i" class="gpu-item">
            <el-col :xs="24" :md="12">
              <div class="gpu-name">
                <span class="gpu-dot"></span>{{ g.available !== false ? g.name : '未检测到 GPU' }}
              </div>
              <div v-if="g.available !== false" class="gpu-sub">
                {{ fmtNum(g.temperature) }}°C · 利用率 {{ fmtNum(g.utilization) }}%
              </div>
              <div v-else class="gpu-note">{{ g.note }}</div>
            </el-col>
            <el-col v-if="g.available !== false" :xs="24" :md="12">
              <div class="gpu-mem-row">
                <span class="gpu-mem-label">显存</span>
                <el-progress :percentage="g.memUsagePercent" :stroke-width="10" />
                <span class="gpu-mem-text">{{ fmtSize(g.memUsed) }} / {{ fmtSize(g.memTotal) }}</span>
              </div>
            </el-col>
          </el-row>
        </template>
        <el-empty v-else description="暂无 GPU 数据" :image-size="60" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import request from '../api'

const loading = ref(false)
const auto = ref(false)
const lastUpdate = ref('')
const detail = reactive({})
let timer = null

const overview = computed(() => [
  { label: 'CPU 负载', percent: clamp(detail.cpu?.load / detail.cpu?.cores * 100), sub: `负载 ${fmtNum(detail.cpu?.load)} / ${detail.cpu?.cores} 核` },
  { label: '内存使用', percent: clamp(detail.memory?.usagePercent), sub: `${fmtSize(detail.memory?.used)} / ${fmtSize(detail.memory?.total)}` },
  { label: '磁盘使用', percent: clamp(detail.disk?.usagePercent), sub: `${fmtSize(detail.disk?.used)} / ${fmtSize(detail.disk?.total)}` },
  { label: 'JVM 堆', percent: clamp(detail.jvm?.heapUsagePercent), sub: `${fmtSize(detail.jvm?.heapUsed)} / ${fmtSize(detail.jvm?.heapMax)}` }
])

const gpus = computed(() => detail.gpus || [])

async function load() {
  loading.value = true
  try {
    const r = await request.get('/api/admin/monitor')
    Object.keys(detail).forEach((k) => delete detail[k])
    Object.assign(detail, r.data || {})
    const t = (detail.timestamp && new Date(detail.timestamp))
    lastUpdate.value = t ? t.toLocaleString() : '—'
  } finally {
    loading.value = false
  }
}

function toggleAuto(v) {
  if (timer) { clearInterval(timer); timer = null }
  if (v) timer = setInterval(load, 5000)
}

function clamp(v) {
  const n = Number(v) || 0
  return Math.min(100, Math.max(0, Math.round(n)))
}

function fmtNum(v) {
  const n = Number(v)
  if (isNaN(n)) return '0'
  return (Math.round(n * 100) / 100).toString()
}

function fmtSize(bytes) {
  const n = Number(bytes) || 0
  if (n === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return v.toFixed(1) + ' ' + units[i]
}

function fmtUptime(ms) {
  const s = Number(ms) || 0
  const days = Math.floor(s / 86400000)
  const hours = Math.floor((s % 86400000) / 3600000)
  const mins = Math.floor((s % 3600000) / 60000)
  if (days > 0) return `${days}天 ${hours}小时`
  if (hours > 0) return `${hours}小时 ${mins}分`
  return `${mins}分`
}

onMounted(load)
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.monitor-page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 8px; }
.title { font-size: 16px; font-weight: 600; }
.actions { display: flex; align-items: center; gap: 12px; }
.updated { font-size: 12px; color: #909399; }
.ov-card { text-align: center; margin-bottom: 12px; }
.ov-value { height: 100px; display: flex; align-items: center; justify-content: center; }
.ov-percent { font-size: 18px; font-weight: 600; color: #1e293b; }
.ov-sub { font-size: 12px; color: #909399; margin-top: 8px; }
.det-row { margin-top: 12px; }
.det-item { display: flex; justify-content: space-between; padding: 6px 2px; font-size: 13px; }
.det-item span { color: #64748b; }
.det-item strong { color: #0f172a; }
.det-item strong.ellipsis { max-width: 60%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gpu-card { margin-top: 12px; }
.gpu-item { padding: 12px 4px; border-bottom: 1px solid #f1f5f9; }
.gpu-item:last-child { border-bottom: none; }
.gpu-name { display: flex; align-items: center; gap: 8px; font-weight: 600; color: #0f172a; }
.gpu-dot { width: 8px; height: 8px; border-radius: 50%; background: #34d399; }
.gpu-sub { font-size: 12px; color: #64748b; margin-top: 6px; }
.gpu-note { font-size: 12px; color: #f59e0b; margin-top: 6px; }
.gpu-mem-row { display: flex; align-items: center; gap: 10px; }
.gpu-mem-label { font-size: 13px; color: #64748b; flex-shrink: 0; }
.gpu-mem-row .el-progress { flex: 1; }
.gpu-mem-text { font-size: 12px; color: #64748b; white-space: nowrap; }
@media (max-width: 768px) {
  .monitor-page { padding: 12px; }
  .ov-value { height: 86px; }
}
</style>
