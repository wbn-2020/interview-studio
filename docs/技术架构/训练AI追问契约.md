# 训练 AI 追问契约

日期：2026-11-23。版本：v1.2。状态：本批自包含题干与提示版本兼容已有[真实服务器样例](../审查/复审修复与服务器验收-20261123-r163.md)；背景校验、额度等其他场景的实际覆盖见[开发阶段与进度](../开发记录/开发阶段与进度.md)。单个样例不认证所有生成题目的语义质量。

范围：在既有训练会话中，对用户明确选择的确切已提交答案生成一道追问，并在授权仍有效时加入训练。复用[AI 任务与外发契约](AI任务与外发契约.md)的协议、预算、尝试、取消及未知费用规则。运行与视觉验收仍在服务器进行，不启动本地服务或编写单元测试。

## 1. 业务与访问边界

1. 用户从某题的已提交修订发起预览，显式确认“生成并加入当前训练”。服务端按 owner、sessionId、questionId、answerId 重读不可变提交，不接受客户端答案正文，不改用最新提交。
2. 每次成功只加入一道题。加入是用户本次授权下的系统接纳：结构、引用、训练代次和额度均有效后，同一事务写入正式题实例。用户不预览或挑选模型候选，加入后可按普通题作答或跳过。
3. 追问仅属于本场训练，记录根主问、父题、父答案与 AI 任务。它不创建题库题目，不修改原题或原答，不生成参考答案、评分或反馈。
4. 每个根主问累计最多 followUpLimit 道追问（0–2）。主问重答、追问再答或更换请求键均不能重置次数；同一根主问同时最多一个未决任务。失败、取消及 UNKNOWN 任务不能发布题干，不计已加入次数，但原费用按原规则保留。
5. 训练专用接口只返回本场已有可见输入、任务状态、费用和已加入题 ID。独立模式可使用；不会因此记录辅助访问。通用 `/ai` 继续受资料守卫限制，不能作为独立训练的旁路。
6. 纯问题要求由提示词和严格输出结构约束，结构校验不能证明模型题干完全没有暗示或技术错误。服务器验收需单独检查问题质量；不宣称系统认证了真实独立性。
7. 2026-11-23补充：生成提示要求题干明确写出对象、必要前提与约束，可独立阅读；必要上下文仅来自已授权输入。新任务使用 `promptVersion="training-follow-up-3"`，历史任务保留原提示词版本。此项是生成质量约束，不靠结构校验认证语义完整，也不改写历史追问。

## 2. 输入与模型输出

`AiPurpose` 新增 `FOLLOW_UP`。`AiManifest` 包含可空 `training: AiFollowUpInput`；旧 JSON 缺少该字段可读取。该用途 `sources=[]`、`instruction=""`；第七批追加本地 `contextChecks` 授权依据，具体状态、代次与兼容规则见[训练背景更正与 AI 使用契约](训练背景更正与AI使用契约.md)。通用出题／知识接口拒绝 FOLLOW_UP，必须从训练入口发起。

`AiFollowUpInput` 固定以下字段：

| 字段 | 内容 |
|---|---|
| sessionId / questionId / answerId / rootQuestionId | 本场训练、父题、确切答案、根主问 ID |
| generation | 训练开始／暂停／恢复／结束的生命周期代次 |
| answerNumber / answerCondition | 当次提交编号与记录条件 |
| question | `AiFollowUpQuestion(title,prompt,conditions,technology,scenario,type)`，只取父题固定题干 |
| answer | `AiFollowUpAnswer(text,nodes,edges)`，节点仅 id/label/description，边仅 id/source/target/label |
| context | `AiFollowUpContext(kind,itemId,versionId,number,title,body)` 列表，从原训练固定背景中仅选当前可用项；失效项在本地清单说明，不发送、不替换新版正文 |

背景 body 为服务端生成的 JSON 文本：TARGET 只含 company、jd、requirements（每项仅 text、dimensions、priority）；PROJECT 只含 overview、technology；FACT 只含 scenario、problem、action、result、technology、responsibility。不得展开组织链接、附件、摘录、外部地址、知识关联或表达稿。答案图不展开节点引用，不外发画布来源、坐标、知识正文。预览逐项展示实际投影，模型使用同一份数据。

总输入沿用 32 KiB 限制及协议预算余量；超限明确拒绝，不截断、不静默丢字段。历史训练背景保持固定；外发使用其中当前仍可用的部分，完整预览显示排除项。训练生命周期代次、确切题／答和准备来源代次须仍匹配；撤回再确认不恢复旧授权。题干及原答中用户已写入的文字不自动删改。

模型只能输出以下 JSON 字段：

```json
{"title":"追问标题","prompt":"一道追问题干","conditions":"补充约束，可为空","citations":[{"answerId":"确切父答案 ID","start":0,"end":4,"quote":"原答片段"}]}
```

title 1–200、prompt 1–4000、conditions 0–2000 个 Unicode 码点；citations 1–3 条，quote 1–500 码点。引用仅定位本次 `answer.text` 的 UTF-16 左闭右开区间，必须匹配 answerId、原文和代理对边界，不允许重复。未知字段、参考答案、解析、评分、工具调用及截断结果均拒绝。`AiFollowUpCandidate` 由服务端追加固定 validationNote；该对象只在服务端保存，训练专用 Task 不直接返回候选。

## 3. 生命周期、并发与费用

training_session 新增 generation，旧记录为 0；开始、暂停、恢复、结束均递增。草稿保存、提交、辅助确认、心跳和插入其他追问不改变 generation。任务创建固定当前 RUNNING 代次；发送前、重试前、结果接纳前均核对。

暂停或结束后，即使已经恢复为 RUNNING，旧代次也不能插题或重新发送；需重新预览授权创建新任务。取消不等于远端未收费。迟到结果只更新费用，不保留可接纳候选；未知费用核对不会恢复模型结果。

同一 owner 锁事务内检查根题、已加入数、未决任务、会话总题数和事件容量。成功事务包含：新题实例、关联的 generatedQuestionId、会话 questionCount 与 revision、FOLLOW_UP_ADDED 事件、任务／尝试终态与费用。已加入追问不可通过换 key 再次加入。接纳因业务条件失效时以明确错误结束任务并保存费用，不能因抛出业务异常把费用回滚成一直执行中。

主问仍最多 20 道，总题数最多 60；追加存储位置 0–59，展示按主问原顺序并把其追问放在下面。单题仍 100 次提交。事件保持 2000 条上限并为结束保留一个位置；事件耗尽时拒绝插题，费用保留。并发结束与结果接纳由 owner 锁串行化；旧结束清单 token 或 session revision 必须拒绝。

## 4. 接口与返回对象

前缀均为 `/api/v1/training/sessions/{sessionId}`。所有写命令使用 `Idempotency-Key`，原键／原正文可恢复回执；守卫先于回执读取。严格拒绝未知字段与类型强制转换。

| 方法与后缀 | 请求／响应 |
|---|---|
| GET `/follow-ups/settings` | `AiSettings`，仅配置公开元数据与预算 |
| POST `/questions/{questionId}/follow-ups/preview` | `AiFollowUpPreviewRequest(answerId)` → `AiPreview`；不持久化，不发送 |
| POST `/questions/{questionId}/follow-ups` | `AiCreateFollowUp(answerId,expectedManifestHash,consentConfirmed)` → `AiFollowUpTask`，201 |
| GET `/follow-ups?cursor=&limit=20` | `AiFollowUpPage(items,nextCursor)`，items 为该会话的任务摘要，最多 50 |
| GET `/follow-ups/{taskId}` | `AiFollowUpTask`，强制验证 owner＋session＋task 绑定 |
| POST `/follow-ups/{taskId}/retry` | 复用 `AiRetryTask` → `AiFollowUpTask`；同一代次、未达额度、显式授权且未知费用确认 |
| POST `/follow-ups/{taskId}/cancel` | 复用 `AiChangeTask` → `AiFollowUpTask`；暂停／结束后仍可处理未决任务 |
| POST `/follow-ups/{taskId}/attempts/{attemptId}/reconcile` | 复用 `AiReconcile` → `AiFollowUpTask`；暂停／结束后仍可核对 |

`AiFollowUpTask(task,manifest,attempts,eligible,generatedQuestionId)`：task 复用 AiSummary；manifest 必须为同场 FOLLOW_UP 清单；eligible 表示同一 RUNNING 代次和根题仍有接纳空间，不代表预算充足或任务状态可重试；generatedQuestionId 只在成功插题后非空。`AiFollowUpPage.items` 使用 AiSummary，不返回其他会话或其他用途。

通用 `/ai/tasks/{id}` 的 AiTask 追加可空 `followUp: AiFollowUpLink(sessionId,questionId,answerId,rootQuestionId,generatedQuestionId)`，用于导航。FOLLOW_UP 不填 candidate、questionCandidate 或两个 decision；采用／忽略入口拒绝该用途。通用 retry 对 FOLLOW_UP 拒绝并提示使用训练入口；取消、核对仍可通过受资料守卫约束的通用入口处理。

TrainingQuestionSummary 与 TrainingQuestion 追加 `kind: MAIN|FOLLOW_UP` 和可空 `followUp: TrainingFollowUpOrigin(rootQuestionId,parentQuestionId,parentAnswerId,taskId)`。旧回执缺 kind 时读取默认 MAIN；Session 响应追加 generation，缺失值由响应 DTO 规范化为 0，避免旧回执缺字段触发 primitive 空值错误；数据库仍使用非空整数。追问 snapshot 的 sourceQuestionId/sourceVersionId 为 null、number 为 0，QuestionPrompt 其余字段用固定父题范围和安全空值；`reference` 返回 422 `FOLLOW_UP_REFERENCE_UNAVAILABLE`，不能取父题参考。

主要新增错误：`AI_FOLLOW_UP_STATE_CHANGED`（409，生命周期或确切绑定失效）、`AI_FOLLOW_UP_LIMIT`（409，根题次数已满或有未决任务）、`AI_FOLLOW_UP_ROUTE_REQUIRED`（409，须使用训练入口）、`AI_FOLLOW_UP_NOT_FOUND`（404）、`FOLLOW_UP_REFERENCE_UNAVAILABLE`（422）。通用预览／创建 FOLLOW_UP 返回 400 `AI_PURPOSE_INVALID`；所有资料守卫仍先执行，独立训练调用 reference 可先收到辅助确认 409。输入超限、费用／配置／幂等／修订冲突沿用原错误。背景大于允许输入时保持原答，允许继续人工训练。

## 5. 存储及界面

V15 增加 ai_follow_up 绑定表：owner、task、session、question、answer、rootQuestion、generation、generatedQuestionId、createdAt；task 唯一，父答案使用含 owner/session/question 的复合外键，generatedQuestionId 唯一且绑定同场题实例。训练题增加 MAIN/FOLLOW_UP 与父链／任务字段；MAIN 保留题库来源外键，FOLLOW_UP 来源列为空并要求完整父链。迁移保留原题、提交与任务 JSON。

前端复用既有命令恢复机制。明确展示选中的提交编号、完整外发投影、费用和自动加入说明；尚未提交草稿不参与外发。预览或同意不跨答案、会话、登录及生命周期变更沿用。3 秒状态查询恢复，离开／失活后停止；模型完成只刷新服务器题单，不覆盖本地答案，不强制切题。命令与保存／提交、结束／暂停保持互锁；迟到成功回执需明确接收。

历史追问及未决费用在该训练内可重新读取；根题计数、父答案和题目类型可见。KeepAlive 至少保留全部 60 个题编辑器，避免动态插题导致未保存输入被淘汰。前端不得通过通用 AI 组件查询知识来源或参考，也不能把任务查询成功当作辅助授权。

## 6. 服务器验收场景

以下均未执行，静态检查不替代运行结果。

| 编号 | 触发与预期 |
|---|---|
| FUP-01 | V14 升 V15，旧数据／回执可读，复合外键、唯一键、范围 CHECK 生效，启动不自动迁移 |
| FUP-02 | 独立条件从确切历史提交生成；预览／外发与该修订相同，未保存草稿和参考不出现，答案条件不变 |
| FUP-03 | TARGET／PROJECT／FACT 及答案图按白名单投影，关联知识、摘录、附件、链接不会扩展；超 32 KiB 明确拒绝 |
| FUP-04 | 两种协议生成单道追问；JSON、长度、UTF-16／emoji、重复与伪造引用／额外字段被拒绝；内容质量人工核验 |
| FUP-05 | 生成、发送、接纳各边界暂停／结束／暂停再恢复，旧代次题干不插入，费用保留；新授权可重新开始 |
| FUP-06 | 同键恢复唯一任务／题目，同键异正文拒绝；换 key、同题修订、追问再追问无法突破根题 0–2 次限制 |
| FUP-07 | 并发任务与重试争用根题槽位；失败／取消／未知不发布题干，未知费用保留，最多 3 次尝试规则不变 |
| FUP-08 | 接纳与结束、事件满额、60 题边界、配置变化竞争；业务拒绝仍记费用，成功原子保存题／事件／任务／回执 |
| FUP-09 | 专用路径不能读其他 owner／其他会话／知识出题任务；通用 AI 及旧回执守卫继续生效 |
| FUP-10 | 刷新任务恢复、同 owner 重登、迟到回执显式接收、任务状态不倒退，未保存答案及费用说明不被轮询覆盖 |
| FUP-11 | 根题分组与来源编号准确，插题不抢焦点，追问不能查看父题参考；60 题切换与窄屏／键盘下保稿可用 |
| FUP-12 | 取消、未知账单核对、进程重启和迟到 usage 沿用费用规则；AI 关闭后仍可读历史和处理费用 |

任务状态通知与单答评价分别由 S5 第六批、第五批契约补充；复练比较仍依 S6。本契约只定义训练追问。
