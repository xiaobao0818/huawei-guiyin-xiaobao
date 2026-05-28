# 华为鲸鸿动能通用自归因平台

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.x-brightgreen.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

通用的 IAA 游戏广告归因系统，专为**华为鲸鸿动能（Petal Ads）**买量投放设计。

**核心特点：** 支持 APK（Android）、HAP（鸿蒙）、RPK（快游戏）三种包体，引擎无关（纯 HTTP 接入，不依赖任何 SDK），多游戏同时管理。

---

## 目录

- [为什么需要自归因](#为什么需要自归因)
- [系统架构](#系统架构)
- [归因流程详解](#归因流程详解)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [使用指南](#使用指南)
- [客户端接入](#客户端接入)
- [API 接口](#api-接口)
- [管理后台](#管理后台)
- [部署指南](#部署指南)
- [配置说明](#配置说明)
- [可观测性](#可观测性)
- [常见问题](#常见问题)

---

## 为什么需要自归因

在鲸鸿动能投放买量广告时，广告平台需要知道"哪个点击带来了哪个转化"，才能优化投放策略（oCPX）。华为提供两种归因方式：

| 方式 | 原理 | 适用场景 |
|------|------|---------|
| **华为分析（HA）** | 集成华为分析 SDK，华为自动完成归因 | 单一包体，接受 SDK 依赖 |
| **自归因（自有分析工具）** | 广告主自己搭建服务端，接收点击回调、匹配转化、签名回传 | 多包体、多引擎、需要自主可控 |

**如果你的场景是：** 多款 IAA 游戏、APK+HAP+RPK 三个包体同时投放、使用 Unity/Cocos 等不同引擎 —— 自归因是唯一选择。本平台就是为此构建的。

---

## 系统架构

### 整体架构图

```
┌──────────────────────────────────────────────────────────────┐
│                        鲸鸿动能广告平台                         │
│  宏展开 → GET 监测链接 → 接收 HMAC-SHA256 签名的转化回传       │
└───────┬──────────────────────────────────────▲───────────────┘
        │ callback + OAID + IP/UA              │ 签名回传
        ▼                                      │
┌───────────────────────────────────────────────────────────────┐
│                     Spring Boot 归因服务                        │
│                                                               │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐  │
│  │ ClickController │ReportController│  AttributionEngine   │  │
│  │ GET /click      │ POST /report   │  归因匹配 + 去重 + 入队│  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬───────────┘  │
│         │                │                     │              │
│         ▼                ▼                     ▼              │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                   核心引擎层                               │  │
│  │  ┌──────────┐  ┌──────────────┐  ┌──────────────────┐   │  │
│  │  │OaidMatcher│  │FingerprintMatcher│  │CallbackRetryService│  │
│  │  │ OAID 匹配 │  │ IP+UA 指纹匹配  │  │ 分布式锁+指数退避 │   │  │
│  │  └──────────┘  └──────────────┘  └──────────────────┘   │  │
│  │  ┌──────────┐  ┌──────────────┐                         │  │
│  │  │EventRouter│  │CallbackService│                        │  │
│  │  │ 事件→类型  │  │ HMAC 签名回传│                        │  │
│  │  └──────────┘  └──────────────┘                         │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                    管理后台 API                           │  │
│  │  GameController │ EventConfigController │ Dashboard     │  │
│  └─────────────────────────────────────────────────────────┘  │
└──────────────┬────────────────────┬───────────────────────────┘
               │                    │
               ▼                    ▼
         ┌─────────┐        ┌──────────┐
         │  Redis  │        │  MySQL   │
         │ 点击缓存 │        │ 归因数据  │
         │ 分布式锁 │        │ 6 张核心表│
         │ Dashboard│        │ Flyway 迁移│
         └─────────┘        └──────────┘
               ▲
    ┌──────────┴──────────┐
    │                     │
┌───┴──────┐      ┌──────┴──────┐
│ APK 客户端│      │ HAP/RPK 客户端│
│ HTTP POST │      │  HTTP POST   │
│ 任意引擎  │      │  任意引擎     │
└──────────┘      └─────────────┘
```

### 分层架构

代码按职责分为四层，依赖方向严格自上而下：

```
api/ (对外接口层)
 │   ClickController, ReportController, HealthController
 │   ReportRequest, ReportResponse
 │
 ├─▶ core/ (核心引擎层)
 │     AttributionEngine, CallbackRetryService, DataCleanupTask
 │     OaidMatcher, FingerprintMatcher
 │     CallbackService, EventRouter, AttributionMetrics
 │
 ├─▶ admin/ (管理后台层)
 │     GameController, EventConfigController, DashboardController
 │     GameService, EventConfigService, AnalyticsService
 │
 └─▶ common/ (公共基础设施层)
       GameConfig, ClickRecord, AttributionRecord (JPA 实体)
       AesUtil, SignatureUtil, RedisKeyUtil (工具)
       CallbackStatus, PlatformType, AttributionType (枚举)
       R<T> (统一响应)
```

### 三层匹配策略

当客户端上报事件时，归因引擎按以下优先级尝试匹配点击：

```
收到 POST /api/v1/report
      │
      ▼
┌─────────────────┐
│ 1. OAID 精确匹配  │ ← 主力方式，命中率 > 95%
│ Redis 缓存 +      │   归因窗口: 30 天
│ MySQL 持久化双层   │   Redis 未命中 → 回查 MySQL → 回填 Redis
└────────┬────────┘
         │ 未命中
         ▼
┌─────────────────┐
│ 2. 指纹降级匹配  │ ← 可选，管理后台按游戏开启
│ IP 前缀 + UA      │   归因窗口: 30 分钟
│ 加权评分 ≥ 2 分   │   DB 层 IP 前缀预筛选 → 最多 500 候选 → 逐条打分
└────────┬────────┘
         │ 未命中
         ▼
┌─────────────────┐
│ 3. 渠道号匹配    │ ← 预留
│ (暂未实现)       │
└─────────────────┘
```

---

## 归因流程详解

### 时序图

```
鲸鸿动能               归因服务                  MySQL        Redis        游戏客户端
   │                      │                        │            │              │
   │─ click callback ────▶│                        │            │              │
   │  GET /api/v1/click   │                        │            │              │
   │  oaid + callback     │── 写入 click_record ──▶│            │              │
   │  + IP/UA/campaign    │── 缓存 ClickCache ────────────────▶│              │
   │                      │                        │            │              │
   │                      │◀────────── POST /api/v1/report ──────────────────│
   │                      │   gameId + event       │            │              │
   │                      │   + device.oaid        │            │              │
   │                      │                        │            │              │
   │                      │── 1. 校验 game_config   │            │              │
   │                      │── 2. 查询 event_definition (EventRouter 缓存)      │
   │                      │── 3. 去重检查 (dedupe_key / Redis 激活锁)          │
   │                      │── 4. OAID 匹配          │            │              │
   │                      │   Redis GET ──────────────────────▶│              │
   │                      │   未命中 → MySQL 回查 ─▶│            │              │
   │                      │   命中 → 回填 Redis ──────────────▶│              │
   │                      │── 5. 保存 attribution_record ──▶│                 │
   │                      │── 6. 创建 callback_task ──▶│                     │
   │                      │                        │            │              │
   │◀── POST 回传 ───────│                        │            │              │
   │   HMAC-SHA256 签名   │── CallbackRetryService  │            │              │
   │   conversion_type    │   定时扫描 pending 任务  │            │              │
   │   + conversion_time  │   分布式锁认领 (Redis)  │            │              │
   │                      │   发送 → 成功则标记     │            │              │
   │                      │   失败 → 指数退避重试   │            │              │
   │                      │                        │            │              │
   │── resultCode: 0 ───▶│                        │            │              │
   │                      │── 更新 callback_status  │            │              │
   │                      │── 保存 callback_log ──▶│            │              │
```

### 数据流关键节点

| 节点 | 说明 | 存储 |
|------|------|------|
| 点击接收 | 鲸鸿动能 GET 请求，展开宏参数 | `click_record` 表 + Redis 缓存 (7天TTL) |
| 事件上报 | 客户端 POST，带 OAID + 事件名 | 进入 AttributionEngine |
| 事件路由 | 查找事件定义，获取 conversion_type | `event_definition` 表 (30分钟缓存) |
| 去重 | activate 用 Redis 锁(180天)，其他用业务幂等键 / 5分钟时间桶 | Redis + DB unique index |
| 匹配 | OAID 精确匹配 → 指纹降级 | `click_record` 表 + Redis |
| 回传入队 | 匹配成功后创建 callback_task | `callback_task` 表 (persistent) |
| 回传执行 | Worker 每 10s 扫描，分布式锁认领，发送，记录 | `callback_log` 表 |
| 重试 | 指数退避 5s→25s→125s，最多 N 次 | `callback_task` 状态机 |
| 完成 | 成功/耗尽 → 更新归因记录状态 | `attribution_record` 表 |

---

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.2.5 | 应用框架 |
| Spring Data JPA | 3.2.5 | ORM / 数据访问 |
| Spring Data Redis | 3.2.5 | Redis 操作 (Lettuce 连接池) |
| Spring Security | 3.2.5 | 管理后台 Basic Auth |
| Spring Actuator | 3.2.5 | 健康检查 + Prometheus 指标 |
| Flyway | — | 数据库版本迁移 |
| MySQL | 8.0 | 持久化存储 |
| Redis | 7.x | 点击缓存 + 分布式锁 + Dashboard 缓存 + 速率限制 |
| Micrometer | — | Prometheus 指标导出 |
| SpringDoc | 2.5.0 | Swagger / OpenAPI 文档 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.x | UI 框架 |
| Element Plus | 2.x | 组件库 |
| Axios | — | HTTP 客户端 (Basic Auth 拦截器) |
| Vite | 5.x | 构建工具 |
| TypeScript | — | 类型安全 |

### 部署

| 技术 | 用途 |
|------|------|
| Docker + Docker Compose | 一键部署 |
| Nginx | 前端静态资源 + 反向代理 + 安全头 |

---

## 项目结构

```
huawei-guiyin-xiaobao/
├── README.md                             # 本文件
├── 技术方案.md                            # 详细技术方案 (含鲸鸿动能 API 研究)
├── 客户端接入指南.md                       # 各平台 OAID 获取 + 接入示例
├── 游戏接入归因完整文档.md                  # 游戏接入全流程文档
├── OAID获取指南.md                        # OAID 获取专题
├── 实施清单.md                            # 部署实施 checklist
├── .gitignore
├── .env.example                          # 环境变量模板
├── docker-compose.yml                    # 一键部署编排 (4 服务)
├── nginx.conf                            # Nginx 配置 (安全头 + 反向代理)
│
├── attribution-server/                   # 后端服务
│   ├── pom.xml                           # Maven 依赖
│   ├── Dockerfile                        # 多阶段构建 (maven → jre)
│   ├── sql/init.sql                      # 参考用 DDL (生产由 Flyway 管理)
│   └── src/
│       ├── main/
│       │   ├── resources/
│       │   │   ├── application.yml       # 通用配置
│       │   │   ├── application-dev.yml   # 开发环境 (H2 内存库)
│       │   │   ├── application-prod.yml  # 生产环境 (MySQL + Flyway)
│       │   │   └── db/migration/         # Flyway 迁移脚本
│       │   │       ├── V1__base_schema.sql
│       │   │       └── V2__outbox_and_idempotency.sql
│       │   └── java/com/attribution/
│       │       ├── AttributionApplication.java
│       │       ├── api/                  # 对外 API
│       │       │   ├── controller/
│       │       │   │   ├── ClickController.java      # GET  /api/v1/click
│       │       │   │   ├── ReportController.java     # POST /api/v1/report
│       │       │   │   └── HealthController.java     # GET  /api/v1/health
│       │       │   └── model/
│       │       ├── core/                 # 核心引擎
│       │       │   ├── engine/
│       │       │   │   ├── AttributionEngine.java    # 归因主引擎 (匹配+去重+入队)
│       │       │   │   ├── CallbackRetryService.java # 回传 Worker (分布式锁+重试)
│       │       │   │   └── DataCleanupTask.java      # 定时数据清理
│       │       │   ├── matcher/
│       │       │   │   ├── OaidMatcher.java          # OAID 精确匹配 (Redis+MySQL 双层)
│       │       │   │   └── FingerprintMatcher.java   # 指纹降级匹配 (IP+UA 加权)
│       │       │   ├── callback/
│       │       │   │   ├── CallbackService.java      # 构造请求+HMAC签名+HTTP发送
│       │       │   │   └── AttributionContext.java   # 回传上下文 DTO
│       │       │   ├── event/
│       │       │   │   └── EventRouter.java          # 事件→conversion_type 路由 (缓存)
│       │       │   └── metrics/
│       │       │       └── AttributionMetrics.java   # Prometheus 业务指标
│       │       ├── admin/                # 管理后台
│       │       │   ├── controller/
│       │       │   │   ├── GameController.java
│       │       │   │   ├── EventConfigController.java
│       │       │   │   └── DashboardController.java
│       │       │   ├── service/
│       │       │   │   ├── GameService.java          # 游戏 CRUD + 密钥加密 + 缓存失效
│       │       │   │   ├── EventConfigService.java   # 事件 CRUD + 缓存失效
│       │       │   │   └── AnalyticsService.java     # 看板统计 + 归因查询 + 缓存
│       │       │   └── dto/
│       │       └── common/               # 公共基础设施
│       │           ├── entity/           # JPA 实体 (6 张表)
│       │           ├── enums/            # CallbackStatus, PlatformType, AttributionType
│       │           ├── constant/         # EventConstants
│       │           ├── util/             # AesUtil, SignatureUtil, RedisKeyUtil
│       │           ├── config/           # Security, CORS, Redis, Filters
│       │           ├── dto/              # R<T> 统一响应
│       │           ├── exception/        # BusinessException, GlobalExceptionHandler
│       │           └── repository/       # JPA Repository (6 个)
│       └── test/                         # 单元测试 + 集成测试
│           └── java/com/attribution/
│               ├── AttributionEngineTest.java
│               ├── EventRouterTest.java
│               ├── SignatureUtilTest.java
│               └── AesUtilTest.java
│
└── admin-frontend/                       # 管理后台前端
    ├── package.json
    ├── vite.config.ts
    ├── Dockerfile                        # 多阶段构建 (node → nginx)
    ├── index.html
    └── src/
        ├── main.ts
        ├── App.vue                       # 布局框架 + 登录态
        ├── router/index.ts               # 路由 (Hash 模式)
        ├── api/
        │   ├── types.ts                  # TypeScript 类型定义
        │   └── attribution.ts            # API 封装 + Basic Auth 拦截器
        └── views/
            ├── Dashboard.vue             # 数据看板 (6 指标卡片 + 快速操作)
            ├── GameManage.vue            # 游戏管理 (CRUD + 密钥脱敏)
            ├── EventConfig.vue           # 事件配置 (按游戏 + 预置事件继承)
            └── AttributionData.vue       # 归因数据查询 (多维筛选 + 分页)
```

---

## 快速开始

### 环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 后端编译运行 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+ | 前端构建 |
| Docker + Compose | — | 生产部署 |

### 本地开发（3 步启动）

```bash
# 1. 启动 Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. 启动后端（H2 内存数据库，无需 MySQL）
cd attribution-server
./mvnw spring-boot:run

# 3. 启动前端
cd admin-frontend
npm ci
npm run dev
```

启动后：

| 服务 | 地址 | 说明 |
|------|------|------|
| 后端 API | http://localhost:8080 | Spring Boot |
| 管理后台 | http://localhost:3000 | Vue3 + Element Plus |
| Swagger | http://localhost:8080/swagger-ui.html | API 文档 |
| H2 控制台 | http://localhost:8080/h2-console | 数据库调试 |
| Prometheus | http://localhost:8080/actuator/prometheus | 指标导出 |

**开发环境默认凭证：**

| 用途 | 值 |
|------|-----|
| 管理后台账号 | `admin` / `admin123` |
| 客户端上报 Header | `X-Attribution-Api-Key: dev-report-api-key` |

> 开发环境默认值仅用于本地调试，生产环境必须通过环境变量覆盖。

### Docker 一键部署

```bash
# 1. 配置环境变量
cp .env.example .env
vim .env   # 填入实际密钥和密码

# 2. 构建并启动（首次构建约 3-5 分钟）
docker compose up -d --build

# 3. 验证
curl http://localhost:8080/api/v1/health
```

---

## 使用指南

### 首次使用完整流程

**第一步：创建游戏配置**

1. 登录管理后台 (http://localhost:3000)
2. 进入「游戏管理」→ 点击「新增游戏」
3. 填写：

| 字段 | 示例 | 说明 |
|------|------|------|
| 游戏 ID | `bead_master` | 唯一标识，客户端上报时使用 |
| 游戏名称 | 串珠大师 | 显示名称 |
| 支持平台 | `apk,hap,rpk` | 逗号分隔 |
| 密钥 | `<从鲸鸿动能后台复制>` | Base64 密钥，提交后 AES-GCM 加密存储 |
| 归因窗口 | 30 天 | OAID 匹配窗口 |
| 最大重试 | 3 次 | 回传失败后的重试次数 |

4. 提交后页面显示 `****`（密钥脱敏，不可见明文）

**第二步：配置鲸鸿动能监测链接**

登录 [ads.huawei.com](https://ads.huawei.com) → 工具 → 事件资产管理：

1. 为 APK/HAP/RPK 分别创建资产
2. 分析工具选择「自有分析工具」
3. 填写监测链接：

```
https://your-domain.com/api/v1/click?game_id=bead_master&callback=__CALLBACK__&oaid=__OAID__&campaign_id=__CID__&adgroup_id=__AID__&content_id=__CONTENT_ID__&ts=__TS__&ip=__IP__&ua=__UA__&platform=__PLATFORM__
```

4. 创建转化事件（activate / register / paid 等）→ 联调测试

**第三步：客户端集成**

客户端只需在关键时机发送 HTTP POST（详见[客户端接入](#客户端接入)）：

```
游戏首次启动  → 上报 activate 事件
用户注册      → 上报 register 事件
付费成功      → 上报 purchase 事件 (带 revenue/order_id)
```

**第四步：验证归因**

1. 准备一台华为手机，获取其 OAID
2. 在鲸鸿动能后台 → 事件概览 → 联调 → 输入测试 OAID
3. 手机打开游戏 → 触发激活上报
4. 查看管理后台「归因查询」→ 确认归因记录出现
5. 检查「回传状态」是否为 success

### 日常运维

```
管理后台四大模块:

数据看板         游戏管理          事件配置         归因查询
┌────────┐    ┌────────┐      ┌────────┐      ┌────────┐
│今日点击 │    │新增游戏 │      │选择游戏 │      │多维筛选 │
│今日激活 │    │编辑配置 │      │新增事件 │      │OAID查询 │
│付费次数 │    │密钥脱敏 │      │映射类型 │      │状态查看 │
│今日收入 │    │删除游戏 │      │参数Schema│     │回传日志 │
│回传成功率│   │状态开关 │      │预置事件 │      │分页浏览 │
│游戏总数 │    └────────┘      └────────┘      └────────┘
└────────┘
```

---

## 客户端接入

客户端**无需集成任何 SDK**，只需发送 HTTP POST 请求。适配所有游戏引擎（Unity、Cocos、Unreal、自研）。

### 激活上报

```http
POST /api/v1/report
Content-Type: application/json
X-Attribution-Api-Key: your-report-api-key

{
  "gameId": "bead_master",
  "platform": "apk",
  "event": "activate",
  "device": {
    "oaid": "1fe9a970-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  },
  "app": {
    "version": "1.0.0"
  },
  "fingerprint": {
    "ip": "192.168.1.1",
    "user_agent": "Mozilla/5.0 ..."
  },
  "ts": 1716883200000
}
```

### 付费上报

```json
{
  "gameId": "bead_master",
  "platform": "apk",
  "event": "purchase",
  "eventParams": {
    "revenue": 6.00,
    "currency": "CNY",
    "order_id": "ORDER_2024_001",
    "product_id": "item_gems_100"
  },
  "device": { "oaid": "1fe9a970-xxxx-xxxx-xxxx-xxxxxxxxxxxx" },
  "ts": 1716883200000
}
```

### 各平台 OAID 获取

| 包体 | 获取方式 | 备注 |
|------|---------|------|
| **APK (Android)** | `AdvertisingIdClient.getAdvertisingIdInfo(context).getId()` | 需集成 `com.huawei.hms:ads-identifier` |
| **HAP (鸿蒙)** | `identifier.getOAID()` from `@kit.AdsKit` | 需权限 `ohos.permission.APP_TRACKING_CONSENT` |
| **RPK (快游戏)** | `qg.getOAID()` → 降级 `qg.getLaunchOptionsSync().query.clickid` → 最终指纹 | 需在 manifest.json 配置 `system.device` |

详细指引见 [客户端接入指南](客户端接入指南.md) 和 [OAID获取指南](OAID获取指南.md)。

---

## API 接口

### 客户端接口

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| `GET` | `/api/v1/click` | 无 (but 有速率限制) | 接收鲸鸿动能点击回调 |
| `POST` | `/api/v1/report` | `X-Attribution-Api-Key` Header | 客户端上报事件 |
| `GET` | `/api/v1/health` | 无 | 健康检查 (含 DB/Redis 连通性) |
| `GET` | `/api/v1/health/live` | 无 | 存活检查 (仅服务状态) |

### 管理后台接口

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| `GET/POST/PUT/DELETE` | `/admin/api/games` | HTTP Basic | 游戏 CRUD |
| `GET/POST/PUT/DELETE` | `/admin/api/events` | HTTP Basic | 事件配置 CRUD |
| `GET` | `/admin/api/games/{gameId}/events` | HTTP Basic | 按游戏查事件 |
| `GET` | `/admin/api/dashboard` | HTTP Basic | 数据看板 (Redis 缓存 5min) |
| `GET` | `/admin/api/attribution` | HTTP Basic | 归因记录多维查询 |
| `GET` | `/admin/api/stats/{gameId}` | HTTP Basic | 游戏维度统计 |
| `GET` | `/admin/api/callback-logs` | HTTP Basic | 回传日志查询 |

### Click 回调参数

| 参数 | 必填 | 说明 |
|------|:--:|------|
| `game_id` | ✅ | 游戏标识 |
| `callback` | ✅ | 鲸鸿动能回传地址 (URL 编码原文) |
| `oaid` | — | 设备 OAID |
| `campaign_id` | — | 广告计划 ID |
| `adgroup_id` | — | 广告任务 ID |
| `content_id` | — | 创意/素材 ID |
| `ts` | — | 点击时间戳 (毫秒) |
| `ip` | — | 用户 IP |
| `ua` / `user_agent` | — | 用户代理 |
| `platform` | — | 包体类型 (apk/hap/rpk) |
| `action_type` | — | CLICK / IMP / DEEPLINKCLICK |
| `tracking_enabled` | — | 0=不允许跟踪, 1=允许 |
| `trace_time` | — | 跟踪时间 |
| `corp_id` | — | 广告主账户 ID |

> 参数同时支持 snake_case 和 camelCase（如 `game_id` / `gameId`），由 `ClickController` 自动合并。

---

## 管理后台

### 1. 数据看板

6 个核心指标卡片 + 快速操作入口：

- **今日点击** — 当日接收的鲸鸿动能点击回调数
- **今日激活** — 当日成功匹配的激活事件数
- **今日付费次数** — 当日 purchase 事件数
- **今日收入** — 当日付费总额 (元)
- **回传成功率** — 当日成功回传 / 总回传尝试
- **游戏总数** — 已注册的游戏数

数据通过 Redis 缓存 5 分钟，修改游戏/事件配置后自动清除缓存。

### 2. 游戏管理

- 新增/编辑/删除游戏配置
- 密钥字段：输入时 `type="password"`，编辑回显为 `****`。后端用 AES-256-GCM 加密后存入 MySQL，管理后台只返回脱敏值 `****`
- 更新时：如果密钥字段值为 `****`（未修改），保留原密钥；否则重新加密存储
- 删除游戏时同时清除 EventRouter 缓存和 Dashboard 缓存

### 3. 事件配置

- 先选择游戏，再查看/管理该游戏的事件列表
- 预置事件（`game_id='*'`，`isPreset=true`）不可删除，只能禁用
- 新建游戏时自动继承所有预置事件（`EventRouter` 的 wildcard 匹配机制）
- `conversion_type` 为**空**时，该事件只记录不向鲸鸿动能回传（适合内部分析事件）
- 删除事件时确认弹窗保护

### 4. 归因数据查询

- 多维筛选：游戏 ID / OAID / 事件类型 / 回传状态
- 分页浏览，每页 20 条
- 支持查看回传日志详情（按 attributioID 查询）

---

## 部署指南

### 环境变量 (`.env`)

| 变量 | 必填 | 说明 |
|------|:--:|------|
| `MYSQL_ROOT_PASSWORD` | ✅ | MySQL root 密码 |
| `MYSQL_PASSWORD` | ✅ | MySQL 应用密码 |
| `ENCRYPTION_KEY` | ✅ | AES 加密主密钥 (32 字符，建议 `openssl rand -hex 16`) |
| `ATTRIBUTION_API_KEY` | ✅ | 客户端上报 API Key |
| `ADMIN_PASSWORD` | ✅ | 管理后台密码 |
| `CALLBACK_URL` | — | 鲸鸿动能回传地址 (有默认值) |
| `ADMIN_USERNAME` | — | 管理后台账号 (默认 admin) |

### 服务端口

| 服务 | 容器内 | 宿主机 | 内存限制 |
|------|:---:|:---:|:---:|
| `attribution-server` | 8080 | 8080 | 1G |
| `admin-frontend` | 80 | 3000 | 256M |
| MySQL | 3306 | 3306 | 1G |
| Redis | 6379 | 6379 | 512M |

### HTTPS 配置

鲸鸿动能**只接受 HTTPS** 监测链接。推荐方案：

1. 在服务器上使用 Nginx / Caddy 作为 SSL 终端
2. Let's Encrypt 免费证书 + 自动续期
3. 将 80 端口请求 301 重定向到 443

项目中的 `nginx.conf` 仅处理容器内反向代理，SSL 终端应在宿主机或负载均衡器层完成。

---

## 配置说明

### 核心配置项 (`application.yml`)

| 配置 | 默认值 | 说明 |
|------|------|------|
| `attribution.callback-url` | `https://ppscrowd-drcn.op.hicloud.com/...` | 鲸鸿动能回传地址 |
| `attribution.callback-retry-max` | 3 | 回传失败最大重试次数 |
| `attribution.callback-retry-base-seconds` | 5 | 重试退避基数 (指数: 5→25→125) |
| `attribution.attribution-window-days` | 30 | OAID 匹配窗口 |
| `attribution.fingerprint-match-minutes` | 30 | 指纹匹配窗口 |
| `attribution.click-cache-ttl-days` | 7 | Redis 点击缓存 TTL |
| `attribution.callback-worker-fixed-delay-ms` | 10000 | 回传 Worker 扫描间隔 |
| `attribution.callback-worker-claim-timeout-minutes` | 10 | sending 任务超时回收 |
| `attribution.click-rate-limit-max` | 100 | /click 接口每分钟每 IP 最大请求 |
| `attribution.click-rate-limit-window-seconds` | 60 | 速率限制窗口 |
| `attribution.encryption-key` | 环境变量 | AES-256-GCM 主密钥 |
| `attribution.api-key` | 环境变量 | 客户端上报 API Key |

### 数据库

生产环境表结构由 **Flyway** 管理，迁移脚本位于 `db/migration/`。

7 张核心表：

| 表名 | 说明 | 关键索引 |
|------|------|------|
| `game_config` | 游戏配置 | `UNIQUE(game_id)` |
| `event_definition` | 事件定义 | `UNIQUE(game_id, event_name)` |
| `click_record` | 点击记录 | `INDEX(game_id, oaid)`, `INDEX(click_time)` |
| `attribution_record` | 归因记录 | `INDEX(game_id, oaid)`, `INDEX(game_id, event_type)`, `UNIQUE(dedupe_key)` |
| `callback_task` | 回传任务 | `INDEX(status, next_retry_at)`, `INDEX(status, locked_at)` |
| `callback_log` | 回传日志 | 按 attribution_id 查询 |
| `flyway_schema_history` | 迁移历史 | Flyway 自动管理 |

数据保留策略 (每天凌晨 3 点自动清理)：

| 表 | 保留天数 |
|------|:---:|
| `click_record` | 90 天 |
| `attribution_record` | 180 天 |
| `callback_task` (已完成) | 90 天 |
| `callback_log` | 90 天 |

---

## 可观测性

### 健康检查

| 端点 | 说明 |
|------|------|
| `/api/v1/health` | 完整检查：服务状态 + 数据库连接 + Redis 连通性 |
| `/api/v1/health/live` | 存活检查：仅服务状态 |
| `/actuator/health` | Spring Actuator 健康端点 |

### Prometheus 指标

端点：`GET /actuator/prometheus`

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `attribution_events_total` | Counter | game, event | 接收的事件总数 |
| `attribution_clicks_total` | Counter | game | 接收的点击回调总数 |
| `attribution_match` | Counter | type(oaid/fingerprint/unmatched), game | 匹配结果分布 |
| `attribution_callback` | Counter | result(success/failure), game | 回传结果 |
| `attribution_callback_retries` | Counter | game, attempt | 重试次数分布 |
| `attribution_processing_time` | Timer | — | 归因引擎处理耗时 |

---

## 常见问题

**Q: 客户端如何接入？**
A: 客户端只需发 HTTP POST 到 `/api/v1/report`，携带 gameId + event + device.oaid，Header 带上 `X-Attribution-Api-Key`。不需要任何 SDK。任何游戏引擎（Unity、Cocos、Unreal、自研）都能接入。

**Q: 归因匹配失败怎么办？**
A: 原因通常是：
1. 用户点击广告后超过 30 天才激活（超出归因窗口）
2. 用户关闭了广告跟踪（OAID 返回全 0）
3. 鲸鸿动能点击回调未到达（检查监测链接配置和 HTTPS 证书）

系统会按优先级自动降级：OAID 精确匹配 → 指纹匹配（需管理后台开启）→ 返回 unmatched。

**Q: 密钥如何存储？**
A: 鲸鸿动能密钥（Base64 字符串）通过 AES-256-GCM 加密后存入 MySQL `game_config.secret_key` 字段，每次加密使用随机 IV。主密钥通过环境变量 `ENCRYPTION_KEY` 注入，只存在于服务器内存中。管理后台 API 返回时脱敏为 `****`。

**Q: 如何添加新游戏？**
A: 管理后台 → 游戏管理 → 新增，填写 gameId、游戏名、从鲸鸿动能后台复制的 Base64 密钥。新建游戏自动继承所有预置事件（activate、register、purchase 等）。

**Q: 支持哪些 conversion_type？**
A: activate（激活）、register（注册）、retain（留存）、paid（付费）、custom（自定义）、browse、addToCart、form_submit 等。在管理后台「事件配置」中可为每个事件映射对应的类型，留空则只记录不回传。

**Q: 回传失败如何处理？**
A: 系统有完整的重试机制：
1. 匹配成功后创建 `callback_task` 记录（持久化，服务重启不丢）
2. Worker 每 10 秒扫描 pending/retry_pending 任务
3. 指数退避重试：5s → 25s → 125s（最多 N 次，由游戏配置决定）
4. 超过最大次数后标记为 dead，状态更新为 failed
5. 所有回传请求/响应完整记入 `callback_log` 表，便于排查

**Q: 如何处理多 Pod 部署？**
A: `CallbackRetryService` 使用 Redis 分布式锁：
- Worker 锁 (`attribution:lock:callback-worker`)：保证同一时刻只有一个 Pod 扫描任务
- 恢复锁 (`attribution:lock:stale-recovery`)：保证超时任务只被一个 Pod 回收
- 任务认领使用 SQL UPDATE + status 条件，天然原子

---

## License

MIT License

---

**作者：** 张小宝  
**仓库：** https://github.com/xiaobao0818/huawei-guiyin-xiaobao
