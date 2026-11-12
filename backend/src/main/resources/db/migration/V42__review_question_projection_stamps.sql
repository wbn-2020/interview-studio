-- 仅由已完整核对题目来源的审阅命令追加；旧记录须经显式保存或查看补建，不从历史快照回填。
CREATE TABLE review_question_projection_stamp (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,question_id,version_id),
    CONSTRAINT fk_review_projection_question FOREIGN KEY (owner_id,question_id,version_id)
        REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_projection_content_hash CHECK (REGEXP_LIKE(source_content_hash,'^[0-9a-f]{64}$','c')),
    CONSTRAINT ck_review_projection_fingerprint CHECK (REGEXP_LIKE(fingerprint,'^[0-9a-f]{64}$','c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
