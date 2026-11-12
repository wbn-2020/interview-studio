# AI 任务与外发契约

日期：2026-11-10。版本：v1.20。状态：S5 第一批代码及构建已完成，后续用途、来源、持续授权及暂态正文规则由下列专项契约补充。实际批次状态以[开发进度](../开发记录/开发阶段与进度.md)为准，运行及真实供应商兼容待服务器验收。适用 AI-01–06、KNOW-07；完整产品范围不缩减。用户确认先做可配置兼容接口，并要求同时支持 Responses。

第三十一批[单次尝试暂态正文流](AI单次尝试暂态正文流契约.md)增加默认关闭的显式流式配置、两协议增量接收及独立正文出口。开启后纳入公开配置、清单与授权指纹，每次仍沿原预览和提交；正文只在有界内存与纯文本预览中存在，不进入原无正文通知或正式候选缓存。断线不重发模型，终态与取消处理回原任务查询；所有用途的最终严格校验和费用规则保持独立，矛盾用量保持待核对。

第三十批[持续范围授权与撤回](AI持续范围授权与撤回契约.md)在旧单次授权之外增加独立许可及逐尝试绑定。许可从本人已持久任务的完整清单重新确认创建，限定用途、完整配置与固定版本／片段；每次仍预览并主动提交。撤回停止本许可下待发任务并阻止在途结果发布，保留已发生或未知费用；旧清单、摘要和成功原键回执保持兼容。完整对象用途撤回及独立最新账本仍归 S8，不能用本批许可表替代恢复守卫。

## 本批边界

第二十八批[AI反馈生成关系候选](AI反馈生成关系候选契约.md)沿 RELATION_DRAFT 增加第四种模式与独立预览／创建入口。Manifest第24项 nullable relationAiFeedback 固定既有18字段Snapshot，旧缺省／null仅从hash投影剥离该尾，非空完整参与摘要；其他用途与其他关系模式拒绝混入。新提示 relation-ai-feedback-draft-1，唯一发送投影 `{purpose,instruction,sources,relationAiFeedback}` 与预览、字节预算一致。完整必要限定保留原历史文字，选区与引文只限明确片段；每条候选至少一条复用形状的reviewCitations，并由Snapshot专用规则校验。V53固定新任务与成功评价身份，前台先核固定答案访问，不能以state代替权限；原键保留原清单和费用记录。

第二十七批[人工反馈／面后回忆关系候选](人工反馈与面后回忆关系候选契约.md)沿同一 RELATION_DRAFT 使用独立 `relation-review-draft-1`，完整投影为 `{purpose,instruction,sources,reviewDraft}`。知识、指令及完整固定复盘输入共同进入预览、预算、摘要和实际发送；完整必要背景不扩大引文权限，每条候选至少一条 reviewCitations，与知识引用合计1–6。该批复用 Manifest.reviewDraft 和 V43，不增加清单字段或改变当时hash函数；旧知识整理按原用途严格分流。绑定、前台访问与后台当前性分别核对，原键恢复不因正常改版而失效；AI 反馈使用第二十八批独立模式。

第二十六批[资料／训练来源关系候选](资料训练来源关系候选契约.md)沿RELATION_DRAFT增加独立预览／创建入口，完整外发投影为`{purpose,instruction,sources,relationEvidence}`。指令原文、2–5个知识版本及完整所选摘录／回答片段共同纳入预算与hash，至少两个概念作为端点；每项候选必须引用直接依据，三类引用合计1–6。Manifest新尾relationEvidence仅在null时从摘要省略，旧prompt、请求和回执保持；其他用途拒非空混入。V52固定来源与任务、尝试、费用在同一owner事务提交，读取／发送／接纳／决定及原键恢复核绑定。手工关系证据保存与这次外发授权分别处理；人工反馈／回忆候选由第二十七批独立入口接入。

第十六批接入[自检／外部反馈／面后回忆片段](复盘与面后片段沉淀知识契约.md)，沿用知识候选任务和两种兼容协议，使用独立 reviewDraft 固定输入及逐次授权。当前累计机器契约是 [OpenAPI S7](openapi-s7.json)；以下首批边界保留其原适用范围，后续来源及用途以专项契约为准。

第十七批[AI 制卡与采用来源](AI制卡与采用来源契约.md)增加 MEMORY_CARD_DRAFT：明确选择类型及 1–5 个当前发布知识，完整预览后生成一张 QA／CLOZE 候选；核对引用、编辑后显式新建卡片，保留确切来源和采用记录。采用不调用模型、不启用排程或记录复习；制卡输入、严格校验和旧清单摘要兼容见专项契约。

第十八批[JD 要求提取与采用](JD要求提取与采用契约.md)增加 JD_REQUIREMENT_DRAFT：只读取显式选择的确切当前目标标题及原始 JD，返回带原文引用的要求候选。用户编辑、选择后追加同目标新版本，保留旧要求和其他字段；采用映射及历史来源单独记录。Manifest 不增加字段，复用准备来源和非负代次，旧哈希协议不改。采用后输入自然成为历史版本，不阻断已保存决定或回执；要求及维度建议仍不认证能力或岗位实际考核。

第十九批[资料与训练事实候选](资料与训练事实候选契约.md)增加 FACT_DRAFT：明确选择资料摘录或确切已提交回答选区，预览授权后生成一条待核实候选，人工编辑并选择项目后只新建事实。训练外发不含题面、评价或参考，项目不参与生成；责任与核实均保持 UNVERIFIED。Manifest 新尾 factDraft、旧摘要空值兼容、引用、固定来源及原子采用规则集中在专项契约；本批独立静态复核及前后端构建通过，20 项服务器场景仍全部未执行。

第二十批[训练改写参考与并排展示](训练改写参考与并排展示契约.md)增加 ANSWER_REWRITE：按确切答案先确认评价／复盘区访问，再完整预览并授权生成只读改写。复用 Manifest.evaluation，不增加清单字段或改变旧摘要；两协议与预算使用唯一 `{purpose,evaluation}` 投影。改写独立绑定与额度，不计入评价覆盖；原答、个人参考和 AI 候选分别展示，引用及待补问题保留。该用途没有采用／忽略，不产生正式答案或复练完成记录；V47及20项服务器场景全部未执行。

第二十一批[正式关系补充理由与来源](正式关系补充理由与来源契约.md)为已有 RELATION_DRAFT 候选新增独立的本地补充命令。用户核对确切关系 ID、规范化端点／类型及全部固定知识来源后，追加不可变记录并保存逐项决定；原新建采用仍拒绝重复关系。RelationDecision 仅尾加可空 supplementId，旧缺省按 null 兼容；原始 AI 建边出处与补充历史分别查询。Manifest、模型输入、用途、提示版本和旧清单摘要保持原样，不产生新模型请求或费用。V48及20项服务器场景全部未执行。

先实现知识整理候选这一条链路：选择 1–5 个已发布且 ACTIVE 的知识对象 → 预览实际外发版本／正文、用途、供应商和费用预留 → 本次授权 → 持久任务／执行尝试 → 校验后展示候选及引用。正文不会自动写回知识库。仅支持 KNOWLEDGE_DRAFT，用途授权不能借给其他任务。

候选正式采用与来源留痕由第二批[AI 候选采用与来源契约](AI候选采用与来源契约.md)补充；第三批[AI 出题候选与题库采用契约](AI出题候选与题库采用契约.md)扩展 QUESTION_DRAFT。第四批[训练 AI 追问](训练AI追问契约.md)与第五批[确切答案评价](训练评价与证据报告契约.md)分别限定训练输入及访问条件，复用本篇外发、费用和恢复规则。第六批[状态推送与恢复](AI任务状态推送与恢复契约.md)接入仅含通知游标的 SSE 和查询退路，该批模型协议为非流式。第七批[训练背景更正与 AI 使用](训练背景更正与AI使用契约.md)补充当前来源检查、失效背景排除、代次及在途接纳。第八批[资料摘录问答](资料摘录问答契约.md)扩展 MATERIAL_QA，分别处理确切摘录问答和显式通用解释。第九批[准备版本生成候选](准备版本生成候选契约.md)允许目标／项目／已确认事实与知识混选，用于知识整理或出题，保留独立来源代次与采用追溯。持续范围授权由第三十批专项契约补充，模型暂态正文由第三十一批独立接入；跨模块统一检索仍待实施，比较及更多输入／沉淀以各专项契约与进度记录为准；不展示假流式输出或模拟模型结果。

第十批[附件摘录生成候选](附件摘录生成候选契约.md)扩展知识整理／出题的显式资料输入，可混选知识及准备，资料引用和采用来源保持独立身份；资料问答用途及结果不变。其生命周期与费用继续遵守本篇规则。

第十一批[事实生成表达候选](事实生成表达候选契约.md)扩展 EXPRESSION_DRAFT，仅输入显式选中的当前确认事实，生成表达并供用户编辑后新建／追加表达版本。Manifest.expressionType 固定授权类型；全部事实代次贯穿发送、接纳与采用。表达的结果、决定及准备域 FACT_BASIS 独立保存，不改变知识／题目候选语义。

第十二批[准备建议与任务](准备建议与任务契约.md)扩展 PREPARATION_PLAN。仅使用单个确切目标、显式准备区间／时区／整个区间预算和用户自述缺口；人工改写并采用后创建普通准备任务。学习预算与模型费用独立，未读取日历或能力画像，不自动调整既有任务。完整今日、回忆与重新计划仍依 S7。

第十三批[训练片段沉淀知识](训练片段沉淀知识契约.md)为 KNOWLEDGE_DRAFT 增加独立训练输入。仅使用固定题面、明确选中的确切原答片段及可选同答反馈；训练来源与知识版本引用分开。人工采用复用知识版本与决定，保留原答／未审反馈身份，不修改反馈决定或项目事实。

第十四批[表达素材输入与来源](表达素材输入与来源契约.md)在知识整理／出题中增加明确选择的已有表达版本。仅发送表达类型与正文投影；关联事实与父项目代次作为本地授权检查，不自动外发依据正文。表达不认证事实，采用来源保留确切表达并显示当前是否待复核。

第十五批[知识关系候选与逐项采用](知识关系候选与逐项采用契约.md)扩展 RELATION_DRAFT。输入 2–5 个显式选择的当前知识版本，至少两个概念，笔记可作证据；完整预览与授权后生成独立候选。无足够依据时允许零候选，用户逐条修改并确认后创建正式关系。来源、费用、取消及重试沿用本篇规则，逐项决定与删除关系后的来源保留见专项契约。

## 配置与第三方协议

使用 JDK 21 HttpClient 与现有 Jackson，薄适配层支持 Chat Completions 和 Responses 两种兼容协议，默认非流式；第三十一批允许通过 STUDIO_AI_STREAMING 显式开启暂态增量接收。服务器以 STUDIO_AI_PROTOCOL 明确选择 CHAT_COMPLETIONS（默认）或 RESPONSES，再配置该协议的完整 HTTPS 端点；不自动拼接地址、探测或失败换协议。协议和开启后的流式选项是清单与配置指纹的一部分，切换后须重新预览授权。

服务器另配置供应商名称、模型、配置修订、价格修订、币种、输入／输出每百万 token 的微单位价格、每日预算、输出上限和超时，不预填模型或价格。缺失／非法单价对外显示 -1，非法预算／额度／超时显示 0，configured=false；不将未配置当作免费。禁止重定向、工具调用、远程材料抓取和本地模型。

下表为默认非流式约定；开启流式后的请求选项、事件及终态核对见[单次尝试暂态正文流契约](AI单次尝试暂态正文流契约.md)。

| 协议 | 请求与结果约定 |
|---|---|
| CHAT_COMPLETIONS | messages、stream=false、n=1；输出上限字段可配置 max_tokens 或 max_completion_tokens；启用 JSON mode 时发送 response_format.type=json_object；只接纳单条 assistant／finish_reason=stop，usage 为 prompt_tokens／completion_tokens |
| RESPONSES | instructions＋input 的 input_text、stream=false、max_output_tokens、store=false；启用 JSON mode 时发送 text.format.type=json_object；不发送 previous_response_id／conversation／工具，只接纳 completed 响应中 completed 的单条 assistant message，拼合 output_text；reasoning 条目不保存或展示，拒绝工具／refusal／截断；usage 为 input_tokens／output_tokens |

两种格式统一成内部 Candidate 与输入／输出用量。store=false 是本次请求参数，不构成供应商无日志／无保留承诺。缓存折扣、特殊服务层和供应商计费规则不自动推断，当前费用是按服务器配置统一价格估算。非流式响应体最大 256 KiB；流式线上、单帧及累计正文上限见专项契约。连接最多 10 秒，完整响应体等待最多为配置时限（1–120 秒）。请求正文只允许一次订阅，应用不重试网络请求；网络／代理／供应商执行次数仍不能作恰好一次保证。

密钥仅从本应用服务器环境的 STUDIO_AI_API_KEY 读取，不从其他应用或用户文件发现；任何查询、日志、导出或配置快照都不得包含密钥。默认 STUDIO_AI_ENABLED=false；缺配置时只显示未配置／未启用，不发请求。配置指纹由公开配置与明确的配置修订构成，替换凭据时也必须递增配置修订。进程另有随机授权代次，旧进程排队授权不能在新进程直接发送；已可能发送的尝试转未知，绝不自动重发。用户可在新进程重新预览或明确重试同配置的失败任务。

## 预览、授权和来源

预览是无持久副作用的 POST；服务器读取确切发布版本，拒绝归档／不存在／非当前发布版，最多 5 条，知识正文和指令合计序列化上限 32 KiB，不静默截断。instruction 最多 1000 字符。资料内指令只是数据，不能增加授权范围或工具权限。

Preview 返回 Manifest、manifestHash 和 Budget。Manifest 包含 purpose、instruction、sources、configuration、inputTokenAllowance、reservedMicros、promptVersion；sources 每项含 itemId、versionId、number、kind、title、body。创建提交同样的用途／指令／来源、expectedManifestHash 和 consentConfirmed=true，owner 锁内重算并匹配，才固定快照、授权、预留和排队。配置、价格或来源变化要求重新预览。

追问清单额外保存全部固定背景的 `contextChecks`，参与授权摘要但不发给模型；实际 `training.context` 只含仍有效的原快照。来源变更及往返变化使旧授权失效，失败后按原规则保存实际／未知费用。旧追问清单缺少核对记录时仍可读取历史及核对费用，不能继续新发送或接纳。单答评价不发送训练背景，具体规则以第七批契约为准。

资料问答使用独立的 `/material-qa/preview` 与 `/material-qa/tasks`，`materialQa` 固定模式、问题和确切摘录。检索命中不自动外发；无命中或证据不足不自动换为通用解释。回答及其引用由专用结构校验，不能按知识候选采用；任务、费用、取消和未知恢复仍按本篇规则。

发送前及接纳结果前再次检查知识归属、ACTIVE 和当前版本、授权代次及配置。发送时不持有数据库锁。排队取消释放未发预留；记录可能发送边界后，取消阻止结果发布，若最后检查已通过仍可能发往供应商，不能承诺远端已经停止或不计费。成功候选后来源有变更时显示过时状态，不能伪装最新依据。

## 状态、预算和恢复

- Task 与 Attempt 分开；Task 状态为 QUEUED／RUNNING／VALIDATING／SUCCEEDED／FAILED／UNKNOWN／CANCEL_REQUESTED／CANCELED。普通写命令使用 owner 锁、CAS 和原幂等键。
- 本批引用校验在网关内同步完成，不另行持久提交 VALIDATING；该状态保留在契约中，不在界面制造校验进度。AI 关闭时后台仍恢复旧进程的未决记录，但不发送新请求。
- 单实例一次执行 1 个请求，工作区最多 20 个未结束任务，每任务最多 3 次尝试。新尝试必须显式授权；发送边界在网络调用前先持久化，租约过期的已发送尝试仅标 UNKNOWN。租约／令牌防止旧进程发布结果，不能提供第三方恰好一次执行保证。
- 费用状态独立为 RESERVED／REPORTED／UNKNOWN／RELEASED／RECONCILED。货币以整数微单位保存，不用浮点；输入按实际提示 UTF-8 字节加固定余量作保守预留、输出按配置上限预留，不宣称该估计是供应商计费承诺。
- 已报告用量按当次配置价格计算，标为用量估算而非账单实扣。无可靠 usage、超时、连接断开或异常响应都保留 UNKNOWN 和原预留。失败校验不等于免费；取消已发任务也不能释放费用。
- 每日预算按 UTC 日。当天 REPORTED／RECONCILED，加所有日期 RESERVED／UNKNOWN 占用一起限制新的尝试；未知预留跨天保留。报告用量超出预留时按实际估算记入并阻止超预算新任务，不声称能绝对限制供应商端收费。
- 其他币种的未结费用或当日支出使新发送返回 AI_CURRENCY_PENDING；不静默换算或忽略占用。金额计算使用精确整数，输入和输出各自向上取整到微单位；聚合显示超过 JS 安全整数时饱和，预算判断仍使用精确值。
- UNKNOWN 可以明确确认再次尝试，旧未知费用继续占用，新尝试另预留。也可根据外部账单手工核对 amountMicros 与说明，记录 RECONCILED（用户核对），不把人工输入当作供应商证明；核对费用不会恢复丢失结果。
- 普通 GET 不更改任务；后台恢复及写命令处理过期状态。断网后查询任务和尝试；创建／取消／重试／费用核对丢响应，保留原正文与幂等键恢复。

## 候选及引用校验

模型输出限定 JSON：title 与 sections；每节 heading、body、citations。每个 citation 含 itemId、versionId、start、end、quote，引用只能指向本次清单，UTF-16 区间与原文子串必须完全一致。标题最多 200 字符，1–12 节，节标题最多 200、正文最多 3000，全部节正文最多 24000；每节 1–5 个引用，quote 长 1–500。拒绝未知字段、类型错误、空内容、越界引用、截断、工具调用和非 stop 结束。

通过校验只表示结构、来源及原文定位合法，不能自动证明技术正确或语义蕴含；界面标为“AI 候选，引用位置已核对，内容仍需审阅”。无效输出不作正式结果，错误正文／鉴权信息不返回浏览器或普通日志。

## API

统一前缀 /api/v1/ai。所有读写需登录、owner；写入有 CSRF，除预览外命令均带 Idempotency-Key。AI 页面、资料 API 和既有回执都纳入独立训练的资料守卫，不允许以知识整理绕过辅助确认。第六批仅为精确 `GET /events` 添加例外，因为事件只含通知游标，不含任何业务正文；详见状态推送契约。

| 方法／路径 | 内容 |
|---|---|
| GET /settings | 安全配置状态与当前 Budget |
| POST /preview | PreviewRequest → Preview，无发送／费用副作用 |
| GET /tasks?cursor&limit | 任务摘要分页，不返回清单正文或候选 |
| POST /tasks | CreateTask → Task，创建并授权一次尝试 |
| GET /tasks/{id} | Task 摘要、固定 Manifest、Attempt 清单、校验后 Candidate、sourcesCurrent |
| POST /tasks/{id}/cancel | expectedRevision；撤回接纳资格，已发费用保留 |
| POST /tasks/{id}/retry | expectedRevision、consentConfirmed、acknowledgeUnknownCost；重新核对来源／配置和预算，增加尝试 |
| POST /tasks/{id}/attempts/{attemptId}/reconcile | expectedRevision、amountMicros、note；仅核对 UNKNOWN 费用 |

第二批追加采用／忽略及知识版本来源查询，接口与事务规则见[候选采用契约](AI候选采用与来源契约.md)。Task 的可空 decision 表示独立于任务状态和费用状态的终态决定；旧幂等回执缺少该字段时按 null 处理，最新状态仍须读取任务。

错误至少包括 AI_DISABLED、AI_NOT_CONFIGURED、AI_CONFIG_CHANGED、AI_MANIFEST_CHANGED、AI_SOURCE_CHANGED、AI_BUDGET_EXCEEDED、AI_CURRENCY_PENDING、AI_UNKNOWN_COST_CONFIRMATION_REQUIRED、AI_LIMIT、AI_STATE_CONFLICT、REVISION_CONFLICT 和 ASSISTANCE_CONFIRMATION_REQUIRED。错误不得清空用户输入。[合并 OpenAPI](openapi-s5.json) 与 [V12 增量表](../../backend/src/main/resources/db/migration/V12__ai_tasks.sql)随本契约维护；迁移仅在服务器显式执行。

## 外部证据与待验证

2026-11-06 已调用 web.run 查询／打开，但未返回可读正文。随后只读 HTTPS 取得 DeepSeek 官方 create-chat-completion 和 json_mode 页面（HTTP 200）：JSON mode 仍需明确 JSON 指令；可能有空白内容，length 表示截断；usage 提供 prompt_tokens／completion_tokens。这只支持兼容适配的字段依据，不证明所有兼容供应商行为相同。资料地址为 `https://api-docs.deepseek.com/api/create-chat-completion`、`https://api-docs.deepseek.com/guides/json_mode`。OpenAI 官方参考页返回 403、Oracle Java 21 页超时，未将这些请求作成功取证。

Responses 补核：OpenAI 官方开发者站／Platform 页均返回 403，web.run 无正文，不能作为成功引用。改用 GitHub API 只读获取 OpenAI 官方 openai-python 仓库固定提交 `c6fc264689d384d5caec150fafa1a9567c4a3f56`（提交日期 2026-11-05）的类型源码：`src/openai/types/responses/` 下 `response_create_params.py`、`response.py`、`response_usage.py`、`response_output_message.py`、`response_text_config_param.py`、`response_format_text_config_param.py`、`easy_input_message_param.py`、`response_input_text_param.py`，8 项 HTTP 200。入口示例为 `https://api.github.com/repos/openai/openai-python/contents/src/openai/types/responses/response_create_params.py?ref=c6fc264689d384d5caec150fafa1a9567c4a3f56`。这些是一手字段证据，不是接口运行证明；未执行或安装远端源码。中间消息中未获工具正文支持的官方引用已更正，不纳入证据。

全部待服务器：两种协议的配置关闭无发送、实际供应商字段／价格、数据库约束、授权与来源竞争、预算并发与跨天、原键恢复、进程重启、网络未知／取消／迟到结果、引用定位、候选质量、窄屏键盘和真实内存／时限。不得在本机启动服务、发付费请求或编写单元测试。
