-- 只登记整理／出题任务创建时的直接知识身份；全部历史任务状态均回填，不复制原文或改写外发清单。
-- MySQL DDL 不随后续数据失败回滚；守卫失败时先在服务器核查原始异常和已建空表，再处理迁移状态，不得跳过校验继续发布。
CREATE TABLE ai_draft_knowledge_input (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,task_id,ordinal),
    UNIQUE KEY uk_ai_draft_knowledge_input_item (owner_id,task_id,knowledge_item_id),
    KEY ix_ai_draft_knowledge_input_version (owner_id,knowledge_item_id,knowledge_version_id,task_id),
    CONSTRAINT fk_ai_draft_knowledge_input_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_draft_knowledge_input_version FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_draft_knowledge_input_kind CHECK (source_kind IN ('NOTE','CONCEPT')),
    CONSTRAINT ck_ai_draft_knowledge_input_ordinal CHECK (ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 异常行故意触发 NOT NULL／ordinal CHECK 中止迁移；正常数据不产生守卫行。
-- 必须先验证完整数组，禁止 JSON_TABLE 的标量转换、长度截断或类型过滤掩盖历史异常。
INSERT INTO ai_draft_knowledge_input
    (owner_id,task_id,ordinal,source_kind,knowledge_item_id,knowledge_version_id)
SELECT NULL,t.id,0,'NOTE',NULL,NULL
FROM ai_task t
WHERE t.purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT')
  AND (COALESCE(JSON_SCHEMA_VALID('{
    "type":"object",
    "required":["purpose","sources"],
    "properties":{
      "purpose":{"type":"string","enum":["KNOWLEDGE_DRAFT","QUESTION_DRAFT"]},
      "sources":{
        "type":"array",
        "maxItems":5,
        "items":{
          "type":"object",
          "required":["kind","itemId","versionId"],
          "properties":{
            "kind":{"type":"string","enum":["NOTE","CONCEPT","TARGET","PROJECT","FACT","EXPRESSION"]},
            "itemId":{
              "type":"string","minLength":36,"maxLength":36,
              "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            },
            "versionId":{
              "type":"string","minLength":36,"maxLength":36,
              "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            }
          }
        }
      }
    }
  }',t.manifest_json),0) <> 1
    OR BINARY JSON_UNQUOTE(JSON_EXTRACT(t.manifest_json,'$.purpose')) <> BINARY t.purpose);

-- 混选中的准备对象同样不能重复；只对知识建唯一键不足以发现这类清单异常。
INSERT INTO ai_draft_knowledge_input
    (owner_id,task_id,ordinal,source_kind,knowledge_item_id,knowledge_version_id)
SELECT NULL,t.id,0,'NOTE',NULL,NULL
FROM ai_task t
WHERE t.purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT')
  AND EXISTS (
    SELECT 1
    FROM JSON_TABLE(CASE WHEN t.purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT')
        THEN t.manifest_json ELSE '{"sources":[]}' END,'$.sources[*]' COLUMNS (
        item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR
    )) AS source_identity
    GROUP BY BINARY source_identity.item_id
    HAVING COUNT(*) > 1
  );

-- 不联结当前知识对象进行过滤：历史版本缺失、跨 owner 或重复身份必须由约束阻止，不能静默漏记。
INSERT INTO ai_draft_knowledge_input
    (owner_id,task_id,ordinal,source_kind,knowledge_item_id,knowledge_version_id)
SELECT t.owner_id,t.id,source_identity.ordinal,source_identity.source_kind,
       source_identity.item_id,source_identity.version_id
FROM ai_task t
CROSS JOIN JSON_TABLE(CASE WHEN t.purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT')
    THEN t.manifest_json ELSE '{"sources":[]}' END,'$.sources[*]' COLUMNS (
    ordinal FOR ORDINALITY,
    source_kind VARCHAR(16) PATH '$.kind' ERROR ON EMPTY ERROR ON ERROR,
    item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.versionId' ERROR ON EMPTY ERROR ON ERROR
)) AS source_identity
WHERE t.purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT')
  AND source_identity.source_kind IN ('NOTE','CONCEPT');
