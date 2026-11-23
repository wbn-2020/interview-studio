-- 服务器显式迁移；旧来源和复练继续保留正式答案身份，回忆来源使用独立确切版本。
ALTER TABLE review_action_source
    DROP CHECK ck_review_source_identity,
    DROP CHECK ck_review_source_observation,
    MODIFY session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY observation_json MEDIUMTEXT NULL,
    ADD COLUMN recollection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recollection_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recollection_json MEDIUMTEXT NULL,
    ADD KEY ix_review_source_recollection (owner_id,recollection_id,recollection_version_id),
    ADD CONSTRAINT fk_review_source_recollection FOREIGN KEY (owner_id,recollection_id,recollection_version_id)
        REFERENCES review_recollection_version (owner_id,recollection_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_review_source_identity CHECK (COALESCE(
        (kind='MANUAL' AND session_id IS NOT NULL AND question_id IS NOT NULL AND answer_id IS NOT NULL
            AND recollection_id IS NULL AND recollection_version_id IS NULL AND recollection_json IS NULL
            AND observation_json IS NOT NULL
            AND feedback_id IS NOT NULL AND feedback_revision IS NOT NULL AND feedback_revision BETWEEN 1 AND 100
            AND task_id IS NULL AND observation_id IS NULL AND manual_kind IS NOT NULL AND author_label IS NOT NULL
            AND ((manual_kind='SELF_CHECK' AND CHAR_LENGTH(author_label)=0)
                OR (manual_kind='EXTERNAL_FEEDBACK' AND CHAR_LENGTH(TRIM(author_label)) BETWEEN 1 AND 120)))
        OR (kind='AI_EVALUATION' AND session_id IS NOT NULL AND question_id IS NOT NULL AND answer_id IS NOT NULL
            AND recollection_id IS NULL AND recollection_version_id IS NULL AND recollection_json IS NULL
            AND observation_json IS NOT NULL AND feedback_id IS NULL AND feedback_revision IS NULL
            AND task_id IS NOT NULL AND observation_id IS NOT NULL AND manual_kind IS NULL AND author_label IS NULL
            AND ((CHAR_LENGTH(observation_id)=2 AND observation_id IN ('f1','f2','f3','f4','f5','f6','f7','f8','f9'))
                OR observation_id IN ('f10','f11','f12')))
        OR (kind='RECOLLECTION' AND session_id IS NULL AND question_id IS NULL AND answer_id IS NULL
            AND feedback_id IS NULL AND feedback_revision IS NULL AND task_id IS NULL AND observation_id IS NULL
            AND manual_kind IS NULL AND author_label IS NULL AND observation_json IS NULL AND decision_json IS NULL
            AND recollection_id IS NOT NULL AND recollection_version_id IS NOT NULL AND recollection_json IS NOT NULL),FALSE)=TRUE),
    ADD CONSTRAINT ck_review_source_observation CHECK (COALESCE(
        (kind='RECOLLECTION' AND observation_json IS NULL)
        OR (kind IN ('MANUAL','AI_EVALUATION') AND observation_json IS NOT NULL AND JSON_VALID(observation_json)
            AND JSON_TYPE(CAST(observation_json AS JSON))='OBJECT' AND OCTET_LENGTH(observation_json)<=131072),FALSE)=TRUE),
    ADD CONSTRAINT ck_review_source_recollection CHECK (recollection_json IS NULL OR (JSON_VALID(recollection_json)
        AND JSON_TYPE(CAST(recollection_json AS JSON))='OBJECT' AND JSON_LENGTH(recollection_json)=5
        AND OCTET_LENGTH(recollection_json)<=65536
        AND COALESCE(
            JSON_TYPE(JSON_EXTRACT(recollection_json,'$.recollectionId'))='STRING'
            AND JSON_UNQUOTE(JSON_EXTRACT(recollection_json,'$.recollectionId'))=recollection_id
            AND JSON_TYPE(JSON_EXTRACT(recollection_json,'$.versionId'))='STRING'
            AND JSON_UNQUOTE(JSON_EXTRACT(recollection_json,'$.versionId'))=recollection_version_id
            AND JSON_TYPE(JSON_EXTRACT(recollection_json,'$.versionNumber'))='INTEGER'
            AND CAST(JSON_UNQUOTE(JSON_EXTRACT(recollection_json,'$.versionNumber')) AS UNSIGNED) BETWEEN 1 AND 1000
            AND JSON_TYPE(JSON_EXTRACT(recollection_json,'$.baselineKind'))='STRING'
            AND JSON_UNQUOTE(JSON_EXTRACT(recollection_json,'$.baselineKind')) IN ('RECOLLECTION','NONE')
            AND JSON_TYPE(JSON_EXTRACT(recollection_json,'$.observation'))='STRING'
            AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(recollection_json,'$.observation'))))>0
            AND CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(recollection_json,'$.observation')))<=5000,FALSE)));

-- 旧行由默认 ANSWER 保持原基线；显式空值分支防止 CHECK 的 UNKNOWN 放过缺失身份。
ALTER TABLE review_repractice
    DROP CHECK ck_repractice_new_session,
    MODIFY baseline_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY baseline_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY baseline_answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN baseline_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ANSWER',
    ADD COLUMN recollection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recollection_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recollection_revision BIGINT NULL,
    ADD KEY ix_repractice_recollection (owner_id,recollection_id,recollection_version_id),
    ADD CONSTRAINT fk_repractice_recollection FOREIGN KEY (owner_id,recollection_id,recollection_version_id)
        REFERENCES review_recollection_version (owner_id,recollection_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_repractice_baseline_identity CHECK (COALESCE(
        (baseline_kind='ANSWER' AND baseline_session_id IS NOT NULL AND baseline_question_id IS NOT NULL AND baseline_answer_id IS NOT NULL
            AND recollection_id IS NULL AND recollection_version_id IS NULL AND recollection_revision IS NULL
            AND session_id<>baseline_session_id AND question_id<>baseline_question_id)
        OR (baseline_kind IN ('RECOLLECTION','NONE') AND baseline_session_id IS NULL AND baseline_question_id IS NULL AND baseline_answer_id IS NULL
            AND recollection_id IS NOT NULL AND recollection_version_id IS NOT NULL AND recollection_revision IS NOT NULL
            AND recollection_revision BETWEEN 0 AND 1000),FALSE)=TRUE);
