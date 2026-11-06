<template>
  <div class="monitor-page">
    <div class="toolbar">
      <div class="title">设备监控</div>
      <div class="actions">
        <span class="updated">上次刷新：{{ lastUpdate || '—' }}</span>
        <el-button size="small" @click="load" :loading="loading">刷新</el-button>
        <span class="autorefresh">
          <el-switch v-model="auto" @change="toggleAuto" />
          <span>自动刷新 {{ auto ? '(5s)' : '' }}</span>
        </span>
      </div>
    </div>

    <div v-loading="loading" class="monitor-body">
      <!-- ===== 概览卡片：CPU / 内存 / 磁盘 / 网络 ===== -->
      <div class="overview-grid">
        <div class="ov-card">
          <div class="ov-head">
            <span class="ov-title">CPU</span>
            <span class="ov-state" :class="cpuState">{{ cpuTempTxt }}</span>
          </div>
          <div class="ov-main">
            <span class="ov-big">{{ ovCpu }}<small>%</small></span>
            <span class="ov-model" :title="detail.cpu?.model">{{ detail.cpu?.model || 'CPU' }}</span>
          </div>
          <div class="ov-sub">{{ detail.cpu?.cores }} 核 · 负载 {{ fmtNum(detail.cpu?.load) }}</div>
          <div ref="cpuSparkEl" class="spark"></div>
        </div>

        <div class="ov-card">
          <div class="ov-head">
            <span class="ov-title">内存 RAM</span>
          </div>
          <div class="ov-main">
            <span class="ov-big">{{ percent(detail.memory?.usagePercent) }}<small>%</small></span>
            <span class="ov-model">{{ fmtSize(detail.memory?.used) }} / {{ fmtSize(detail.memory?.total) }}</span>
          </div>
          <div class="ov-sub">{{ fmtSize(detail.memory?.free) }} 可用</div>
          <div ref="memSparkEl" class="spark"></div>
        </div>

        <div class="ov-card">
          <div class="ov-head">
            <span class="ov-title">磁盘</span>
          </div>
          <div class="ov-main">
            <span class="ov-big">{{ percent(detail.disk?.usagePercent) }}<small>%</small></span>
            <span class="ov-model">{{ fmtSize(detail.disk?.used) }} / {{ fmtSize(detail.disk?.total) }}</span>
          </div>
          <div class="ov-sub">{{ fmtSize(detail.disk?.free) }} 可用</div>
          <div ref="diskSparkEl" class="spark"></div>
        </div>

        <div class="ov-card">
          <div class="ov-head">
            <span class="ov-title">网络</span>
            <span class="ov-state net-up" v-if="detail.network?.available">在线</span>
          </div>
          <div class="ov-main">
            <span class="ov-big small">{{ fmtRate(detail.network?.rxRate) }}</span>
          </div>
          <div class="ov-sub">↓ 下载 {{ fmtRate(detail.network?.rxRate) }} · ↑ 上传 {{ fmtRate(detail.network?.txRate) }}</div>
          <div ref="netSparkEl" class="spark"></div>
        </div>
      </div>

      <!-- ===== GPU ===== -->
      <div class="section-card" v-if="gpu.available">
        <div class="section-head">
          <span class="gpu-led"></span>
          <span class="section-title">{{ gpu.name }}</span>
          <span class="gpu-util">{{ gpu.utilization }}%</span>
        </div>
        <div class="gpu-grid">
          <div class="gpu-ring">
            <el-progress type="dashboard" :percentage="clamp(gpu.utilization)" :width="120" :stroke-width="9"
                         :color="utilColor">
              <template #default>
                <span class="ring-center">
                  <strong>{{ gpu.utilization }}%</strong>
                  <small>用量</small>
                </span>
              </template>
            </el-progress>
          </div>
          <div class="gpu-stats">
            <div class="bs-row" v-if="gpu.temperature != null"><span>温度</span><b :class="tempClass">{{ gpu.temperature }}°C</b></div>
            <div class="bs-row"><span>功耗</span><b>{{ fmtNum(gpu.powerDraw) }} / {{ fmtNum(gpu.powerLimit) }} W</b></div>
            <div class="bs-row"><span>SM 频率</span><b>{{ gpu.clockSmMHz || '—' }} MHz</b></div>
            <div class="bs-row"><span>状态</span><b>{{ gpu.throttled ? '受限 (throttled)' : '正常' }}</b></div>
          </div>
          <div class="gpu-vram">
            <div class="bs-row"><span>显存 VRAM</span><b>{{ percent(gpu.memUsagePercent) }}%</b></div>
            <el-progress :percentage="clamp(gpu.memUsagePercent)" :stroke-width="12" :color="vramColor" />
            <div class="vram-detail">
              <span>{{ fmtSize(gpu.memUsed) }} 已用</span>
              <span>{{ fmtSize(gpu.memFree) }} 可用</span>
            </div>
          </div>
        </div>

        <!-- GPU 进程 -->
        <div class="proc-block" v-if="gpuProcesses.length">
          <div class="proc-title">GPU 进程（按显存）</div>
          <table class="proc-table">
            <thead><tr><th>PID</th><th>进程</th><th class="r">显存</th></tr></thead>
            <tbody>
              <tr v-for="(p, i) in gpuProcesses.slice(0, 8)" :key="i">
                <td class="mono">{{ p.pid }}</td>
                <td class="proc-name" :title="p.name">{{ p.name }}</td>
                <td class="r mono">{{ fmtSize(p.memBytes) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="section-card" v-else>
        <div class="section-head">
          <span class="gpu-led off"></span>
          <span class="section-title">显卡（GPU）</span>
        </div>
        <div class="gpu-note">{{ gpu.note || '未检测到 GPU' }}</div>
      </div>

      <!-- ===== 明细：CPU / 内存 / 磁盘 / 网络 ===== -->
      <div class="detail-grid">
        <div class="section-card">
          <div class="section-title">CPU 详情</div>
          <div class="det-item"><span>型号</span><strong class="ellipsis">{{ detail.cpu?.model || '—' }}</strong></div>
          <div class="det-item"><span>核心数</span><strong>{{ detail.cpu?.cores }}</strong></div>
          <div class="det-item"><span>负载 (1min)</span><strong>{{ fmtNum(detail.cpu?.load) }}</strong></div>
          <div class="det-item"><span>进程 CPU</span><strong>{{ fmtNum(detail.cpu?.processCpu) }}%</strong></div>
          <div class="det-item"><span>温度</span><strong>{{ detail.cpu?.temperature != null ? detail.cpu.temperature + '°C' : '—' }}</strong></div>
        </div>

        <div class="section-card">
          <div class="section-title">内存 / JVM</div>
          <div class="det-item"><span>总内存</span><strong>{{ fmtSize(detail.memory?.total) }}</strong></div>
          <div class="det-item"><span>已用 / 可用</span><strong>{{ fmtSize(detail.memory?.used) }} / {{ fmtSize(detail.memory?.free) }}</strong></div>
          <el-divider />
          <div class="det-item"><span>JVM 堆</span><strong>{{ fmtSize(detail.jvm?.heapUsed) }} / {{ fmtSize(detail.jvm?.heapMax) }}</strong></div>
          <div class="det-item"><span>运行时长</span><strong>{{ fmtUptime(detail.jvm?.uptime) }}</strong></div>
        </div>

        <div class="section-card">
          <div class="section-title">磁盘</div>
          <div class="disk-row" v-for="(d, i) in detail.disks" :key="i">
            <div class="disk-head"><span class="mono">{{ d.path }}</span><span>{{ percent(d.usagePercent) }}%</span></div>
            <el-progress :percentage="clamp(d.usagePercent)" :stroke-width="10" />
            <div class="disk-sub">
              <span>{{ fmtSize(d.used) }} / {{ fmtSize(d.total) }}</span>
              <span>{{ fmtSize(d.free) }} 可用</span>
            </div>
          </div>
        </div>

        <div class="section-card">
          <div class="section-title">网络接口</div>
          <template v-if="detail.network?.interfaces && detail.network.interfaces.length">
            <div class="iface-row" v-for="(it, i) in detail.network.interfaces" :key="i">
              <div class="iface-head">
                <span class="mono">{{ it.name }}</span>
              </div>
              <div class="iface-io"><span class="down">↓ {{ fmtSize(it.rxBytes) }}</span><span class="up">↑ {{ fmtSize(it.txBytes) }}</span></div>
            </div>
          </template>
          <div class="iface-empty" v-else>暂无网络数据</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import request from '../api'
import * as echarts from 'echarts'

const loading = ref(false)
const auto = ref(false)
const lastUpdate = ref('')
const detail = reactive({})
let timer = null

// sparkline DOM + 数据
const cpuSparkEl = ref(), memSparkEl = ref(), diskSparkEl = ref(), netSparkEl = ref()
const histCpu = ref([]), histMem = ref([]), histDisk = ref([]), histNet = ref([])
let charts = {}

const gpu = computed(() => (detail.gpus || []).find(g => g.available) || detail.gpus?.[0] || {})
const gpuProcesses = computed(() => detail.gpuProcesses || [])

const ovCpu = computed(() => percent(detail.cpu?.usage))
const cpuTempTxt = computed(() => detail.cpu?.temperature != null ? `${detail.cpu.temperature}°C` : '')
const cpuState = computed(() => {
  const t = detail.cpu?.temperature
  if (t == null) return ''
  if (t >= 80) return 'hot'
  if (t >= 65) return 'warm'
  return 'cool'
})
const tempClass = computed(() => {
  const t = gpu.value.temperature
  if (t == null) return ''
  if (t >= 80) return 'hot'
  if (t >= 65) return 'warm'
  return 'cool'
})
const utilColor = computed(() => {
  const u = gpu.value.utilization || 0
  if (u >= 80) return '#ef4444'
  if (u >= 50) return '#f59e0b'
  return '#10b981'
})
const vramColor = computed(() => {
  const u = gpu.value.memUsagePercent || 0
  if (u >= 85) return '#ef4444'
  if (u >= 60) return '#f59e0b'
  return '#3b82f6'
})

async function load() {
  loading.value = true
  try {
    const r = await request.get('/api/admin/monitor')
    Object.keys(detail).forEach(k => delete detail[k])
    Object.assign(detail, r.data || {})
    updateSparklines()
    const t = detail.timestamp && new Date(detail.timestamp)
    lastUpdate.value = t ? t.toLocaleString() : '—'
    await nextTick()
    renderCharts()
  } finally {
    loading.value = false
  }
}

function updateSparklines() {
  const push = (arr, v, maxLen) => { arr.push(v); if (arr.length > maxLen) arr.shift() }
  push(histCpu.value, clamp(detail.cpu?.usage), 40)
  push(histMem.value, clamp(detail.memory?.usagePercent), 40)
  push(histDisk.value, clamp(detail.disk?.usagePercent), 40)
  push(histNet.value, fmtRateNum(detail.network?.rxRate), 40)
}

function sparkOption(data, color) {
  return {
    grid: { left: 2, right: 2, top: 2, bottom: 2 },
    xAxis: { type: 'category', show: false },
    yAxis: { type: 'value', show: false, min: 0 },
    series: [{
      type: 'line', data, symbol: 'none', smooth: true, lineStyle: { width: 2, color },
      areaStyle: { color: hexA(color, 0.25) }
    }],
    tooltip: { show: false }
  }
}

function hexA(hex, a) {
  const r = parseInt(hex.slice(1, 3), 16), g = parseInt(hex.slice(3, 5), 16), b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r},${g},${b},${a})`
}

function renderCharts() {
  const specs = [
    [cpuSparkEl.value, histCpu.value, '#10b981'],
    [memSparkEl.value, histMem.value, '#3b82f6'],
    [diskSparkEl.value, histDisk.value, '#f59e0b'],
    [netSparkEl.value, histNet.value, '#8b5cf6']
  ]
  specs.forEach(([el, data, color]) => {
    if (!el) return
    if (!charts[color]) {
      charts[color] = echarts.init(el)
    }
    charts[color].setOption(sparkOption(data, color), true)
  })
}

function toggleAuto(v) {
  if (timer) { clearInterval(timer); timer = null }
  if (v) timer = setInterval(load, 5000)
}

function fmtRateNum(v) {
  const n = Number(v) || 0
  return n
}

function percent(v) {
  const n = Number(v)
  if (isNaN(n)) return 0
  return Math.round(n)
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

function fmtRate(v) {
  const n = Number(v) || 0
  const units = ['B/s', 'KB/s', 'MB/s', 'GB/s']
  let i = 0
  let x = n
  while (x >= 1000 && i < units.length - 1) { x /= 1000; i++ }
  return x.toFixed(x >= 10 || i === 0 ? 0 : 1) + ' ' + units[i]
}

function fmtSize(bytes) {
  const n = Number(bytes) || 0
  if (n === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return (v >= 10 || i === 0 ? v.toFixed(0) : v.toFixed(1)) + ' ' + units[i]
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

function onResize() {
  Object.values(charts).forEach(c => c && c.resize())
}

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  window.removeEventListener('resize', onResize)
  Object.values(charts).forEach(c => c && c.dispose())
  charts = {}
})
</script>

<style scoped>
.monitor-page { padding: 16px; max-width: 1280px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 8px; }
.title { font-size: 16px; font-weight: 600; color: #0f172a; }
.actions { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.updated { font-size: 12px; color: #909399; }
.autorefresh { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #64748b; }

.monitor-body { display: flex; flex-direction: column; gap: 16px; }

/* 概览网格 */
.overview-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.ov-card { background: #fff; border: 1px solid #eef2f7; border-radius: 12px; padding: 14px 16px; box-shadow: 0 1px 3px rgba(16,24,40,.04); }
.ov-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.ov-title { font-size: 13px; color: #64748b; font-weight: 600; }
.ov-state { font-size: 11px; padding: 2px 8px; border-radius: 999px; }
.ov-main { display: flex; align-items: baseline; gap: 8px; margin-bottom: 4px; }
.ov-big { font-size: 30px; font-weight: 700; color: #0f172a; line-height: 1; }
.ov-big small { font-size: 15px; font-weight: 600; color: #94a3b8; }
.ov-big.small { font-size: 22px; }
.ov-model { font-size: 12px; color: #94a3b8; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.ov-sub { font-size: 12px; color: #94a3b8; margin-bottom: 8px; }
.spark { height: 34px; }
.cool { color: #10b981; }
.warm { color: #f59e0b; }
.hot { color: #ef4444; }
.net-up { color: #10b981; background: #ecfdf5; }

/* 区块卡片 */
.section-card { background: #fff; border: 1px solid #eef2f7; border-radius: 12px; padding: 16px; box-shadow: 0 1px 3px rgba(16,24,40,.04); }
.section-title { font-size: 14px; font-weight: 600; color: #0f172a; display: inline-block; }
.detail-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.det-item { display: flex; justify-content: space-between; padding: 6px 0; font-size: 13px; }
.det-item span { color: #64748b; }
.det-item strong { color: #0f172a; }
.ellipsis { max-width: 62%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* GPU */
.gpu-led { width: 9px; height: 9px; border-radius: 50%; background: #10b981; display: inline-block; margin-right: 8px; }
.gpu-led.off { background: #cbd5e1; }
.section-head { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
.section-head .section-title { flex: 1; }
.gpu-util { font-size: 18px; font-weight: 700; color: #0f172a; }
.gpu-grid { display: grid; grid-template-columns: auto 1fr 1.3fr; gap: 24px; align-items: center; }
.gpu-ring { display: flex; justify-content: center; }
.ring-center { display: flex; flex-direction: column; align-items: center; }
.ring-center strong { font-size: 24px; color: #0f172a; }
.ring-center small { font-size: 11px; color: #94a3b8; }
.bs-row { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; font-size: 13px; }
.bs-row span { color: #64748b; }
.bs-row b { color: #0f172a; }
.vram-detail { display: flex; justify-content: space-between; font-size: 12px; color: #94a3b8; margin-top: 6px; }
.gpu-note { color: #f59e0b; font-size: 13px; padding: 8px 0; }

/* GPU 进程表 */
.proc-block { margin-top: 16px; border-top: 1px solid #f1f5f9; padding-top: 12px; }
.proc-title { font-size: 12px; color: #64748b; font-weight: 600; margin-bottom: 8px; }
.proc-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.proc-table th { text-align: left; color: #94a3b8; font-weight: 500; padding: 4px 6px; border-bottom: 1px solid #f1f5f9; }
.proc-table td { padding: 4px 6px; border-bottom: 1px solid #f8fafc; color: #334155; }
.proc-name { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mono { font-family: 'SFMono-Regular', Consolas, monospace; }
.r { text-align: right; }

/* 磁盘 / 网络 */
.disk-row { padding: 8px 0; border-bottom: 1px solid #f8fafc; }
.disk-row:last-child { border-bottom: none; }
.disk-head { display: flex; justify-content: space-between; font-size: 12px; color: #334155; margin-bottom: 4px; }
.disk-sub { display: flex; justify-content: space-between; font-size: 11px; color: #94a3b8; margin-top: 4px; }
.iface-row { padding: 7px 0; border-bottom: 1px solid #f8fafc; }
.iface-head { font-size: 12px; color: #0f172a; }
.iface-io { display: flex; justify-content: space-between; font-size: 12px; margin-top: 2px; color: #64748b; }
.iface-io .down { color: #3b82f6; }
.iface-io .up { color: #8b5cf6; }
.iface-empty { color: #94a3b8; font-size: 13px; padding: 8px 0; }

@media (max-width: 1100px) {
  .overview-grid { grid-template-columns: repeat(2, 1fr); }
  .detail-grid { grid-template-columns: repeat(2, 1fr); }
  .gpu-grid { grid-template-columns: 1fr; gap: 16px; }
}
@media (max-width: 700px) {
  .monitor-page { padding: 12px; }
  .overview-grid, .detail-grid { grid-template-columns: 1fr; }
}
</style>
