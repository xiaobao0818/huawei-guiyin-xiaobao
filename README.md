# 华为鲸鸿动能自归因平台

[![CI](https://github.com/xiaobao0818/huawei-guiyin-xiaobao/actions/workflows/ci.yml/badge.svg)](https://github.com/xiaobao0818/huawei-guiyin-xiaobao/actions/workflows/ci.yml)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring Boot 3.2.5](https://img.shields.io/badge/Spring%20Boot-3.2.5-green.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3-brightgreen.svg)](https://vuejs.org/)

这是一个面向华为鲸鸿动能投放的通用自归因系统。它负责接收广告点击回调、接收游戏客户端转化上报、完成设备匹配和事件去重，并把符合条件的转化签名回传给鲸鸿动能。

项目适合多游戏、多包体、多引擎场景：APK、HAP、RPK 均通过 HTTP 接入，不强依赖客户端 SDK。同一次广告点击可以支撑激活、注册、付费、留存等多个事件回传，重复控制由事件幂等键和归因记录完成，而不是简单地把点击一次性消费掉。

## 当前能力

| 模块 | 能力 |
|------|------|
| 点击接收 | `GET /api/v1/click`，保存点击记录，缓存 OAID/GAID/IDFA 到 Redis |
| 事件上报 | `POST /api/v1/report`，支持同步处理和 `?async=true` 异步入队 |
| 匹配链路 | OAID → GAID → IDFA → 指纹降级，Redis 优先，数据库兜底 |
| 事件配置 | 内置预置事件，也支持按游戏覆盖、新增、停用事件 |
| 回传规则 | 支持阈值规则、时间窗口规则、and/or 组合规则 |
| 回传队列 | `callback_task` 持久化，Worker 分布式锁认领，指数退避重试 |
| 再归因 | 支持保护期和额外沉默期，配置在 `window_config` |
| 留存检查 | 默认只审计缺失留存；显式开启后才自动生成留存回传 |
| 管理后台 | 游戏管理、事件配置、Dashboard、归因查询、回传日志抽屉 |
| 安全 | 上报 API Key、后台 Basic Auth、密钥 AES-GCM 加密、点击限流 |
| 运维 | 健康检查、Prometheus 指标、Flyway 迁移、定时清理任务 |

## 代码结构

```text
huawei-guiyin-xiaobao/
├── attribution-server/                 # Spring Boot 后端
│   ├── src/main/java/com/attribution/
│   │   ├── api/                        # 对外点击和上报接口
│   │   ├── admin/                      # 管理后台 API 和服务
│   │   ├── common/                     # 实体、仓库、配置、工具
│   │   └── core/                       # 归因、匹配、回传、指标、任务
│   └── src/main/resources/
│       ├── application*.yml            # dev/prod/test 配置
│       └── db/migration/               # Flyway V1-V5 迁移
├── admin-frontend/                     # Vue 3 + Element Plus 管理后台
├── docker-compose.yml                  # MySQL + Redis + 后端 + 前端
├── nginx.conf                          # 前端镜像内 Nginx 配置
├── grafana-dashboard.json              # Grafana 面板模板
├── 客户端接入指南.md                    # 客户端接入补充文档
├── 游戏接入归因完整文档.md               # 游戏侧完整接入流程
├── OAID获取指南.md                     # OAID 获取专题
├── 技术方案.md                         # 早期技术方案资料
└── 实施清单.md                         # 实施状态清单
```

README 是当前主说明文档。其他中文文档保留为专题资料，如与 README 不一致，以当前代码和 README 为准。

## 核心流程

```text
鲸鸿动能广告点击
    |
    | GET /api/v1/click?game_id=...&callback=...&oaid=...
    v
ClickController
    |
    | 保存 click_record，写入 Redis 点击缓存
    v
游戏客户端转化上报
    |
    | POST /api/v1/report
    v
AttributionEngine
    |
    | 校验游戏和事件配置
    | 激活去重 / 业务幂等去重
    | OAID -> GAID -> IDFA -> 指纹匹配
    | 保存 attribution_record
    v
CallbackRetryService
    |
    | 创建 callback_task
    | Worker 分布式锁认领
    | 发送 HMAC-SHA256 签名回传
    | 记录 callback_log，更新回传状态
    v
鲸鸿动能接收转化
```

关键设计点：

- 点击记录会保留并可被多个不同事件复用。
- 激活事件使用设备身份做首激活/再归因判断。
- 付费等业务事件优先使用 `event_id`、`request_id`、`order_id` 去重；没有自然幂等键时使用 5 分钟时间桶兜底。
- Redis 异常时，多数读写会降级到数据库或跳过缓存，不直接中断核心业务路径。
- 回传成功以 HTTP 2xx 且响应体 `resultCode=0` 为准。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17, Spring Boot 3.2.5, Spring Security, Spring Data JPA |
| 数据 | MySQL 8, Redis 7, Flyway |
| 回传 | RestTemplate, AES-GCM 密钥解密, HMAC-SHA256 Authorization |
| 观测 | Spring Actuator, Micrometer, Prometheus, Grafana |
| 前端 | Vue 3, TypeScript strict, Vite 8, Element Plus, Axios |
| 部署 | Docker Compose, Nginx |
| CI | GitHub Actions: 后端 `mvn test` + 前端 `npm run build` |

## 快速启动

### 1. 准备依赖

本地开发至少需要：

- JDK 17
- Maven 3.9+
- Node.js 22 或兼容版本
- Redis 7

开发环境默认使用 H2 内存数据库，Redis 仍建议启动：

```bash
docker run --rm -p 6379:6379 redis:7-alpine
```

### 2. 启动后端

```bash
cd attribution-server
mvn spring-boot:run
```

dev profile 默认值：

| 项 | 默认值 |
|----|--------|
| 后端地址 | `http://localhost:8080` |
| 上报 API Key | `dev-report-api-key` |
| 后台账号 | `admin` |
| 后台密码 | `admin123` |
| 数据库 | H2 内存库 |

### 3. 启动前端

```bash
cd admin-frontend
npm install
npm run dev
```

访问：

- 管理后台：`http://localhost:3000`
- 健康检查：`http://localhost:8080/api/v1/health`
- Swagger：`http://localhost:8080/swagger-ui.html`（dev profile 默认开放）
- H2 Console：`http://localhost:8080/h2-console`（dev profile 默认开放）

## 生产部署

### 1. 生成环境变量

```bash
cp .env.example .env
```

必须修改 `.env` 中的密码和密钥：

```bash
# 32 字符/字节 AES-GCM 主密钥
openssl rand -hex 16

# 上报 API Key 和后台密码建议使用独立随机值
openssl rand -hex 24
```

关键变量：

| 变量 | 用途 |
|------|------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 |
| `MYSQL_PASSWORD` | 应用连接 MySQL 的密码 |
| `CALLBACK_URL` | 鲸鸿动能转化回传地址 |
| `ENCRYPTION_KEY` | 加密游戏密钥的 AES-GCM 主密钥，必须 32 字符/字节 |
| `ATTRIBUTION_API_KEY` | 客户端上报接口 API Key |
| `ADMIN_USERNAME` | 管理后台账号 |
| `ADMIN_PASSWORD` | 管理后台密码 |

### 2. 启动服务

```bash
docker compose up -d --build
```

容器和端口：

| 服务 | 容器 | 端口 |
|------|------|------|
| 后端 | `attribution-server` | `8080` |
| 前端 | `attribution-admin` | `3000 -> 80` |
| MySQL | `attribution-mysql` | `3306` |
| Redis | `attribution-redis` | `6379` |

### 3. 检查状态

```bash
curl http://localhost:8080/api/v1/health
docker compose ps
docker compose logs -f attribution-server
```

生产 profile 使用 MySQL + Flyway，`ddl-auto=validate`。迁移脚本位于 `attribution-server/src/main/resources/db/migration/`。

## 后台配置流程

### 1. 登录管理后台

访问 `http://localhost:3000`，输入 `ADMIN_USERNAME` / `ADMIN_PASSWORD`。

前端把 Basic Auth 凭证保存在当前浏览器会话的 `sessionStorage`，遇到 401/403 会自动清理登录态并回到登录页。

### 2. 创建游戏

在「游戏管理」中创建游戏：

| 字段 | 说明 |
|------|------|
| 游戏 ID | 客户端和点击链接中的 `game_id` / `gameId`，必须唯一 |
| 游戏名称 | 后台展示名 |
| 支持平台 | `apk,hap,rpk` |
| secretKey | 鲸鸿动能后台复制的游戏回传密钥，保存时会用 `ENCRYPTION_KEY` 加密 |
| 归因窗口 | 点击到转化允许匹配的天数，默认 30 |
| 最大重试次数 | 回传失败后的重试次数，默认 3 |
| 指纹降级匹配 | OAID/GAID/IDFA 无法匹配时是否启用 IP+UA 指纹匹配 |
| 窗口配置 | 再归因和留存检查 JSON |

推荐窗口配置：

```json
{"protection_days":7,"silence_days":0,"retain_days":[1,7],"auto_retention_callback":false}
```

字段说明：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `protection_days` | 7 | 激活保护期，保护期内重复激活返回 `already_processed` |
| `silence_days` | 0 | 保护期结束后的额外等待天数 |
| `retain_days` | `[1,7]` | 留存检查关注的天数 |
| `auto_retention_callback` | `false` | 默认只审计缺失留存；设为 `true` 才自动生成留存记录并回传 |

停用游戏是软停用，不会删除历史数据。停用后点击和上报不再参与匹配。

### 3. 配置事件

系统内置通配预置事件：

| event | 显示名 | 默认 conversion_type | 说明 |
|-------|--------|----------------------|------|
| `activate` | 激活 | `activate` | 首启/激活 |
| `register` | 注册 | `register` | 注册、创角 |
| `login` | 登录 | 空 | 仅记录，不回传 |
| `purchase` | 付费 | `paid` | 需要 `revenue`，可带 `currency` / `order_id` |
| `retain_1d` | 次留 | `retain` | 客户端真实留存上报 |
| `retain_7d` | 7 日留存 | 空 | 默认仅记录，可按游戏启用回传 |
| `level_up` | 升级 | 空 | 仅记录 |
| `level_complete` | 通关 | 空 | 仅记录 |
| `tutorial_complete` | 新手引导完成 | 空 | 仅记录 |
| `custom` | 自定义事件 | `custom` | 自定义回传 |

可按游戏创建同名事件覆盖通配预置事件，也可新增自定义事件。事件的 `conversion_type` 留空表示只记录不回传。

### 4. 回传规则

事件可配置 `callbackRule`，为空表示只要匹配成功就回传。支持以下 JSON：

付费金额阈值：

```json
{"type":"threshold","field":"eventParams.revenue","operator":"gte","value":6.0}
```

时间窗口，限制同一游戏同一设备同一事件在窗口内只回传一次：

```json
{"type":"time_window","window_minutes":1440,"scope":"game:device"}
```

组合规则：

```json
{
  "type": "and",
  "rules": [
    {"type":"threshold","field":"eventParams.revenue","operator":"gte","value":6.0},
    {"type":"time_window","window_minutes":1440}
  ]
}
```

支持的规则类型：`threshold`、`time_window`、`and`、`or`。支持的阈值操作符：`gte`、`gt`、`lte`、`lt`、`eq`。

## 对外接口

### 点击回调

鲸鸿动能监测链接应指向：

```text
GET https://你的域名/api/v1/click
```

核心参数：

| 参数 | 必填 | 说明 |
|------|------|------|
| `game_id` / `gameId` | 是 | 游戏 ID |
| `callback` | 是 | 鲸鸿动能 callback 原文，服务端会解码并保存 |
| `oaid` | 否 | 华为/Android 设备 OAID |
| `gaid` / `google_adid` | 否 | Google Advertising ID |
| `idfa` | 否 | iOS IDFA |
| `campaign_id` / `campaignId` | 否 | 计划 ID |
| `adgroup_id` / `adGroupId` | 否 | 任务/广告组 ID |
| `content_id` / `contentId` | 否 | 创意 ID |
| `ts` | 否 | 点击时间戳，毫秒 |
| `trace_time` | 否 | 点击时间戳，秒 |
| `ip` | 否 | 用户 IP，指纹匹配使用 |
| `ua` / `user_agent` | 否 | User-Agent，指纹匹配使用 |
| `platform` | 否 | `apk` / `hap` / `rpk` |
| `tracking_enabled` | 否 | 鲸鸿动能跟踪标记 |

返回值是纯文本：

- `success`：接收成功
- `error`：参数缺失或游戏未注册/已停用

`/click` 不要求 API Key，但有基于 IP 的 Redis 限流，默认每分钟 100 次。Redis 不可用时限流 fail-open。

### 事件上报

```text
POST /api/v1/report
Header: X-Attribution-Api-Key: <ATTRIBUTION_API_KEY>
Content-Type: application/json
```

同步上报示例：

```bash
curl -X POST http://localhost:8080/api/v1/report \
  -H 'Content-Type: application/json' \
  -H 'X-Attribution-Api-Key: dev-report-api-key' \
  -d '{
    "gameId": "demo_game",
    "platform": "apk",
    "event": "activate",
    "device": {
      "oaid": "oaid-demo"
    },
    "app": {
      "version": "1.0.0"
    },
    "ts": 1735689600000
  }'
```

付费上报示例：

```json
{
  "gameId": "demo_game",
  "platform": "apk",
  "event": "purchase",
  "device": {
    "oaid": "oaid-demo"
  },
  "eventParams": {
    "revenue": 9.9,
    "currency": "CNY",
    "order_id": "ORDER-20260531-001"
  }
}
```

异步上报：

```text
POST /api/v1/report?async=true
```

异步模式会先写入 `event_task` 并立即返回 `accepted`，随后由 `EventWorker` 每秒扫描并处理。失败任务可在后台接口中查看并重放。

返回结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "matched",
    "attributionId": 123,
    "conversionType": "activate",
    "message": "处理成功"
  },
  "timestamp": 1735689600000
}
```

常见 `status`：

| status | 说明 |
|--------|------|
| `matched` | 匹配到点击并已保存归因记录 |
| `no_match` | 未匹配到点击，但事件已记录 |
| `already_processed` | 激活保护期或幂等键重复 |
| `accepted` | 异步任务已入队 |

## 回传给鲸鸿动能

回传地址由 `attribution.callback-url` / `CALLBACK_URL` 配置，默认：

```text
https://ppscrowd-drcn.op.hicloud.com/action-lib-track/hiad/v2/actionupload
```

回传体包含：

| 字段 | 说明 |
|------|------|
| `callback` | 点击回调中的 callback 原文 |
| `conversion_type` | 事件映射后的转化类型 |
| `conversion_time` | 转化时间，秒级时间戳 |
| `timestamp` | 请求时间，毫秒级时间戳 |
| `oaid` | OAID。GAID/IDFA 匹配时若没有 OAID 会传空字符串 |
| `content_id` | 可选，创意 ID |
| `campaign_id` | 可选，计划 ID |
| `tracking_enabled` | 可选，默认 `1` |
| `conversion_extend` | 付费事件扩展字段，包含 `revenue` 和 `currency` |

系统会解密游戏 `secretKey`，基于请求体生成 HMAC-SHA256 `Authorization` 头。回传结果写入 `callback_log`，归因记录的 `callback_status` 会随任务状态更新。

## 数据表

生产环境由 Flyway 管理表结构：

| 版本 | 说明 |
|------|------|
| V1 | 基础表：游戏、事件、点击、归因、回传日志，内置预置事件 |
| V2 | 回传任务表、归因幂等键 |
| V3 | 异步事件任务、回传规则、窗口配置、debug/reattribution 字段 |
| V4 | GAID/IDFA 字段、click_record 乐观锁版本 |
| V5 | 清理任务所需 created_at / status_created 索引 |

核心表：

| 表 | 用途 |
|----|------|
| `game_config` | 游戏配置和加密后的回传密钥 |
| `event_definition` | 事件到 `conversion_type` 的映射和回传规则 |
| `click_record` | 广告点击明细 |
| `attribution_record` | 归因结果、回传状态、幂等键 |
| `callback_task` | 持久化回传任务队列 |
| `callback_log` | 每次回传请求和响应 |
| `event_task` | 异步上报任务 |

## 定时任务

| 任务 | 频率 | 说明 |
|------|------|------|
| `CallbackRetryService` | 每 10 秒 | 认领并发送到期回传任务 |
| `EventWorker` | 每 1 秒 | 消费异步事件任务 |
| `EventWorker` stale recovery | 每 5 分钟 | 回收超时 processing 任务 |
| `EventWorker` cleanup | 每天 4:00 | 清理 7 天前 done 任务 |
| `RetentionCheckTask` | 每天 2:00 | 检查配置天数的留存缺失 |
| `DataCleanupTask` | 每天 3:00 | 分批清理过期数据 |
| `EventRouter` cache cleanup | 每 10 分钟 | 清理事件路由缓存 |

可通过配置关闭调度：

```yaml
attribution:
  scheduling:
    enabled: false
```

## 数据保留策略

| 表 | 保留策略 |
|----|----------|
| `click_record` | 90 天 |
| `attribution_record` | 180 天 |
| `callback_task` | 已完成/终态任务保留 90 天 |
| `callback_log` | 90 天 |
| `event_task` | done 任务保留 7 天 |

清理任务使用 `LIMIT 1000` 分批删除，每批独立短事务，并在批次之间短暂停顿，减少对线上数据库的冲击。

## 配置参考

### attribution 配置

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `attribution.callback-url` | 华为中国区默认地址 | 鲸鸿动能回传地址 |
| `attribution.callback-retry-max` | 3 | 全局默认值，游戏配置中也有最大重试次数 |
| `attribution.callback-retry-base-seconds` | 5 | 指数退避基数，最大延迟 3600 秒 |
| `attribution.attribution-window-days` | 30 | 全局默认归因窗口；实际以游戏配置为准 |
| `attribution.fingerprint-match-minutes` | 30 | 指纹匹配窗口 |
| `attribution.click-cache-ttl-days` | 7 | Redis 点击缓存 TTL |
| `attribution.callback-worker-fixed-delay-ms` | 10000 | 回传 Worker 扫描间隔 |
| `attribution.callback-worker-claim-timeout-minutes` | 10 | sending 任务超时回收阈值 |
| `attribution.click-rate-limit-max` | 100 | `/click` 每个 IP 每窗口最大请求数 |
| `attribution.click-rate-limit-window-seconds` | 60 | `/click` 限流窗口 |
| `attribution.api-key` | 环境变量 | 客户端上报 API Key |
| `attribution.debug-api-key` | 空 | 可选 debug header |
| `attribution.security.swagger-public` | false | 是否公开 Swagger |
| `attribution.security.h2-console-public` | false | 是否公开 H2 Console |
| `attribution.security.metrics-public` | false | 是否公开 Prometheus/metrics |

### 安全配置

| 路径 | 认证 |
|------|------|
| `GET /api/v1/click` | 公开，带限流 |
| `POST /api/v1/report` | `X-Attribution-Api-Key` |
| `/admin/api/**` | HTTP Basic Admin |
| `/actuator/health` | 公开 |
| `/actuator/prometheus` | 默认 Admin，可配置公开 |
| `/swagger-ui.html` | dev 公开，prod 默认 Admin |
| `/h2-console/**` | dev 公开，prod 默认拒绝 |

## 可观测性

健康检查：

```bash
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/api/v1/health/live
```

`/api/v1/health` 会检查数据库和 Redis。任一依赖不可用时返回 HTTP 503；`/api/v1/health/live` 只表示进程存活。

Prometheus 指标：

| 指标 | 标签 | 说明 |
|------|------|------|
| `attribution_events_total` | `game`, `event` | 收到的上报事件 |
| `attribution_clicks_total` | `game` | 收到的点击回调 |
| `attribution_match` | `game`, `type` | 匹配方式：oaid/gaid/idfa/fingerprint/unmatched |
| `attribution_result` | `game`, `result` | 引擎处理结果，包括重复、停用、未配置等路径 |
| `attribution_callback` | `game`, `result` | 回传成功/失败 |
| `attribution_callback_retries` | `game`, `attempt` | 回传重试次数 |
| `attribution_processing_time` | - | 归因处理耗时 |
| `attribution_callback_queue_depth` | `queue` | 回传队列积压 |

仓库提供 `grafana-dashboard.json` 作为基础面板模板。

## 本地验证

后端测试：

```bash
cd attribution-server
mvn test
```

前端构建：

```bash
cd admin-frontend
npm ci
npm run build
```

CI 会在 `main` push 和 PR 时执行同样的后端测试和前端构建。

## 常见问题

### 为什么点击已经 matched 还允许付费复用？

这是有意设计。同一次广告点击对应的是一次投放触达，后续激活、注册、付费、留存都可能需要回传给广告平台。系统用 `attribution_record.dedupe_key` 控制事件重复，而不是用 `click_record.matched` 阻断后续事件。

### 没有 OAID 怎么办？

上报可带 GAID 或 IDFA；若游戏开启指纹降级，还可以用 IP + User-Agent 做短窗口匹配。指纹匹配只作为兜底，窗口默认 30 分钟。

### 留存是否会自动回传？

默认不会。客户端真实发生留存时应上报 `retain_1d`、`retain_7d` 等事件。`RetentionCheckTask` 默认只审计缺失留存；只有把游戏 `window_config.auto_retention_callback` 显式设为 `true`，系统才会自动生成留存记录并尝试回传。

### 回传失败会丢吗？

不会直接丢。匹配成功后先创建 `callback_task`，Worker 负责发送。失败会按游戏配置重试，耗尽后标记 `dead` / `failed`，可在归因查询和回传日志中排查。

### Redis 不可用会怎样？

健康检查会返回 DOWN。业务侧尽量降级：点击缓存写入失败时保留数据库记录，匹配 Redis 未命中会回查数据库，点击限流和部分缓存能力会跳过。但回传 Worker 的分布式锁依赖 Redis，Redis 不可用时会暂停当轮发送。

### 生产为什么必须保护后台和指标？

后台可管理游戏密钥和事件回传，指标可能暴露投放数据。生产 profile 默认 Swagger、H2、metrics 都不公开；如需开放 Prometheus，应在内网或网关层做访问控制。

## 相关文档

| 文档 | 用途 |
|------|------|
| `客户端接入指南.md` | 客户端事件上报时机和示例 |
| `游戏接入归因完整文档.md` | 游戏团队接入全流程 |
| `OAID获取指南.md` | APK/HAP/RPK 获取 OAID 的补充说明 |
| `技术方案.md` | 早期技术设计和调研记录 |
| `实施清单.md` | 项目实施状态和后续任务 |
