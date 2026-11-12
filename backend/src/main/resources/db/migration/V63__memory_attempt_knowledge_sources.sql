-- 仅在服务器维护窗口暂停相关写入后执行；登记全部状态的固定尝试来源，不复制正文、不改写原快照与历史。
-- MySQL DDL 不随后续失败回滚。失败时保留原始异常，核查已建表、登记与迁移记录后按服务器恢复流程处理，不跳过守卫继续发布。
CREATE TABLE memory_attempt_knowledge_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,attempt_id,ordinal),
    UNIQUE KEY uk_memory_attempt_knowledge_source_pair (owner_id,attempt_id,knowledge_item_id,knowledge_version_id),
    KEY ix_memory_attempt_knowledge_source_version (owner_id,knowledge_item_id,knowledge_version_id,attempt_id),
    CONSTRAINT fk_memory_attempt_knowledge_source_attempt FOREIGN KEY (owner_id,card_id,attempt_id)
        REFERENCES memory_recall_attempt (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_attempt_knowledge_source_version FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_attempt_knowledge_source_ordinal CHECK (ordinal BETWEEN 0 AND 9)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 异常行故意触发 NOT NULL／ordinal CHECK 中止迁移；先验真实 JSON 类型，不能让 JSON_TABLE 截断或强制转换掩盖异常。
-- schedule/admission 只要求完整 start 跨副本一致，不在此重算算法、预算或当前资格。
INSERT INTO memory_attempt_knowledge_source
    (owner_id,card_id,attempt_id,ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.card_id,a.id,-1,NULL,NULL
FROM memory_recall_attempt a
WHERE COALESCE(JSON_SCHEMA_VALID('{
  "type":"object","additionalProperties":false,
  "required":["cardVersionId","cardVersionNumber","cardRevision","gateRevision","mode","countRegular","cramBatchId","zoneId","schedule","sources","admission"],
  "properties":{
    "cardVersionId":{
      "type":"string","minLength":36,"maxLength":36,
      "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    },
    "cardVersionNumber":{"type":"integer","minimum":1,"maximum":1000},
    "cardRevision":{"type":"integer","minimum":0,"maximum":1000},
    "gateRevision":{"type":"integer","minimum":0,"maximum":9007199254740991},
    "mode":{"type":"string","enum":["REGULAR","CRAM"]},
    "countRegular":{"type":"boolean"},
    "cramBatchId":{
      "type":["string","null"],"minLength":36,"maxLength":36,
      "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    },
    "zoneId":{"type":"string","minLength":1,"maxLength":100},
    "schedule":{"type":["object","null"]},
    "admission":{"type":["object","null"]},
    "sources":{
      "type":"array","minItems":1,"maxItems":10,
      "items":{
        "type":"object","additionalProperties":false,
        "required":["itemId","versionId","kind","versionNumber","state","currentVersionId","bodyChanged"],
        "properties":{
          "itemId":{
            "type":"string","minLength":36,"maxLength":36,
            "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
          },
          "versionId":{
            "type":"string","minLength":36,"maxLength":36,
            "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
          },
          "kind":{"type":"string","enum":["NOTE","CONCEPT"]},
          "versionNumber":{"type":"integer","minimum":1,"maximum":2147483647},
          "state":{"type":"string","enum":["CURRENT","SUPERSEDED","ARCHIVED"]},
          "currentVersionId":{
            "type":"string","minLength":36,"maxLength":36,
            "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
          },
          "bodyChanged":{"type":"boolean","enum":[false]}
        }
      }
    }
  }
}',a.start_snapshot_json),0) <> 1
  OR COALESCE(JSON_TYPE(JSON_EXTRACT(a.start_snapshot_json,'$.cardVersionNumber'))='INTEGER'
    AND JSON_TYPE(JSON_EXTRACT(a.start_snapshot_json,'$.cardRevision'))='INTEGER'
    AND JSON_TYPE(JSON_EXTRACT(a.start_snapshot_json,'$.gateRevision'))='INTEGER',0) <> 1;

-- 只校验完整尝试的身份投影；回答、揭示与评分正文不参与来源登记。
INSERT INTO memory_attempt_knowledge_source
    (owner_id,card_id,attempt_id,ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.card_id,a.id,-1,NULL,NULL
FROM memory_recall_attempt a
WHERE COALESCE(JSON_SCHEMA_VALID('{
  "type":"object","required":["id","cardId","sequence","startedAt","start"],
  "properties":{
    "id":{
      "type":"string","minLength":36,"maxLength":36,
      "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    },
    "cardId":{
      "type":"string","minLength":36,"maxLength":36,
      "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    },
    "sequence":{"type":"integer","minimum":1,"maximum":9007199254740991},
    "startedAt":{
      "type":"string",
      "pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$"
    },
    "start":{"type":"object"}
  }
}',a.snapshot_json),0) <> 1
  OR COALESCE(JSON_TYPE(JSON_EXTRACT(a.snapshot_json,'$.sequence'))='INTEGER',0) <> 1;

-- LEFT JOIN 保留缺失 START 的尝试；既有唯一键 (owner_id,attempt_id,event_kind) 与存在性检查共同保证唯一 START。
INSERT INTO memory_attempt_knowledge_source
    (owner_id,card_id,attempt_id,ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.card_id,a.id,-1,NULL,NULL
FROM memory_recall_attempt a
LEFT JOIN memory_attempt_event e
  ON e.owner_id=a.owner_id AND e.card_id=a.card_id AND e.attempt_id=a.id AND e.event_kind='START'
WHERE e.id IS NULL OR COALESCE(JSON_SCHEMA_VALID('{
  "type":"object","additionalProperties":false,
  "required":["id","attemptId","sequence","attemptRevision","kind","fromState","toState","actor","occurredAt","started","revealed","rated","skipped","invalidated"],
  "properties":{
    "id":{
      "type":"string","minLength":36,"maxLength":36,
      "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    },
    "attemptId":{
      "type":"string","minLength":36,"maxLength":36,
      "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    },
    "sequence":{"type":"integer","enum":[1]},
    "attemptRevision":{"type":"integer","enum":[0]},
    "kind":{"type":"string","enum":["START"]},
    "fromState":{"type":"null"},
    "toState":{"type":"string","enum":["THINKING"]},
    "actor":{
      "type":"object","additionalProperties":false,"required":["kind","ownerId"],
      "properties":{
        "kind":{"type":"string","enum":["OWNER"]},
        "ownerId":{
          "type":"string","minLength":36,"maxLength":36,
          "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        }
      }
    },
    "occurredAt":{
      "type":"string",
      "pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$"
    },
    "started":{
      "type":"object","additionalProperties":false,"required":["snapshot"],
      "properties":{"snapshot":{"type":"object"}}
    },
    "revealed":{"type":"null"},"rated":{"type":"null"},"skipped":{"type":"null"},"invalidated":{"type":"null"}
  }
}',e.snapshot_json),0) <> 1
  OR COALESCE(JSON_TYPE(JSON_EXTRACT(e.snapshot_json,'$.sequence'))='INTEGER'
    AND JSON_TYPE(JSON_EXTRACT(e.snapshot_json,'$.attemptRevision'))='INTEGER',0) <> 1;

-- JSON 对象按同键同值、数组按位置比较；整份 start 相等才能证明三个副本的来源内容与顺序均固定。
INSERT INTO memory_attempt_knowledge_source
    (owner_id,card_id,attempt_id,ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.card_id,a.id,-1,NULL,NULL
FROM memory_recall_attempt a
LEFT JOIN memory_card_version v
  ON v.owner_id=a.owner_id AND v.card_id=a.card_id AND v.id=a.card_version_id
LEFT JOIN memory_attempt_event e
  ON e.owner_id=a.owner_id AND e.card_id=a.card_id AND e.attempt_id=a.id AND e.event_kind='START'
WHERE COALESCE(
    v.id IS NOT NULL AND e.id IS NOT NULL
    AND JSON_TYPE(JSON_EXTRACT(a.snapshot_json,'$.start'))='OBJECT'
    AND JSON_TYPE(JSON_EXTRACT(e.snapshot_json,'$.started.snapshot'))='OBJECT'
    AND CAST(a.start_snapshot_json AS JSON)=JSON_EXTRACT(a.snapshot_json,'$.start')
    AND CAST(a.start_snapshot_json AS JSON)=JSON_EXTRACT(e.snapshot_json,'$.started.snapshot')
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_json,'$.id'))=BINARY a.id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_json,'$.cardId'))=BINARY a.card_id
    AND CAST(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_json,'$.sequence')) AS UNSIGNED)=a.sequence_number
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.start_snapshot_json,'$.cardVersionId'))=BINARY a.card_version_id
    AND CAST(JSON_UNQUOTE(JSON_EXTRACT(a.start_snapshot_json,'$.cardVersionNumber')) AS UNSIGNED)=v.version_number
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.start_snapshot_json,'$.mode'))=BINARY a.mode
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.start_snapshot_json,'$.countRegular'))=BINARY IF(a.count_regular=1,'true','false')
    AND ((a.cram_batch_id IS NULL AND JSON_TYPE(JSON_EXTRACT(a.start_snapshot_json,'$.cramBatchId'))='NULL')
      OR (a.cram_batch_id IS NOT NULL
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.start_snapshot_json,'$.cramBatchId'))=BINARY a.cram_batch_id))
    AND e.attempt_sequence=a.sequence_number AND e.sequence_number=1 AND e.attempt_revision=0
    AND e.count_regular=a.count_regular AND e.rate_basis_kind IS NULL AND e.occurred_at=a.started_at
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(e.snapshot_json,'$.id'))=BINARY e.id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(e.snapshot_json,'$.attemptId'))=BINARY a.id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(e.snapshot_json,'$.actor.ownerId'))=BINARY a.owner_id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(e.snapshot_json,'$.occurredAt'))
      =BINARY JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_json,'$.startedAt'))
    AND CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(e.snapshot_json,'$.occurredAt')),'T',' '),'Z','') AS DATETIME(6))=a.started_at
    AND JSON_LENGTH(a.start_snapshot_json,'$.sources')=v.source_count
    AND (SELECT COUNT(*) FROM memory_card_source s
      WHERE s.owner_id=a.owner_id AND s.card_id=a.card_id AND s.version_id=a.card_version_id)=v.source_count
    AND EXISTS (
      SELECT 1 FROM JSON_TABLE(a.start_snapshot_json,'$.sources[*]' COLUMNS (
        source_state VARCHAR(10) PATH '$.state' ERROR ON EMPTY ERROR ON ERROR
      )) AS usable_source
      WHERE usable_source.source_state IN ('CURRENT','SUPERSEDED')
    ),0) <> 1;

-- 同对象不同知识版本允许并存；只拒绝重复的确切 pair，不把 currentVersionId 当成实际来源。
INSERT INTO memory_attempt_knowledge_source
    (owner_id,card_id,attempt_id,ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.card_id,a.id,-1,NULL,NULL
FROM memory_recall_attempt a
WHERE EXISTS (
    SELECT 1 FROM JSON_TABLE(a.start_snapshot_json,'$.sources[*]' COLUMNS (
      item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
      version_id VARCHAR(36) PATH '$.versionId' ERROR ON EMPTY ERROR ON ERROR
    )) AS source_identity
    GROUP BY BINARY source_identity.item_id,BINARY source_identity.version_id
    HAVING COUNT(*) > 1
);

-- 逐位置交核固定卡版的全部来源；只核历史身份与开始时状态，不读取当前正文或重算今天的来源资格。
INSERT INTO memory_attempt_knowledge_source
    (owner_id,card_id,attempt_id,ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.card_id,a.id,-1,NULL,NULL
FROM memory_recall_attempt a
CROSS JOIN JSON_TABLE(a.start_snapshot_json,'$.sources[*]' COLUMNS (
    ordinal FOR ORDINALITY,
    item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.versionId' ERROR ON EMPTY ERROR ON ERROR,
    source_kind VARCHAR(7) PATH '$.kind' ERROR ON EMPTY ERROR ON ERROR,
    version_number INT PATH '$.versionNumber' ERROR ON EMPTY ERROR ON ERROR,
    source_state VARCHAR(10) PATH '$.state' ERROR ON EMPTY ERROR ON ERROR,
    current_version_id VARCHAR(36) PATH '$.currentVersionId' ERROR ON EMPTY ERROR ON ERROR
)) AS source_identity
LEFT JOIN memory_card_source s
  ON s.owner_id=a.owner_id AND s.card_id=a.card_id AND s.version_id=a.card_version_id
    AND s.position_index=source_identity.ordinal-1
    AND s.knowledge_item_id=BINARY source_identity.item_id AND s.knowledge_version_id=BINARY source_identity.version_id
LEFT JOIN knowledge_item k
  ON k.owner_id=a.owner_id AND k.id=BINARY source_identity.item_id
LEFT JOIN knowledge_version v
  ON v.owner_id=a.owner_id AND v.item_id=BINARY source_identity.item_id AND v.id=BINARY source_identity.version_id
LEFT JOIN knowledge_version observed
  ON observed.owner_id=a.owner_id AND observed.item_id=BINARY source_identity.item_id AND observed.id=BINARY source_identity.current_version_id
WHERE COALESCE(
    s.knowledge_item_id IS NOT NULL AND k.id IS NOT NULL AND v.id IS NOT NULL AND observed.id IS NOT NULL
    AND BINARY k.kind=BINARY source_identity.source_kind AND v.version_number=source_identity.version_number
    AND JSON_TYPE(JSON_EXTRACT(a.start_snapshot_json,CONCAT('$.sources[',source_identity.ordinal-1,'].versionNumber')))='INTEGER'
    AND ((source_identity.source_state='CURRENT' AND BINARY source_identity.version_id=BINARY source_identity.current_version_id)
      OR (source_identity.source_state='SUPERSEDED' AND BINARY source_identity.version_id<>BINARY source_identity.current_version_id)
      OR (source_identity.source_state='ARCHIVED' AND source_identity.source_kind='CONCEPT')),0) <> 1;

-- 前述守卫已证明关系行与三个快照逐项一致，才从历史卡版窄表回填；不按状态过滤，不借当前卡版补过去。
INSERT INTO memory_attempt_knowledge_source
    (owner_id,card_id,attempt_id,ordinal,knowledge_item_id,knowledge_version_id)
SELECT a.owner_id,a.card_id,a.id,s.position_index,s.knowledge_item_id,s.knowledge_version_id
FROM memory_recall_attempt a JOIN memory_card_source s
  ON s.owner_id=a.owner_id AND s.card_id=a.card_id AND s.version_id=a.card_version_id;

-- 任何遗漏均阻止发布，不能将关联查询漏行当作“没有来源”。
INSERT INTO memory_attempt_knowledge_source
    (owner_id,card_id,attempt_id,ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,a.card_id,a.id,-1,NULL,NULL
FROM memory_recall_attempt a
WHERE COALESCE((SELECT COUNT(*) FROM memory_attempt_knowledge_source s WHERE s.owner_id=a.owner_id AND s.attempt_id=a.id)
    =JSON_LENGTH(a.start_snapshot_json,'$.sources'),0) <> 1;
