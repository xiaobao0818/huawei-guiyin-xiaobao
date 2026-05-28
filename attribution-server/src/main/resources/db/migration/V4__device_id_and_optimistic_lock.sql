-- V4: 多设备ID匹配 + click_record 乐观锁

ALTER TABLE click_record
    ADD COLUMN version BIGINT DEFAULT 0 COMMENT '乐观锁版本号' AFTER matched;

-- 新增 GAID/IDFA 字段，支持非华为设备匹配
ALTER TABLE click_record
    ADD COLUMN gaid VARCHAR(128) NULL COMMENT 'Google广告ID' AFTER oaid,
    ADD COLUMN idfa VARCHAR(128) NULL COMMENT 'iOS广告ID' AFTER gaid;

CREATE INDEX idx_click_gaid ON click_record (game_id, gaid);
CREATE INDEX idx_click_idfa ON click_record (game_id, idfa);
