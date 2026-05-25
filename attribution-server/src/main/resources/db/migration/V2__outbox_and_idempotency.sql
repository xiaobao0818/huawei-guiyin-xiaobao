ALTER TABLE attribution_record
    MODIFY callback_status VARCHAR(32) DEFAULT 'pending' COMMENT '回传状态 pending/success/failed/unmatched/no_callback',
    ADD COLUMN dedupe_key VARCHAR(128) NULL COMMENT '业务幂等键' AFTER retry_count;

CREATE UNIQUE INDEX uk_attribution_dedupe_key ON attribution_record (dedupe_key);

CREATE TABLE callback_task (
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
