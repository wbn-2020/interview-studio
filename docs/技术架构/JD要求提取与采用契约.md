# JD 要求提取与采用契约

日期／版本：2026-11-09 · v1.0。状态：**代码、独立静态复核及前后端构建通过，待服务器验收。**适用：S5 第十八批，将用户明确选择的当前岗位目标 JD 提取为待审要求，人工选择和编辑后追加到同一目标的新版本。承接 [PRD](../需求文档/产品需求文档.md) PRE-02、PRE-05、AI-02–06，以及[准备与资料契约](准备与资料接口与数据契约.md)和[AI 任务与外发契约](AI任务与外发契约.md)。实际完成项、检查证据及剩余事项统一维护在[开发阶段与进度](../开发记录/开发阶段与进度.md)。

## 1. 用户流程与边界

用户选择一个本人 ACTIVE 岗位目标的确切当前版本 → 核对目标标题、完整原始 JD、整理要求及本次模型／费用信息 → 明确授权生成 → 查看 0–20 条带原文引用的候选与局限 → 选择并编辑 1–20 条 → 确认追加到原目标的新版本，或忽略整份候选。

候选的考察项与优先级均为 AI 未审建议。引用定位仅说明文字来自所选 JD，不证明提取语义充分、岗位实际考核内容、全部要求已覆盖、用户匹配程度或能力。采用代表用户决定使用，不提升证据等级，不创建普通准备任务、不记录任务完成，也不输出能力结论。模型返回零条是合法成功结果；用户仍可手工维护目标要求，不能采用空数组。

本批只支持单个当前 TARGET 的 JD 原文，不混选项目、事实、表达、附件、知识或其他目标，不自动打开来源链接，不覆盖、去重合并或删除既有要求。目标原字段及旧要求身份、内容和顺序均由准备域保留，新增要求依用户提交顺序追加。

## 2. 请求、DTO 与接口

[JdRequirementsDtos](../../backend/src/main/java/com/wenjie/studio/ai/JdRequirementsDtos.java) 定义本批十个 record：

| 对象 | 字段及含义 |
|---|---|
| PreviewRequest | targetItemId、targetVersionId、instruction；只引用确切目标版本，不由客户端提交或替换 JD 正文 |
| CreateRequest | 上述字段加 expectedManifestHash、consentConfirmed；创建与预览使用同一来源和要求 |
| Suggestion | number、text、dimensions、priority、citations；number 为服务端按候选顺序生成的连续 1 起始编号 |
| Candidate | requirements、limitations、validationNote；保留原候选、原引用和固定未审说明 |
| Selection | candidateNumber、text、dimensions、priority；用户可以改写文字、考察项和优先级，不能传入正式要求 ID |
| Adopt | expectedRevision、expectedTargetRevision、requirements、confirmed；分别保护 AI 任务与目标对象修订 |
| RequirementLink | candidateNumber、requirementId；固定候选编号到服务器新生成正式 UUID 的映射 |
| Decision | status、targetItemId、targetVersionId、requirements、createdAt；每任务一个采用或忽略终态决定 |
| Origin | taskId、targetVersionId、targetVersionNumber、adoptedAt、direct、source、requirements；仅返回采用及来源元数据和编号映射 |
| OriginPage | items、nextCursor；按采用目标版本号分页 |

新增四个入口：

| 方法与路径 | 行为 |
|---|---|
| POST `/api/v1/ai/jd-requirements/preview` | 返回完整 Manifest、摘要及预算快照，不创建任务或调用模型 |
| POST `/api/v1/ai/jd-requirements/tasks` | 持 Idempotency-Key 创建持久任务及首次尝试，保存固定来源，预留费用 |
| POST `/api/v1/ai/tasks/{id}/adopt-requirements` | 持 Idempotency-Key 采用用户选择到原目标的新版本，返回任务与终态决定 |
| GET `/api/v1/ai/targets/{targetId}/versions/{versionId}/requirement-origins` | 查询确切目标版本及此前版本的要求采用来源，支持 before／limit |

忽略、取消、重试、费用核对、任务查询与状态通知沿既有 AI 入口和状态机。新命令采用标准 HTTP 方法加完整路径作为 operation，旧请求结构及旧操作摘要协议不改。

三个新 POST 均从原始 JSON 字节解析：请求体最多 262144 字节，拒绝重复键、尾随内容、非对象以及额外或缺失字段。采用还逐项检查 Selection 的精确字段集合；修订必须为可转换为 Long 的 JSON 整数，candidateNumber／priority 为可转换为 Int 的 JSON 整数，confirmed 为 Boolean，text 与 dimensions 成员为字符串。字符串数字、小数截断、数字枚举和布尔转换均不允许，业务值域由服务端再次收口；不扩大旧用途的数字字段白名单。

## 3. 唯一 JD 投影与授权

用途为 `JD_REQUIREMENT_DRAFT`，提示版本为 `jd-requirements-1`。Manifest **不增加字段**，复用 `sources` 和 `preparationChecks`，两者各有且只有一个相互对应的 TARGET。其他用途专属字段均为空；沿既有清单记录供应商、模型、配置指纹、费用及来源代次。

| 输入 | 规则 |
|---|---|
| targetItemId／targetVersionId | 本人所有、类型 TARGET、ACTIVE、确切版本属于该目标且为当前版本；不能将同目标另一版本或别人的版本替入 |
| instruction | 必须提供字符串，可为空，最多 1000 个 UTF-16 码元；禁止 NUL 和不完整代理对 |
| Source | itemId、versionId、number、kind、title、body；kind 固定 TARGET，number 为真实版本号，标题最多 200 个 UTF-16 码元 |
| Source.body | 固定版本内原始 `jd` 字符串，非全空白，最多 50000 个 UTF-16 码元；禁止 NUL 和不完整代理对，不拼接标题、不序列化整个目标 content、不裁剪或归一化正文 |
| preparationChecks | 对应确切 TARGET 的 CURRENT 状态、非负 sourceGeneration，projectGeneration 为空；新建目标的 0 代次有效，作为本地授权复核信息，不发给模型 |

Chat Completions 与 Responses 使用同一个唯一业务投影：

```json
{"purpose":"JD_REQUIREMENT_DRAFT","instruction":"用户整理要求","sources":[{"itemId":"目标 UUID","versionId":"确切版本 UUID","number":1,"kind":"TARGET","title":"岗位标题","body":"原始 JD"}]}
```

岗位标题明确纳入预览和外发。公司备注 company、已有 requirements、sourceUrl、capturedOn、每周预算 weeklyMinutes、links 及关联内容均不从目标字段投影或展开；这些文字若本来就在用户保存的 JD 内，属于已预览 JD 原文，不做静默删改。

完整业务投影以实际 UTF-8 JSON 字节计数，上限 **32 KiB**；超限原样拒绝，不截断 JD 或引用、不自动拆分或扩大发送范围。来源元数据和 instruction 也计入此容量。配置、预算、清单摘要及 preparationChecks 用于本地核验，不混入业务投影。

预览、创建及后续执行按同一确切版本、正文和来源代次核验。修改目标、切换来源、改变 instruction 或模型配置后，旧预览与同意失效，需重新查看清单并授权。保存 JD、关注目标和历史授权均不构成本次外发同意。

## 4. 模型输出与引用

模型原始结果只允许 `{requirements,limitations}`。每项只含 text、dimensions、priority、citations，不能由模型生成 number、正式 UUID、validationNote、采用状态或能力评分。服务端接纳后按顺序添加稳定编号和固定未审说明。

| 字段 | 规则 |
|---|---|
| requirements | 0–20 项；证据不足允许空数组，不为凑数补造岗位要求 |
| text／limitations | 各为 1–2000 个 UTF-16 码元的非全空白文字，禁止 NUL、不完整代理对；文字原样保留 |
| dimensions | 1–6 个互不重复值，只能为 EXPLAIN、DEBUG、TRADEOFF、OWNERSHIP、COMMUNICATION、OTHER |
| priority | 1–5 的 JSON 整数，含义是未审建议优先级 |
| citations | 每项要求 1–5 条；itemId／versionId 必须是本次唯一 TARGET 的确切来源 |
| quote | 1–500 个 UTF-16 码元的非全空白连续 JD 原文，不引用来源标题或其他字段 |
| start／end | 原始 Source.body 的绝对 UTF-16 左闭右开整数区间 `[start,end)`；不按 Unicode 码点、外层 JSON 或格式化正文定位 |

quote 必须逐字等于区间原文，区间不能越界或拆开代理对。同一要求内的 `(itemId,versionId,start,end)` 不得重复；不同要求可以引用同一原文，但引用数量不代表语义充分性。

模型解析与持久候选读回均严格核对字段、文字、整数、用途及确切引用。读回还检查候选编号必须从 1 连续排列、固定 validationNote 一致；不能因为 candidate_json 已保存就跳过校验。旧用途候选与本批候选互斥，不将其他分支的结果当作 JD 要求。

## 5. 选择、追加与事务

采用要求任务为 SUCCEEDED、存在合法候选、没有采用／忽略决定，任务 expectedRevision 匹配；目标必须仍是授权时的 ACTIVE 当前版本且 expectedTargetRevision 匹配。关闭 AI 不阻止采用有效的本地候选，采用不调用模型。

用户选择 1–20 个不同 candidateNumber，均须存在于本次候选。每条 text 可改写并继续受 1–2000 UTF-16、空白／NUL／代理对限制；dimensions 和 priority 按准备域同样值域校验。候选和引用保留原样，最终文字不被表述为模型原答或原引用逐句支持。

沿同一 owner 命令锁，准备域内部追加方法以 **MANDATORY** 加入 AI 命令事务：

1. 核对任务、固定清单、来源绑定、候选编号、当前授权代次和两类预期修订。
2. 为每条选中要求生成新 UUID，保存候选编号到新 UUID 的映射；不接收客户端或模型指定正式 ID。
3. 在原目标确切版本基础上追加要求，保留 title、company、jd、sourceUrl、capturedOn、weeklyMinutes、links 和全部旧要求的 ID／内容／顺序。追加后总数最多 **50**；超限整个命令失败。
4. 通过准备域正常版本保存创建同一目标的新版本并递增对象修订。
5. 保存一次 AI 终态决定、目标新版本与映射，递增任务修订，写通知及幂等回执。

上述步骤一起提交或回滚，不留下无决定的新版本，不开启第二份准备域幂等命令。原键同请求返回原回执，异正文同键拒绝，换键不能重复采用；采用／忽略并发由 owner 锁和唯一决定约束处理。忽略保留原候选，目标字段与映射为空，不要求输入来源当前，也不创建版本。

采用追加本身会生成目标新版本，因此**输入 JD 旧版本自然变为 SUPERSEDED，任务 sourcesCurrent=false 属正常结果**。这不能撤销已保存决定，不能阻断采用成功回执、原键恢复、任务历史或采用来源历史。当前性只约束尚未采用的执行及采用资格；来源完整性和 owner 访问边界始终需要核验。

## 6. 绑定、迁移与旧用途兼容

固定来源复用 V20 的 `ai_preparation_source`，不创建第二套 JD 来源表。任务关联校验必须结合 task 与 Manifest：JD 用途恰有一条 TARGET 来源，ordinal=1，owner、task、itemId、versionId、number、title、kind 均与清单一致；检查绑定数量、顺序、摘要与专属分支互斥，不能仅凭 Manifest 形状判定绑定完整。

创建、领取、发送前、接纳、恢复／重试、读取及采用各阶段保留相同不变量。正常改版、归档或代次变化使来源不再当前；归档后恢复、内容改后改回不会恢复旧授权。绑定缺失、额外绑定、用途或摘要矛盾、非法持久候选／决定及数据库故障须作为完整性错误传播，不能伪装 source changed、空历史或费用释放条件。

[V45](../../backend/src/main/resources/db/migration/V45__ai_jd_requirements.sql) 在 V44 后扩展任务 purpose CHECK，保留全部旧用途并加入 JD_REQUIREMENT_DRAFT；新增 `ai_jd_requirement_decision`。每任务至多一条决定，owner 复合外键约束任务和同 owner 的目标版本；ADOPTED 必须有目标与版本以及 1–20 个映射，IGNORED 两目标字段均为空且映射为 []。映射 JSON 必须为有效数组且不超过 8192 字节，目标采用关系唯一。候选编号与正式 UUID 的业务对应关系仍由服务端逐项复核。

Manifest 字段数和 `AiSources.hash` 算法完全不改。旧清单的既有条件省略规则、其他 null、字段／数组顺序、保存摘要及回执原样保留；Task 追加的可空 JD 候选／决定按旧缺省 null 兼容。新增用途不重写旧清单、候选、决定或原键请求，不改变旧用途来源与采用规则。V45 仅为待部署迁移文件，未在本机或服务器执行。

取消、租约、恢复、未知发送与费用、迟到结果、重试上限和状态通知沿现有任务状态机。来源变化不自动退款、不删除历史或授权重发；异常响应必须保留原请求，不能通过更换幂等键规避未知结果。

## 7. 采用来源与前端恢复

来源使用本批专用 OriginPage／Origin，而不混入普通知识或回忆卡来源 DTO。source 仅含确切 TARGET 的身份、当时版本号／标题、kind 和当前状态；requirements 只含候选编号到正式要求 UUID 的映射，不返回 JD、候选正文或最终要求全文。

查询先核验 owner、目标类型及目标／版本的确切归属，再按所请求版本号上界倒序读取本版本及更早采用。before 为排他版本号游标；direct 仅表示该采用恰好创建了所请求版本。更晚的人工版本仍可以追溯原采用，direct=false；即使后续保留同一 requirementId 并改写文字，也不能声称新文字已被原 AI 或原引用支持。原映射是采用时的身份关系，不是当前文字认证。

采用目标版本与源版本必须属于同一目标，映射编号存在于原候选且正式 ID 确实出现在所采用版本；不能把其他目标、其他版本或伪造映射当作历史。source 当前状态沿 CURRENT、SUPERSEDED、ARCHIVED、UNAVAILABLE 表示，不改写原清单、候选、要求或决定。

前端需显示完整原始 JD 预览和明确的未审身份，选择／改写候选时保留本地稿。来源变化、修订冲突、容量超限或未知回执均保留用户选择和编辑内容；未决写入以原键恢复。采用后的 sourcesCurrent=false 不得被成功回执校验误判为失败。查询和迟到回执不能回退已读到的候选、决定或当前来源状态；登录代次、资料访问遮挡、页面离开和明确接收迟到结果沿现有守卫处理。

## 8. 待服务器验收

以下 **18 项均未执行**。文档和静态检查不能替代真实 MySQL、模型、接口、事务、交互或视觉验收；本批不新增或运行单元测试，不在本地启动服务，不执行 SQL、不调用真实模型。实施后的实际静态证据由开发记录单独维护。

| 编号 | 场景与预期 |
|---|---|
| JDR-01 | V45 在 V44 后迁移，保留旧用途；决定表 owner 复合外键、唯一约束、ADOPTED／IGNORED 分支和映射容量有效，V20 来源复用不改旧数据 |
| JDR-02 | 仅接受本人 ACTIVE 当前单 TARGET 确切版本，新建目标的 sourceGeneration=0 可用；跨 owner、错类型、错版本、历史版本、归档及空白／非法 JD 拒绝 |
| JDR-03 | 两协议实际发送均为唯一 purpose／instruction／sources 投影；title 明确预览，body 等于原始 JD，公司、旧要求、URL、日期、预算、links 不从目标字段混入 |
| JDR-04 | 32 KiB UTF-8 容量覆盖 instruction 和来源元数据；超过边界原样拒绝，中文／补充字符不被截断，预览与发送逐字一致 |
| JDR-05 | 新 POST 重复／额外／缺失键、尾随 JSON、非对象、字符串整数、小数／溢出、null、布尔与数组成员类型及请求容量严格拒绝，旧用途数值协议不变 |
| JDR-06 | 0–20 候选、空结果与局限显示；服务端稳定编号、未审说明、dimensions 去重和值域、priority 整数及 2000 UTF-16 文字边界正确 |
| JDR-07 | 每项 1–5 引用、quote 最多 500 UTF-16；确切 JD 原文、绝对区间、逐字、同项去重和代理对边界；引用标题、外层 JSON 或其他版本拒绝 |
| JDR-08 | 持久候选读回仍严格复核结构、连续编号、固定未审说明与引用；损坏 JSON、假编号、用途混入和错误摘要不作为正常候选返回 |
| JDR-09 | 创建、领取、发送、接纳、恢复／重试、读取及采用核任务绑定；缺失／额外／错序／错身份来源为完整性错误，不伪装源变化或释放费用 |
| JDR-10 | 目标改版、归档恢复或改后改回使旧来源与代次失效，阻止未执行／未采用流程；原清单、候选和本地编辑稿保留 |
| JDR-11 | 选择 1–20 个不同有效候选并允许改写，服务器生成新 UUID；原目标字段、旧要求 ID／内容／顺序不变，新增按提交顺序追加，总数超过 50 整体拒绝 |
| JDR-12 | 双修订 CAS、同 owner 事务创建目标新版本／映射／决定／通知／回执；任一步故障整体回滚，不留孤立版本或重复要求 |
| JDR-13 | 采用成功后输入 SUPERSEDED、sourcesCurrent=false 仍正常返回并原键恢复；历史任务和来源可读，关闭 AI 后有效候选仍可采用，忽略不要求源当前 |
| JDR-14 | 原键同请求恢复、异正文同键拒绝、换键重复阻断、采用／忽略并发；重试或取消历史回执晚于新查询时解除未决且不回退候选／决定／来源状态 |
| JDR-15 | 来源分页、目标／版本归属、候选编号到正式 UUID 映射和 direct 正确；后续改写同 ID 不冒充原 AI 支持；来源响应不返回 JD 或要求全文 |
| JDR-16 | 取消、租约、未知费用、迟到结果与重试遵循既有状态机，不因来源变化自动退款或重复外发；依赖故障保留可核对状态 |
| JDR-17 | Manifest 字段／hash 算法保持，旧清单及原键回执可读、旧用途摘要与采用语义不变；新增可空 Task 字段缺省兼容且分支互斥 |
| JDR-18 | 服务器长中文／补充字符、窄屏、键盘、全文预览、同意重置、零候选、采用保稿、断线原键恢复、登录代次与迟到响应；保存要求不生成任务完成或能力结论 |

本契约不授权 Agent 部署、连接服务器或执行迁移。静态检查证据见开发记录，服务器欠项保持到对应场景实际通过。
