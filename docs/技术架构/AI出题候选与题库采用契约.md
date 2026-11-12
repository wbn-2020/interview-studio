# AI 出题候选与题库采用契约

日期：2026-11-07。版本：v1.3。状态：S5 第三批契约，第九／十／十四批补充准备、资料和表达来源；代码与静态证据见[开发进度](../开发记录/开发阶段与进度.md)，运行与真实模型质量待服务器验收。适用 AI-04、DATA-02／03 以及题库、独立训练和来源追溯边界；生成与费用基础沿用[AI 任务与外发契约](AI任务与外发契约.md)。知识候选采用仍由[AI 候选采用与来源契约](AI候选采用与来源契约.md)负责。

## 决定与本批边界

**用户确认：**完整产品范围、暂不做语音、Java 21／MySQL／MyBatis、第三方 Chat Completions／Responses 兼容接口、本地不启动服务或编写单元测试，详见[项目约束](../../AGENTS.md)。

**本批设计决定：**增加 `QUESTION_DRAFT`，每任务生成 1 道题，输入沿用用户显式选择的 1–5 个当前已发布知识版本；用户审阅、改写后采用为一条新题库题目。采用不覆盖已有题，不自动加入题单、路线或训练会话，也不自动开始训练。这些是本批实施规则，不将其表述为用户逐项确认的产品限制。

完整链路为：选择知识 → 预览完整外发清单 → 授权一次尝试 → 生成并校验候选 → 用户审阅／改写 → 采用为题库首版或忽略 → 在既有题库流程中选择训练。

第九批按[准备版本生成候选契约](准备版本生成候选契约.md)增加 TARGET／PROJECT／FACT，可与知识混选，总量仍为 1–5；使用独立入口、固定字段与来源代次，保留责任边界。第十批按[附件摘录生成候选契约](附件摘录生成候选契约.md)增加显式摘录输入、独立资料引用及采用来源，总量和旧请求兼容规则继续保持。第十四批按[表达素材输入与来源契约](表达素材输入与来源契约.md)增加已有表达，其依据只用于本地授权核对；采用历史可回到确切表达版本，并保留待复核提示。训练片段到知识由第十三批专项契约处理，不扩大本篇出题输入。知识和准备关联不触发自动扩展。动态追问、回答评价、SSE 和资料问答由各自专项契约负责。

## 输入、预览与授权

PreviewRequest 与 CreateTask 复用现有结构，仅扩展 purpose。每项来源仍为 `itemId + versionId`，服务端读取当前 owner 的 ACTIVE 知识对象及确切发布正文，拒绝重复对象、归档、非当前版、空正文或不可访问来源。草稿、附件、反链和主题正文不外发。

instruction 最多 1000 个 UTF-16 单元；资料与要求序列化总大小沿用 32 KiB 上限，不静默截断。Manifest 固定 purpose、instruction、全部来源正文、公开模型配置、费用预留及该用途的 promptVersion。出题与知识整理分别使用提示版本，旧用途授权不能借用。

创建时在 owner 锁内重算 manifestHash；预览后来源、要求、协议、价格或配置变化需要重新预览。发送前及接纳前复核来源、配置和授权代次，取消、费用未知、人工核对与重试沿用已有任务规则。实际协议请求参数与传输机制不因本批新增用途而改变。

## 模型候选与最终题目

模型只返回单个 JSON 对象。服务端拒绝未知字段、错误类型、截断、工具调用、拒答和非法 Unicode；校验后形成 `QuestionCandidate`。文本长度沿用题库的 Unicode 码点计数；引用偏移另按 UTF-16，不能混用。

| 字段 | 候选约束 |
|---|---|
| title | 非空，最多 200 码点 |
| prompt | 非空，最多 10000 码点 |
| conditions | 字符串，可空，最多 5000 码点 |
| technology／scenario | 字符串，可空，各最多 2000 码点 |
| type | EXPLAIN／CODE_READING／DEBUG／PROJECT／TRADEOFF／SYSTEM_DESIGN／BEHAVIOR |
| difficulty | null 或 1–5 的整数；表示个人难度建议 |
| dimensions | 1–6 个不重复值：EXPLAIN／DEBUG／TRADEOFF／OWNERSHIP／COMMUNICATION／OTHER |
| referenceAnswer | 字符串，可空，最多 20000 码点 |
| checkpoints | 0–30 个不重复的非空字符串，每项最多 500 码点；模型不生成检查点 ID |
| citations | 纯知识／准备为 1–5 个引用；每项 itemId、versionId、start、end、quote，quote 非空且最多 500 码点；含资料时可为空，与 materialCitations 合计 1–5 |
| materialCitations | 第十批含资料时为 1–5 个确切摘录引用；旧结果可缺省／null。excerptId、start、end、quote 相对于摘录正文，与知识／准备引用分开 |
| validationNote | 服务端生成；不属于模型可输出字段 |

模型不生成题目 ID、版本 ID、主题、tags、knowledgeLinks 或外部来源 URL。最终采用的 `QuestionInput` 复用[现有题库 DTO](../../backend/src/main/java/com/wenjie/studio/bank/QuestionDtos.java)及[服务端校验](../../backend/src/main/java/com/wenjie/studio/bank/QuestionService.java)：用户可修改所有内容，检查点 ID 在用户编辑／采用请求中生成并保持稳定，原键恢复时不得重新生成。

用户可以清空参考答案和检查点。最终 content 的 topicIds、tags、source 和 knowledgeLinks 按既有题库规则校验，不由服务端从模型原文补造。题目初始 ID 和首版 ID 由服务端生成，题目标准状态固定为 `PERSONAL_UNREVIEWED`。用户采用不代表技术审阅完成。

## 三种来源关系

| 关系 | 含义与存放位置 |
|---|---|
| AI citation | 模型候选引用的外发原文位置，保留在任务候选；只证明位置合法 |
| AI 采用来源 | Manifest 的知识来源保存于 ai_question_source；准备与资料来源分别通过 ai_preparation_source、ai_material_draft_source 关联已采用任务；全部生成依据保留，即使没有被模型直接引用 |
| 题目 knowledgeLinks | 用户明确选入题目版本的知识关联，保存于题库既有关系；可为空，与候选引用不同 |

citation 的 start/end 为原始来源 body 的 UTF-16 偏移、左闭右开。服务端核对 Manifest 白名单、区间范围、代理对边界与 quote 完全匹配；合法定位不证明技术正确或引用蕴含结论。用户改写不自动获得逐句引用认证。候选引用及采用来源不生成知识语义边。

新增 knowledgeLinks 必须是当前 owner 可访问、ACTIVE、当前已发布的知识版本；不能因为模型曾引用某历史版本就允许新增过时关联，也不能填入准备对象 ID。用户主题选择同样按现有题库归属规则校验。采用时知识来源须为当前 ACTIVE 发布版，准备来源须满足对应核实状态、来源及父项目代次。

## 采用、忽略与原子事务

`POST /tasks/{taskId}/adopt-question` 接收 `expectedRevision`、`content: QuestionInput`、`confirmed=true`。采用仅适用于已成功且未决定的 QUESTION_DRAFT；提交值是用户最终内容，服务端不能用原模型候选覆盖。采用不发模型请求、不新增模型费用，AI 关闭或配置改变后仍可采用已有有效候选。

在现有 owner 工作区锁和同一数据库事务中依次完成：训练资料守卫 → 幂等回执判断 → 任务归属／用途／成功状态／未决定／revision 检查 → 来源当前性及最终 content 检查 → 创建题目与首版／主题／维度／知识关联 → AUTHORED 曝光 → 采用决定与全部来源记录 → 任务修订 → 回执。题库事务内创建入口使用 MANDATORY，复用常规创建的内容校验和首版逻辑。任何一步失败整体回滚。

任务 revision 使用非负 JS 安全整数；不为新题引入不存在的目标版本 CAS。费用核对或其他任务命令造成 revision 变化时，旧采用请求报冲突；重新读取后保留用户改写并重新确认。已有 UNKNOWN 费用本身不禁止采用成功候选，费用决定与候选决定保持独立。

`POST /tasks/{taskId}/ignore` 按 purpose 写对应决定，要求任务成功、有对应候选且未决定；忽略不要求来源仍然当前，不写题库、不退款，保留候选及费用记录。

单任务只产生一个终态决定 ADOPTED 或 IGNORED。原幂等键同请求返回原回执；同键异请求返回 IDEMPOTENCY_KEY_REUSED，新键重复决定返回 AI_CANDIDATE_DECIDED。采用／忽略并发由同一 owner 锁及唯一约束裁决。旧回执不代表当前可再次决策，必须读取数据库决定。资料守卫须在历史回执重放之前执行，沿用[IdempotencyService](../../backend/src/main/java/com/wenjie/studio/common/IdempotencyService.java)顺序。

来源改版／撤回／归档或准备代次变化时不能直接采用旧候选，可忽略或重新生成；后续历史阅读不删除原候选。已经采用的题目不随来源编辑而改写，来源状态在查询时动态显示。完整更正和删除传播仍由 S8 生命周期设计负责，不能绕过来源外键。

## 数据关系与接口

V14 新增 `ai_question_decision` 与 `ai_question_source`。前者以 owner＋task 唯一记录决定，以 owner＋题目＋题目版本的复合外键固定采用目标；ADOPTED 必须有目标，IGNORED 目标为空。后者依赖决定，保存 Manifest 知识来源的确切对象／版本、版本号、当时标题和顺序；来源有 owner 复合外键和反向查找索引。第九批准备来源在任务创建时存入独立关系表，读取采用历史时再按决定筛选。既有 V13／V14 知识外键保持领域语义。

Task 增加可空 `questionCandidate` 和 `questionDecision`；旧 `candidate`／`decision` 仍只表示知识用途。旧回执缺少新字段时按 null 处理，任务详情始终按用途读取持久结果，不能将一种候选反序列化成另一种。

`QuestionDecision` 字段为 status、targetQuestionId、targetVersionId、createdAt。ADOPTED 返回确切新题首版；IGNORED 两个目标字段为空。

以下路径统一加前缀 `/api/v1/ai`；需登录与 owner，POST 有 CSRF，除 preview 外所有命令有 Idempotency-Key：

| 方法／路径 | 内容 |
|---|---|
| POST /preview | 现有 PreviewRequest 支持 QUESTION_DRAFT，返回完整 Manifest、摘要和预算，无发送副作用 |
| POST /tasks | 现有 CreateTask 支持 QUESTION_DRAFT，授权一次尝试 |
| GET /tasks/{taskId} | Task 包含与用途匹配的候选／决定及 sourcesCurrent |
| POST /tasks/{taskId}/adopt-question | expectedRevision、content、confirmed=true → Task |
| POST /tasks/{taskId}/ignore | expectedRevision → Task，按 purpose 写忽略决定 |
| GET /question-origins?questionId&versionId&beforeVersion&limit | 查询所选题版本及更早版本的采用来源；beforeVersion 为正整数目标版本游标，limit 默认20、范围1–50 |

题目来源返回任务 ID、采用目标版本及版本号、采用时间、是否为所选版本的直接来源、全部 OriginSource 和下一页游标。来源状态与第九批追加的可空 kind 采用[准备候选契约](准备版本生成候选契约.md)，历史标题和版本号保持采用时值。后续人工修改的题版本只显示“历史采用来源”，不宣称新题干逐句源于旧材料。仅新建采用意味着每个题目至多有一条直接采用记录，查询仍明确限制不能包含未来版本。

错误沿用 INVALID_REQUEST、AI_TASK_NOT_FOUND、QUESTION_NOT_FOUND、AI_PURPOSE_INVALID、AI_STATE_CONFLICT、AI_CANDIDATE_DECIDED、AI_SOURCE_CHANGED、SOURCE_CHANGED、SOURCE_UNAVAILABLE、REVISION_CONFLICT、IDEMPOTENCY_KEY_REUSED 和 ASSISTANCE_CONFIRMATION_REQUIRED。错误码与请求字段以[合并 OpenAPI](openapi-s5.json)同步为准。

## 参考、曝光与训练守卫

AI 任务详情包含参考答案、检查点和资料原文，因此整个 AI 入口与来源接口属于受控资料出口。独立训练期间必须先在当前训练确认辅助，HTTP 读取、结果序列化、命令回执重放和前端迟到响应均受既有资料守卫约束。不能只在浏览器折叠参考而提前返回正文。

采用记 AUTHORED，说明用户参与题目编写；不记成系统已观察到的 REFERENCE_VIEW，也不宣称用户实际阅读或掌握。新题普通详情、列表和训练题快照仍使用不含参考的 QuestionPrompt；专用参考查看接口才返回参考并记录曝光。标准保持 PERSONAL_UNREVIEWED。

采用成功后题目可由现有题库／题单／路线选择流程供训练使用；已有训练快照不变化。动态追问需要另建训练实例的父题、根题、依据 answerId 和额度边界；评价也必须绑定确切已提交答案，不能自动读取“最新答案”或客户端提供正文。本批不改变这些训练模型。

## 前端保存与恢复

任务用途可选知识整理或出题；改变用途必须使旧预览与授权勾选失效。任务列表及详情明确用途，知识采用表单不能出现在题目候选上。

题目审阅显示模型原候选、引用及用户最终字段；轮询只刷新任务状态，不覆盖改写。采用、忽略、费用核对和重试互锁，草稿与费用输入分别跟踪。关闭／切换／导航／刷新保护未保存修改；页面内草稿尚无自动持久化，不能宣传刷新后可恢复草稿。

会话过期或独立训练遮挡时保留当前编辑和原请求／原幂等键，取消过时读取并撤销确认。相同 owner 重登后用原请求恢复；成功迟到回执须显式接收，随后刷新题库和任务。采用后导航题库应打开决定中确切题目，不重复执行采用。

## 服务器验收场景

下表均为待执行预期，不是运行结论。第三方协议字段沿用第一批既有取证；本批不新增供应商能力承诺。

| 编号 | 场景与预期 |
|---|---|
| QAI-01 | V13 升级 V14，任务用途约束、新表复合外键／唯一决定／CHECK 生效；历史知识任务和回执保留 |
| QAI-02 | 1–5 个显式知识版本和完整清单，切用途／要求／来源／配置废弃旧授权；未附带草稿、关联和附件 |
| QAI-03 | Chat Completions／Responses 分别返回合格题目；空参考、空检查点合法，未知字段、非法枚举／长度／引用／代理对、截断、工具和拒答被拒绝 |
| QAI-04 | 用户修改题干、参考和检查点后采用，保存最终提交；ACTIVE 首版、PERSONAL_UNREVIEWED、AUTHORED、来源关系准确 |
| QAI-05 | AI citations 不自动生成 knowledgeLinks；用户新增的引用必须当前有效；全部外发来源留痕而不生成语义边 |
| QAI-06 | 来源改版／归档拒绝采用但允许忽略；AI 关闭后仍可本地采用有效候选，费用未知不被释放 |
| QAI-07 | 同键重放／异正文拒绝、新键重复决定、双标签采用与忽略竞争；失败保留编辑，只创建一题 |
| QAI-08 | 题目版本、关联、曝光、决定、来源、任务修订或回执任一点写入失败整体回滚 |
| QAI-09 | 当前和历史题版本来源分页准确；历史标题不漂移，来源改版／归档状态正确，不含未来版本 |
| QAI-10 | 普通题目及训练快照无参考；采用后的题目可由既有题库选择创建训练；采用不自动启动会话 |
| QAI-11 | 独立训练的 AI 任务／来源读取、采用／忽略及旧回执均受资料守卫；跨标签和迟到响应不泄露参考 |
| QAI-12 | 轮询不覆盖改写；原键重登恢复、成功迟到回执显式接收、关闭导航提示、长文、窄屏及键盘操作有效 |

实现阶段主会话同步[技术架构索引](README.md)、[项目状态](../审查/项目状态.md)、[审查索引](../审查/README.md)与[开发进度](../开发记录/开发阶段与进度.md)。数据库迁移、真实模型、接口与视觉验收必须在用户提供的服务器环境执行。
