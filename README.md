# Local LLM Guard

本地大模型网关（Local LLM Guard）：为 **vLLM / OpenAI 兼容**的自建 LLM 服务提供一层**API Key 管理 / 流量代理 / 用量统计 / 费用核算 / 对话测试**的轻量网关。带一个开箱即用的 Web 管理台，可直接部署到 Windows 或 Linux 服务器。

---

## 功能特性

- **OpenAI 兼容代理**
  - 对外暴露 `POST /v1/chat/completions`（普通 + 流式 SSE）与 `GET /v1/models`。
  - 调用方用 `Authorization: Bearer <key>` 或 `X-API-Key: <key>` 鉴权，网关校验通过后转发到上游 vLLM。
  - 支持配置「上游 API Key」；若未配置则把调用方的 Key 透传给上游，避免上游返回 `invalid_api_key`。
- **API Key 管理**
  - 创建 / 编辑 / 启停 / 删除 Key，支持有效期、允许的模型白名单。
  - 完整 Key 仅创建时展示一次，列表仅显示脱敏前缀。
  - 内置「**验证 Key**」工具：粘贴 Key 立即诊断是否可用、命中哪个记录、当前数据库与记录数。
- **数据看板与统计**
  - 总请求数 / 总 Tokens / 今日请求；每日趋势与模型分布。
  - **各 API Key 请求数、成功/失败、输入/输出 Token、费用**统计，并支持导出 CSV。
  - 请求明细分页查询（按模型 / Key / 状态 / 时间过滤）。
- **费用核算**
  - 管理台可设置「输入 / 输出每百万 Token 单价（元）」。
  - 看板实时按单价估算**总费用 / 今日费用 / 各 Key 费用**；调整单价后历史统计按新单价实时重算。
- **对话测试**
  - 内置对话测试页：选择 API Key、模型，创建会话，流式对话并实时显示 **token 生成速度**。
- **管理员账号**
  - 修改自己的登录密码。

---

## 技术架构

```
调用方 (OpenAI SDK / 浏览器)
        │  Authorization: Bearer <网关 Key>
        ▼
  Local LLM Guard (Spring Boot + SQLite)
        │  ① 校验网关 Key (sha256 比对, 含有效期/白名单/启停)
        │  ② 计量 token, 落库 request_log
        │  ③ 转发 + 按需携带上游 Key
        ▼
 vLLM / OpenAI 兼容服务 (http://host:8000/v1)
```

- 后端：Java 17 + Spring Boot 3 + MyBatis-Plus
- 存储：SQLite（单文件，零依赖）
- 前端：Vue 3 + Element Plus + ECharts（构建产物内嵌进 JAR）
- 部署：`build.bat` 打包出可执行 JAR，`deploy.bat` 一键本机 / 远程(SSH) 部署

---

## 快速开始

### 0. 前置环境
- JDK 17+
- （仅打包构建时需要）Maven、Node.js + npm

### 1. 打包
```bat
build.bat
```
产出物：`target/vllm-gateway.jar`。

### 2. 启动
```bat
:: Windows
start.bat                          :: 启动（前台，Ctrl+C 停止）
start.bat http://你的vLLM:8000/v1  :: 启动并指定上游地址
start.bat stop                     :: 停止
start.bat status                   :: 查看状态

:: Linux
./start.sh start
./start.sh restart
./start.sh stop
./start.sh status
```

默认配置：
- 管理台：`http://127.0.0.1:8080`
- 数据库：运行目录下 `data/vllm_gateway.db`（自动创建）
- 管理员：`admin` / `Admin@123456`（**首次登录后请立即修改密码**）

### 3. 可选环境变量
| 变量 | 说明 | 默认 |
|------|------|------|
| `SERVER_PORT` | 管理端口 | `8080` |
| `VLLM_UPSTREAM_URL` | 上游 vLLM 地址 | `http://127.0.0.1:8000/v1` |
| `SPRING_DATASOURCE_URL` | 数据库连接串 | `jdbc:sqlite:<脚本目录>/data/vllm_gateway.db` |

> `start.bat` / `start.sh` 会强制把数据库固定到**脚本所在目录的 data/**，与启动时所在目录无关，避免数据库被写在意外位置。

### 4. 登录并初始化
浏览器打开 `http://127.0.0.1:8080`，用默认管理员登录后：
1. **模型配置**页：填写上游地址、上游 API Key（可选）、默认模型、采样参数，以及**输入/输出单价**。
2. **API Key 管理**页：创建 Key（复制保存完整 Key）。
3. 即可通过网关调用，或在**对话测试**页体验。

---

## 部署到服务器

### 本机生成部署包（Windows）
```bat
deploy.bat
```
在 `deploy\` 目录生成可直接拷贝到服务器的部署包（含 JAR、`start.bat`、`start.sh`、说明）。

### 远程部署（走 SSH）
```bat
:: 编辑 deploy.conf 填写服务器信息, 并把 DEPLOY_MODE 改为 remote
deploy.bat
```
或命令行覆盖远程目标：
```bat
deploy.bat --config my.conf       :: 指定配置文件
deploy.bat user@host:/opt/vllm-gateway   :: 临时覆盖远程地址
deploy.bat --local                :: 强制本地打包
```
`deploy.bat` 会自动完成：打包 → 上传 JAR / 脚本 → 远程 `./start.sh restart`。
服务器信息保存在 **`deploy.conf`**（已加入 `.gitignore`，不会泄露密钥），模板见 `deploy.conf.example`。

### 远程配置示例（deploy.conf）
```ini
DEPLOY_MODE=local          # local=本地打包, remote=远程部署
DEPLOY_HOST=192.168.1.10
DEPLOY_USER=root
DEPLOY_PATH=/opt/vllm-gateway
DEPLOY_SSH_PORT=22
DEPLOY_SSH_KEY=C:\Users\you\.ssh\id_rsa
DEPLOY_SERVER_PORT=8080
DEPLOY_UPSTREAM_URL=http://127.0.0.1:8000/v1
```

---

## 使用说明（管理台）

| 页面 | 功能 |
|------|------|
| **数据看板** | 总览统计、每日趋势、模型分布、各 Key 用量与费用、请求明细、导出 CSV |
| **API Key 管理** | 创建/编辑/启停/删除 Key，设置有效期与模型白名单，验证 Key |
| **对话测试** | 选 Key 与模型，流式对话，实时 token 速度 |
| **模型配置** | 上游地址、上游 Key、默认模型采样参数、**输入/输出单价** |
| 右上角头像 | 修改管理员密码、退出登录 |

### 费用核算要点
- 在「模型配置 → 费用计价」设置「输入单价 / 输出单价」（元 / 每百万 Token）。
- 看板按 `promptTokens×输入单价 + completionTokens×输出单价` 实时估算。
- 单价为 0 时金额显示 2 位小数，否则显示 4 位小数。
- **调整单价后，已有日志会按新单价重新核算**（成本是查询时实时计算的）。

---

## OpenAI 兼容接口

所有 `/v1/*` 都需要网关 API Key：`Authorization: Bearer <key>` 或 `X-API-Key: <key>`。

### 列模型
```bash
curl http://127.0.0.1:8080/v1/models \
  -H "Authorization: Bearer sk-xxxx"
```

### 聊天补全（普通）
```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-xxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen2.5-7b","messages":[{"role":"user","content":"你好"}]}'
```

### 聊天补全（流式）
在上面请求中加 `"stream": true` 即可，返回 SSE 流，结束符为 `data: [DONE]`。

> 用任意 OpenAI SDK 即可对接：把 `base_url` 指向网关地址、`api_key` 填网关生成的 Key 即可。

---

## 管理后台 API（均需 Bearer 管理 Token）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/login` | 登录，返回 token |
| POST | `/api/admin/password` | 修改当前管理员密码 |
| GET/PUT | `/api/admin/config` | 读取 / 保存配置（含单价） |
| GET | `/api/admin/apikey/list` | Key 列表 |
| POST | `/api/admin/apikey/create` | 创建 Key |
| PUT | `/api/admin/apikey/update` | 更新 Key |
| DELETE | `/api/admin/apikey/delete` | 删除 Key |
| POST | `/api/admin/apikey/verify` | 验证 Key 是否可用 |
| GET | `/api/admin/stat/dashboard` | 看板汇总（含各 Key 费用） |
| GET | `/api/admin/stat/daily` | 每日统计 |
| GET | `/api/admin/stat/detail` | 请求明细分页 |
| GET | `/api/admin/stat/exportCsv` | 导出 CSV |
| GET | `/api/admin/chat/*` | 会话、对话测试相关接口 |

---

## 常见问题（FAQ）

**Q1：接口返回 `invalid_api_key`，但我 Key 是对的？**
- 先到「API Key 管理 → 验证 Key」粘贴完整 Key，确认网关数据库里确实有这个 Key（看提示的数据库路径与总记录数）。
- 确认上游服务需要的 Key 已配置到「模型配置 → 上游 API Key」，或留空让网关透传调用方 Key。

**Q2：`bash\r: No such file or directory`**
- Windows 产生的 `start.sh` 是 CRLF。`deploy.bat` 打包时会自动规范化为 LF；若手动拷贝，请用 `dos2unix` 或编辑器转换。

**Q3：流式响应出现 `ResponseBodyEmitter has already completed`**
- 多由客户端中途断开 / 超时后服务端仍对已完结的连接 send/complete 引起，已做安全处理（发送前检查、失败即忽略）。若上游响应缓慢，可检查反向代理对 SSE 的缓冲与超时设置。

**Q4：如何换端口或上游地址？**
- 启动时传环境变量 `SERVER_PORT` / `VLLM_UPSTREAM_URL`，或在管理台「模型配置」页修改后再保存。

**Q5：默认密码是什么 / 忘了密码？**
- 默认 `Admin@123456`。可在「右上角 → 修改密码」更改。若完全遗忘，删除 `data/vllm_gateway.db` 后重启即重建默认管理员（会重置全部数据，谨慎操作）。

---

## 项目结构

```
├─ build.bat              打包脚本（前端 vite + Maven）
├─ deploy.bat             本地 / 远程(SSH) 部署脚本
├─ deploy.conf*           远程部署配置（不入库）
├─ deploy.conf.example    配置模板
├─ start.bat / start.sh   Windows / Linux 启动脚本
├─ frontend/              Vue3 前端源码
├─ src/main/java/         后端源码
├─ src/main/resources/    配置 + SQL + 构建后的前端静态资源
└─ target/                构建产物（vllm-gateway.jar）
```

---

## License
MIT（见 LICENSE）
