# AI反馈生成关系候选契约

版本：v1.0 · 2026-11-10。适用 S5 第二十八批，状态：静态检查通过待服务器验收。承接 PRD KNOW-06／07、REV-06、AI-02–06，以及[知识关系候选](知识关系候选与逐项采用契约.md)、[资料训练来源关系候选](资料训练来源关系候选契约.md)、[人工反馈与回忆关系候选](人工反馈与面后回忆关系候选契约.md)和[AI反馈手工证据](正式关系AI反馈原文证据契约.md)。本文§10摘录源码核对及主会话提供的本批构建、独立复核证据；实际进度及完整验证记录统一维护在[开发阶段与进度](../开发记录/开发阶段与进度.md)，静态检查与编译不表示功能或服务器验收通过。

## 1. 业务流程与意见层级

用户选择2–5个本人ACTIVE、当前已发布的NOTE／CONCEPT版本，其中至少两个CONCEPT，再从一份成功AI评价的单条观察中明确选取1–8段原文。预览完整知识、固定AI反馈快照、必要限定、供应商／模型与费用后，单次授权生成0–10条关系候选。依据不足可返回零候选，不为连通星图、主题共属或词频共现补造边。

本批是 `RELATION_DRAFT` 的第四种输入模式，不新增purpose。只有所选CONCEPT的itemId可作关系端点；NOTE及AI反馈只作依据，评价任务、fN、答案或选区不能变成端点。每条候选必须实际引用本次所选AI观察片段，知识引用可以为空。

原反馈始终为AI_UNREVIEWED，MENTIONED／NOT_MENTIONED／UNCERTAIN保持原模型覆盖标签。当时ACCEPTED、DISPUTED、IGNORED或未决定均可由用户明确选入；采用候选不追加或覆盖原反馈决定，不认证模型判断、反馈来源、关系语义、技术正确、经历真实或用户已掌握。新受审标准、受审观察、另一份评价或新答案不升级或替换旧反馈。

生成与手工证据保存分别授权。本批不复制或改写V51手工AI反馈证据，不把AI反馈伪装成V43人工反馈／回忆或V52资料／训练来源。逐项采用、补充、忽略和候选星图沿既有流程；面后回忆手工关系证据、持续范围授权、模型暂态正文流、统一检索及S8其余事项继续保留，前批服务器欠项不因本批关闭。

## 2. 请求与固定成功报告身份

[AiDtos](../../backend/src/main/java/com/wenjie/studio/ai/AiDtos.java)新增专用请求，source直接复用[RelationAiFeedbackEvidenceDtos.Selection](../../backend/src/main/java/com/wenjie/studio/knowledge/RelationAiFeedbackEvidenceDtos.java)：

```text
RelationAiFeedbackPreviewRequest = {instruction, sources, source}
CreateRelationAiFeedbackDraft = {instruction, sources, source,
                                expectedManifestHash, consentConfirmed}
sources[] = {itemId, versionId}
source = {taskId, attemptId, feedbackId, reportHash,
          expectedDecisionRevision, answer, fragments}
answer = {sessionId, questionId, answerId}
fragment = {field, start, end, quote}
```

所有字段必须显式提供。instruction允许空字符串，但不得为null；0–1000个UTF-16码元，拒绝NUL和孤立代理，包含两端空白的原文用于预览、Manifest、外发及回执，不执行strip。sources保持用户顺序，知识对象及版本分别不重复；必须是本人当前ACTIVE发布版本，至少两个CONCEPT，不用草稿或新版本自动替换。创建须提供64位小写SHA-256的expectedManifestHash与consentConfirmed=true。

source.taskId指原成功评价任务，不能与新关系任务ID混淆；原任务须为本人ANSWER_EVALUATION／SUCCEEDED，attemptId为其确切成功currentAttempt。feedbackId只允许f1–f12，不是UUID。原评价绑定、成功尝试和确切answer三元必须一致；answerNumber为1–100，answerCondition保留LEARNING／BEFORE_ASSISTANCE／ASSISTED。

reportHash对数据库原 `candidate_json` 字符串按UTF-8计算SHA-256，以64位小写十六进制固定；不先解析重排、不重新序列化、不归一换行或空白。报告没有人工反馈式正文revision，不能用task.revision、updatedAt、费用修订或最新答案指针代替成功attempt＋reportHash＋feedbackId。

expectedDecisionRevision为0–20：0对应尚未决定、decisionChoice=null；1–20对应该观察的确切ACCEPTED／DISPUTED／IGNORED决定。首次捕获重新核本条观察最新决定，不以整任务revision或另一观察的决定制造冲突。只固定当时revision／choice，不外发决定原因或整份决定历史。

用户手选同一条观察的1–8个片段，允许字段仅OBSERVATION、CONDITION、BASIS、LIMITATION、SUGGESTION。区间为原字段绝对UTF-16半开位置，满足 `0 ≤ start < end` 与 `quote.length=end-start`，每段最多8000码元、逐字匹配且按Java `isBlank`非空白。拒绝NUL、孤立代理、越界、代理对拆分、同field重叠或重复；相邻允许，所选顺序保持，不默认全选或裁剪原文。

## 3. 固定快照、历史文本与外发边界

Manifest.relationAiFeedback使用既有18字段Snapshot，服务端从确切成功报告捕获，客户端不能自填快照或必要限定：

| 内容 | 固定字段 |
|---|---|
| 原评价身份 | taskId、attemptId、feedbackId、reportHash、answer三元组 |
| 原提交与观察条件 | answerNumber、answerCondition、dimension、coverage |
| 未审身份与报告限定 | level=AI_UNREVIEWED、完整reportLimitations、原评价validationNote |
| 当时决定 | decisionRevision、decisionChoice |
| 必要限定全文 | condition、basis、limitation |
| 用户明确选择 | fragments及原顺序 |

必要限定即使未被选中也完整固定并展示；完整reportLimitations与原评价validationNote保留，不能用用户instruction、生成理由或选区替代。必要限定不占1–8选区名额，但仍属于本次授权和预算。未选observation／suggestion全文、报告summary、其他observations、原citations、原答、题面、标准正文及原评价Manifest不进入快照或实际外发。

完整历史字段沿原评价的码点与Java `isBlank`口径：reportLimitations为1–2000码点，condition为0–1000，basis／limitation为1–1000；validationNote必须等于原评价固定说明。完整历史背景允许既有NUL，外发JSON原样转义，不清洗、删除或用新文本规则误拒；孤立代理仍拒绝。新instruction、选区、生成文本和所有新引文禁止NUL。包含NUL的历史字段仍可选取不含NUL的合法区间，不能因完整背景已授权而引用未选部分。

[RelationAiFeedbackSources](../../backend/src/main/java/com/wenjie/studio/ai/RelationAiFeedbackSources.java)先核元数据与原答访问，再读取并严格解析完整成功报告，用于核对字段、顺序、大小、覆盖标签及引用结构，包括未选观察。该内部完整报告读取不等于完整报告外发，也不重新核验旧citations与原答／题面／标准逐字对应、元素实际存在或技术语义；新选区另与所选五字段原文逐字核验。

[窄来源mapper](../../backend/src/main/resources/mapper/ai/RelationAiFeedbackSourceMapper.xml)读取成功任务／评价／尝试／答案绑定、candidate_json与单条决定，不读取manifest_json、原答正文、题面或标准正文。目录使用既有growth AI_OBSERVATION元数据，用户选定taskId＋partId后才读单条窄Source；Source的19字段包含五个原文字段，外发的18字段Snapshot只保留明确片段和必要限定。

## 4. Manifest与唯一外发投影

Manifest在原23字段末尾新增第24个nullable字段 `relationAiFeedback: Snapshot`。本模式purpose=RELATION_DRAFT、promptVersion=`relation-ai-feedback-draft-1`，保留全部知识Source；relationAiFeedback非null，reviewDraft、relationEvidence及其他用途专属输入均为null。旧三种关系模式及其他用途不能携带非null新字段。

[AiSources](../../backend/src/main/java/com/wenjie/studio/ai/AiSources.java)与[ModelGateway](../../backend/src/main/java/com/wenjie/studio/ai/ModelGateway.java)共用唯一业务投影 `{purpose,instruction,sources,relationAiFeedback}`。预览、32KiB UTF-8字节预算、输入费用估算和两种模型协议传输须使用一致投影；完整知识、固定18字段Snapshot、身份、限定、当时决定、JSON转义及instruction全部计入。不截断或摘要改写，不去掉历史NUL，不遗漏限定后继续发送；超限明确拒绝，用户调整选择后重新预览。

新字段非null时完整参与原Manifest摘要。仅当relationAiFeedback为null或历史缺省时，从hash投影移除这一新增空尾字段，再沿原relationEvidence／factDraft／cardDraftType／reviewDraft空尾规则；不全局删除null，不改旧字段、数组顺序和旧hash函数其他语义。历史manifest_json、manifest_hash、候选及回执不重算、不回填、不重写。

请求使用两个独立DTO与新操作字符串，不给旧请求增加source或改变幂等摘要。旧纯知识strip规则保持；资料／训练和人工反馈／回忆关系路径保持各自固定原文、提示与引用语义。候选继续使用已存在的reviewCitations形状，不新增候选字段，也不把AI Snapshot交给人工反馈Input校验器。

前端Manifest及候选指纹须完整包含本次Snapshot和全部引用；仅兼容旧nullable尾字段的缺省／null，不忽略非null内容、引文顺序、quote、决定或原报告身份。旧回执恢复不重存新的空尾字段，迟到回执不能回退已知较新候选或逐项决定。

## 5. 模型输出与选区引用

模型只输出 `{summary,limitations,relations}`：summary为1–1000个UTF-16码元，limitations为1–2000，relations为0–10项，每项reason为1–1000。新summary／limitations／reason及知识引文使用严格Unicode空白规则并拒绝NUL、孤立代理；AI反馈选区与其review引文沿Java `isBlank`且仍禁NUL。字段完整但依据不足时返回零候选，不补造引用或关系。

每项模型对象精确六字段：`{sourceItemId,targetItemId,type,reason,citations,reviewCitations}`。模型不提供id、materialCitations、trainingCitations、validationNote、正式relationId或采用决定。端点只取所选两个不同CONCEPT的itemId；type限PRECEDES／PART_OF／APPLIED_IN／RELATED／CONTRASTS。前三类保留方向，对称类型按小写UUID字典序归一，同规范端点＋type不重复，不同类型可并存；服务端按模型顺序赋r1–r10。

| 引用数组 | 数量与原文边界 |
|---|---|
| citations | 0–5条 `{itemId,versionId,start,end,quote}`，只引所选Source.body；quote最多500个Unicode码点，坐标为原body绝对UTF-16半开区间。 |
| reviewCitations | 至少1条 `{field,start,end,quote}`；quote最多1000个UTF-16码元，只限五种AI反馈字段，原绝对区间须完整落入某一个已选fragment并逐字匹配。 |

每项两数组合计1–6条，整份最多60条；两数组必须显式非null，知识引用不能代替至少一条AI反馈选区引用。复用的是引用数据形状，必须使用专用Snapshot校验：拒绝人工反馈／回忆字段、跨选区空隙、未选完整限定、相对坐标、重复位置、越界、代理对拆分、NUL和孤立代理。引文命中不证明原AI判断或关系语义正确。

持久RelationSuggestion为既有九字段，即六个模型字段加id、materialCitations=null、trainingCitations=null。本模式不允许这两个null变成[]，也不允许其他模式借reviewCitations混入AI来源。持久读回核原rN顺序、已归一端点、精确字段、来源模式、全部引用与本模式固定validationNote，不修补损坏历史。

本批关系validationNote与原Snapshot内的评价validationNote分别保留：前者提示新关系候选未审、AI反馈身份与引用范围，后者是原评价固定说明。网关新关系固定说明如下：

> AI 未审关系候选，已核对结构、所选概念端点及知识与 AI 反馈选区的原文位置；原报告仍为 AI_UNREVIEWED，保留原作答条件、完整局限与当时决定。不认证原报告或来源真实性，不证明原报告引文逐字匹配、关系语义正确、技术正确或用户已经掌握。候选需逐项审阅，不会自动进入正式星图。

模型接纳、任务详情、Graph、原始出处及逐项命令均使用同一说明与严格解析规则，不能由模型自行改写。

## 6. API、V53绑定与权限先行

[AiController](../../backend/src/main/java/com/wenjie/studio/ai/AiController.java)新增两个POST，接口完整前缀为 `/api/v1`：

| 方法与路径 | 请求与结果 |
|---|---|
| POST `/ai/relation-ai-feedback-drafts/preview` | RelationAiFeedbackPreviewRequest → Preview；完整清单、hash和预算，不创建任务或调用模型。 |
| POST `/ai/relation-ai-feedback-drafts/tasks` | CreateRelationAiFeedbackDraft → 201 Task；CSRF、Idempotency-Key和本次外发同意。 |

[累计OpenAPI](openapi-s7.json) v7.22.0定义 `aiPreviewRelationAiFeedbackDraft`、`aiCreateRelationAiFeedbackDraft` 两个operation及AiRelationAiFeedbackPreviewRequest、AiCreateRelationAiFeedbackDraft两个请求schema；[生成类型](../../frontend/src/api/schema.d.ts)包含对应路径和字段。当前304 paths／358 operations／982 schemas／7424 refs、Manifest24字段，Snapshot18字段和Selection7字段保持原样。主会话旧协议比对结果为旧302 paths／356 operations语义保持，仅七处既有schema调整和两个新增请求schema；不宣称全部旧schema逐字不变。

原始请求最多262144bytes，逐层拒绝重复键、尾随JSON、未知／缺失字段、错误null、数字枚举、字符串／浮点整数、隐式标量转换及错误数组成员。taskId、attemptId和答案三元为标准小写UUID，feedbackId及reportHash遵守专门格式；selection只交身份、决定基线与片段，不接受完整Source或Snapshot。

[V53迁移](../../backend/src/main/resources/db/migration/V53__ai_relation_feedback_draft_sources.sql)增加 `ai_relation_feedback_draft_source`，按owner＋taskId唯一固定一行新关系任务来源；13列包括owner、taskId、evaluationTaskId、attemptId、reportHash、feedbackId、sessionId／questionId／answerId、decisionRevision／decisionChoice、level和sourceCount。CHECK限定sourceCount为1–8、feedbackId为f1–f12、reportHash为64位小写十六进制、level为AI_UNREVIEWED；无决定时revision／choice全null，有决定时revision1–20且choice为三种决定之一。

四个owner复合FK分别关联新ai_task、原ai_evaluation及完整答案三元、原ai_attempt、原ai_feedback_decision，均RESTRICT；成功状态、报告摘要和原决定choice仍由应用层核验，不以FK代替业务完整性。迁移已静态回读，真实MySQL外键／CHECK尚未执行验收。

[绑定行](../../backend/src/main/java/com/wenjie/studio/ai/RelationAiFeedbackDraftRows.java)、[专用mapper](../../backend/src/main/java/com/wenjie/studio/ai/RelationAiFeedbackDraftMapper.java)及其[XML](../../backend/src/main/resources/mapper/ai/RelationAiFeedbackDraftMapper.xml)只读取／追加固定身份，正文仍在Manifest；全部知识版本继续写原关系来源台账。[来源适配器](../../backend/src/main/java/com/wenjie/studio/ai/RelationAiFeedbackDraftSources.java)要求实际可写事务、插入恰一行并写后逐字段核对。新来源行与知识count、ordinal、task／item／version身份及number／title／kind元数据共同核验，知识核对不回调任务绑定或逐项决定。V43／V51／V52表、mapper与已保存记录不改；旧模式不得带孤立V53行，新模式不得混入其他来源绑定。

[AiService](../../backend/src/main/java/com/wenjie/studio/ai/AiService.java)创建在同一owner锁事务中重建来源和清单、核hash／容量、预留费用、写任务与首次尝试、写两类来源并写后核对，最后保存修订／通知／回执；失败整体回滚。读取、领取、发送前、接纳、恢复／重试、取消／费用命令、逐项决定、Graph与出处统一核task／purpose／Manifest／hash、两类绑定及成功状态与候选存在性，不把固定绑定缺失当普通来源失效。

通用登录、owner与独立训练资料守卫之外，前台正文出口和原键恢复必须先核固定Snapshot.answer的确切评价访问，再判断来源state／current；不得因为原任务正常缺失、知识先失效或current=false就返回缓存快照。`requireAccess`独立调用原答权限后再核固定来源，AFTER_SESSION且会话未结束仍须原入口提前反馈许可，组件与后台不能自动grantAccess。GET／Preview的资料序列化守卫和owner命令事务内、恢复原回执前的访问守卫分别保留。

来源读取依旧先核真实评价／尝试／答案元数据与当前原答权限，再读candidate_json。错误权限、不完整绑定、坏报告或数据库故障不能降级成可读UNAVAILABLE。UI遮挡不能代替服务端守卫，目录元数据也不能代替本次来源读取与授权。

## 7. 当前性、历史与费用恢复

新发送、结果接纳和首次采用／补充要求全部所选知识与AI反馈来源当前；未被候选引用的知识或已选片段也不能省略。固定报告、成功attempt、答案、必要限定、选区、level及当时决定必须一致；仅本条反馈追加新决定会使旧来源需复核，另一观察决定、费用修订、会话推进、新答案或另一个评价任务不替换原来源。

| 来源状态 | 规则 |
|---|---|
| CURRENT | 固定成功报告、尝试、原答、限定与选区一致，本条最新decisionRevision／choice与当时相同。CURRENT可保留当时DISPUTED／IGNORED，不表示接受或正确。 |
| REVIEW_REQUIRED | 固定来源仍完整，本条观察已追加更高决定修订；保留原快照及当时决定，禁止旧候选未经新核对直接采用。 |
| UNAVAILABLE | 明确原成功评价任务来源已无法取得；不含权限拒绝、绑定或报告损坏、数据库异常。 |

已保存任务的用途／成功状态变化、成功attempt或reportHash漂移、固定答案／限定／片段变异、决定修订倒退、同revision换choice或固定非零决定缺失均为完整性错误；不能伪装REVIEW_REQUIRED、UNAVAILABLE或current=false。首次客户端旧选择与已保存不可变历史分别处理：首次capture可报来源变化冲突，持久历史损坏必须失败。

后台current仅将AI_EVALUATION_ACCESS_REQUIRED和AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED两个明确访问阻断转为false，停止新的执行／结果接纳并沿原费用结算，不因权限阻断回滚已发生费用；前台独立权限守卫仍拒绝正文。其他结构、报告、绑定、修订回退和数据库异常继续抛出，不吞错后假释放预算。

历史读取、原键回执和忽略不要求决定仍为当前；正常决定追加不使已发生操作失效，也不重建任务、边或外发。当前访问、固定绑定与原历史决定完整性仍须核验。取消、UNKNOWN费用、租约、最多三次尝试、迟到结果及配置／授权代次沿[AI任务与外发契约](AI任务与外发契约.md)；生成引用非法与真实模型费用分别处理，不自动退款或换报告重试。

## 8. 逐项决定、出处、星图与已实现界面边界

采用／补充／忽略沿原接口和expectedRevision，每个rN至多一个ADOPTED／IGNORED终态。用户仅可保留或互换原两个概念、编辑type和reason，不能换第三个端点；新建沿ACTIVE、自环、对称归一、同类型无环与容量限制。同元组已存在返回冲突，不自动补充；补充须明确选择确切relationId并核同规范元组，删边后不自动重建或匹配同元组新边。

补充表及source表仍只保存人工最终内容、知识版本来源及taskId／candidateId回链；AI反馈身份和Snapshot通过原任务及V53追溯，不复制成V51手工证据或修改原评价决定。正式边／补充、逐项决定、修订、通知和回执同事务保存。忽略不要求来源当前、不改原反馈决定或退款；关闭AI不妨碍符合条件的本地采用／补充。

RelationOrigin尾增nullable `relationAiFeedback`及`relationAiFeedbackState`。显式打开原始AI建边出处时返回完整18字段Snapshot与三态；旧模式新尾缺省／null兼容，本模式reviewDraft及relationEvidence分支为null。此出口包含原文与完整历史限定，先核两类绑定及fixed.answer权限，再核状态，不能称为仅元数据。原始出处只表示AI首次建边，人工建边后追加AI补充仍通过补充历史回原任务；删边保留原relationId历史，同元组新边不继承旧出处。

RELATION_DRAFT成功任务目录包含四种模式，Graph结构保持窄投影；sources只含知识元数据，不返回Snapshot、报告正文或引文quote。sourcesCurrent同时覆盖知识与AI反馈来源，citationCount计入reviewCitations并保持每项1–6；过滤已决定项保留原rN。虚线候选仅连接当前图窗已有概念，范围外保留列表，不扩图、改布局或计入正式路径／数量；回原任务逐项审阅。

创建复用[AiRelationReviewCreate](../../frontend/src/components/ai/AiRelationReviewCreate.vue)，在已有人工反馈／回忆来源旁增加AI_FEEDBACK选择。复用[AI反馈选择器](../../frontend/src/components/RelationAiFeedbackPicker.vue)窄目录并显式读取单条Source，手选五字段片段后完整展示Snapshot和知识；[专用前端API](../../frontend/src/api/aiRelationAiFeedback.ts)核完整六字段知识Source、原文instruction、18字段Snapshot及请求Selection，用AI反馈专用校验，不套人工反馈／回忆Input。历史NUL的完整限定按原值展示和授权，选区仍禁NUL。

来源、instruction、配置、决定或访问条件变化撤销旧预览及同意，保留可重核的选择和人工稿。创建及原键重放核首次QUEUED／revision0／attemptCount1、唯一number1 QUEUED／RESERVED尝试、授权预留金额及初始null计费／usage／错误／发送结束字段，完整Manifest与原请求匹配。异常回执或未知结果保留原request／key恢复，明确冲突保稿重核，不换评价task、attempt、fN或报告。

[Manifest预览](../../frontend/src/components/ai/AiManifest.vue)、[任务候选](../../frontend/src/components/ai/AiRelationCandidates.vue)与[原始出处](../../frontend/src/components/ai/AiRelationOrigin.vue)复用[固定AI反馈展示](../../frontend/src/components/ai/AiRelationAiFeedbackInput.vue)，显示AI_UNREVIEWED、当时决定、报告／反馈完整限定、原提交条件及全部选区；DISPUTED／IGNORED另有明确提示。[引用展示](../../frontend/src/components/ai/AiReviewCitations.vue)以独立aiInput分支验证并标示已选片段中的引文，列出fN／成功attempt及原字段坐标，回链确切原评价观察，不默认展开整份报告。

[公共Manifest与候选指纹](../../frontend/src/api/ai.ts)纳入完整Snapshot及全部review引用，[候选Graph校验](../../frontend/src/api/relationGraph.ts)接受本模式独立说明并保持无正文投影。前端独立终审发现的三个旧独立入口新增尾字段漏核已修并定向复核关闭；其他用途不接受非null relationAiFeedback。权限变化、跨标签、请求代次、轮询与离开守卫覆盖新source；键盘、窄屏、长文本、选区定位及网络恢复均待服务器验收，不把静态接线称为视觉通过。

## 9. 待服务器验收

以下RAI-01–20全部未执行。本地执行边界沿[AGENTS.md](../../AGENTS.md)，本文不授权启动服务、执行SQL、调用模型、编写或运行单元测试及部署；用户部署后验收真实两协议、数据库、权限时机与界面。

| 编号 | 场景与预期 | 状态 |
|---|---|---|
| RAI-01 | V53新任务与原评价／成功attempt／单条决定四类复合FK、片段数与决定空值联合有效；旧V43／V51／V52不变，新旧模式孤立或混装绑定拒绝。 | 未执行 |
| RAI-02 | 同RELATION_DRAFT第四模式、24字段Manifest与专用prompt；旧null尾hash兼容、历史JSON/hash不重写；其他用途非null新字段拒绝，旧三模式不放宽。 | 未执行 |
| RAI-03 | 2–5当前ACTIVE知识且至少2CONCEPT，对象／版本去重；旧版／归档／跨owner拒绝，NOTE／评价／fN／答案不能作端点。 | 未执行 |
| RAI-04 | 仅成功ANSWER_EVALUATION及其成功currentAttempt、f1–f12、原答案三元；合法未成功或其他用途的首次读取拒绝，持久成功身份变异报完整性错误。 | 未执行 |
| RAI-05 | reportHash按原candidate_json UTF-8字节计算，空白／换行变化可检测，不由重序列化、task.revision、updatedAt或费用修订替代。 | 未执行 |
| RAI-06 | 1–8手选五字段片段、原绝对UTF-16／8000限制、逐字及顺序正确；相邻允许，重叠／重复／越界／代理对拆分／NUL／孤立代理拒绝。 | 未执行 |
| RAI-07 | 完整18字段Snapshot保AI_UNREVIEWED、成功身份、原答条件、报告限制／说明、必要限定及当时决定；0/null与非零choice组合正确，不要求先接受。 | 未执行 |
| RAI-08 | 两协议外发精确等于purpose／instruction／sources／relationAiFeedback，排除summary／其他观察／原citations／原答／题面／原Manifest；内部完整报告结构读取不冒充外发。 | 未执行 |
| RAI-09 | 历史完整背景NUL保留并JSON转义，旧码点／Java isBlank口径保持；新instruction／选区／生成文本／引文禁NUL，合法不含NUL选区可用，不清洗历史。 | 未执行 |
| RAI-10 | 32KiB预算覆盖知识、全部Snapshot限定／元数据／转义及原文instruction；超限不截断或删限定；修改任何授权输入使旧hash／同意失效。 | 未执行 |
| RAI-11 | 两POST严格262144bytes、重复键／尾随JSON／未知缺失字段／错null／数字枚举／字符串浮点整数／数组成员；instruction空串合法、null非法，0–1000原文保留。 | 未执行 |
| RAI-12 | 0与10候选有效，六字段model、九字段persist及独立note严格；非法端点／type／重复元组、额外id／material／training、文本异常拒绝，rN及对称归一稳定。 | 未执行 |
| RAI-13 | review至少1＋知识0–5合计1–6、最多60，material／training明确null；专用Snapshot校验1000UTF-16引文仅一个选区，知识500码点，未选限定／跨空隙／相对坐标／错field／重复引用拒绝。 | 未执行 |
| RAI-14 | 原观察正常追加决定变REVIEW_REQUIRED，旧历史／原键仍可按权限读取；另一观察决定、新答案、费用修订不替换来源；倒退修订、同revision换choice、旧决定缺失及报告／attempt漂移报错。 | 未执行 |
| RAI-15 | fixed.answer访问先于state/current，原任务缺失或知识先失效仍不能绕过独立训练／评价访问／提前反馈许可；前台读取及原键恢复拒权限，不自动grantAccess。 | 未执行 |
| RAI-16 | 全生命周期核V53与知识count／ordinal／身份／元数据及固定候选；后台仅两访问码false并保真实费用处理，DB／JSON／报告／绑定损坏继续抛，不伪装失效退款或静默重发。 | 未执行 |
| RAI-17 | Create初态QUEUED／rev0／attempt1／RESERVED、预留金额／初始null与授权匹配；异常回执和未知结果保原key/request；nullable尾fingerprint兼容，迟到回执不回退决定或覆盖稿。 | 未执行 |
| RAI-18 | 逐项adopt／supplement／ignore一次终态与CAS并发、原两端、环／容量／重复边约束；补充确切边、知识来源＋task回链，不复制手工证据；相关写入同事务回滚。 | 未执行 |
| RAI-19 | 四模式目录及Graph窄投影无Snapshot／quote，sourcesCurrent含AI来源、review计数和原rN正确；显式Origin完整Snapshot三态、固定权限与删边历史隔离正确。 | 未执行 |
| RAI-20 | 服务器验证AI_FEEDBACK窄目录／单条读取、手选／预览、未审及当时争议标签、引用回链、键盘／窄屏／跨标签权限／保稿恢复，无自动扩大来源或技术认证。 | 未执行 |

## 10. 最终静态证据与待服务器项

2026-11-10已回读最终AiDtos、AiController、AiSources、AiService、ModelGateway新分支，RelationAiFeedbackSources／DraftSources、绑定行／mapper及XML、V53，核对24字段Manifest、原报告UTF-8摘要、完整Snapshot与历史NUL、专用引用、V53和知识台账、权限先行、三态与后台费用边界。前端专用API、AI_FEEDBACK创建／选择、预览／Input／引用／出处、公共指纹和Graph也已回读。新validationNote与网关常量逐字一致；生成类型SHA-256与主会话提供的最终值一致。

主会话于2026-11-10提供下列最终检查结果，契约维护者据此升级本文，未重复运行构建。完整命令、输入集合、摘要及独立审查记录统一保留在开发记录本批条目。

| 检查对象／方式 | 本批最终证据 | 验证范围 |
|---|---|---|
| 后端离线Maven package | 315个Java文件、release21，`BUILD SUCCESS`，退出0，44.049s；`Not compiling test sources`、`Tests skipped`。 | 编译／打包通过，未运行单元测试、SQL或真实接口。 |
| JAR与target/classes逐项比对 | 1666个class＋112个资源一致，8个必要条目齐全，`issues=[]`；70,258,248bytes，SHA-256为 `94AFA1E4B4783104105DCB85A27042B4317B45A1BEE0D0897F4C1EE32BA6039C`。 | 制品与编译输出一致，未部署。 |
| API类型生成 | openapi-typescript7.13.0，1.5s，退出0；schema.d.ts最终摘要已直接核对匹配。 | 生成类型对应规格，不表示真实HTTP行为通过。 |
| 前端最终类型与构建 | 三个旧入口修正后重新执行类型检查及Vite构建，退出0，3357 modules、4.42s；创建组件35.25kB／gzip12.07kB，新Input11.73kB／gzip3.76kB。 | 最终修正已进入生产构建，未启动应用或视觉验收。 |
| 构建输入冻结 | 后端429个输入、前端239个输入的最终构建前后集合摘要一致，`changed=[]`。 | 本次构建输入稳定，集合及摘要见开发记录。 |
| OpenAPI与旧协议 | v7.22.0为304 paths／358 operations／982 schemas／7424 refs、Manifest24字段；旧302 paths／356 operations无语义变更，仅七处旧schema调整及两个新增请求schema，原Snapshot／Selection保持。 | 本契约维护者直接解析核对当前统计、两个operation／请求schema、18／7字段及无重复operationId；未执行接口。 |
| 来源mapper | 安全XML检查中binding／insert两个Java方法与两条SQL对应，13列一致，无动态SQL替换、UPDATE或DELETE。 | mapper与DDL静态一致，真实V53约束仍待服务器。 |
| 既有逻辑范围核对 | 17个旧prompt字节摘要相同，8个旧网关解析方法及8个旧来源入口／投影原文相同；从RelationAiFeedbackSources逆向移除本批唯一新增helper块后全文与基线一致。 | 主会话提供的定向源码差异证据，不将旧完整系统行为概括为已运行回归。 |
| 后端与前端独立复核 | 后端12份文件摘要全匹配、无确认缺陷；前端26份文件全匹配，三个旧独立入口尾字段守卫漏核修正已定向关闭，其余无确认缺陷。 | 独立静态问题闭环；真实权限时机、费用恢复、事务及视觉仍待验证。 |

MarkdownEditor既有502.02kB／gzip175.02kB体积警告保留，未宣称已消除。本契约维护者仅新增／维护本文并执行只读核对，未运行服务、SQL、模型、构建、单元测试或部署。

RAI-01–20全部未执行。AI反馈生成关系候选已有上述静态交付及独立复核证据，V53真实升级、两协议外发、权限时机、事务／费用恢复、原文回链及视觉交互均待用户部署后验收；前批服务器欠项、面后回忆手工关系证据和其余完整产品范围继续保留。
