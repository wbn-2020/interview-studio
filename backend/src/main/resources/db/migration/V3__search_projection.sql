-- 检索是可重建投影，代际指针只以数据库为准；目录和提交标记必须与该指针一致。
CREATE TABLE search_projection (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    build_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scan_cursor CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    boundary_event_id BIGINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_search_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace(owner_id),
    CONSTRAINT ck_search_phase CHECK (phase IN ('IDLE', 'SCANNING', 'CATCHING_UP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每一代按确切事件确认；自增最大值不能替代事件已应用的事实。
CREATE TABLE search_generation_event (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id BIGINT NOT NULL,
    applied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id, generation, event_id),
    CONSTRAINT fk_search_event_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace(owner_id),
    CONSTRAINT fk_search_event_outbox FOREIGN KEY (event_id) REFERENCES knowledge_outbox(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_outbox_owner_pending ON knowledge_outbox(owner_id, processed_at, id);
