-- V5: indexes for scheduled cleanup and dashboard/history queries.

CREATE INDEX idx_click_record_created ON click_record (created_at);
CREATE INDEX idx_attribution_record_created ON attribution_record (created_at);
CREATE INDEX idx_callback_log_created ON callback_log (created_at);
CREATE INDEX idx_callback_task_status_created ON callback_task (status, created_at);
