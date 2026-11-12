# 正式关系 AI 反馈原文证据契约

版本：v1.1 · 2026-11-10。适用 S5 第二十五批及第二十八批共享来源边界，状态：静态检查通过待服务器验收。承接 PRD KNOW-06／07、REV-01／02／04／06，以及[正式关系补充契约](正式关系补充理由与来源契约.md)、[人工反馈原文证据契约](正式关系反馈原文证据契约.md)和[训练评价契约](训练评价与证据报告契约.md)。实际进度只维护在[开发阶段与进度](../开发记录/开发阶段与进度.md)，本文§10保留第二十五批静态证据；静态复核与构建不表示数据库、接口或视觉验收通过。

第二十八批[AI反馈生成关系候选](AI反馈生成关系候选契约.md)复用本窄来源、Selection及18字段Snapshot，另行预览授权并写独立V53任务绑定；本V51正式关系证据的保存仍为本地命令，不授权模型外发。来源适配器仅追加独立访问、后台当前性和Snapshot引用校验，原read／capture／state／validSnapshot保持原文与边界。完整历史限定中的NUL保留，新片段与引文禁NUL；新生成模式不读取本证据台账来拼装授权，也不因候选采用而自动复制手工证据。当前累计规格及本轮证据另见开发记录。

## 1. 范围、意见层级与独立台账

用户从本人一份成功 AI 评价中的一条观察选择原文字段片段，填写理由并明确确认，追加为本人已有正式关系的独立证据。每次保存只绑定一条观察和一个原 relationId。保存是本地人工操作，不生成新 AI 任务、不调用模型、不增加费用，也不构成外发授权。

台账固定原关系 ID、规范化端点／类型、理由、成功评价身份、当时决定和来源快照。追加不修改正式边的身份、端点、类型或 revision，不改变图路径、计数或布局；不改原答、原报告、反馈决定或既有证据。面后回忆选区和直接从资料／训练／反馈生成 AI 关系候选不在本批范围，本批不关闭 KNOW-07／REV-06 的完整候选生成要求。

来源始终为 `AI_UNREVIEWED`。ACCEPTED、DISPUTED、IGNORED 或尚未决定均可被用户明确选为说明材料，保存不将争议／忽略自动改成接受。采用、原文命中、结构校验与来源 CURRENT 都不证明 AI 判断正确、关系语义成立、原答正确、用户已掌握或经历真实。后来产生受审标准或受审观察也不升级旧 AI 报告的意见层级。

本批使用 V51 独立台账与专用 DTO、快照和回执。V50 人工反馈的 SELF_CHECK／EXTERNAL_FEEDBACK DTO、快照、回执与历史语义保持原样；V49 MATERIAL／TRAINING 直接证据也不混入本批。共享选区字段类型只复用字段与区间表达，不使 AI 观察成为人工反馈修订。

## 2. 固定身份与主动来源读取

AI 单条观察以 `taskId＋feedbackId` 定位；feedbackId 只允许 `f1` 至 `f12`，不是 UUID。任务必须属于当前 owner，purpose 为 ANSWER_EVALUATION、status 为 SUCCEEDED，currentAttempt 为该 owner／task 的成功尝试，评价绑定必须指向其真实 sessionId／questionId／answerId。回答提交编号范围 1–100，作答条件保留 LEARNING／BEFORE_ASSISTANCE／ASSISTED。

保存身份进一步固定 `attemptId＋reportHash`。reportHash 是数据库读取的原 `candidate_json` 字符串按 UTF-8 编码后计算的 SHA-256，表示为 64 位小写十六进制；不先解析重排、不重新序列化、不做空白或换行归一。它是固定报告变更检测值，不是权限凭据。成功报告没有人工反馈式的正文 revision，不借 task.revision、updatedAt、费用修订或最新答案指针替代这一身份。

用户主动查询既有 growth `AI_OBSERVATION` 元数据目录，按 taskId＋partId 选择一条；partId 对应反馈 ID，目录 EvidenceRef 的 versionId／revision 均为 null。目录只负责发现与定位，不能代替保存时的报告或决定核验。选择器不自动读全部报告；用户选定后，明确请求本批来源接口。

[共享 DTO](../../backend/src/main/java/com/wenjie/studio/knowledge/RelationAiFeedbackEvidenceDtos.java)中的 Choice 为 `{taskId,feedbackId,answer}`，供界面固定所选身份；answer 为 `{sessionId,questionId,answerId}`。Source 返回任务／成功尝试／反馈／报告摘要、确切答案与提交条件、未审级别、报告 limitations／validationNote、dimension／coverage、当前决定以及 observation／condition／basis／limitation／suggestion 五个原文字段。

[来源读取器](../../backend/src/main/java/com/wenjie/studio/ai/RelationAiFeedbackSources.java)先读取并核对任务、评价、尝试和答案绑定元数据，按真实答案执行查看权限检查，之后才读取 candidate_json。[窄 mapper](../../backend/src/main/resources/mapper/ai/RelationAiFeedbackSourceMapper.xml)不读取 manifest_json、原答案正文、题面或标准正文列。来源响应不含报告 summary、citations、未选其他反馈、决定理由或完整清单。

首次来源GET先解析合法purpose／status并判断是否为成功评价，再要求成功尝试和评价绑定完整。合法的QUEUED、RUNNING、VALIDATING、FAILED、UNKNOWN、CANCEL_REQUESTED、CANCELED任务或其他用途返回404 RELATION_AI_FEEDBACK_SOURCE_NOT_FOUND；不能仅因合法未成功任务尚无成功尝试而报持久数据损坏。反之，已保存证据对应的原任务仍在但用途／成功状态已改变，或成功评价的绑定、尝试、报告损坏，属于完整性错误。首次capture遇到来源不再可选按409来源变化处理，已保存历史的完整性边界保持严格。

读取器在服务端严格解析完整报告，包括未选观察和 citations，用于核对固定报告结构、字段类型、顺序身份、大小、引用结构与覆盖规则；不能跳过坏项后把局部结果称为完整合法来源。此读取不重新核验旧 citation 与原答案／题面／标准的逐字对应、元素实际存在或技术语义，也不代表重新运行模型质量评价。新选区仍必须与所选 AI 反馈五字段原文逐字匹配；这两种定位检查的对象不同。

## 3. 创建请求与固定快照

请求结构与共享 DTO 一致：

```text
Create = {reason, source, confirmed}
Selection = {taskId, attemptId, feedbackId, reportHash,
             expectedDecisionRevision, answer, fragments}
answer = {sessionId, questionId, answerId}
fragment = {field, start, end, quote}
```

全部请求字段显式提供，confirmed 必须 true。taskId、attemptId 和答案三元组必须是标准小写 UUID，feedbackId 和 reportHash 沿前节限定。source 不接受完整 Source 或客户端自填限定正文；服务端从真实报告重新构造快照。请求答案必须与所选评价真实绑定相同，不能跨任务、会话、题目、答案或成功尝试拼接。

reason 原样保存，长度为 1–1000 个 UTF-16 码元，拒绝 NUL、孤立代理以及全部由 `Character.isWhitespace` 或 `Character.isSpaceChar` 组成的内容；不 trim 或重写用户理由。

fragments 为同一 AI 观察的 1–8 项，按用户选择顺序保存，只允许 OBSERVATION、CONDITION、BASIS、LIMITATION、SUGGESTION。start／end 是整数，满足 `0 <= start < end`，指向原字段绝对 UTF-16 半开区间；quote 必须逐字相同，长度等于 end−start，最多 8000 个 UTF-16 码元，不越界、不切开代理对。片段拒绝 NUL／孤立代理，并按 Java `isBlank` 要求非空白。相同 field 的区间不能重叠或重复，相邻选区允许。

expectedDecisionRevision 范围 0–20；0 表示此 taskId＋feedbackId 尚无决定，对应 decisionChoice=null；1–20 对应 ACCEPTED／DISPUTED／IGNORED。首次事务必须读取该单条反馈最新决定并精确比较修订。另一条反馈的决定变化不修改本条决定基线；不按整任务 revision 制造冲突。服务端固定当时修订和 choice，决定理由继续留在原决定记录，不复制整份决定历史。

完整历史 AI 文本沿生成时的 Unicode 码点与 Java `isBlank` 规则：报告 summary／limitations 各 1–2000 码点；observations 为 1–12 条；observation 为 1–2000，condition 为 0–1000，basis／limitation／suggestion 各为 1–1000；每项最多 6 个不重复引用，引用 quote 为 1–500 码点，报告文字合计沿原 24000 码点上限。完整字段拒绝孤立代理，但允许既有历史 NUL，不用新 reason 或片段的规则重写或拒绝这些历史字段。validationNote 必须与既有服务端评价说明一致。选区自身仍禁止包含 NUL。

Snapshot 精确固定以下内容：

| 内容 | 字段与规则 |
|---|---|
| 固定来源 | taskId、attemptId、feedbackId、reportHash、answer 三元组。 |
| 当时作答与观察条件 | answerNumber、answerCondition、dimension、coverage。 |
| 意见层级与整份报告限制 | level=AI_UNREVIEWED、完整 reportLimitations、validationNote。 |
| 当时单条决定 | decisionRevision、decisionChoice；后续决定不覆盖它们。 |
| 必要限定全文 | condition、basis、limitation，即使未选这些字段的片段也完整保存。 |
| 用户明确选区 | fragments 中 field、start、end、quote 及原顺序。 |

Snapshot 不额外复制未选 observation／suggestion 全文、报告 summary、citations、其他观察、原答案、题面或标准正文。condition／basis／limitation 与报告限制不可被用户理由或片段裁剪替代。MENTIONED／NOT_MENTIONED／UNCERTAIN 保持原模型覆盖标签；“未提及”不能被保存操作升级为确定缺陷。

## 4. 四个操作与三个路径

接口前缀为 `/api/v1`。所有入口受登录、owner 隔离、独立训练资料守卫和确切答案评价权限约束；写命令要求 CSRF 与 Idempotency-Key，owner 来自认证上下文。[Controller](../../backend/src/main/java/com/wenjie/studio/knowledge/RelationAiFeedbackEvidenceController.java)定义以下路径：

| 方法与路径 | 输入 | 响应 |
|---|---|---|
| GET `/knowledge/relation-ai-feedback-sources/{taskId}/feedback/{feedbackId}` | 确切任务与单条反馈身份。 | Source，当前可选的单条五字段原文及必要元数据。 |
| GET `/knowledge/relations/{relationId}/ai-feedback-evidence` | 可选 cursor／limit，limit 默认 20、范围 1–20。 | Page，仅台账元数据，不读取或返回快照。 |
| POST `/knowledge/relations/{relationId}/ai-feedback-evidence` | Create。 | 201 Entry，仅元数据回执。 |
| GET `/knowledge/relations/{relationId}/ai-feedback-evidence/{evidenceId}` | 原 relationId＋evidenceId。 | Detail，显式读取该条保存快照与当前 sourceState。 |

| 对象 | 精确字段 |
|---|---|
| Source | `taskId,attemptId,feedbackId,reportHash,answer,answerNumber,answerCondition,level,reportLimitations,validationNote,dimension,coverage,decisionRevision,decisionChoice,observation,condition,basis,limitation,suggestion`。 |
| Snapshot | `taskId,attemptId,feedbackId,reportHash,answer,answerNumber,answerCondition,level,reportLimitations,validationNote,dimension,coverage,decisionRevision,decisionChoice,condition,basis,limitation,fragments`。 |
| Entry | `id,relationId,sourceId,targetId,type,reason,taskId,attemptId,feedbackId,sourceCount,createdAt`。sourceId／targetId 为关系概念端点，sourceCount 为片段数；不含 reportHash、决定或快照。 |
| Page | `relationId,relationExists,items,nextCursor`，items 为 Entry。 |
| Detail | `entry,relationExists,source,sourceState`，source 为保存的 Snapshot，不是最新报告替换值。 |

记录按 evidence UUID 字典序升序排列，cursor 为排他 `id > cursor`，有后续项时 nextCursor 才取本页末项 ID，不按 createdAt 排序，也不承诺跨请求快照。Entry／Page 查询只投影元数据及绑定检查字段；理由可能复述 AI 反馈，列表中的每行以及 limit＋1 的下一页探测行都必须通过确切答案访问守卫。

POST 原始 JSON 限 262144 bytes。严格拒绝重复键、未知／缺失字段、尾随 JSON、错误类型、数字枚举、浮点或字符串修订／偏移、隐式标量转换和不合法 null。超限原始请求返回 400 INVALID_REQUEST。Snapshot 按实际序列化 UTF-8 JSON 字节数限 1048576 bytes，包含转义和全部字段；超限返回 422 RELATION_AI_FEEDBACK_EVIDENCE_TOO_LARGE，整次事务回滚，不截断。这是保存快照上限，不是整个 Detail 响应上限。

[OpenAPI 规格](openapi-s7.json) v7.19.0 定义 `getKnowledgeRelationAiFeedbackSource`、`listKnowledgeRelationAiFeedbackEvidence`、`createKnowledgeRelationAiFeedbackEvidence`、`getKnowledgeRelationAiFeedbackEvidence` 四个operation，以及 RelationAiFeedbackEvidenceSelection、CreateRelationAiFeedbackEvidence、RelationAiFeedbackEvidenceSource、RelationAiFeedbackEvidenceSnapshot、RelationAiFeedbackEvidenceEntry、RelationAiFeedbackEvidencePage、RelationAiFeedbackEvidenceDetail 七个具名schema。[生成类型](../../frontend/src/api/schema.d.ts)已包含相同三路径、四操作和七对象。旧969个schema、295条路径内348个operation的语义摘要保持不变，不扩展旧V50／V49快照或回执。

[前端边界校验](../../frontend/src/api/relationAiFeedbackEvidence.ts)核对Source／Snapshot精确字段、19／18字段结构、UUID／fN／摘要、答案三元、意见层级、决定组合及历史文本规则；枚举先要求字符串，再按封闭集合检查。Snapshot只可就已携带的condition／basis／limitation全文复核片段，未保存的observation／suggestion全文由服务端来源校验，不凭生成类型伪称已逐字核对。Page校验有序UUID、同关系元组和游标，Detail对照所选Entry完整指纹及固定task／attempt／feedback／片段数。JSON Schema的码点长度与运行时UTF-16／逐字匹配检查各自保留边界。

| 状况 | 对外结果 |
|---|---|
| 非法身份／摘要／字段／文本／片段／决定范围／确认，或原始请求超限 | 400 INVALID_REQUEST。 |
| 来源GET中的确切任务／反馈不存在或不可访问，或任务具有合法的非成功状态／其他用途 | 404 RELATION_AI_FEEDBACK_SOURCE_NOT_FOUND；不回落另一任务、尝试或反馈，不把合法QUEUED等状态误报为成功结构损坏。 |
| 首次保存时尝试／报告摘要／答案绑定／决定／选区与选择基线不符 | 409 RELATION_AI_FEEDBACK_SOURCE_CHANGED，保留草稿并重新核对。 |
| 尚未获确切答案评价访问许可 | 409 AI_EVALUATION_ACCESS_REQUIRED。 |
| AFTER_SESSION 尚未结束且没有提前反馈确认 | 409 AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED。 |
| 原关系不存在／不可访问，且列表也没有本人该关系历史 | 404 RELATION_NOT_FOUND。 |
| 首次写入时概念端点已归档 | 409 RELATION_ENDPOINT_ARCHIVED。 |
| 证据不属于本人或路径中的原 relationId | 404 RELATION_AI_FEEDBACK_EVIDENCE_NOT_FOUND。 |
| Snapshot 实际 UTF-8 JSON 超过保存上限 | 422 RELATION_AI_FEEDBACK_EVIDENCE_TOO_LARGE。 |
| 同幂等键改变操作或请求 | 409 IDEMPOTENCY_KEY_REUSED。 |

持久报告／绑定／决定／快照损坏及数据库错误按完整性或读取异常失败，不伪装为普通 404、空目录或来源 UNAVAILABLE，不在异常日志输出报告、选区或完整请求。

## 5. 权限先行与三种历史来源状态

通用资料守卫与原答评价守卫分别执行。后者复用 [ReviewDraftSources.requireAnswerAccess](../../backend/src/main/java/com/wenjie/studio/review/ReviewDraftSources.java)，仅按确切答案三元组读取访问与反馈时机元数据，不为权限检查展开原答、题面或标准正文。必须存在该提交的评价授权；若会话尚未结束、反馈时机为 AFTER_SESSION 且 earlyFeedback 未确认，则拒绝。

来源读取在 candidate_json 前执行上述检查；详情先读取并核对 Entry 元数据，按台账 row.answer 检查当前权限，之后才能读取 snapshot_json。列表包含下一页探测行、原键回执和元数据理由同样受限。GET 还沿现有资料模块序列化前守卫，不能仅在浏览器用隐藏样式遮挡已经返回的正文。

[台账服务](../../backend/src/main/java/com/wenjie/studio/knowledge/RelationAiFeedbackEvidenceService.java)严格核对 row、Snapshot 与固定来源的 task／attempt／feedback／reportHash、答案三元、片段数和当时决定。非零决定还须对应原独立决定行，不能把后来最新决定当作保存时的决定。坏 JSON、字段形状错误、缺失绑定或决定错配均保留完整性错误。

Detail.sourceState 只使用以下三个 OriginState 值，不输出 SUPERSEDED／ARCHIVED，不折叠为布尔值：

| sourceState | 本批精确定义 |
|---|---|
| CURRENT | 固定成功尝试、报告、答案绑定、必要限定和选区仍一致，且该观察的最新决定修订／choice 与保存时相同。 |
| REVIEW_REQUIRED | 固定来源仍完整可定位，但本条观察决定已追加到更高修订；当时快照保留，提示重新核对。 |
| UNAVAILABLE | 明确的原成功评价任务来源已无法取得；不包含权限拒绝、绑定损坏、报告损坏或数据库异常。 |

已保存来源的任务用途／成功状态变化、固定成功尝试或 reportHash 无故改变、固定答案／限定／选区变异、决定修订倒退、同一修订的 choice 改变、固定非零决定缺失都属于完整性错误。不得以 REVIEW_REQUIRED 或 UNAVAILABLE 掩盖这些不可变事实损坏。首次保存的客户端旧选择则按来源变化冲突处理，两者不能混淆。

CURRENT 可以同时保留原 DISPUTED 或 IGNORED：它只说明与保存时一致，不表示接受、无争议或语义正确。界面在 CURRENT 状态旁仍明确显示“当时已争议”／“当时已忽略”，不因绿色成功或保存完成隐藏意见层级。报告、当时决定、必要限定与 quote 始终保持原值。

本批 sourceState 不等同[成长证据契约](成长证据查询与复用实施契约.md)的 directState／reviewFlags／lineageCheck，也不检查全部原答背景、标准或间接依赖的当前适用性。growth 中 DISPUTED 至少为 REVIEW_REQUIRED 的规则继续成立；其目录状态不得被本批 CURRENT 覆盖。新答案、另一个评价 task、同任务其他反馈的决定、费用核对或会话推进都不替换已保存来源，也不制造本批 SUPERSEDED 状态。

## 6. 首次写入、回执恢复与关系历史

创建通过[七参数幂等入口](../../backend/src/main/java/com/wenjie/studio/common/IdempotencyService.java)，在同一 owner 锁事务中先执行通用资料守卫与独立 accessGuard，再查询原回执。accessGuard 按 request.source.answer 调用 requireAnswerAccess，因此同键重放也必须满足本次原答查看条件。

仅首次 action 核对确切正式关系仍存在、两概念端点均 ACTIVE，并从真实成功报告 capture。PRECEDES／PART_OF／APPLIED_IN 保留方向；RELATED／CONTRASTS 按小写 UUID 字典序归一，全部类型拒绝自环。capture 重新比对 taskId、attemptId、feedbackId、reportHash、答案三元、expectedDecisionRevision 和每个片段；不得用旧目录、浏览器缓存或客户端限定文本替代来源。

来源捕获、关系检查、台账追加、写后元数据／绑定复读及 Entry 回执同事务提交，任一步失败均回滚。保存不追加反馈决定，不改变 task 或成功尝试，也不建立新关系。不同键视为新的人工追加，不自动按相同理由或选区去重；未知执行结果必须保留原键与原请求恢复。

同键同请求恢复原 Entry，不重新要求决定仍一致、来源仍当前或原边仍存在，但当前资料与原答权限继续检查。回执仅表示原命令元数据，不代表已经重新读取报告、核对完整快照或确认当前关系存在。相同键的不同请求仍拒绝。

正式边删除后，历史台账仍绑定旧 relationId，relationExists=false。相同端点／类型重建是新关系 ID，新边不继承旧台账；恢复原回执也不重建旧边。历史查看权限和固定快照校验继续执行。删除边不撤回反馈、不修改决定或原报告。

## 7. V51 持久化边界

[V51](../../backend/src/main/resources/db/migration/V51__relation_ai_feedback_evidence.sql)新增 `knowledge_relation_ai_feedback_evidence`，保存 owner、证据与原关系 ID、规范关系元组、理由、task／attempt／feedback／reportHash、片段数、答案三元、当时决定、snapshot_json 和 created_at，不对可删除正式边建立外键。

迁移在 ai_evaluation 新增 `(owner_id,task_id,session_id,question_id,answer_id)` 完整唯一键，台账用同一五元组外键绑定评价与确切答案；成功尝试外键为 `(owner_id,task_id,attempt_id)`，固定决定外键为 `(owner_id,task_id,feedback_id,decision_revision)`。接口与快照中的 decisionRevision=0 映射为数据库 null，decisionChoice 同为 null；不制造第 0 条决定行。非零修订为1–20且choice必须非空合法。

CHECK 覆盖五种关系类型、无自环／对称归一、feedbackId、64位小写报告摘要、片段数1–8、决定组合、JSON对象和快照字节上限。数据库字符长度检查不替代 Java UTF-16、Unicode空白、确切选区或引用结构校验。FK只建立存储身份关系；是否为成功评价与成功尝试、报告和 fN 的结构、权限时机及原文匹配由领域层核对。

[台账 mapper](../../backend/src/main/resources/mapper/knowledge/RelationAiFeedbackEvidenceMapper.xml)只提供查询与追加，不提供覆盖或删除台账的方法。元数据读按保存的决定修订连接决定行，不使用最新决定覆盖当时choice；Snapshot独立查询，列表不加载它。迁移当前只作源码文本核对，真实MySQL升级、外键和CHECK行为须服务器验证。

## 8. 已实现界面与请求恢复边界

[选择器](../../frontend/src/components/RelationAiFeedbackPicker.vue)主动按日期查询 growth AI_OBSERVATION，默认最近28天、可调整到最多366天，每页20项并核对既有growth水位／游标；扫描受限、空页仍有游标或能力错误不显示为“没有反馈”。选中时只携带Choice身份，明确读取Source后才出现五字段正文。来源读取失败保留旧选择，不回落另一task或fN。

[追加表单](../../frontend/src/components/RelationAiFeedbackEvidenceCreate.vue)展示原答身份与条件、维度／覆盖、AI未审级别、报告limitations／validationNote、完整condition／basis／limitation和当前单条决定；支持拖选、键盘或原字段UTF-16位置输入逐段加入片段。展示换行与textarea归一映射回原文偏移，quote保留原文；实际浏览器坐标行为仍待服务器验证。理由／片段／来源选择发生变化后撤销确认，重新明确确认才提交。

Source重读指纹包含固定身份、五字段、报告限定与决定。读取到差异时保留旧理由、片段和未加入选区，要求用户显式核对，不自动接受新决定或修改request。访问代次变化隐藏原文并保留草稿；恢复访问后明确重读。执行结果未知时保留原幂等键和完整请求，用原请求核对；收到明确来源冲突409时清除未决命令，但保留草稿、撤销确认并使来源核对失效，须用户重新核对后明确提交，不自动换报告或重发。

Entry回执只核对原relationId、规范元组、理由、task／attempt／feedback及片段数等元数据，不声称已经验证回执未包含的reportHash、限定或quote；保存成功后不自动请求Detail。会话或访问条件变化后到达的合法旧回执暂缓接收，恢复访问后再明确接收。

[历史面板](../../frontend/src/components/RelationAiFeedbackEvidencePanel.vue)明确展开后读Page，再明确打开单条Detail。显示固定当时决定、未审身份、整份报告限制、必要限定、片段和三态说明，并回链确切原AI任务／反馈；不自动跳到最新答案或另一报告。刷新、会话／访问变化使旧详情失效，迟到响应不得覆盖新选择。

有效Detail确认relationExists=false后，面板保留“原边已移除”的已确认状态并禁止新追加；收起详情或读取失败不清除该状态，只由新的有效Page重新判定。草稿和原键恢复入口保留。[关系列表](../../frontend/src/components/ConnectionsPanel.vue)以第四个独立 `ai-feedback-evidence:${connection.id}` 状态键汇总本批与知识补充、直接原文、人工反馈面板的dirty／locked状态，保护关闭、切换关系、分页、删除和页面离开。上述实现已回读源码并完成独立静态复核；真实权限时机、网络恢复、选区、键盘和窄屏交互仍待服务器验收。

## 9. 待服务器验收

RAE-01–20 全部未执行。用户部署并提供服务器地址后开展数据库、接口、事务、权限及视觉验收；本地约束沿[AGENTS.md](../../AGENTS.md)，本文不授权启动服务、执行SQL、部署、读取凭据或编写单元测试。

| 编号 | 场景与预期 | 状态 |
|---|---|---|
| RAE-01 | 既有V50数据上显式执行V51；完整owner＋task＋答案五元唯一键／FK、确切attempt与决定FK、decision0→null及CHECK生效；旧V50／V49 DTO、快照和回执保持兼容。 | 未执行 |
| RAE-02 | 用户主动查询AI_OBSERVATION元数据后选择task＋fN；日期、水位、分页、空页续页和扫描受限正确，不预读全部报告，目录错误不伪造无记录。 | 未执行 |
| RAE-03 | Source先核真实答案权限后读candidate；不查询manifest／原答／题面／标准正文列，响应无summary／citations／其他观察／决定理由；完整报告内部结构损坏不被跳过。 | 未执行 |
| RAE-04 | 成功评价单条f1及f12分别保存1项和8项选区；完整报告limitations／validationNote、单条condition／basis／limitation、未审身份和当时决定均固定且可回看。 | 未执行 |
| RAE-05 | 错owner／不存在来源与合法非评价／非成功任务的Source GET返回404；QUEUED等合法状态不因缺成功attempt误报500。错误task／attempt／fN／答案组合、不合法hash、0或9片段拒绝；不将另一task或新答案当替代来源。 | 未执行 |
| RAE-06 | 五字段UTF-16区间与quote逐字匹配；CRLF／LF／emoji、邻接、越界、代理对切断、同字段重叠／重复、quote错配及8000码元限制按契约处理。 | 未执行 |
| RAE-07 | 新reason的1／1000 UTF-16边界、严格Unicode全空白／NUL／孤立代理拒绝；完整历史AI字段按码点＋Java isBlank且允许历史NUL，片段禁止NUL，不trim历史。 | 未执行 |
| RAE-08 | 严格请求与快照解析拒绝重复键、未知／缺失字段、尾随JSON、隐式转换、数组或对象枚举、浮点／字符串整数、错误null、confirmed=false；坏报告引用结构报完整性错误。 | 未执行 |
| RAE-09 | raw POST的262144字节与Snapshot实际UTF-8 JSON的1048576字节边界、转义计算及超限回滚正确；reportHash来自原candidate UTF-8字节，不采用重新序列化摘要。 | 未执行 |
| RAE-10 | 未决定0/null和1–20决定组合正确；选择后本条决定进位使首次保存冲突并保稿，另一fN决定或费用变化不误阻断；ACCEPTED／DISPUTED／IGNORED保存均不改变原决定。 | 未执行 |
| RAE-11 | Source／Page／Detail／POST原回执均受评价授权和AFTER_SESSION提前反馈条件限制；权限拒绝不降为UNAVAILABLE或带正文的错误状态。 | 未执行 |
| RAE-12 | Detail按row.answer核权限后才读snapshot；Page每项及limit＋1探测行均核权限；Entry／Page无快照，保存后不自动读Detail。 | 未执行 |
| RAE-13 | 已保存来源的任务purpose／成功status变化、固定reportHash／成功attempt无故改变、row与Snapshot绑定错配、原固定决定缺失、决定倒退／同修订choice变化及坏JSON／数据库失败均报完整性或读取错误，不降为普通404／UNAVAILABLE或正常来源变化。 | 未执行 |
| RAE-14 | 同报告同决定为CURRENT，本条决定进位为REVIEW_REQUIRED，原任务明确缺失为UNAVAILABLE；原DISPUTED／IGNORED仍可CURRENT且警示保留，三态不替代growth直接依赖状态。 | 未执行 |
| RAE-15 | 新答案、另一评价task、暂停／恢复／结束和费用核对不替换原来源；后来受审标准不升级AI身份；旧citations只核结构，界面不宣称重新核对原答逐字证据或技术正确。 | 未执行 |
| RAE-16 | 首次追加要求原relationId和ACTIVE端点；删边保历史，已确认移除状态不因收起／失败解除，同元组新边不继承；仍保留草稿和原键恢复入口。 | 未执行 |
| RAE-17 | 同键同请求在决定进位／删边后恢复原Entry且不重复追加；当前权限先于回执，同键异请求拒绝；捕获、写台账、复读或写回执受控失败均整事务回滚。 | 未执行 |
| RAE-18 | UUID排他分页、limit／nextCursor、删除边历史元组一致；四操作均核跨owner隔离与独立训练守卫，三个GET另核序列化前守卫；POST核CSRF，并在owner事务内查询原回执前核当前访问条件，旧报告内容不会通过元数据理由或迟到响应越权返回。 | 未执行 |
| RAE-19 | 服务器验证五字段原文选择／换行坐标、限定预览、明确确认、重读差异保稿、访问变化、原键恢复、迟到回执，以及面板关闭／关系切换／分页／离开保护。 | 未执行 |
| RAE-20 | 服务器核对三态历史详情、确切任务／fN回链、争议／忽略警示、错误重试、键盘和窄屏；全过程无模型调用／费用／外发，无原答／原报告／决定／关系结构改写。 | 未执行 |

## 10. 最终静态证据与待服务器项

2026-11-10 已回读最终共享DTO、Source读取器及其mapper、台账Controller／Service／Rows／mapper、V51、OpenAPI与生成类型、前端边界校验、选择器／追加表单／历史面板及Connections接线。直接读取确认Source GET先判断合法任务用途／状态再要求成功结构，历史来源仍执行严格完整性校验；来源、规格、生成类型和Connections的SHA-256均与主会话提供的最终版本一致。这些读取证明源码内容，不是接口运行结果。

主会话于2026-11-10提供以下最终检查结果；契约维护者据此升级本文，未重复运行构建。完整执行命令、输入集合和审查记录统一保留在开发记录本批条目。

| 检查对象／方式 | 最终证据 | 验证范围 |
|---|---|---|
| 后端离线Maven编译／打包 | 309个Java文件、release21，`BUILD SUCCESS`，退出0，33.247s；`Tests skipped`。 | 编译／打包通过，未运行单元测试、真实接口或SQL。AiController既有deprecation提示保留。 |
| 后端输入冻结 | src/main、pom与公开Maven settings共419个输入，前后摘要均为 `CE93EE7204BF0D058E3F41EB19F2B169D8736BFCA8C546A91DD58AB06DDFB686`。 | 本次编译输入保持稳定。 |
| JAR与编译输出逐项核对 | 70,213,831 bytes；1648个class＋108个资源与target/classes逐项一致，11个必要项齐全，`issues=[]`；JAR SHA-256为 `0195B7302F95B6FBF5138DEBFD11819C832B86B33BF3123A62ACB7BF1F2EEC1F`。 | 制品与本次编译输出一致，未部署；V51未执行。 |
| 前端API类型生成与生产构建 | generate 1.4s、退出0；schema SHA-256为 `FB6028BA033FD434D126865D7822B17FA8052530B31809C0A2081E63CAEA0C1B`；`vue-tsc --noEmit && vite build`退出0，3339 modules，Vite 4.94s；ItemEditor 188.46kB／gzip56.32kB。 | 生成类型、类型检查与生产构建通过，未启动服务或视觉验收。 |
| 前端输入冻结 | 含index.html的231个所选输入前后摘要均为 `DC22435695D8EA2DB7FAC749362C23D9D293EA3C9458AB5AED6D00DA55EEA257`。 | 本次前端构建输入保持稳定。 |
| OpenAPI与旧契约静态核对 | v7.19.0：298 paths、352 operations、976 schemas、7290 refs，未解析引用与重复operationId均为空，Manifest仍22字段；旧969个schema及295条路径内348个operation语义摘要未变，主会话与独立审查均重算。规格SHA-256为 `975A2342C6BBE60CA0E1DBCA2233A83A7505B9F318B7B0C4BBAE59405EF79398`。 | 新路径／对象与旧契约兼容静态一致，不证明真实HTTP行为。 |
| Mapper与权限路径静态核对 | 来源mapper为4个方法／4条语句，台账mapper为6／6，无mismatch、UPDATE或DELETE；列表含lookahead、详情快照和原键回执均保留访问检查。 | 映射与查询结构已核对，未执行MySQL迁移或真实事务。 |
| 独立只读复核 | 来源／后端、三个UI组件、API／规格及Connections均无未关闭确认问题；来源读取器最终SHA-256为 `C02392F151B68CBC8FF527217C4485AA1731D195BE5AA52272B6777DAF55CAD9`，Connections为 `8F9461795FDD9C75B00A6B7DB6B8E1B743A4A1FD5B6F9D3330D01DB55F9EF01D`。 | 静态问题闭环；成功任务筛选、权限时机、恢复、并发与视觉仍须服务器验证。 |

MarkdownEditor 502.02kB／gzip175.02kB的既有体积警告保留，未表述为已消除。本契约维护者仅修改本文并进行只读核对，未重新执行构建、启动服务、执行SQL、操作部署／凭据或编写单元测试。

RAE-01–20 全部保持未执行。V51实际升级与约束、四操作真实响应、原答权限时机、回执与快照恢复、事务并发和视觉交互均待用户部署后验收；直接来源AI关系候选、面后回忆选区及完整间接依赖检查仍在后续范围，静态通过不关闭这些欠项。
