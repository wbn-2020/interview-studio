-- 仅在服务器维护窗口暂停相关写入后执行；只登记历史显式知识身份，不读正文、不改写历史 JSON。
-- MySQL DDL 不随后续失败回滚。失败时保留原始异常，核查已建表、已登记行及迁移记录后按服务器恢复流程处理；
-- 不盲目重跑、不跳过守卫、不把部分登记发布为完整目录。
CREATE TABLE memory_source_history_knowledge_reference (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    history_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_ordinal INT NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,history_id,role,source_ordinal),
    KEY ix_memory_history_knowledge_version (owner_id,knowledge_item_id,knowledge_version_id,role,history_id),
    CONSTRAINT fk_memory_history_knowledge_history FOREIGN KEY (owner_id,card_id,history_id)
        REFERENCES memory_source_history (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_history_knowledge_version FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_history_knowledge_role CHECK (role IN
        ('REVIEW_DEPENDENCY','REVIEW_OBSERVED_CURRENT','IMPACT_DEPENDENCY','IMPACT_PREVIOUS','IMPACT_CURRENT')),
    CONSTRAINT ck_memory_history_knowledge_ordinal CHECK (source_ordinal BETWEEN 0 AND 9
        AND (role NOT IN ('IMPACT_PREVIOUS','IMPACT_CURRENT') OR source_ordinal=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 异常行故意触发 NOT NULL／ordinal CHECK 中止；先验真实 JSON 类型，避免 JSON_TABLE 截断、强转或漏行。
INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'REVIEW_DEPENDENCY',-1,NULL,NULL
FROM memory_source_history h
WHERE COALESCE(JSON_SCHEMA_VALID('{
  "type":"object","additionalProperties":false,"required":["sequence","kind","impact","decision"],
  "properties":{
    "sequence":{"type":"integer","minimum":1,"maximum":9007199254740991},
    "kind":{"type":"string","enum":["IMPACT","SOURCE_REVIEW"]},
    "impact":{"type":["object","null"]},
    "decision":{"type":["object","null"]}
  }
}',h.snapshot_json),0) <> 1
  OR COALESCE(JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sequence'))='INTEGER'
    AND CAST(JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.sequence')) AS UNSIGNED)=h.sequence_number
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.kind'))=BINARY h.history_kind
    AND ((h.history_kind='SOURCE_REVIEW' AND h.impact_reason IS NULL
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.impact'))='NULL'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.decision'))='OBJECT')
      OR (h.history_kind='IMPACT' AND h.impact_reason IS NOT NULL
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.impact'))='OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.decision'))='NULL')),0) <> 1;

INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'REVIEW_DEPENDENCY',-1,NULL,NULL
FROM memory_source_history h
WHERE h.history_kind='SOURCE_REVIEW' AND COALESCE(JSON_SCHEMA_VALID('{
  "type":"object","additionalProperties":false,
  "required":["id","cardId","cardVersionId","cardRevision","reviewedGateRevision","resultingGateRevision","sources","reason","actor","createdAt"],
  "properties":{
    "id":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
    "cardId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
    "cardVersionId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
    "cardRevision":{"type":"integer","minimum":0,"maximum":1000},
    "reviewedGateRevision":{"type":"integer","minimum":0,"maximum":9007199254740991},
    "resultingGateRevision":{"type":"integer","minimum":1,"maximum":9007199254740991},
    "sources":{
      "type":"array","minItems":1,"maxItems":10,
      "items":{
        "type":"object","additionalProperties":false,
        "required":["itemId","versionId","kind","versionNumber","state","currentVersionId","bodyChanged"],
        "properties":{
          "itemId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
          "versionId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
          "kind":{"type":"string","enum":["NOTE","CONCEPT"]},
          "versionNumber":{"type":"integer","minimum":1,"maximum":2147483647},
          "state":{"type":"string","enum":["CURRENT","SUPERSEDED","ARCHIVED"]},
          "currentVersionId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
          "bodyChanged":{"type":"boolean","enum":[false]}
        }
      }
    },
    "reason":{"type":"string","minLength":1,"maxLength":1000},
    "actor":{
      "type":"object","additionalProperties":false,"required":["kind","ownerId"],
      "properties":{
        "kind":{"type":"string","enum":["OWNER"]},
        "ownerId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"}
      }
    },
    "createdAt":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$"}
  }
}',JSON_EXTRACT(h.snapshot_json,'$.decision')),0) <> 1;

INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'IMPACT_DEPENDENCY',-1,NULL,NULL
FROM memory_source_history h
WHERE h.history_kind='IMPACT' AND COALESCE(JSON_SCHEMA_VALID('{
  "type":"object","additionalProperties":false,
  "required":["id","cardId","cardVersionId","gateRevision","reason","source","reviewRequired","actor","createdAt"],
  "properties":{
    "id":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
    "cardId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
    "cardVersionId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
    "gateRevision":{"type":"integer","minimum":0,"maximum":9007199254740991},
    "reason":{"type":"string","enum":["BASELINE_REVIEW_REQUIRED","SOURCE_BODY_CHANGED","SOURCE_ARCHIVED","SOURCE_RESTORED","CARD_BASIS_CHANGED","CARD_ARCHIVED","CARD_RESTORED"]},
    "source":{
      "type":["object","null"],"additionalProperties":false,
      "required":["itemId","kind","referencedVersionIds","previousVersionId","currentVersionId","previousStatus","currentStatus"],
      "properties":{
        "itemId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
        "kind":{"type":"string","enum":["NOTE","CONCEPT"]},
        "referencedVersionIds":{
          "type":"array","minItems":1,"maxItems":10,"uniqueItems":true,
          "items":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"}
        },
        "previousVersionId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
        "currentVersionId":{"type":"string","minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
        "previousStatus":{"type":"string","enum":["ACTIVE","ARCHIVED"]},
        "currentStatus":{"type":"string","enum":["ACTIVE","ARCHIVED"]}
      }
    },
    "reviewRequired":{"type":"boolean"},
    "actor":{
      "type":"object","additionalProperties":false,"required":["kind","ownerId"],
      "properties":{
        "kind":{"type":"string","enum":["OWNER","SYSTEM"]},
        "ownerId":{"type":["string","null"],"minLength":36,"maxLength":36,"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"}
      }
    },
    "createdAt":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$"}
  }
}',JSON_EXTRACT(h.snapshot_json,'$.impact')),0) <> 1;

-- 快照身份、时间和 gate/sequence 与结构列逐项交核；不拿卡片今天的修订或来源状态解释过去。
INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'REVIEW_DEPENDENCY',-1,NULL,NULL
FROM (
    SELECT history.*,JSON_EXTRACT(snapshot_json,
      IF(history_kind='SOURCE_REVIEW','$.decision','$.impact')) AS detail
    FROM memory_source_history history
) h
WHERE COALESCE(
    BINARY JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.id'))=BINARY h.id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.cardId'))=BINARY h.card_id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.cardVersionId'))=BINARY h.card_version_id
    AND CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.createdAt')),'T',' '),'Z','') AS DATETIME(6))=h.created_at
    AND h.sequence_number>=h.gate_revision AND h.sequence_number-h.gate_revision<=1
    AND ((h.history_kind='SOURCE_REVIEW'
        AND JSON_TYPE(JSON_EXTRACT(h.detail,'$.cardRevision'))='INTEGER'
        AND JSON_TYPE(JSON_EXTRACT(h.detail,'$.reviewedGateRevision'))='INTEGER'
        AND JSON_TYPE(JSON_EXTRACT(h.detail,'$.resultingGateRevision'))='INTEGER'
        AND CAST(JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.resultingGateRevision')) AS UNSIGNED)=h.gate_revision
        AND CAST(JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.resultingGateRevision')) AS UNSIGNED)
          =CAST(JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.reviewedGateRevision')) AS UNSIGNED)+1
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.actor.ownerId'))=BINARY h.owner_id)
      OR (h.history_kind='IMPACT'
        AND JSON_TYPE(JSON_EXTRACT(h.detail,'$.gateRevision'))='INTEGER'
        AND CAST(JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.gateRevision')) AS UNSIGNED)=h.gate_revision
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.reason'))=BINARY h.impact_reason
        AND ((h.impact_reason='BASELINE_REVIEW_REQUIRED'
            AND h.sequence_number=1 AND h.gate_revision=0 AND h.id=h.card_id
            AND JSON_EXTRACT(h.detail,'$.reviewRequired')=CAST('true' AS JSON)
            AND JSON_TYPE(JSON_EXTRACT(h.detail,'$.source'))='NULL'
            AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.actor.kind'))=BINARY 'SYSTEM'
            AND JSON_TYPE(JSON_EXTRACT(h.detail,'$.actor.ownerId'))='NULL')
          OR (h.impact_reason<>'BASELINE_REVIEW_REQUIRED' AND h.gate_revision>=1
            AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.actor.kind'))=BINARY 'OWNER'
            AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.detail,'$.actor.ownerId'))=BINARY h.owner_id
            AND ((h.impact_reason IN ('CARD_BASIS_CHANGED','CARD_ARCHIVED','CARD_RESTORED')
                AND JSON_TYPE(JSON_EXTRACT(h.detail,'$.source'))='NULL'
                AND (h.impact_reason<>'CARD_BASIS_CHANGED' OR JSON_EXTRACT(h.detail,'$.reviewRequired')=CAST('true' AS JSON)))
              OR (h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED')
                AND JSON_TYPE(JSON_EXTRACT(h.detail,'$.source'))='OBJECT')))))),0) <> 1;

-- 成功复核可保留正文相同的旧版、归档概念；至少一个当时可用来源，不能要求全部 CURRENT。
INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'REVIEW_DEPENDENCY',-1,NULL,NULL
FROM memory_source_history h
LEFT JOIN memory_card_version c ON c.owner_id=h.owner_id AND c.card_id=h.card_id AND c.id=h.card_version_id
WHERE h.history_kind='SOURCE_REVIEW' AND COALESCE(
    c.id IS NOT NULL AND JSON_LENGTH(h.snapshot_json,'$.decision.sources')=c.source_count
    AND (SELECT COUNT(*) FROM memory_card_source s
      WHERE s.owner_id=h.owner_id AND s.card_id=h.card_id AND s.version_id=h.card_version_id)=c.source_count
    AND EXISTS (
      SELECT 1 FROM JSON_TABLE(h.snapshot_json,'$.decision.sources[*]' COLUMNS (
        source_state VARCHAR(10) PATH '$.state' ERROR ON EMPTY ERROR ON ERROR
      )) AS usable_source
      WHERE usable_source.source_state IN ('CURRENT','SUPERSEDED')
    )
    AND NOT EXISTS (
      SELECT 1 FROM JSON_TABLE(h.snapshot_json,'$.decision.sources[*]' COLUMNS (
        item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
        version_id VARCHAR(36) PATH '$.versionId' ERROR ON EMPTY ERROR ON ERROR
      )) AS source_identity
      GROUP BY BINARY source_identity.item_id,BINARY source_identity.version_id HAVING COUNT(*)>1
    ),0) <> 1;

-- 逐位置核对历史卡版的实际来源；当前观察版只核同 owner、同 item 的确切版本存在，不比今天的当前指针。
INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'REVIEW_DEPENDENCY',-1,NULL,NULL
FROM memory_source_history h
CROSS JOIN JSON_TABLE(h.snapshot_json,'$.decision.sources[*]' COLUMNS (
    source_ordinal FOR ORDINALITY,
    item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.versionId' ERROR ON EMPTY ERROR ON ERROR,
    source_kind VARCHAR(7) PATH '$.kind' ERROR ON EMPTY ERROR ON ERROR,
    version_number BIGINT PATH '$.versionNumber' ERROR ON EMPTY ERROR ON ERROR,
    source_state VARCHAR(10) PATH '$.state' ERROR ON EMPTY ERROR ON ERROR,
    current_version_id VARCHAR(36) PATH '$.currentVersionId' ERROR ON EMPTY ERROR ON ERROR
)) AS source_identity
LEFT JOIN memory_card_source s
  ON s.owner_id=h.owner_id AND s.card_id=h.card_id AND s.version_id=h.card_version_id
    AND s.position_index=source_identity.source_ordinal-1
    AND s.knowledge_item_id=BINARY source_identity.item_id AND s.knowledge_version_id=BINARY source_identity.version_id
LEFT JOIN knowledge_item k ON k.owner_id=h.owner_id AND k.id=BINARY source_identity.item_id
LEFT JOIN knowledge_version v
  ON v.owner_id=h.owner_id AND v.item_id=BINARY source_identity.item_id AND v.id=BINARY source_identity.version_id
LEFT JOIN knowledge_version observed
  ON observed.owner_id=h.owner_id AND observed.item_id=BINARY source_identity.item_id AND observed.id=BINARY source_identity.current_version_id
WHERE h.history_kind='SOURCE_REVIEW' AND COALESCE(
    s.knowledge_item_id IS NOT NULL AND k.id IS NOT NULL AND v.id IS NOT NULL AND observed.id IS NOT NULL
    AND BINARY k.kind=BINARY source_identity.source_kind AND v.version_number=source_identity.version_number
    AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,CONCAT('$.decision.sources[',source_identity.source_ordinal-1,'].versionNumber')))='INTEGER'
    AND ((source_identity.source_state='CURRENT' AND BINARY source_identity.version_id=BINARY source_identity.current_version_id)
      OR (source_identity.source_state='SUPERSEDED' AND BINARY source_identity.version_id<>BINARY source_identity.current_version_id)
      OR (source_identity.source_state='ARCHIVED' AND source_identity.source_kind='CONCEPT')),0) <> 1;

-- 影响的观察前后版不是卡片依赖；只验证保存的原因／状态及确切版本身份，归档／恢复不要求前后指针不同。
INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'IMPACT_DEPENDENCY',-1,NULL,NULL
FROM memory_source_history h
LEFT JOIN knowledge_item k
  ON k.owner_id=h.owner_id AND k.id=BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.itemId'))
LEFT JOIN knowledge_version previous_version
  ON previous_version.owner_id=h.owner_id AND previous_version.item_id=k.id
    AND previous_version.id=BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.previousVersionId'))
LEFT JOIN knowledge_version current_version
  ON current_version.owner_id=h.owner_id AND current_version.item_id=k.id
    AND current_version.id=BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.currentVersionId'))
WHERE h.history_kind='IMPACT' AND h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED')
  AND COALESCE(
    k.id IS NOT NULL AND previous_version.id IS NOT NULL AND current_version.id IS NOT NULL
    AND BINARY k.kind=BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.kind'))
    AND JSON_LENGTH(h.snapshot_json,'$.impact.source.referencedVersionIds')=(
      SELECT COUNT(*) FROM memory_card_source s
      WHERE s.owner_id=h.owner_id AND s.card_id=h.card_id AND s.version_id=h.card_version_id AND s.knowledge_item_id=k.id)
    AND ((h.impact_reason='SOURCE_BODY_CHANGED'
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.previousStatus'))=BINARY 'ACTIVE'
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.currentStatus'))=BINARY 'ACTIVE'
        AND previous_version.id<>current_version.id
        AND JSON_EXTRACT(h.snapshot_json,'$.impact.reviewRequired')=CAST('true' AS JSON))
      OR (h.impact_reason='SOURCE_ARCHIVED'
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.previousStatus'))=BINARY 'ACTIVE'
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.currentStatus'))=BINARY 'ARCHIVED'
        AND (k.kind<>'NOTE' OR JSON_EXTRACT(h.snapshot_json,'$.impact.reviewRequired')=CAST('true' AS JSON)))
      OR (h.impact_reason='SOURCE_RESTORED'
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.previousStatus'))=BINARY 'ARCHIVED'
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.currentStatus'))=BINARY 'ACTIVE')),0) <> 1;

-- referencedVersionIds 必须是固定卡版对同一 item 的全部确切引用集合；不要求包含观察前后版。
INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'IMPACT_DEPENDENCY',-1,NULL,NULL
FROM memory_source_history h
CROSS JOIN JSON_TABLE(h.snapshot_json,'$.impact.source.referencedVersionIds[*]' COLUMNS (
    source_ordinal FOR ORDINALITY,
    version_id VARCHAR(36) PATH '$' ERROR ON EMPTY ERROR ON ERROR
)) AS source_identity
LEFT JOIN memory_card_source s
  ON s.owner_id=h.owner_id AND s.card_id=h.card_id AND s.version_id=h.card_version_id
    AND s.knowledge_item_id=BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.itemId'))
    AND s.knowledge_version_id=BINARY source_identity.version_id
LEFT JOIN knowledge_version v
  ON v.owner_id=h.owner_id AND v.item_id=s.knowledge_item_id AND v.id=BINARY source_identity.version_id
WHERE h.history_kind='IMPACT' AND h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED')
  AND (s.knowledge_item_id IS NULL OR v.id IS NULL);

-- 五角色均从同一历史 JSON 逐位置登记。观察当前版允许重复 pair；前后观察相同仍分别保留。
INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT h.owner_id,h.card_id,h.id,'REVIEW_DEPENDENCY',source_identity.source_ordinal-1,source_identity.item_id,source_identity.version_id
FROM memory_source_history h
CROSS JOIN JSON_TABLE(h.snapshot_json,'$.decision.sources[*]' COLUMNS (
    source_ordinal FOR ORDINALITY,
    item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.versionId' ERROR ON EMPTY ERROR ON ERROR
)) AS source_identity
WHERE h.history_kind='SOURCE_REVIEW'
UNION ALL
SELECT h.owner_id,h.card_id,h.id,'REVIEW_OBSERVED_CURRENT',source_identity.source_ordinal-1,source_identity.item_id,source_identity.version_id
FROM memory_source_history h
CROSS JOIN JSON_TABLE(h.snapshot_json,'$.decision.sources[*]' COLUMNS (
    source_ordinal FOR ORDINALITY,
    item_id VARCHAR(36) PATH '$.itemId' ERROR ON EMPTY ERROR ON ERROR,
    version_id VARCHAR(36) PATH '$.currentVersionId' ERROR ON EMPTY ERROR ON ERROR
)) AS source_identity
WHERE h.history_kind='SOURCE_REVIEW'
UNION ALL
SELECT h.owner_id,h.card_id,h.id,'IMPACT_DEPENDENCY',source_identity.source_ordinal-1,
    JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.itemId')),source_identity.version_id
FROM memory_source_history h
CROSS JOIN JSON_TABLE(h.snapshot_json,'$.impact.source.referencedVersionIds[*]' COLUMNS (
    source_ordinal FOR ORDINALITY,
    version_id VARCHAR(36) PATH '$' ERROR ON EMPTY ERROR ON ERROR
)) AS source_identity
WHERE h.history_kind='IMPACT' AND h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED')
UNION ALL
SELECT h.owner_id,h.card_id,h.id,'IMPACT_PREVIOUS',0,
    JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.itemId')),
    JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.previousVersionId'))
FROM memory_source_history h
WHERE h.history_kind='IMPACT' AND h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED')
UNION ALL
SELECT h.owner_id,h.card_id,h.id,'IMPACT_CURRENT',0,
    JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.itemId')),
    JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.impact.source.currentVersionId'))
FROM memory_source_history h
WHERE h.history_kind='IMPACT' AND h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED');

-- 每条历史逐角色核完整数；没有显式知识身份的系统基线与卡片影响必须保持零行。
INSERT INTO memory_source_history_knowledge_reference
    (owner_id,card_id,history_id,role,source_ordinal,knowledge_item_id,knowledge_version_id)
SELECT NULL,h.card_id,h.id,'REVIEW_DEPENDENCY',-1,NULL,NULL
FROM memory_source_history h
LEFT JOIN (
    SELECT owner_id,history_id,
      SUM(role='REVIEW_DEPENDENCY') AS review_dependencies,
      SUM(role='REVIEW_OBSERVED_CURRENT') AS review_observations,
      SUM(role='IMPACT_DEPENDENCY') AS impact_dependencies,
      SUM(role='IMPACT_PREVIOUS') AS impact_previous,
      SUM(role='IMPACT_CURRENT') AS impact_current
    FROM memory_source_history_knowledge_reference
    GROUP BY owner_id,history_id
) registered ON registered.owner_id=h.owner_id AND registered.history_id=h.id
WHERE COALESCE(
    COALESCE(registered.review_dependencies,0)
      =IF(h.history_kind='SOURCE_REVIEW',JSON_LENGTH(h.snapshot_json,'$.decision.sources'),0)
    AND COALESCE(registered.review_observations,0)
      =IF(h.history_kind='SOURCE_REVIEW',JSON_LENGTH(h.snapshot_json,'$.decision.sources'),0)
    AND COALESCE(registered.impact_dependencies,0)
      =IF(h.history_kind='IMPACT' AND h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED'),
        JSON_LENGTH(h.snapshot_json,'$.impact.source.referencedVersionIds'),0)
    AND COALESCE(registered.impact_previous,0)
      =IF(h.history_kind='IMPACT' AND h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED'),1,0)
    AND COALESCE(registered.impact_current,0)
      =IF(h.history_kind='IMPACT' AND h.impact_reason IN ('SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED'),1,0),0) <> 1;
