-- 题面、当前服务器草稿、不可变提交分别登记确切知识身份，不复制正文或引用标题。
-- MySQL DDL 不随后续数据失败回滚；守卫失败时先核查原始异常、已建表和迁移状态，不得跳过校验发布。
CREATE TABLE training_question_knowledge_snapshot (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,question_id,knowledge_item_id,knowledge_version_id),
    KEY ix_training_question_knowledge_version (owner_id,knowledge_item_id,knowledge_version_id,question_id),
    CONSTRAINT fk_training_question_knowledge_target FOREIGN KEY (owner_id,session_id,question_id)
        REFERENCES training_question (owner_id,session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_training_question_knowledge_source FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_training_question_knowledge_identity CHECK (
        CHAR_LENGTH(knowledge_item_id)=36 AND CHAR_LENGTH(knowledge_version_id)=36)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE training_draft_knowledge_reference (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,question_id,knowledge_item_id,knowledge_version_id),
    KEY ix_training_draft_knowledge_version (owner_id,knowledge_item_id,knowledge_version_id,question_id),
    CONSTRAINT fk_training_draft_knowledge_target FOREIGN KEY (owner_id,session_id,question_id)
        REFERENCES training_question (owner_id,session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_training_draft_knowledge_source FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_training_draft_knowledge_identity CHECK (
        CHAR_LENGTH(knowledge_item_id)=36 AND CHAR_LENGTH(knowledge_version_id)=36)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE training_answer_knowledge_reference (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,answer_id,knowledge_item_id,knowledge_version_id),
    KEY ix_training_answer_knowledge_version (owner_id,knowledge_item_id,knowledge_version_id,answer_id),
    CONSTRAINT fk_training_answer_knowledge_target FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_training_answer_knowledge_source FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_training_answer_knowledge_identity CHECK (
        CHAR_LENGTH(knowledge_item_id)=36 AND CHAR_LENGTH(knowledge_version_id)=36)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 仅校验与本次登记有关的固定身份及数组，不给合法历史正文和标题追加新输入限制。
SET @wenjie_v62_question_schema = '{
  "type":"object",
  "required":["sourceQuestionId","sourceVersionId","title","number","stage","content"],
  "properties":{
    "sourceQuestionId":{
      "type":["string","null"],"minLength":36,"maxLength":36,
      "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    },
    "sourceVersionId":{
      "type":["string","null"],"minLength":36,"maxLength":36,
      "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    },
    "title":{"type":"string"},
    "number":{"type":"integer","minimum":0,"maximum":2147483647},
    "stage":{"type":"string"},
    "content":{
      "type":"object",
      "required":["title","knowledgeLinks"],
      "properties":{
        "title":{"type":"string"},
        "knowledgeLinks":{
          "type":"array","maxItems":20,
          "items":{
            "type":"object",
            "required":["itemId","versionId"],
            "additionalProperties":false,
            "properties":{
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
    }
  }
}';

SET @wenjie_v62_answer_schema = '{
  "type":"object",
  "required":["text","diagram"],
  "properties":{
    "text":{"type":"string"},
    "diagram":{
      "type":"object",
      "required":["nodes"],
      "properties":{
        "nodes":{
          "type":"array","maxItems":80,
          "items":{
            "type":"object",
            "required":["id","reference"],
            "properties":{
              "id":{
                "type":"string","minLength":36,"maxLength":36,
                "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
              },
              "reference":{
                "type":["object","null"],
                "required":["kind","itemId","versionId","title","number"],
                "additionalProperties":false,
                "properties":{
                  "kind":{"type":"string","enum":["KNOWLEDGE","PROJECT","FACT"]},
                  "itemId":{
                    "type":"string","minLength":36,"maxLength":36,
                    "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
                  },
                  "versionId":{
                    "type":"string","minLength":36,"maxLength":36,
                    "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
                  },
                  "title":{"type":"string"},
                  "number":{"type":"integer","minimum":1,"maximum":2147483647}
                }
              }
            }
          }
        }
      }
    }
  }
}';

-- 正常数据不产生守卫行；异常故意触发 NOT NULL／标识长度 CHECK，不能静默截断或略过。
INSERT INTO training_question_knowledge_snapshot
    (owner_id,session_id,question_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,q.session_id,q.id,NULL,NULL
FROM training_question q
WHERE COALESCE(JSON_SCHEMA_VALID(@wenjie_v62_question_schema,q.snapshot_json),0) <> 1;

-- 无题库身份的追问／回忆重放必须空知识；有身份的完整题面须等于其确切题库版本。
-- LEFT JOIN 只用于发现异常，缺失或跨 owner 不能通过内联结从检查集合中消失。
INSERT INTO training_question_knowledge_snapshot
    (owner_id,session_id,question_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,q.session_id,q.id,NULL,NULL
FROM training_question q
LEFT JOIN question_version v
    ON v.owner_id=q.owner_id AND v.question_id=q.source_question_id AND v.id=q.source_version_id
WHERE JSON_EXTRACT(q.snapshot_json,'$.title') <> CAST(JSON_QUOTE(q.title) AS JSON)
   OR JSON_EXTRACT(q.snapshot_json,'$.content.title') <> CAST(JSON_QUOTE(q.title) AS JSON)
   OR JSON_EXTRACT(q.snapshot_json,'$.stage') <> CAST(JSON_QUOTE(q.stage) AS JSON)
   OR (q.source_question_id IS NULL AND (
       q.source_version_id IS NOT NULL
       OR JSON_EXTRACT(q.snapshot_json,'$.sourceQuestionId') <> CAST('null' AS JSON)
       OR JSON_EXTRACT(q.snapshot_json,'$.sourceVersionId') <> CAST('null' AS JSON)
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(q.snapshot_json,'$.number')) AS UNSIGNED) <> 0
       OR JSON_LENGTH(JSON_EXTRACT(q.snapshot_json,'$.content.knowledgeLinks')) <> 0))
   OR (q.source_question_id IS NOT NULL AND (
       v.id IS NULL
       OR BINARY v.title <> BINARY q.title
       OR JSON_EXTRACT(q.snapshot_json,'$.sourceQuestionId') <> CAST(JSON_QUOTE(q.source_question_id) AS JSON)
       OR JSON_EXTRACT(q.snapshot_json,'$.sourceVersionId') <> CAST(JSON_QUOTE(q.source_version_id) AS JSON)
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(q.snapshot_json,'$.number')) AS UNSIGNED) <> v.version_number
       OR JSON_EXTRACT(q.snapshot_json,'$.content') <> CAST(v.prompt_json AS JSON)));

-- 逐位置、逐确切身份和总数核对题库绑定；既不能只信 JSON，也不能把绑定表当作题面已核验的替代。
INSERT INTO training_question_knowledge_snapshot
    (owner_id,session_id,question_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,q.session_id,q.id,NULL,NULL
FROM training_question q
WHERE q.source_question_id IS NOT NULL AND (
    JSON_LENGTH(JSON_EXTRACT(q.snapshot_json,'$.content.knowledgeLinks')) <> (
        SELECT COUNT(*) FROM question_knowledge_reference r
        WHERE r.owner_id=q.owner_id AND r.question_id=q.source_question_id AND r.version_id=q.source_version_id)
    OR EXISTS (
        SELECT 1
        FROM JSON_TABLE(q.snapshot_json,'$.content.knowledgeLinks[*]' COLUMNS (
            ordinal FOR ORDINALITY,
            item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
            version_id VARCHAR(36) PATH '$.versionId' ERROR ON EMPTY ERROR ON ERROR
        )) AS source_identity
        LEFT JOIN question_knowledge_reference r
            ON r.owner_id=q.owner_id AND r.question_id=q.source_question_id AND r.version_id=q.source_version_id
            AND r.position_index=source_identity.ordinal-1
            AND BINARY r.knowledge_item_id=BINARY source_identity.item_id
            AND BINARY r.knowledge_version_id=BINARY source_identity.version_id
        WHERE r.version_id IS NULL
    )
);

INSERT INTO training_draft_knowledge_reference
    (owner_id,session_id,question_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,q.session_id,q.id,NULL,NULL
FROM training_question q
WHERE q.draft_json IS NOT NULL
  AND COALESCE(JSON_SCHEMA_VALID(@wenjie_v62_answer_schema,q.draft_json),0) <> 1;

INSERT INTO training_answer_knowledge_reference
    (owner_id,session_id,question_id,answer_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.session_id,a.question_id,a.id,NULL,NULL
FROM training_answer a
WHERE COALESCE(JSON_SCHEMA_VALID(@wenjie_v62_answer_schema,a.content_json),0) <> 1;

-- 同一图的节点身份必须唯一，但多个不同节点可以合法引用同一知识确切版本。
INSERT INTO training_draft_knowledge_reference
    (owner_id,session_id,question_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,q.session_id,q.id,NULL,NULL
FROM training_question q
WHERE q.draft_json IS NOT NULL AND EXISTS (
    SELECT 1
    FROM JSON_TABLE(CASE WHEN q.draft_json IS NULL THEN '{"diagram":{"nodes":[]}}'
        ELSE q.draft_json END,'$.diagram.nodes[*]' COLUMNS (
        node_id VARCHAR(36) PATH '$.id' ERROR ON EMPTY ERROR ON ERROR
    )) AS node_identity
    GROUP BY BINARY node_identity.node_id HAVING COUNT(*) > 1
);

INSERT INTO training_answer_knowledge_reference
    (owner_id,session_id,question_id,answer_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.session_id,a.question_id,a.id,NULL,NULL
FROM training_answer a
WHERE EXISTS (
    SELECT 1
    FROM JSON_TABLE(a.content_json,'$.diagram.nodes[*]' COLUMNS (
        node_id VARCHAR(36) PATH '$.id' ERROR ON EMPTY ERROR ON ERROR
    )) AS node_identity
    GROUP BY BINARY node_identity.node_id HAVING COUNT(*) > 1
);

-- 准备引用不进入知识登记表，仍须先验证同 owner、声明类型及确切版本，避免回填时静默略过异常。
-- 归档对象和历史版本仍是合法身份；保留引用时的标题，不用当前展示值改写历史。
INSERT INTO training_draft_knowledge_reference
    (owner_id,session_id,question_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,q.session_id,q.id,NULL,NULL
FROM training_question q
CROSS JOIN JSON_TABLE(CASE WHEN q.draft_json IS NULL THEN '{"diagram":{"nodes":[]}}'
    ELSE q.draft_json END,'$.diagram.nodes[*]' COLUMNS (
    source_kind VARCHAR(16) PATH '$.reference.kind' NULL ON EMPTY ERROR ON ERROR,
    item_id VARCHAR(36) PATH '$.reference.itemId' NULL ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.reference.versionId' NULL ON EMPTY ERROR ON ERROR
)) AS source_identity
LEFT JOIN preparation_item p
    ON p.owner_id=q.owner_id AND BINARY p.id=BINARY source_identity.item_id
    AND BINARY p.kind=BINARY source_identity.source_kind
LEFT JOIN preparation_version v
    ON v.owner_id=q.owner_id AND BINARY v.item_id=BINARY source_identity.item_id
    AND BINARY v.id=BINARY source_identity.version_id
WHERE q.draft_json IS NOT NULL AND source_identity.source_kind IN ('PROJECT','FACT')
    AND (p.id IS NULL OR v.id IS NULL);

INSERT INTO training_answer_knowledge_reference
    (owner_id,session_id,question_id,answer_id,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.session_id,a.question_id,a.id,NULL,NULL
FROM training_answer a
CROSS JOIN JSON_TABLE(a.content_json,'$.diagram.nodes[*]' COLUMNS (
    source_kind VARCHAR(16) PATH '$.reference.kind' NULL ON EMPTY ERROR ON ERROR,
    item_id VARCHAR(36) PATH '$.reference.itemId' NULL ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.reference.versionId' NULL ON EMPTY ERROR ON ERROR
)) AS source_identity
LEFT JOIN preparation_item p
    ON p.owner_id=a.owner_id AND BINARY p.id=BINARY source_identity.item_id
    AND BINARY p.kind=BINARY source_identity.source_kind
LEFT JOIN preparation_version v
    ON v.owner_id=a.owner_id AND BINARY v.item_id=BINARY source_identity.item_id
    AND BINARY v.id=BINARY source_identity.version_id
WHERE source_identity.source_kind IN ('PROJECT','FACT')
    AND (p.id IS NULL OR v.id IS NULL);

-- 全部状态、全部答案修订均回填；不联结当前知识过滤，缺失／跨 owner 由复合 FK 阻止。
INSERT INTO training_question_knowledge_snapshot
    (owner_id,session_id,question_id,knowledge_item_id,knowledge_version_id)
SELECT q.owner_id,q.session_id,q.id,source_identity.item_id,source_identity.version_id
FROM training_question q
CROSS JOIN JSON_TABLE(q.snapshot_json,'$.content.knowledgeLinks[*]' COLUMNS (
    item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.versionId' ERROR ON EMPTY ERROR ON ERROR
)) AS source_identity;

INSERT INTO training_draft_knowledge_reference
    (owner_id,session_id,question_id,knowledge_item_id,knowledge_version_id)
SELECT DISTINCT q.owner_id,q.session_id,q.id,source_identity.item_id,source_identity.version_id
FROM training_question q
CROSS JOIN JSON_TABLE(CASE WHEN q.draft_json IS NULL THEN '{"diagram":{"nodes":[]}}'
    ELSE q.draft_json END,'$.diagram.nodes[*]' COLUMNS (
    source_kind VARCHAR(16) PATH '$.reference.kind' NULL ON EMPTY ERROR ON ERROR,
    item_id VARCHAR(36) PATH '$.reference.itemId' NULL ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.reference.versionId' NULL ON EMPTY ERROR ON ERROR
)) AS source_identity
WHERE q.draft_json IS NOT NULL AND source_identity.source_kind='KNOWLEDGE';

INSERT INTO training_answer_knowledge_reference
    (owner_id,session_id,question_id,answer_id,knowledge_item_id,knowledge_version_id)
SELECT DISTINCT a.owner_id,a.session_id,a.question_id,a.id,source_identity.item_id,source_identity.version_id
FROM training_answer a
CROSS JOIN JSON_TABLE(a.content_json,'$.diagram.nodes[*]' COLUMNS (
    source_kind VARCHAR(16) PATH '$.reference.kind' NULL ON EMPTY ERROR ON ERROR,
    item_id VARCHAR(36) PATH '$.reference.itemId' NULL ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.reference.versionId' NULL ON EMPTY ERROR ON ERROR
)) AS source_identity
WHERE source_identity.source_kind='KNOWLEDGE';

SET @wenjie_v62_question_schema = NULL;
SET @wenjie_v62_answer_schema = NULL;
