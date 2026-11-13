# AI 制卡与采用来源契约

日期／版本：2026-11-09 · v1.0。状态：**代码、独立静态复核及前后端构建通过，待服务器验收。**适用：S5 第十七批，从明确选择的已发布知识生成单张回忆卡候选，并显式采用为正式卡片。本文为本批实施规范，实际完成项、检查证据与剩余事项以[开发阶段与进度](../开发记录/开发阶段与进度.md)为准；文件或代码存在不代表运行验收通过。

共享字段以 [CardDraftDtos.java](../../backend/src/main/java/com/wenjie/studio/ai/CardDraftDtos.java) 为准。相关规则见[AI 任务与外发](AI任务与外发契约.md)、[AI 候选采用与来源](AI候选采用与来源契约.md)、[回忆卡与来源](回忆卡与来源契约.md)、[主动回忆与排程](主动回忆与排程契约.md)及[复盘片段沉淀](复盘与面后片段沉淀知识契约.md)。

S8第二十八批补充[材料更正AI制卡出口契约](材料更正AI制卡出口契约.md)：沿真实知识采用链核原任务、完整固定清单与来源、真实首版卡；实际受影响的当前卡须保留全部原知识来源，才可由既有知识影响机制触发卡片复核。移除原来源的AI卡仍保守阻塞；不改写原采用回执、不自动恢复排程或旧AI资格。该扩展不放行材料归档／恢复及原S25用途门禁，进度与未执行服务器场景见开发记录及新契约。

## 1. 业务边界与来源

用户明确选择 **1–5 个本人 ACTIVE、当前已发布的 NOTE／CONCEPT 版本**，再选择 QA 或 CLOZE。每个任务生成一张待审卡片候选；打开入口、选择来源或预览均不调用模型，创建任务并明确同意后才授权一次尝试。

每个来源固定为 `Source(itemId,versionId,number,kind,title,body)`。标题和全文取确切发布版本，不取未发布草稿，不展开关系、关联概念、准备资料、附件、训练记录或其他知识。来源对象和版本均不重复，按用户选择顺序保存、显示、外发及绑定。全部已选正文都是外发内容，须在预览中完整展示；不能为了通过容量检查而截断原文。

QA 为问答卡。CLOZE 沿用手工卡片的可见空位语义，例如在题面写 `____`，参考独立保存；本批不解析挖空语法、不自动遮蔽原文、不生成多张子卡。候选及采用类型均固定为本次选择的类型，用户若要另一类型须重新预览生成。

生成结果和参考都保留 AI 未审身份。逐字引用只说明出处，不认证技术正确、完整性或用户已掌握；采用是用户保存最终内容，不等于复习、评分、完成学习或能力提升。

## 2. 接口与共享 DTO

新增 purpose 为 `MEMORY_CARD_DRAFT`。路径位于 `/api/v1/ai`，沿用登录、owner 隔离、CSRF、独立训练资料守卫、任务通知及费用规则。

| 操作 | 方法及路径 | 请求／响应 |
|---|---|---|
| 预览 | POST `/card-drafts/preview` | PreviewRequest → 200 既有 Preview |
| 创建 | POST `/card-drafts/tasks` | CreateRequest → 201 既有 Task；带 Idempotency-Key |
| 采用 | POST `/tasks/{id}/adopt-card` | Adopt → 200 既有 Task；带 Idempotency-Key |
| 忽略、取消、重试、费用核对及任务读取 | 既有 AI 任务路径 | 沿用原请求、任务与尝试状态机 |
| 卡片采用来源 | GET `/memory-cards/{cardId}/versions/{versionId}/origins` | 通用 OriginPage；支持 before／limit 与 direct |

| CardDraftDtos 类型 | 精确字段顺序 |
|---|---|
| PreviewRequest | `kind,instruction,sources` |
| CreateRequest | `kind,instruction,sources,expectedManifestHash,consentConfirmed` |
| Candidate | `kind,title,prompt,reference,citations,validationNote` |
| Adopt | `expectedRevision,content,confirmed` |
| Decision | `status,targetCardId,targetVersionId,createdAt` |

kind 使用 `MemoryDtos.Kind(QA,CLOZE)`；sources 为既有 `AiDtos.SourceLink(itemId,versionId)` 数组。instruction 必须显式提供，保留原文，允许空串，最多 1000 UTF-16 码元；拒绝 NUL 和孤立代理。创建必须 consentConfirmed=true，并核对当前重新生成的 expectedManifestHash。

新请求采用严格 JSON 结构：所有定义字段显式提供，拒绝未知、缺失、重复字段、尾随 JSON、数字枚举、修订或授权值的类型强制转换。UUID 为标准小写格式。Adopt.expectedRevision 是 AI 任务修订，不是新卡片修订；confirmed 必须 true。客户端不能提供伪造的来源正文替代服务端读取。

## 3. Manifest、任务分支和实际外发

Manifest 在现有 20 个字段末尾追加第 **21** 项 `cardDraftType: MemoryDtos.Kind|null`；Task 尾部追加 `cardCandidate: CardDraftDtos.Candidate|null` 和 `cardDecision: CardDraftDtos.Decision|null`。旧请求 DTO 和字段顺序不变。

本分支必须满足：

- purpose=MEMORY_CARD_DRAFT，cardDraftType 为 QA 或 CLOZE，promptVersion=`memory-card-draft-1`。
- sources 是 §1 的 1–5 个完整固定知识版本；training、evaluation、contextChecks、materialQa、preparationChecks、materialDraftSources、expressionType、preparationPlan、trainingDraft、expressionChecks、comparisonInput、comparisonChecks、reviewDraft 全部 null。
- 只有 cardCandidate／cardDecision 对应本用途，其他候选、采用决定和专用输入分支为空。其他用途必须 cardDraftType、cardCandidate、cardDecision 为 null；旧历史缺少尾字段按 null 兼容。
- purpose、清单类型、候选类型及保存的来源关系必须一致。网关、任务读取、前端接纳与采用均执行分支互斥检查，不能只在预览入口验证一次。

两种已支持模型协议共用唯一业务投影：

`{purpose:"MEMORY_CARD_DRAFT",instruction,cardDraftType,sources}`

这里的 sources 原样包含六个固定字段，保留顺序与完整正文。预览、输入 allowance、费用预留和网关发送共用这一投影；configuration、检查结果和预算值不作为来源正文外发。投影 UTF-8 编码最多 **32 KiB**，超限原样拒绝，不自动截断、缩写或替换为摘要。协议实际开销继续受已授权预留限制。

修改来源、来源顺序、卡片类型、instruction 或配置后撤销预览和同意。源发布版本或状态变化时，不自动替换源、重试生成或接受新清单。模型不获工具调用、外部搜索、额外资料读取或持久上下文权限。

## 4. 候选及逐字引用

模型严格输出单个对象：

`{kind,title,prompt,reference,citations:[{itemId,versionId,start,end,quote}]}`

仅允许上述字段；validationNote 由服务器回填。kind 必须等于 Manifest.cardDraftType。title 为 1–200、prompt 为 1–10000、reference 为 1–20000 Unicode 码点，均非空白，拒绝 NUL、孤立代理及超长内容，不静默修剪成另一份候选。

citations 为 **1–10 条**，每条都必须指向本次所选的确切 itemId／versionId，并逐字匹配其固定 body：

- start/end 为 body 内的绝对 UTF-16 半开区间，满足 `0 ≤ start < end ≤ body.length`。
- quote 非空白、最多 1000 UTF-16 码元，长度等于 end-start；不能使用 JSON 偏移、码点位置或标题位置代替。
- 不拆开代理对，不正规化或折叠大小写，不接受伪造来源、越界及重复 `(itemId,versionId,start,end)`。
- 引用仅证明连续文字出处，不要求以引用数量覆盖所有来源，也不据此认证卡片中每个陈述。

结果接纳、持久候选读回和采用前复核候选结构、类型、引用及限制；不能因为 candidate_json 已保存就跳过校验。普通知识候选、题目候选、复盘引文和资料引文不能混入制卡候选。

## 5. 最终内容与采用事务

Adopt.content 使用既有 `MemoryDtos.Content(kind,title,prompt,reference,sources,relatedConceptIds)`。前端从候选填入可编辑 title／prompt／reference，默认 relatedConceptIds=[]；最终保存内容可以由用户改写，原候选与原引用分别保留。

采用时执行以下固定条件：

| 字段／条件 | 规则 |
|---|---|
| kind | 必须等于 Manifest.cardDraftType 和候选 kind，不允许在采用时切换 |
| title／prompt／reference | 按普通回忆卡规则校验 200／10000／20000 码点、非空白、Unicode 与完整内容字节限制 |
| sources | 必须与 Manifest.sources 的 `(itemId,versionId)` **全部相同且顺序一致**；不得增加、删除、替换或重排 |
| relatedConceptIds | 0–20 个不同的本人 ACTIVE CONCEPT；作为独立分类关联，不自动添加为来源、不随模型生成或外发 |
| 任务 | SUCCEEDED，有合法 cardCandidate，expectedRevision 匹配，尚无采用／忽略终态决定 |
| 来源 | 全部仍满足本批当前来源要求；源变化阻止采用并保留用户编辑稿 |
| AI 配置 | 关闭 AI 不阻止采用已经成功且本地仍有效的候选；采用不再次调用模型 |

只支持新建卡片，不把候选追加到已有卡片，也不借知识采用路径保存。沿现有 owner 命令锁，`MemoryService.createWithinCommand` 以 MANDATORY 加入同一写事务，复用手工卡片校验和创建逻辑：

1. 复核任务、固定清单、来源绑定、合法候选、最终内容及当前来源。
2. 创建 ACTIVE 卡片、revision=0、版本号=1 的首版，并保存来源和相关概念。
3. 初始化记忆来源复核 gate。
4. 保存 AI 一次终态采用决定，递增任务修订并记录通知及命令回执。

上述步骤一起提交或回滚；不能先创建卡片再单独写 AI 决定。原键同请求恢复原回执，换键不能再次采用；采用与忽略并发由同一 owner 锁和唯一决定约束处理。忽略不要求来源当前，不创建卡片。

创建 gate 只建立卡片来源复核基线。**采用不会创建或启用排程，不产生 attempt、reveal、rate、FSRS 或复习记录，也不将卡片自动加入今日待复习。**普通卡片列表／详情／历史继续不返回 reference；显式参考浏览和正式揭示分别沿原契约，AI 候选中已显示参考也不能冒充正式复习已揭示。

## 6. 来源当前性与 task-aware 生命周期

预览以同一一致快照核对 owner、ACTIVE、确切版本归属、当前发布版本和全文。创建及各后续阶段在适用事务中重复核对。知识未发布草稿不替换固定正文；较新发布版、归档或正常不可访问使旧候选来源不再当前，历史清单仍保留当时原文。

创建、领取、发送前、结果接纳、恢复／重试、采用及任务读取均采用 **task-aware** 检查：任务用途与清单一致，制卡来源关系数量必须等于 1–5 个清单来源，ordinal 从 1 连续排列，owner、task、itemId、versionId、number、title、kind 和顺序均与固定清单一致。普通任务不得带本批孤立来源或决定关系。

来源正常变化沿 sourcesCurrent=false／AI_SOURCE_CHANGED 处理；数据库故障、无效持久 JSON、摘要矛盾、缺少／重复／越界／错序／孤立绑定是完整性错误，返回或传播 500，不冒充普通 source changed，不因关系损坏释放预算或接纳候选。

任务取消、超时、租约、未知结果、重试次数、费用预留和迟到费用沿既有 AI 状态机。来源变化不会自动退款、删除历史或授权重发。迟到结果不能绕过取消、租约、任务代次、来源当前性或终态决定。

任务响应、采用来源历史与幂等回执都受当前资料访问守卫；命令 operation 使用标准 HTTP 方法与路径，在 owner 锁内、读取历史回执之前核对。后台执行授权与用户当下能否查看正文分别处理，不通过内部来源读取制造参考曝光。

## 7. V44、历史出处与旧数据兼容

[V44__ai_memory_card_drafts.sql](../../backend/src/main/resources/db/migration/V44__ai_memory_card_drafts.sql) 扩展 V31 的任务 purpose CHECK，保留原用途并加入 MEMORY_CARD_DRAFT，增加：

| 表 | 内容和约束 |
|---|---|
| ai_card_draft_source | 创建任务时固定全部来源：owner、task、确切知识 item／version、当时 number／title／kind 及 ordinal；任务内来源和位置分别唯一，ordinal 1–5，owner 复合外键指向任务及所属知识版本 |
| ai_card_draft_decision | 每任务最多一个终态决定；ADOPTED 必须有确切 targetCardId／targetVersionId，IGNORED 二者均 null；owner 复合外键指向任务及所属卡片版本，目标采用关系唯一 |

V44 不回写旧 Manifest、候选或回执，也不创建记忆排程。迁移仅准备为部署文件，数据库执行及约束行为待服务器验证。

采用来源入口复用通用 OriginPage 和普通 `Origin.sources` 元数据，显示固定知识版本身份、当时标题／版本号及当前来源状态；materialSources、trainingSource、reviewSource 为空。不读取卡片 reference、不返回源正文，不增加专用 cardSource 分支。

查询先验证 owner、cardId 与 versionId 的确切归属，再按该版本号上界读取本版本或更早采用记录；before 为排他版本号，limit 沿通用来源分页范围，顺序和 nextCursor 与现有 OriginPage 一致。direct 仅在采用目标版本等于所请求版本时为 true。用户后来保存卡片新版本时，原采用仍显示为更早采用、direct=false，不把新参考或新句子逐句认证为原 AI 引文支持。

来源动态状态沿普通知识来源规则：归档优先 ARCHIVED；未归档且确切版本仍当前为 CURRENT，否则 SUPERSEDED；明确正常不可访问可标 UNAVAILABLE。状态不会改写清单、原候选、卡片版本或引用，也不代替记忆领域的持久来源复核状态。

兼容协议必须同时保护历史读取和实时摘要：

1. Manifest 新 cardDraftType、Task 新 cardCandidate／cardDecision 缺省按 null 读取。原 17／19／20 字段历史 JSON、保存的 manifest_hash、候选、采用历史和回执均不写回、不重算覆盖。
2. `AiSources.hash` 对当前对象按既有 canonical SHA-256 计算前，**仅当 cardDraftType==null 时移除 cardDraftType，再仅当 reviewDraft==null 时移除 reviewDraft**。其他 null、既有字段及数组顺序全部保留；不尝试多套宽松 hash，也不剔除其他可空字段。
3. 因而旧普通实时清单仍使用其原 19 字段摘要，复盘入口保留原 20 字段摘要；新制卡保留非空 cardDraftType，空 reviewDraft 沿既有规则省略。21 是当前 Manifest 声明字段数，不表示所有分支的摘要都含 21 个键。
4. 旧请求 DTO、字段／数组顺序、操作字符串及 Idempotency-Key 摘要协议不变；新请求只用于新路径。新可空响应字段不加入旧命令请求。
5. 旧用途不因新分支变更来源、决定或采用语义；前端兼容旧缺省字段，同时拒绝新分支混入旧用途。

## 8. 前端、错误与恢复

入口可以从知识已发布版本或 AI 创建页进入，只预选确切 ID，不自动发出模型请求。先选 QA／CLOZE、1–5 个来源及整理要求；预览逐项显示全部外发全文、卡片类型、供应商／模型、费用上限和实际投影。生成只提供候选，用户核对并编辑后才能采用。

采用表单固定来源列表和类型，提供人工 title／prompt／reference 编辑；relatedConceptIds 默认空，分类可在保存后的普通卡片编辑页维护。source changed、任务修订冲突、知识分类失效或未知响应都保留本地稿和原请求；未决写入使用原键恢复，轮询／迟到响应不能覆盖新稿。任务重试、取消或费用核对的历史回执按发出时快照核验；合法旧回执可以结束原命令，已读取的较新候选、决定与当前来源状态保留，再查询当前任务。普通查询仍拒绝固定清单变更或已有候选／决定消失。页面离开、登录代次及资料权限遮挡沿现有守卫，禁止凭缓存绕过正文访问。

字段错误沿 INVALID_REQUEST，错误用途、缺失归属、来源当前性、清单变化、容量、输出及引用分别沿现有 AI 错误类别；普通卡片内容／分类校验沿 MEMORY_LIMIT／SOURCE_UNAVAILABLE 等原规则。最终错误码以累计 OpenAPI 与实现一致核对。绑定损坏和依赖故障不得吞成正常源变化或空来源。

## 9. 待服务器验收

以下 **16 项均未执行**。本批不新增或运行单元测试，不在本地启动前后端、数据库或模型；文档、类型及编译检查不能替代数据库、实际外发和视觉验收。

| 编号 | 场景与预期 |
|---|---|
| ACD-01 | V44 在服务器迁移：保留 V31 所有用途并允许 MEMORY_CARD_DRAFT；两新表的 owner 复合 FK、ordinal／来源／终态／目标唯一约束及分支 CHECK 有效，旧数据不重写 |
| ACD-02 | 1–5 个本人 ACTIVE 当前发布 NOTE／CONCEPT；拒绝重复、跨 owner、错版本、旧版、归档、空白或非法正文，来源顺序固定，不取未发布草稿 |
| ACD-03 | QA／CLOZE 明确选择，每任务仅一个候选；CLOZE 为可见手写空位，不触发语法解析、自动遮蔽或多子卡 |
| ACD-04 | 两协议实际外发等于唯一投影，全文、顺序、kind 和 instruction 均与预览一致；32 KiB 原样拒绝，其他资料／预算字段不混入 |
| ACD-05 | 新请求原始 JSON 重复／额外／缺失字段、尾随内容、类型转换、UUID、修订及授权值严格拒绝，Unicode 和请求容量边界正确 |
| ACD-06 | 21 字段 Manifest 与所有旧分支互斥，Task 只接纳 cardCandidate／cardDecision；模型 kind 必须匹配，title／prompt／reference 码点上限及非法字符拒绝 |
| ACD-07 | 1–10 条确切正文引用，1000 UTF-16 码元上限、绝对偏移、逐字、去重和代理对边界；伪造来源、引用标题、越界或无引用拒绝，持久候选读回仍复核 |
| ACD-08 | 创建、领取、发送、接纳、恢复／重试、采用及读取逐阶段核全部绑定数量／顺序／身份；损坏绑定为完整性错误，不冒充 source changed 或释放预算 |
| ACD-09 | 来源发布新版本、归档或不可访问阻止待执行／未采用流程并保留稿；原候选与历史不倒改，未发布草稿不替换原来源 |
| ACD-10 | 最终内容可人工改写，但 kind 固定、sources 全部原序一致；增删替换重排均拒绝；relatedConceptIds 默认空，0–20 个 ACTIVE CONCEPT 独立分类，不外发或冒充来源 |
| ACD-11 | 采用同事务创建 ACTIVE/rev0/首版、来源／分类及 gate，再写 AI 决定／通知／回执；任一步失败整体回滚，不遗留无决定卡片 |
| ACD-12 | 原键恢复、异正文同键拒绝、换键重复阻断、采用／忽略并发；重试的 QUEUED 回执丢失后先读到 SUCCEEDED，再恢复原回执，应解除未决而不回退候选或决定，取消／费用核对同验；关闭 AI 后仍可本地采用有效候选，源变化仍阻止采用，忽略不要求源当前 |
| ACD-13 | 采用后没有 schedule／attempt／reveal／rate／FSRS 或今日复习新增；普通卡片响应与采用来源不含 reference，显式浏览不冒充正式揭示 |
| ACD-14 | 来源历史卡片／版本归属、分页、direct 与更早采用正确；后续人工卡片新版本仍保留原采用、direct=false，不逐句认证或自动换来源 |
| ACD-15 | 旧 17／19／20 字段历史和旧原键回执可读，实时旧 hash 与操作摘要保持；仅按条件省略两个新增域，其他 null／顺序不变，保存的 hash 不重写 |
| ACD-16 | 服务器长中文／补充字符、窄屏、键盘、清单全文、同意重置、断线原键恢复、采用保稿、权限遮挡／重登和迟到响应；取消、未知费用沿原状态机，无重复外发 |

静态检查证据与未完成范围由开发记录维护。本规范不授权 Agent 部署、连接服务器或执行迁移。
