-- 仅在服务器维护窗口暂停安排写入后执行；历史目录提取状态窄列，原快照及当前安排均不改写。
-- MySQL DDL 不随后续失败回滚：失败时 visibility 可能已存在且可空，或已回填但尚未收紧。
-- 须保留原始异常，核查列、CHECK、数据与迁移记录后按服务器恢复流程处理，不跳过守卫或直接重跑发布。
ALTER TABLE today_placement_history
    ADD COLUMN visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL;

SET @wenjie_v64_history_schema = '{
  "type":"object","additionalProperties":false,
  "required":["change","snapshot","sourceStamp","reason","createdAt"],
  "properties":{
    "change":{"type":"string","enum":["CREATED","UPDATED","ORDERED","SUGGESTION_APPLIED","INTERVIEW_RESCHEDULED"]},
    "reason":{"type":["string","null"]},
    "createdAt":{
      "type":"string",
      "pattern":"^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$"
    },
    "snapshot":{
      "type":"object","additionalProperties":false,
      "required":["id","source","revision","plannedDate","planZoneId","estimatedMinutes","priority","orderValue","dateLocked","visibility","suppressedOn","createdAt","updatedAt"],
      "properties":{
        "id":{
          "type":"string","minLength":36,"maxLength":36,
          "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        },
        "source":{
          "type":"object","additionalProperties":false,"required":["kind","id"],
          "properties":{
            "kind":{"type":"string","enum":["KNOWLEDGE_DRAFT","TRAINING_SESSION","PREPARATION_TASK","MEMORY_CARD","REVIEW_ACTION","INTERVIEW_EVENT"]},
            "id":{
              "type":"string","minLength":36,"maxLength":36,
              "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            }
          }
        },
        "revision":{"type":"integer","minimum":0,"maximum":9007199254740991},
        "plannedDate":{"type":["string","null"],"pattern":"^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$"},
        "planZoneId":{"type":["string","null"],"minLength":1,"maxLength":100},
        "estimatedMinutes":{"type":["integer","null"],"minimum":1,"maximum":1440},
        "priority":{"type":"integer","minimum":1,"maximum":5},
        "orderValue":{"type":"integer","minimum":0,"maximum":1000000},
        "dateLocked":{"type":"boolean"},
        "visibility":{"type":"string","enum":["VISIBLE","PAUSED"]},
        "suppressedOn":{
          "type":["object","null"],"additionalProperties":false,"required":["localDate","zoneId"],
          "properties":{
            "localDate":{"type":"string","pattern":"^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$"},
            "zoneId":{"type":"string","minLength":1,"maxLength":100}
          }
        },
        "createdAt":{
          "type":"string",
          "pattern":"^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$"
        },
        "updatedAt":{
          "type":"string",
          "pattern":"^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$"
        }
      }
    },
    "sourceStamp":{
      "type":"object","additionalProperties":false,
      "required":["kind","knowledge","training","task","memory","action","interview"],
      "properties":{
        "kind":{"type":"string","enum":["KNOWLEDGE_DRAFT","TRAINING_SESSION","PREPARATION_TASK","MEMORY_CARD","REVIEW_ACTION","INTERVIEW_EVENT"]},
        "knowledge":{
          "type":["object","null"],"additionalProperties":false,
          "required":["itemRevision","draftRevision","currentVersionId"],
          "properties":{
            "itemRevision":{"type":"integer","minimum":0,"maximum":9007199254740991},
            "draftRevision":{"type":"integer","minimum":0,"maximum":9007199254740991},
            "currentVersionId":{
              "type":"string","minLength":36,"maxLength":36,
              "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            }
          }
        },
        "training":{
          "type":["object","null"],"additionalProperties":false,"required":["revision","generation","questionsHash"],
          "properties":{
            "revision":{"type":"integer","minimum":0,"maximum":9007199254740991},
            "generation":{"type":"integer","minimum":0,"maximum":9007199254740991},
            "questionsHash":{"type":"string","pattern":"^[0-9a-f]{64}$"}
          }
        },
        "task":{
          "type":["object","null"],"additionalProperties":false,"required":["revision"],
          "properties":{"revision":{"type":"integer","minimum":0,"maximum":9007199254740991}}
        },
        "memory":{
          "type":["object","null"],"additionalProperties":false,
          "required":["cardRevision","cardVersionId","gateRevision","scheduleRevision","attemptId","attemptRevision","settingsRevision"],
          "properties":{
            "cardRevision":{"type":"integer","minimum":0,"maximum":1000},
            "cardVersionId":{
              "type":"string","minLength":36,"maxLength":36,
              "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            },
            "gateRevision":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991},
            "scheduleRevision":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991},
            "attemptId":{
              "type":["string","null"],"minLength":36,"maxLength":36,
              "pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            },
            "attemptRevision":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991},
            "settingsRevision":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991}
          }
        },
        "action":{
          "type":["object","null"],"additionalProperties":false,"required":["revision","progressHash"],
          "properties":{
            "revision":{"type":"integer","minimum":0,"maximum":1000},
            "progressHash":{"type":"string","pattern":"^[0-9a-f]{64}$"}
          }
        },
        "interview":{
          "type":["object","null"],"additionalProperties":false,"required":["revision"],
          "properties":{"revision":{"type":"integer","minimum":0,"maximum":9007199254740991}}
        }
      }
    }
  }
}';

-- 守卫行故意以 revision=-1 触发既有 CHECK；正常数据不插入任何行，异常不能被联结或类型转换略过。
INSERT INTO today_placement_history (owner_id,placement_id,revision,snapshot_json,visibility,created_at)
SELECT h.owner_id,h.placement_id,-1,'{}','VISIBLE',h.created_at
FROM today_placement_history h
WHERE COALESCE(JSON_SCHEMA_VALID(@wenjie_v64_history_schema,h.snapshot_json),0) <> 1
  OR COALESCE(
    JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.revision'))='INTEGER'
    AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.priority'))='INTEGER'
    AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.orderValue'))='INTEGER'
    AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.estimatedMinutes')) IN ('INTEGER','NULL'),0) <> 1;

-- 不先按 JSON 声称的来源筛选；六类历史都必须与根安排的不可变身份逐条相等。
INSERT INTO today_placement_history (owner_id,placement_id,revision,snapshot_json,visibility,created_at)
SELECT h.owner_id,h.placement_id,-1,'{}','VISIBLE',h.created_at
FROM today_placement_history h
LEFT JOIN today_placement p ON p.owner_id=h.owner_id AND p.id=h.placement_id
WHERE COALESCE(
    p.id IS NOT NULL
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.id'))=BINARY h.placement_id
    AND CAST(JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.revision')) AS UNSIGNED)=h.revision
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.source.kind'))=BINARY p.source_kind
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.source.id'))=BINARY p.source_id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.kind'))=BINARY p.source_kind
    AND ((JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.knowledge'))='OBJECT')
      + (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.training'))='OBJECT')
      + (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.task'))='OBJECT')
      + (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory'))='OBJECT')
      + (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.action'))='OBJECT')
      + (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.interview'))='OBJECT'))=1
    AND CASE p.source_kind
      WHEN 'KNOWLEDGE_DRAFT' THEN JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.knowledge'))='OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.knowledge.itemRevision'))='INTEGER'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.knowledge.draftRevision'))='INTEGER'
      WHEN 'TRAINING_SESSION' THEN JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.training'))='OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.training.revision'))='INTEGER'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.training.generation'))='INTEGER'
      WHEN 'PREPARATION_TASK' THEN JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.task'))='OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.task.revision'))='INTEGER'
      WHEN 'MEMORY_CARD' THEN JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory'))='OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.cardRevision'))='INTEGER'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.gateRevision')) IN ('INTEGER','NULL')
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.scheduleRevision')) IN ('INTEGER','NULL')
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.attemptRevision')) IN ('INTEGER','NULL')
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.settingsRevision')) IN ('INTEGER','NULL')
        AND (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.attemptId'))='NULL')
          =(JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.attemptRevision'))='NULL')
      WHEN 'REVIEW_ACTION' THEN JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.action'))='OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.action.revision'))='INTEGER'
      WHEN 'INTERVIEW_EVENT' THEN JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.interview'))='OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.interview.revision'))='INTEGER'
      ELSE FALSE END
    AND (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.plannedDate'))='NULL')
      =(JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.planZoneId'))='NULL')
    AND (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.plannedDate'))='NULL'
      OR p.source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','REVIEW_ACTION'))
    AND (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.estimatedMinutes'))='NULL'
      OR p.source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','MEMORY_CARD','REVIEW_ACTION'))
    AND (JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.dateLocked'))='false'
      OR p.source_kind='PREPARATION_TASK'
      OR (p.source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','REVIEW_ACTION')
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.plannedDate'))='STRING'))
    AND (JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.visibility'))='VISIBLE'
      OR p.source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','REVIEW_ACTION'))
    AND ((h.revision=0 AND JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.change')) IN ('CREATED','SUGGESTION_APPLIED'))
      OR (h.revision>0 AND JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.change'))<>'CREATED'))
    AND (JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.change'))<>'INTERVIEW_RESCHEDULED'
      OR (p.source_kind='REVIEW_ACTION' AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.reason'))='STRING'))
    AND CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.createdAt')),'T',' '),'Z','') AS DATETIME(6))=p.created_at
    AND CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.updatedAt')),'T',' '),'Z','') AS DATETIME(6))=h.created_at
    AND CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.createdAt')),'T',' '),'Z','') AS DATETIME(6))=h.created_at
    AND h.created_at>=p.created_at
    AND (h.revision<>0 OR h.created_at=p.created_at),0) <> 1;

-- 发布指针是写入时观察值；仅核对同 owner、同知识对象的真实版本，不要求它仍是当前版本。
INSERT INTO today_placement_history (owner_id,placement_id,revision,snapshot_json,visibility,created_at)
SELECT h.owner_id,h.placement_id,-1,'{}','VISIBLE',h.created_at
FROM today_placement_history h
JOIN today_placement p ON p.owner_id=h.owner_id AND p.id=h.placement_id
LEFT JOIN knowledge_item k ON k.owner_id=p.owner_id AND k.id=p.source_id
LEFT JOIN knowledge_version observed
    ON observed.owner_id=p.owner_id AND observed.item_id=p.source_id
    AND BINARY observed.id=BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.knowledge.currentVersionId'))
WHERE p.source_kind='KNOWLEDGE_DRAFT' AND (k.id IS NULL OR observed.id IS NULL);

-- 根安排没有多态来源外键；身份与历史一致后，仍须核对五类实际对象属于同一 owner。
INSERT INTO today_placement_history (owner_id,placement_id,revision,snapshot_json,visibility,created_at)
SELECT h.owner_id,h.placement_id,-1,'{}','VISIBLE',h.created_at
FROM today_placement_history h
JOIN today_placement p ON p.owner_id=h.owner_id AND p.id=h.placement_id
WHERE CASE p.source_kind
    WHEN 'TRAINING_SESSION' THEN NOT EXISTS (
      SELECT 1 FROM training_session s WHERE s.owner_id=p.owner_id AND s.id=p.source_id)
    WHEN 'PREPARATION_TASK' THEN NOT EXISTS (
      SELECT 1 FROM preparation_task t WHERE t.owner_id=p.owner_id AND t.id=p.source_id)
    WHEN 'MEMORY_CARD' THEN NOT EXISTS (
      SELECT 1 FROM memory_card c WHERE c.owner_id=p.owner_id AND c.id=p.source_id)
    WHEN 'REVIEW_ACTION' THEN NOT EXISTS (
      SELECT 1 FROM review_action a WHERE a.owner_id=p.owner_id AND a.id=p.source_id)
    WHEN 'INTERVIEW_EVENT' THEN NOT EXISTS (
      SELECT 1 FROM interview_event e WHERE e.owner_id=p.owner_id AND e.id=p.source_id)
    ELSE FALSE END;

-- stamp 记录当时卡版与未决尝试；尝试的卡版身份固定，之后终结或卡片改版仍保留合法历史。
INSERT INTO today_placement_history (owner_id,placement_id,revision,snapshot_json,visibility,created_at)
SELECT h.owner_id,h.placement_id,-1,'{}','VISIBLE',h.created_at
FROM today_placement_history h
JOIN today_placement p ON p.owner_id=h.owner_id AND p.id=h.placement_id
LEFT JOIN memory_card_version observed_version
    ON observed_version.owner_id=p.owner_id AND observed_version.card_id=p.source_id
    AND BINARY observed_version.id=BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.cardVersionId'))
LEFT JOIN memory_recall_attempt observed_attempt
    ON observed_attempt.owner_id=p.owner_id AND observed_attempt.card_id=p.source_id
    AND BINARY observed_attempt.id=BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.attemptId'))
WHERE p.source_kind='MEMORY_CARD' AND COALESCE(
    observed_version.id IS NOT NULL
    AND (JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.sourceStamp.memory.attemptId'))='NULL'
      OR (observed_attempt.id IS NOT NULL AND BINARY observed_attempt.card_version_id=BINARY observed_version.id)),0) <> 1;

-- 主键保证修订唯一；总数、首尾和根修订共同证明从 0 开始无缺口，不能只验证已有历史行。
INSERT INTO today_placement_history (owner_id,placement_id,revision,snapshot_json,visibility,created_at)
SELECT p.owner_id,p.id,-1,'{}','VISIBLE',p.created_at
FROM today_placement p
LEFT JOIN (
    SELECT owner_id,placement_id,COUNT(*) AS history_count,MIN(revision) AS first_revision,MAX(revision) AS last_revision
    FROM today_placement_history GROUP BY owner_id,placement_id
) chain ON chain.owner_id=p.owner_id AND chain.placement_id=p.id
WHERE COALESCE(chain.history_count=p.revision+1 AND chain.first_revision=0 AND chain.last_revision=p.revision,0) <> 1;

INSERT INTO today_placement_history (owner_id,placement_id,revision,snapshot_json,visibility,created_at)
SELECT h.owner_id,h.placement_id,-1,'{}','VISIBLE',h.created_at
FROM today_placement_history h
LEFT JOIN today_placement_history previous
    ON previous.owner_id=h.owner_id AND previous.placement_id=h.placement_id AND previous.revision=h.revision-1
WHERE h.revision>0 AND (previous.placement_id IS NULL OR previous.created_at>h.created_at);

-- 末版偏好逐字段核对当前窄列；时间按微秒比较，兼容历史 JSON 的合法小数秒表达。
INSERT INTO today_placement_history (owner_id,placement_id,revision,snapshot_json,visibility,created_at)
SELECT h.owner_id,h.placement_id,-1,'{}','VISIBLE',h.created_at
FROM today_placement p
JOIN today_placement_history h ON h.owner_id=p.owner_id AND h.placement_id=p.id AND h.revision=p.revision
WHERE COALESCE(
    JSON_EXTRACT(h.snapshot_json,'$.snapshot.plannedDate')=CAST(IF(p.planned_date IS NULL,'null',JSON_QUOTE(CAST(p.planned_date AS CHAR))) AS JSON)
    AND JSON_EXTRACT(h.snapshot_json,'$.snapshot.planZoneId')=CAST(IF(p.plan_zone_id IS NULL,'null',JSON_QUOTE(p.plan_zone_id)) AS JSON)
    AND JSON_EXTRACT(h.snapshot_json,'$.snapshot.estimatedMinutes')=CAST(IF(p.estimated_minutes IS NULL,'null',CAST(p.estimated_minutes AS CHAR)) AS JSON)
    AND CAST(JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.priority')) AS UNSIGNED)=p.priority
    AND CAST(JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.orderValue')) AS UNSIGNED)=p.order_value
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.dateLocked'))=BINARY IF(p.date_locked=1,'true','false')
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.visibility'))=BINARY p.visibility
    AND ((p.suppressed_date IS NULL AND p.suppressed_zone_id IS NULL
        AND JSON_TYPE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.suppressedOn'))='NULL')
      OR (p.suppressed_date IS NOT NULL AND p.suppressed_zone_id IS NOT NULL
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.suppressedOn.localDate'))=BINARY CAST(p.suppressed_date AS CHAR)
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(h.snapshot_json,'$.snapshot.suppressedOn.zoneId'))=BINARY p.suppressed_zone_id))
    AND h.created_at=p.updated_at,0) <> 1;

-- 所有历史先通过守卫，再从各自快照提取；回填不得读取当前 visibility 或改写 snapshot_json。
UPDATE today_placement_history
SET visibility=JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.snapshot.visibility'));

ALTER TABLE today_placement_history
    MODIFY COLUMN visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT ck_today_placement_history_visibility CHECK (visibility IN ('VISIBLE','PAUSED'));

SET @wenjie_v64_history_schema = NULL;
