-- V3: 异步事件任务 + 回传规则 + 窗口期配置 + 调试模式

CREATE TABLE event_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    game_id         VARCHAR(64)  NOT NULL COMMENT '游戏ID',
    request_json    TEXT         NOT NULL COMMENT '原始上报请求 JSON',
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/processing/done/failed',
    result_json     TEXT         NULL COMMENT '处理结果 JSON',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_created (status, created_at)
) COMMENT='异步事件处理任务';

ALTER TABLE event_definition
    ADD COLUMN callback_rule JSON NULL COMMENT '回传规则，NULL表示无条件回传' AFTER param_schema;

ALTER TABLE game_config
    ADD COLUMN window_config JSON NULL COMMENT '归因窗口期配置' AFTER callback_retry_max;

ALTER TABLE attribution_record
    ADD COLUMN debug_mode TINYINT DEFAULT 0 COMMENT '是否调试模式' AFTER callback_response,
    ADD COLUMN reattribution TINYINT DEFAULT 0 COMMENT '是否再归因' AFTER debug_mode;
