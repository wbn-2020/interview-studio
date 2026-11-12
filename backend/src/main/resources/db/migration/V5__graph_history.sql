-- 世界坐标仍属于 knowledge_position；镜头和可撤销的布局变更使用独立投影。
CREATE TABLE graph_viewport (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    zoom DOUBLE NOT NULL,
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_graph_viewport_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_graph_viewport_x CHECK (x BETWEEN -1000000 AND 1000000),
    CONSTRAINT ck_graph_viewport_y CHECK (y BETWEEN -1000000 AND 1000000),
    CONSTRAINT ck_graph_viewport_zoom CHECK (zoom BETWEEN 0.05 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 历史只记录当次涉及的坐标，撤销不覆盖后来创建的节点，也不改变正文。
CREATE TABLE graph_layout_history (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    before_json JSON NOT NULL,
    after_json JSON NOT NULL,
    position_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id, revision),
    CONSTRAINT fk_graph_history_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_graph_history_revision CHECK (revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_graph_history_count CHECK (position_count BETWEEN 0 AND 200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
