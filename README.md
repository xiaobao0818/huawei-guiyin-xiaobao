# 华为鲸鸿动能通用自归因平台

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.x-brightgreen.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

通用的 IAA 游戏广告归因系统，专为**华为鲸鸿动能（Petal Ads）**买量投放设计。支持 APK（Android）、HAP（鸿蒙）、RPK（快游戏）三种包体，引擎无关（HTTP 协议接入），多游戏同时管理。

---

## 目录

- [核心功能](#核心功能)
- [架构概览](#架构概览)
- [归因流程](#归因流程)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [客户端接入](#客户端接入)
- [API 接口](#api-接口)
- [管理后台](#管理后台)
- [部署指南](#部署指南)
- [配置说明](#配置说明)

---

## 核心功能

| 功能 | 说明 |
|------|------|
| **自归因匹配** | OAID 精确匹配 → 指纹降级（IP+UA 加权评分）→ 渠道号匹配（预留） |
| **多包体支持** | APK（Android）、HAP（鸿蒙）、RPK（华为快游戏） |
| **引擎无关** | 客户端仅需 HTTP POST 上报，不依赖任何 SDK |
| **事件可配置** | 管理后台自定义事件，灵活映射华为 conversion_type |
| **签名回传** | HMAC-SHA256 签名，符合鲸鸿动能自归因 API 规范 |
| **持久化回传** | 数据库任务队列 + 指数退避重试（5s → 25s → 125s），服务重启不丢任务 |
| **多游戏管理** | 管理后台注册游戏，配置独立密钥和归因策略 |
| **数据安全** | 华为密钥 AES-GCM 加密存储，密钥永不暴露 |

---

## 架构概览

```
┌──────────────────────────────────────────────────────────┐
│                       管理后台 (Vue3)                      │
│         游戏管理 │ 事件配置 │ 归因查询 │ 数据看板          │
└──────────────────────┬───────────────────────────────────┘
                       │ /admin/api/*
                       ▼
┌──────────────────────────────────────────────────────────┐
│                  Spring Boot 归因服务                      │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │ Click    │  │ Report   │  │ Matcher  │  │Callback │ │
│  │ Controller│  │ Controller│  │ OAID/FP  │  │ HMAC签名│ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬────┘ │
│       │             │             │             │       │
│       ▼             ▼             ▼             ▼       │
│  ┌──────────────────────────────────────────────────┐   │
│  │               Attribution Engine                  │   │
│  └──────────────────────────────────────────────────┘   │
└──────────┬──────────────┬────────────────────────────────┘
           │              │
           ▼              ▼
     ┌─────────┐   ┌──────────┐
     │  Redis  │   │  MySQL   │
     │ 点击缓存 │   │ 归因数据  │
     └─────────┘   └──────────┘
```

**三层匹配策略：**

1. **OAID 精确匹配**（主力）— Redis 缓存 + MySQL 持久化双层查找，归因窗口 30 天
2. **指纹降级匹配**（可选）— IP + UserAgent 加权评分，阈值 ≥ 2 分，窗口 30 分钟
3. **渠道号匹配**（预留）— 适用于无法获取 OAID 的场景

---

## 归因流程

```
鲸鸿动能广告平台                   归因服务端                    游戏客户端
      │                              │                            │
      │── click callback ───────────▶│                            │
      │   (OAID + callback + IP/UA)  │                            │
      │                              │── 存入 Redis + MySQL ──▶  │
      │                              │                            │
      │                              │◀── POST /api/v1/report ───│
      │                              │    (OAID + event + params) │
      │                              │                            │
      │                              │── OAID 匹配                │
      │                              │── 查询事件配置              │
      │                              │── 保存归因记录              │
      │                              │                            │
      │◀── POST HMAC-SHA256 ────────│                            │
      │    (conversion_type + time)  │                            │
      │                              │                            │
      │── resultCode: 0 ───────────▶│                            │
      │                              │── 更新 callback_status     │
```

---

## 技术栈

### 后端
| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.2.5 | 应用框架 |
| Spring Data JPA | 3.2.5 | ORM / 数据访问 |
| Spring Data Redis | 3.2.5 | Redis 操作 |
| MySQL | 8.0 | 持久化存储 |
| Redis | 7.x | 点击数据缓存 |
| Lombok | — | 代码简化 |

### 前端
| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.x | UI 框架 |
| Element Plus | 2.x | 组件库 |
| Axios | — | HTTP 客户端 |
| Vite | 5.x | 构建工具 |

### 部署
| 技术 | 用途 |
|------|------|
| Docker | 容器化 |
| Docker Compose | 一键部署 |
| Nginx | 前端 + 反向代理 |

---

## 项目结构

```
华为归因系统-张小宝/
├── README.md
├── .gitignore
├── .env.example                        # 环境变量模板
├── docker-compose.yml                  # 一键部署编排
├── nginx.conf                          # Nginx 配置
│
├── attribution-server/                 # 后端服务
│   ├── pom.xml
│   ├── Dockerfile
│   ├── sql/
│   │   └── init.sql                    # 建表 + 预置事件
│   └── src/main/
│       ├── resources/
│       │   ├── application.yml         # 通用配置
│       │   ├── application-dev.yml     # 开发环境 (H2)
│       │   └── application-prod.yml    # 生产环境 (MySQL)
│       └── java/com/attribution/
│           ├── AttributionApplication.java
│           ├── common/
│           │   ├── entity/             # JPA 实体
│           │   │   ├── GameConfig.java
│           │   │   ├── ClickRecord.java
│           │   │   ├── ClickCache.java
│           │   │   ├── EventDefinition.java
│           │   │   ├── AttributionRecord.java
│           │   │   └── CallbackLog.java
│           │   ├── enums/              # 枚举
│           │   │   ├── PlatformType.java
│           │   │   ├── AttributionType.java
│           │   │   └── CallbackStatus.java
│           │   ├── util/               # 工具类
│           │   │   ├── AesUtil.java        # AES-GCM 加解密
│           │   │   ├── SignatureUtil.java  # HMAC-SHA256 签名
│           │   │   └── RedisKeyUtil.java   # Redis Key 规范
│           │   ├── config/             # Spring 配置
│           │   │   ├── CorsConfig.java
│           │   │   └── RedisConfig.java
│           │   ├── dto/
│           │   │   └── R.java          # 统一响应
│           │   ├── exception/
│           │   │   ├── BusinessException.java
│           │   │   └── GlobalExceptionHandler.java
│           │   └── repository/         # JPA Repository
│           ├── api/
│           │   ├── controller/         # 客户端 API
│           │   │   ├── ClickController.java
│           │   │   ├── ReportController.java
│           │   │   └── HealthController.java
│           │   └── model/              # 请求/响应模型
│           ├── core/
│           │   ├── engine/             # 归因引擎
│           │   │   ├── AttributionEngine.java
│           │   │   ├── CallbackRetryService.java
│           │   │   └── DataCleanupTask.java
│           │   ├── matcher/            # 匹配器
│           │   │   ├── OaidMatcher.java
│           │   │   └── FingerprintMatcher.java
│           │   ├── callback/           # 回传
│           │   │   ├── CallbackService.java
│           │   │   └── AttributionContext.java
│           │   └── event/              # 事件路由
│           │       └── EventRouter.java
│           └── admin/                  # 管理后台 API
│               ├── controller/
│               ├── service/
│               └── dto/
│
└── admin-frontend/                     # 管理后台前端
    ├── package.json
    ├── vite.config.ts
    ├── index.html
    └── src/
        ├── main.ts
        ├── App.vue
        ├── router/index.ts
        ├── api/attribution.ts          # API 封装
        └── views/
            ├── Dashboard.vue           # 数据看板
            ├── GameManage.vue          # 游戏管理
            ├── EventConfig.vue         # 事件配置
            └── AttributionData.vue     # 归因数据查询
```

---

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- Docker & Docker Compose（生产部署）

### 本地开发

```bash
# 1. 启动 Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. 启动后端 (H2 内存库，无需 MySQL)
cd attribution-server
mvn spring-boot:run

# 3. 启动前端
cd admin-frontend
npm ci
npm run dev
```

- 后端: http://localhost:8080
- 前端: http://localhost:3000
- H2 控制台: http://localhost:8080/h2-console
- 本地管理后台账号: `admin` / `admin123`
- 本地客户端上报 Header: `X-Attribution-Api-Key: dev-report-api-key`

### Docker 一键部署

```bash
# 1. 配置环境变量
cp .env.example .env
# 编辑 .env 填入实际密钥

# 2. 构建并启动（后端和前端镜像都会自动构建）
docker compose up -d --build

# 3. 访问
# 管理后台: http://localhost:3000
# 后端 API: http://localhost:8080
```

---

## 客户端接入

客户端**无需集成任何 SDK**，仅需发送 HTTP POST 请求。适配所有引擎（Unity、Cocos、Unreal、自研）。

### 1. 激活上报

```http
POST /api/v1/report
Content-Type: application/json
X-Attribution-Api-Key: your-report-api-key

{
  "gameId": "your_game_id",
  "platform": "apk",
  "event": "activate",
  "device": {
    "oaid": "设备OAID"
  },
  "app": {
    "version": "1.0.0",
    "channel": "huawei"
  },
  "fingerprint": {
    "ip": "192.168.1.1",
    "user_agent": "Mozilla/5.0 ..."
  },
  "ts": 1716883200000
}
```

### 2. 事件上报

```json
{
  "gameId": "your_game_id",
  "platform": "apk",
  "event": "purchase",
  "eventParams": {
    "revenue": 6.00,
    "currency": "CNY",
    "order_id": "ORDER_2024_001"
  },
  "device": { "oaid": "设备OAID" },
  "ts": 1716883200000
}
```

### 3. OAID 获取

各端 OAID 获取方式：

| 包体 | 获取方式 |
|------|----------|
| **APK** | `HwID.getInstance().getOAID()` → 或通过华为广告 SDK |
| **HAP** | `import { aa } from '@kit.AdsKit'` → 调用 OAID API |
| **RPK** | `qg.getOAID()` → 降级 `getLaunchOptionsSync().query.clickid` → 降级 `qa.getId(AAID)` → 最终指纹 |

详见 [客户端接入指南](docs/客户端接入指南.md)

---

## API 接口

### 客户端接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/click` | 接收鲸鸿动能点击回调 |
| `POST` | `/api/v1/report` | 客户端上报事件，需 `X-Attribution-Api-Key` |
| `GET` | `/api/v1/health` | 健康检查 |

### 管理后台接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET/POST/PUT/DELETE` | `/admin/api/games` | 游戏 CRUD |
| `GET/POST/PUT/DELETE` | `/admin/api/events` | 事件配置 CRUD |
| `GET` | `/admin/api/dashboard` | 数据看板 |
| `GET` | `/admin/api/attribution` | 归因记录查询 |
| `GET` | `/admin/api/stats/{gameId}` | 统计数据 |
| `GET` | `/admin/api/callback-logs` | 回传日志 |

### Click 回调参数

鲸鸿动能点击回调通过 `GET /api/v1/click` 接收，关键参数：

| 参数 | 说明 |
|------|------|
| `oaid` | 设备 OAID |
| `callback` | 回传地址（加密后的 URL） |
| `campaignId` | 计划 ID |
| `adGroupId` | 任务 ID |
| `contentId` | 创意 ID |
| `clickTime` | 点击时间戳（毫秒） |
| `ip` | 用户 IP |
| `userAgent` | 用户代理 |
| `actionType` | CLICK / IMP / DEEPLINKCLICK |
| `trackingEnabled` | 0 或 1 |

---

## 管理后台

管理后台提供四个功能模块：

### 1. 数据看板
- 今日激活数、事件数、回传成功率、ARPU
- 各游戏指标横向对比

### 2. 游戏管理
- 新增/编辑/删除游戏配置
- 配置归因窗口、密钥、指纹降级开关
- 密钥 AES-GCM 加密存储，页面仅显示掩码（`abc***xyz`）

### 3. 事件配置
- 自定义事件类型和参数 Schema
- 映射华为鲸鸿动能 conversion_type
- 支持预置事件（game_id='*'），新建游戏自动继承

### 4. 归因数据
- 按游戏/时间/事件查询归因记录
- 查看回传日志和响应详情

---

## 部署指南

### Docker Compose（推荐）

```bash
# 1. 修改环境变量
cp .env.example .env
vim .env  # 填入实际密钥和数据库密码

# 2. 构建并启动全部服务
docker compose up -d --build

# 3. 验证
curl http://localhost:8080/api/v1/health
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| attribution-server | 8080 | Spring Boot API |
| admin-frontend | 3000 | Nginx + Vue 管理后台 |
| MySQL | 3306 | 数据库 |
| Redis | 6379 | 缓存 |

---

## 配置说明

### 归因配置 (`application.yml`)

```yaml
attribution:
  callback-url: https://ppscrowd-drcn.op.hicloud.com/action-lib-track/hiad/v2/actionupload
  callback-retry-max: 3              # 最大重试次数
  callback-retry-base-seconds: 5     # 重试基数秒(指数退避)
  attribution-window-days: 30        # OAID 归因窗口
  fingerprint-match-minutes: 30      # 指纹匹配窗口
  click-cache-ttl-days: 7            # Redis 缓存 TTL
  callback-worker-fixed-delay-ms: 10000 # 回传任务扫描间隔
  callback-worker-claim-timeout-minutes: 10 # sending 任务超时回收
  encryption-key: <32字符密钥>       # AES 加密密钥
  api-key: <客户端上报API密钥>

admin:
  username: admin
  password: <管理后台密码>
```

生产环境表结构由 Flyway 管理，迁移脚本位于 `attribution-server/src/main/resources/db/migration`。`attribution-server/sql/init.sql` 仅保留为人工参考。

### 数据库

6 张核心表：

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| `game_config` | 游戏配置 | game_id, secret_key(AES加密), platforms |
| `event_definition` | 事件定义 | game_id, event_name, conversion_type |
| `click_record` | 点击记录 | game_id, oaid, callback, click_time |
| `attribution_record` | 归因记录 | game_id, oaid, event_type, callback_status, dedupe_key |
| `callback_task` | 持久化回传任务 | attribution_id, status, next_retry_at, attempt_count |
| `callback_log` | 回传日志 | attribution_id, response_code, result_code |

---

## 常见问题

**Q: 客户端如何接入？**
A: 客户端只需发 HTTP POST 到 `/api/v1/report`，携带 gameId + event + deviceInfo。不需要任何 SDK。

**Q: 支持哪些引擎？**
A: 所有引擎（Unity、Cocos、Unreal、自研引擎等），因为接入层是纯 HTTP 协议。

**Q: 如何添加新游戏？**
A: 管理后台 → 游戏管理 → 新增，填写 gameId、游戏名、鲸鸿动能密钥即可。

**Q: 密钥如何存储？**
A: 使用 AES-256-GCM 加密后存入 MySQL，密钥明文仅存在内存中。

**Q: 归因匹配失败怎么办？**
A: 1) OAID 精确匹配（30 天窗口）→ 2) 指纹降级（IP+UA，30 分钟窗口，需后台开启）→ 3) 返回未匹配，客户端稍后重试。

---

## License

MIT License

---

**作者：** 张小宝
**仓库：** https://github.com/xiaobao0818/huawei-guiyin-xiaobao
