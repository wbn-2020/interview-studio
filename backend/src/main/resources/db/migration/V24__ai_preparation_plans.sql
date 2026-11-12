-- 服务器显式升级；采用决定固定人工审阅后的安排与新建任务映射，后续任务编辑不改写此记录。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v22,
    ADD CONSTRAINT ck_ai_task_purpose_v24 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT','PREPARATION_PLAN'));

CREATE TABLE ai_preparation_plan_decision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json MEDIUMTEXT NULL,
    tasks_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    CONSTRAINT fk_ai_preparation_plan_decision_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 20 步合法文本经 JSON 转义最多约 160 KiB，存储上限需覆盖 UTF-16 内容规则允许的原文。
    CONSTRAINT ck_ai_preparation_plan_decision_content CHECK (
        content_json IS NULL OR (JSON_VALID(content_json)
            AND JSON_TYPE(CAST(content_json AS JSON))='OBJECT' AND OCTET_LENGTH(content_json)<=262144)),
    CONSTRAINT ck_ai_preparation_plan_decision_tasks CHECK (
        JSON_VALID(tasks_json) AND JSON_TYPE(CAST(tasks_json AS JSON))='ARRAY' AND OCTET_LENGTH(tasks_json)<=131072),
    CONSTRAINT ck_ai_preparation_plan_decision_state CHECK (
        (status='ADOPTED' AND content_json IS NOT NULL AND JSON_LENGTH(CAST(tasks_json AS JSON)) BETWEEN 1 AND 20)
        OR (status='IGNORED' AND content_json IS NULL AND JSON_LENGTH(CAST(tasks_json AS JSON))=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
