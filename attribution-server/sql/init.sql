-- ============================================
-- 华为鲸鸿动能自归因平台 - 数据库初始化脚本
-- ============================================
-- ⚠️ 此文件仅供人工参考。生产环境表结构由 Flyway 管理。
--    迁移脚本位置: attribution-server/src/main/resources/db/migration/
--    如需添加新表或修改表结构，请创建新的 Flyway 迁移 (V3__xxx.sql)。
-- ============================================

CREATE DATABASE IF NOT EXISTS attribution
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE attribution;

-- 游戏配置表
CREATE TABLE IF NOT EXISTS game_config (
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    game_id              VARCHAR(64)  NOT NULL UNIQUE COMMENT '游戏唯一标识',
    game_name            VARCHAR(128) NOT NULL COMMENT '游戏名称',
    platforms            VARCHAR(64)  NOT NULL DEFAULT 'apk,hap,rpk' COMMENT '支持平台,逗号分隔',
    secret_key           VARCHAR(512) NOT NULL COMMENT '鲸鸿动能密钥(AES-GCM加密存储)',
    attribution_window_days INT DEFAULT 30 COMMENT '归因窗口(天)',
    callback_retry_max   INT DEFAULT 3 COMMENT '回传最大重试次数',
    fingerprint_fallback TINYINT DEFAULT 1 COMMENT '是否启用指纹降级匹配 0=否 1=是',
    status               TINYINT DEFAULT 1 COMMENT '0=停用 1=启用',
    created_at           DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='游戏配置';

-- 事件定义表
CREATE TABLE IF NOT EXISTS event_definition (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    game_id         VARCHAR(64)  NOT NULL COMMENT '所属游戏',
    event_name      VARCHAR(64)  NOT NULL COMMENT '事件名',
    display_name    VARCHAR(128) COMMENT '显示名称',
    conversion_type VARCHAR(32)  COMMENT '映射鲸鸿动能 conversion_type,NULL表示不回传',
    param_schema    JSON         COMMENT '参数 JSON Schema',
    is_preset       TINYINT DEFAULT 0 COMMENT '是否预置事件',
    enabled         TINYINT DEFAULT 1 COMMENT '0=停用 1=启用',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_game_event (game_id, event_name)
) COMMENT='事件定义';

-- 点击记录表
CREATE TABLE IF NOT EXISTS click_record (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    game_id         VARCHAR(64)  NOT NULL COMMENT '游戏ID',
    oaid            VARCHAR(128) NOT NULL COMMENT '设备OAID',
    callback        TEXT         NOT NULL COMMENT '鲸鸿动能 callback原文',
    campaign_id     VARCHAR(64)  COMMENT '计划ID',
    adgroup_id      VARCHAR(64)  COMMENT '任务ID',
    content_id      VARCHAR(64)  COMMENT '创意ID',
    click_time      BIGINT       NOT NULL COMMENT '点击时间戳(毫秒)',
    ip              VARCHAR(45)  COMMENT '用户IP',
    user_agent      VARCHAR(512) COMMENT '用户代理',
    platform        VARCHAR(20)  COMMENT 'apk/hap/rpk',
    action_type     VARCHAR(20)  COMMENT 'CLICK/IMP/DEEPLINKCLICK',
    tracking_enabled VARCHAR(4)  COMMENT '0/1',
    matched         TINYINT DEFAULT 0 COMMENT '是否已归因匹配',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_game_oaid (game_id, oaid),
    INDEX idx_click_time (click_time)
) COMMENT='点击记录';

-- 归因记录表
CREATE TABLE IF NOT EXISTS attribution_record (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    game_id           VARCHAR(64)  NOT NULL COMMENT '游戏ID',
    click_id          BIGINT       COMMENT '关联click_record.id',
    oaid              VARCHAR(128) NOT NULL COMMENT '设备OAID',
    event_type        VARCHAR(64)  NOT NULL COMMENT '事件类型',
    event_params      JSON         COMMENT '事件参数',
    conversion_type   VARCHAR(32)  COMMENT '回传的conversion_type',
    callback          TEXT         COMMENT '使用的callback',
    conversion_time   BIGINT       COMMENT '转化时间(秒)',
    revenue           DECIMAL(12,2) DEFAULT 0 COMMENT '付费金额',
    currency          VARCHAR(8)   DEFAULT 'CNY' COMMENT '货币代码',
    platform          VARCHAR(20)  COMMENT 'apk/hap/rpk',
    app_version       VARCHAR(32)  COMMENT 'App版本',
    attribution_type  VARCHAR(32)  DEFAULT 'oaid' COMMENT '归因方式 oaid/fingerprint/channel',
    callback_status   VARCHAR(32)  DEFAULT 'pending' COMMENT '回传状态 pending/success/failed/unmatched/no_callback',
    callback_response TEXT         COMMENT '华为回传响应',
    retry_count       INT DEFAULT 0 COMMENT '重试次数',
    dedupe_key        VARCHAR(128) COMMENT '业务幂等键',
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_attribution_dedupe_key (dedupe_key),
    INDEX idx_game_oaid (game_id, oaid),
    INDEX idx_game_event (game_id, event_type),
    INDEX idx_conversion_time (conversion_time)
) COMMENT='归因记录';

-- 持久化回传任务表
CREATE TABLE IF NOT EXISTS callback_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    attribution_id  BIGINT       NOT NULL COMMENT '关联 attribution_record.id',
    game_id         VARCHAR(64)  NOT NULL COMMENT '游戏ID',
    status          VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT 'pending/sending/retry_pending/success/dead',
    context_json    TEXT         NOT NULL COMMENT '回传上下文JSON',
    attempt_count   INT          NOT NULL DEFAULT 0 COMMENT '已发送次数',
    max_attempts    INT          NOT NULL DEFAULT 1 COMMENT '最大发送次数',
    next_retry_at   DATETIME     NOT NULL COMMENT '下次发送时间',
    locked_at       DATETIME     NULL COMMENT '任务认领时间',
    last_error      TEXT         NULL COMMENT '最后失败原因',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_callback_task_due (status, next_retry_at),
    INDEX idx_callback_task_locked (status, locked_at),
    INDEX idx_callback_task_attribution (attribution_id)
) COMMENT='持久化回传任务';

-- 回传日志表
CREATE TABLE IF NOT EXISTS callback_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    attribution_id  BIGINT       NOT NULL COMMENT '关联attribution_record.id',
    game_id         VARCHAR(64)  NOT NULL COMMENT '游戏ID',
    request_url     VARCHAR(512) COMMENT '回传URL',
    request_body    TEXT         COMMENT '回传请求体JSON',
    response_code   INT          COMMENT 'HTTP状态码',
    response_body   TEXT         COMMENT '华为响应内容',
    result_code     INT          COMMENT '华为resultCode: 0=成功 1=签名失败 2=参数非法',
    duration_ms     INT          COMMENT '请求耗时(毫秒)',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT='回传日志';

-- 插入预置事件
INSERT INTO event_definition (game_id, event_name, display_name, conversion_type, param_schema, is_preset) VALUES
('*', 'activate',          '激活',           'activate',           '[]', 1),
('*', 'register',          '注册',           'register',           '[]', 1),
('*', 'login',             '登录',           NULL,                 '[]', 1),
('*', 'purchase',          '付费',           'paid',               '[{"key":"revenue","type":"number","required":true,"desc":"金额(元)"},{"key":"currency","type":"string","default":"CNY","desc":"货币代码"},{"key":"order_id","type":"string","desc":"订单号"},{"key":"product_id","type":"string","desc":"商品ID"}]', 1),
('*', 'retain_1d',         '次留',           'retain',             '[]', 1),
('*', 'retain_7d',         '7日留存',        NULL,                 '[]', 1),
('*', 'level_up',          '升级',           NULL,                 '[{"key":"level","type":"number","required":false,"desc":"等级"}]', 1),
('*', 'level_complete',    '通关',           NULL,                 '[{"key":"level_id","type":"string","desc":"关卡ID"},{"key":"score","type":"number","desc":"分数"}]', 1),
('*', 'tutorial_complete', '完成新手引导',    NULL,                 '[{"key":"duration","type":"number","desc":"耗时(秒)"}]', 1),
('*', 'custom',            '自定义事件',      'custom',             '[]', 1);
