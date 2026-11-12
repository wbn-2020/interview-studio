# AI 复练比较候选契约

日期／版本：2026-11-08 · v1.1。状态：S6 第四批静态检查及构建通过，待服务器验收；证据见[开发记录](../开发记录/开发阶段与进度.md)。对应 [PRD](../需求文档/产品需求文档.md) RET-04，基于[独立复练](独立复练与来源判断契约.md)及[AI 任务](AI任务与外发契约.md)。

## 1. 业务边界

- 仅对已建立的正式双答案 comparison 生成 `ANSWER_COMPARISON` 候选。两端必须是本人已结束训练的确切提交，已分别确认评价访问；面后回忆和无旧答基线不调用双答案比较。
- 用户显式选择该次复练的 1–10 条来源，当前仍属 OPEN 动作、TRACKING 且验证条件等于创建复练时的固定条件。没有所选来源时继续人工对照。
- 预览逐字展示完整发送内容、费用预留及配置。确认预览摘要后创建任务；不因打开页面、生成对照或看过单答评价而默认授权。
- AI 只提出逐来源建议。用户可以读取，或明确将一条建议及新答引文填入判断草稿；已有输入需明确处理，最终仍通过原 Judge 的当前动作／判断修订和人工确认保存。候选和人工判断各自保留，不自动关闭动作、保存判断或认证提升。
- 候选暂存后，最终填入前重新读取原任务，核对来源仍当前、原清单的动作修订、双端提交、建议正文及所选新答引文。读取期间锁定替换；来源、会话或人工输入变化则不接纳，保留原稿。人工采用新的 CAS 不能把旧候选自动升级为可用。

## 2. 外发投影与来源核对

- `comparisonInput` 只含比较 ID、双端确切提交身份／序号、题面、回答文字与图中文字、模式／反馈时机／辅助条件／历史曝光、时间预算及整场时间；来源只含稳定 sourceId 与固定 verification。
- 背景、参考、题面、模式、辅助、曝光、预算和时间是否变化以八个布尔差异标记表达。背景正文、参考正文、原反馈／决定、动作详情、图坐标／知识链接及附件不随整个对象序列化外发；题目与回答本身已经写入的私人文字仍完整发送。
- `comparisonChecks` 只参与本地授权摘要：比较 ID、两场训练 generation、每条来源当前 actionId/actionRevision/sourceId。来源集合按 UUID 排序去重，不静默截断。当前归属／修订或训练代次变化使旧授权失效，条件改后改回也需重新预览。
- 固定外发输入沿用 32 KiB 上限及费用计算；超限明确拒绝，不截断原答。读取、创建、发送前、恢复、接纳与重试核对来源；历史结果在来源变化后仍可阅读并显示不可继续发送／需要复核。未发送失效释放预留，已发送／不确定沿用实际或未知费用，不自动重发。
- 不注入 RepracticeService 到 AI 来源链，避免现有 `RepracticeService → ReviewService → AiService → AiSources` 依赖环；通过 owner Mapper 和 TrainingEvaluations 读取确切对象。

## 3. 结果与原文引用

- 每个选中来源恰好一条候选；结果包括 summary（1–1000 UTF-16 码元）、limitations（1–2000）、suggestions（1–10）。每条含 sourceId、suggestedOutcome、reason（1–1400）、limitations（1–500）、citations（0–6）。所有文本禁止 NUL 和孤立代理，拒绝未知字段、重复／遗漏来源及额外来源。
- suggestedOutcome 为 HANDLED／PARTIAL／UNHANDLED／UNCERTAIN；HANDLED／PARTIAL 至少一处 CURRENT 新答引文。所有判断仍标为 AI_UNREVIEWED，level 和 validationNote 由服务器赋予，不接受模型自行认证。
- 引文含 side=BASELINE／CURRENT、kind、elementId、start、end、quote。kind 仅为 ANSWER_TEXT／ANSWER_NODE_LABEL／ANSWER_NODE_DESCRIPTION／ANSWER_EDGE_LABEL；对应侧确切文字，UTF-16 左闭右开区间，不拆代理对，逐字验证，quote 为 1–500 码点，同项内去重。
- 两侧引用在页面分别标注。填入人工判断草稿时只转入 CURRENT 引文，旧答引文留在候选展示；原因与限制一同进入可编辑草稿。引用合法不证明语义正确、技术正确或比较可比，不输出总分、录用推断或自动完成字段。
- 两协议沿用当前发送格式及共同结果解析流程，不增加工具调用、模型会话、本地模型或自动协议降级。

## 4. API、存储和兼容

| 路径（前缀 `/api/v1/review/comparisons/{id}/ai`） | 说明 |
|---|---|
| POST `/preview` | `{sourceIds}` → 既有 AiPreview；只读预览，无模型请求 |
| POST `/tasks` | `{sourceIds,expectedManifestHash,consentConfirmed}`，Idempotency-Key → AiTask（201） |
| GET `/tasks?cursor=&limit=` | AiTaskPage，cursor UUID，limit 1–20，默认 10 |

新增 `ComparisonDtos`，Manifest 尾部增加可空 comparisonInput/comparisonChecks，Task 尾部增加可空 comparison/comparisonCandidate。旧用途两字段保持 null，旧请求体、原键摘要和历史字段不改写；当前预览摘要采用当前完整清单，升级前未创建的预览应重新预览，不冒用旧摘要。

V31 只扩展 AI 用途 CHECK 并新增 ai_comparison 绑定，owner 复合外键指向 task 和 review_comparison；候选及所选来源复用既有 JSON。每份 comparison 最多 3 个任务、最多 1 个在途任务（QUEUED/RUNNING/VALIDATING/CANCEL_REQUESTED）；UNKNOWN 保留费用并要求显式处理，沿用既有预算阻止超额。每任务尝试仍不超过现有上限。

通用任务详情、取消、重试、未知费用核对和状态 SSE 继续复用；候选不进入自动采用事务。错误沿用 INVALID_REQUEST、AI_LIMIT、AI_SOURCE_CHANGED、授权／预算错误，新增 404 AI_COMPARISON_NOT_FOUND、409 AI_COMPARISON_LIMIT。无新增依赖、配置键或本地运行要求。

## 5. 待服务器验收

全部未执行。

| 编号 | 场景与预期 |
|---|---|
| CMP-01 | V31 显式迁移；旧任务／回执可读，两字段 null 不改变旧用途或旧原键请求 |
| CMP-02 | 双端归属／ENDED／访问授权、伪造比较或来源、无来源及数量边界拒绝；训练安全路径不返回比较内容 |
| CMP-03 | 逐字预览与两协议实际发送一致，只有白名单正文，checks 不外发；超过 32 KiB 不截断 |
| CMP-04 | 来源合并／改条件后恢复／停止跟踪、训练代次变化，在创建／发送／接纳／重试均失效；实际或未知费用保留 |
| CMP-05 | 输出未知字段、数字枚举、额外／遗漏／重复来源、旧新串位、代理对和伪造引文拒绝；无支持时返回不确定建议 |
| CMP-06 | 显式填入一条草稿只采用新答引文，不自动保存或覆盖未决草稿；暂存后来源变化须在最终重读时拒绝，读取期间变化保稿；最终按当前 CAS 人工判断，其他来源不变 |
| CMP-07 | 单比较任务额度、原键重试／回滚、取消／断线／重启、未知费用与迟到结果，不能自动重复外发 |
| CMP-08 | 缓存重入、切换比较／任务、权限遮挡、重登、异常回执及窄屏／键盘／长中文交互 |
