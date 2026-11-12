# AI 候选采用与来源契约

日期：2026-11-09。版本：v1.5。状态：S5 第二批契约，第九／十／十三／十四／十六批补充准备、资料、训练、表达及复盘来源；各批实际状态见[开发记录](../开发记录/开发阶段与进度.md)。范围：KNOW-07、AI-04、DATA-02／03、AC-33／43／44／45 的知识候选采用；生成与外发沿用[AI 任务与外发契约](AI任务与外发契约.md)。

## 业务规则

成功的 KNOWLEDGE_DRAFT 任务保留不可变模型候选；用户可改写标题、Markdown 正文，预览候选／最终正文与目标原文，再明确采用。采用只写本应用知识库，不外发、不新增模型费用；AI 关闭或配置变化不阻止已有候选的本地审阅。引用定位正确与用户采用均不赋予技术已审身份。

- NEW：显式选择 NOTE 或 CONCEPT，创建新对象及首个发布版本，主题先留空；之后可在知识工作区整理。新概念复用现有坐标与布局修订规则。
- EXISTING：选择 ACTIVE 对象，固定目标 currentVersionId 与 draft.revision，展示发布正文和草稿。保存前要求草稿与发布内容完全一致，且两项基线未变；否则返回冲突并保留输入，允许重新对照或改为另存。成功追加发布版本并同步草稿；历史版本保持原文。
- 采用时核对任务 revision、SUCCEEDED 状态、未决策、所有清单来源当前可用。知识须为当前 ACTIVE 发布版本；第九批准备来源还须满足[准备候选契约](准备版本生成候选契约.md)的核实状态及来源／父项目代次。来源改版／撤回／归档后不能直接采用旧候选，可忽略或重新生成。目标同时是来源时，核对发生于写入前，写入后旧来源显示为历史版。
- 单任务只能产生一次终态决定：ADOPTED 或 IGNORED。忽略不写知识、不退款；决定后原候选仍可读。再次提交新幂等键返回 AI_CANDIDATE_DECIDED，原键重试返回原回执。其他候选类型不随笔记采用而确认。
- 最终正文是用户明确提交的内容，不能由服务端用模型输出覆盖；标题 1–200 UTF-16 单元，正文非空且不超过 100000 UTF-16 单元。采用前的改写仅存在当前页面；刷新前提示未保存。

## 原子保存与留痕

训练来源按[训练片段沉淀知识契约](训练片段沉淀知识契约.md)检查确切历史提交与所选反馈；它们不采用知识的“必须最新版本”规则。原答、图元素及报告是独立来源身份，V25 关系与原清单保存其出处，Origin.trainingSource 可空。采用不会改变原答、评价决定、事实核实或其他来源行动。

自检、用户录入外部反馈及面后回忆按[复盘与面后片段沉淀知识契约](复盘与面后片段沉淀知识契约.md)检查当前修订／决定或 ACTIVE 当前版本及对象修订。V43 固定绑定，Origin.reviewSource 保存确切历史身份；来源变更阻止旧候选直接采用，已采用知识保持当时出处。本分支的人工决定变化使旧输入失效，不改变上述训练来源的规则；原反馈、回忆与动作均不因知识采用被修改。

表达来源按[表达素材输入与来源契约](表达素材输入与来源契约.md)核对自身和事实依据代次，采用只保存人工最终知识。来源历史保留确切表达；自身当前但依赖或授权代次变化时显示 REVIEW_REQUIRED，不把表达原文当作已确认事实，也不把未外发的依据列为模型直接来源。

新增 V13 `ai_candidate_decision` 与 `ai_knowledge_source`。决定唯一约束覆盖 owner＋task；采用决定固定目标知识对象／版本，忽略决定的目标字段为空。来源行保存该次外发清单所有知识对象及确切版本，不将输入材料关系冒充概念语义边。

同一个 owner 锁和数据库事务中完成：幂等检查、训练资料守卫、任务与来源核对、目标双基线检查、知识版本／草稿／引用索引／检索 outbox、决定／来源记录、任务修订及回执。任一失败全部回滚。引用原文与区间保留于原任务的 Candidate；采用后编辑不重写原候选或引用。来源表提供可查反向依赖，完整删除／更正传播仍依 S8 生命周期清单实施，不能绕过外键删除原文。

知识版本可查本版本及更早版本发生的采用记录，按采用的知识版本号倒序分页；每条说明是否为当前所选版本的直接采用。后续人工编辑只保留“历史采用来源”，不声称新文字逐句源自这些材料，也不把用户改写的段落自动视为已核验引用。知识来源显示 CURRENT、SUPERSEDED、ARCHIVED、UNAVAILABLE。第九批为已采用任务合并准备来源及 WITHDRAWN、PROJECT_ARCHIVED、UNVERIFIED 状态，按来源 kind 打开确切知识／准备版本及原 AI 任务；未采用任务不混入采用历史。

第十批[附件摘录生成候选契约](附件摘录生成候选契约.md)追加 Section.materialCitations 和 Origin.materialSources，历史缺省按 null 兼容。采用前核对全部确切摘录及资料模块可用性；资料来源单独保存于 ai_material_draft_source，不写知识外键。纯资料采用的 Origin.sources 可为空，materialSources 返回当时元数据与 CURRENT／UNAVAILABLE 状态。正文引用、全部外发来源与用户最终改写分别保留，不自动确认事实或关系。

## 接口契约

前缀 `/api/v1/ai`，所有读取与命令受会话、owner 和训练资料守卫。POST 要求 CSRF 与 Idempotency-Key。已有任务返回增加可空 `decision`；旧幂等回执未包含该字段时按 null 读取，决策的新请求仍查数据库唯一约束。

| 接口 | 请求与响应 |
|---|---|
| POST /tasks/{id}/adopt | AdoptCandidate → Task |
| POST /tasks/{id}/ignore | ChangeTask → Task |
| GET /knowledge/{itemId}/versions/{versionId}/origins?before&limit | OriginPage；before 为已采用目标版本号，limit 默认20、范围1–50 |

`AdoptCandidate`：expectedRevision（非负安全整数）、mode（NEW／EXISTING）、kind（NEW 必填 NOTE／CONCEPT；EXISTING 必须 null）、targetItemId／expectedTargetVersionId／expectedTargetDraftRevision（EXISTING 必填；NEW 必须 null）、title、body、confirmed=true。全部字段须显式给出，未知字段／错误类型拒绝。

`CandidateDecision`：status（ADOPTED／IGNORED）、mode（NEW／EXISTING／null）、targetItemId／targetVersionId（可空）、createdAt。

`OriginSource`：itemId、versionId、number、title（当时标题）、state、kind（第九批追加可空字段，旧缺省按历史知识来源）。状态与领域含义见[准备候选契约](准备版本生成候选契约.md)。`Origin`：taskId、targetVersionId、targetVersionNumber、adoptedAt、direct（是否为所查版本的直接采用）、sources。`OriginPage`：items、nextCursor（可空整数版本号）。不在此接口复制候选或用户正文。

错误包含 INVALID_REQUEST（400）、AI_TASK_NOT_FOUND／KNOWLEDGE_NOT_FOUND／VERSION_NOT_FOUND（404）、AI_CANDIDATE_DECIDED、AI_STATE_CONFLICT、AI_SOURCE_CHANGED、REVISION_CONFLICT、VERSION_CONFLICT、DRAFT_UNPUBLISHED、KNOWLEDGE_ARCHIVED（409）、容量及来源异常（422）。冲突恢复必须重新读取明确的目标基线，不自动提升 CAS；重新核对期间新增的本地编辑继续保留。

## 前端与验收边界

采用表单与任务费用表单使用独立脏状态和命令锁；互相阻止未决写入。会话过期或资料遮挡保留改写、目标副本和原幂等键，取消过时读取并撤销勾选确认。同 owner 重登后原键恢复；成功迟到回执先暂存，显式接收后再刷新任务／知识列表。轮询不覆盖改写；切换任务、关闭表单、导航或刷新保护未保存输入。

服务器待验：新建两种知识类型、现有目标追加版本、草稿冲突／目标改版／来源变化／归档、同键恢复／不同键重复采用、采用与忽略并发、事务失败回滚、AI 关闭后本地采用、当前与历史版本来源、独立训练遮挡、会话重登／迟到回执和窄屏键盘。对应阶段编号见[开发记录](../开发记录/开发阶段与进度.md)。本机不启动服务、不执行迁移、不写单元测试。
