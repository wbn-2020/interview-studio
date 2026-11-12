# AI 评价观察检索契约

版本：v1.0 · 2026-11-11。状态：静态检查通过待服务器验收，OpenAPI 7.28.0。范围：统一搜索的 AI_FEEDBACK 域；沿用[统一检索契约](跨模块统一检索契约.md)和[训练、复盘与回忆检索契约](训练复盘与回忆检索契约.md)的普通资料守卫、原文坐标和错误边界，实际进度见[开发记录](../开发记录/开发阶段与进度.md)。

## 1. 身份和只读范围

每条结果是一份成功评价的一条观察。id=taskId，versionId=成功attemptId，versionNumber=null；aiFeedback.feedbackId 为报告中的 f1…f12，reportHash 为原 candidate_json 字符串 UTF-8 字节的 SHA-256，小写64位十六进制。四项共同参与选中键、详情请求、响应核对及回链，不能伪造正文版本号或以重序列化JSON代替原字节摘要。

kind=AI_FEEDBACK，status=AI_UNREVIEWED；currentVersion=true仅表示仍对应任务的成功尝试，不表示同题最新评价或已核实。成功任务不能重试，独立决定变化不更改报告身份。任务缺失／权属不符或合法观察编号不存在返回404；固定尝试／摘要与来源不符返回409 SEARCH_SOURCE_CHANGED，不回退另一份报告。

只搜索 observation／condition／basis／limitation／suggestion 五字段。summary、citations、完整清单、题面／参考、结构ID和决定理由不参与匹配，也不从搜索详情返回。来源读取器为结构校验会在授权后解析完整报告；它不重新认证引文逐字对应或技术正确性，不能描述成完全不读取报告引用。完整引用回原报告查看。

历史AI正文允许NUL，保留完整Unicode及原文，不用其他域的禁NUL规则拒绝合法旧报告；必填仍按Java isBlank，不能用trim改写。命中片段及详情五字段的UTF-16坐标沿既有规则。

## 2. 授权和条件

元数据目录先按owner、成功ANSWER_EVALUATION、确切原答owner／session／question／answer许可、有效会话、模式／提交条件、反馈时机及提前确认限制可读集合。未开放报告不进入扫描计数、额外命中、摘要或隐藏数量。目录只返回任务／尝试／原答身份与OCTET_LENGTH(candidate_json)，不投影报告正文，不按整份JSON做LIKE。

报告正式读取复用 RelationAiFeedbackSources 的严格解析和原答访问守卫；一次读取／解析／哈希展开该报告全部观察，保留原单观察read／capture／state语义。损坏报告、缺失成功尝试、非法原文长度及不一致绑定显式失败，不静默短返或伪装无命中。

每条AI结果必须有training和aiFeedback上下文，feedback／recollection省略。training复用确切原答的完整提交条件、先前曝光及固定题目条件，不能按当前辅助状态回算。aiFeedback包含：

```text
feedbackId, reportHash, level, reportLimitations, validationNote,
dimension, coverage, condition, basis, limitation,
decisionRevision, decisionChoice
```

level始终AI_UNREVIEWED；完整报告限制、校验说明、条件／依据／限制随列表和详情展示，不由命中摘要代替。NOT_MENTIONED表示可能未提及，不等于技术错误或能力缺陷。

decisionRevision=0且decisionChoice=null表示尚未决定；1–20对应该观察本次读取时的最新决定。接受不认证技术正确性，争议／忽略不删除观察。列表到详情之间允许决定推进，保留同一报告身份并提示决定变化，不要求回到旧决定，也不把决定修订当报告版本。决定理由／时间未在此窄投影返回，不补造。

返回正文前在新的短只读事务重新核任务成功尝试、原答绑定及评价访问；不重新读取完整报告或调用授权写命令。普通资料守卫持续覆盖请求和返回。返回核对与网络发送仍有时间窗口，当前追加式访问记录不替代S8独立最新撤权账本。

## 3. 有界报告扫描

AI域使用已获准报告元数据扫描及Java原文字面匹配，区别于前两批的SQL字面候选。按taskId降序、报告内f1…fN数字顺序展开；offset计最终匹配观察，额外一条有效命中才hasMore，不提供精确总数。q／limit1–50／offset0–1000继续沿统一入口验证。

单批元数据最多16项，单次查询最多64份报告，参与读取报告原文累计最多8MiB；单报告沿原严格读取器最多1MiB。读取前按同一RR快照的字节长度核预算；null、空或超限是来源损坏，超过剩余查询预算则scanLimited=true，不读取超预算报告。64份报告最多768条观察，offset允许范围不保证本次扫描一定能到达该位置。

预算耗尽时无下一页承诺，scanLimited提示结果可能不完整，可调整检索词或回原模块；没有命中不代表全库没有内容。元数据seek只统计授权集合；既有索引是否支持有效查询须服务器EXPLAIN验证，不把64份报告当作数据库实际扫描行数上限。

搜索编排保持20秒只读RR事务；新增目录SELECT、被复用AI来源读取器四SELECT及原答access查询显式5秒。新旧搜索域保留原规则；AI_FEEDBACK没有归档状态，ARCHIVED返回applicable=false，不把训练结束说成归档。

## 4. API和回链

沿用两个GET，详情AI域新增feedbackId及reportHash可选参数，但该域两者必填；revision只适用于人工反馈。其他域带feedbackId／reportHash返回400。AI域versionId必为确切成功attemptId：

```text
GET /api/v1/search?domain=AI_FEEDBACK&q=缓存&status=ACTIVE&offset=0&limit=20
GET /api/v1/search/sources/AI_FEEDBACK/{taskId}?versionId={attemptId}&feedbackId=f2&reportHash={sha256}&q=缓存
```

Hit尾加可选aiFeedback，未适用时省略；保留原13及16参Java构造和旧七域响应形状。新的机器契约新增UnifiedSearchAiFeedbackContext，不改原报告或受审观察资格。

搜索页显示“仅搜索已在复盘确认开放的AI评价”，提供通用复盘入口，不复制开放写命令、不显示隐藏数量。列表／详情及迟到响应身份包含四项固定标识，新增正文只放页面暂态，不进入持久存储或Query正文缓存；失权、会话／请求代次变化清除正文。

严格回链为review：sessionId、questionId、answerId、taskId、observationId，以及新增成组aiAttemptId／aiReportHash。复盘先通过现有单观察窄来源核确切成功尝试、原报告摘要、观察和原答，再展示匹配报告；不匹配明确拒绝，不能回退普通任务报告。旧无严格参数链接保留原行为；参数缺失／非法须提示，不能当作普通链接放行。报告组件仅在验证后定位所选观察，不自动打开决定或形成动作。决定使用独立当前修订，不冻结于回链。

## 5. 待服务器验收

以下ASR-01–16全部未执行；静态审查和构建不代表运行或视觉验收。

| 场景 | 操作与预期 |
|---|---|
| ASR-01 | 五正文分别命中；报告summary、citations、参考、结构ID和决定理由不产生匹配 |
| ASR-02 | 无确切原答许可的报告不进入目录／摘要／计数／额外命中或扫描上限提示 |
| ASR-03 | 未结束AFTER_SESSION只有已提前确认的原答可读，换owner／题／提交不能借用 |
| ASR-04 | 同报告多观察键唯一，按task降序及f数字顺序分页，offset按实际匹配观察计数 |
| ASR-05 | 固定task／attempt／hash／f读取正确，缺参／非法返回400，来源变化409而非回退 |
| ASR-06 | 完整作答条件、曝光、报告限制、校验说明、观察限定及AI_UNREVIEWED同时可查 |
| ASR-07 | 决定0／null、接受／争议／忽略及推进准确，不改变报告身份，详情提示决定变化 |
| ASR-08 | 报告空／损坏／超长、成功attempt或绑定失配显式拒绝，不静默跳过截断分页 |
| ASR-09 | 旧报告NUL／NBSP／emoji和CRLF／CR／LF保留，原文UTF-16定位与必填规则一致 |
| ASR-10 | 元数据16／报告64／8MiB／单份1MiB边界、额外命中及scanLimited行为准确 |
| ASR-11 | 5秒语句／20秒事务、实际查询计划及内存开销符合限制，原七域查询兼容 |
| ASR-12 | ARCHIVED不适用，训练ENDED仍可在ACTIVE查询；读取不调用模型或写曝光／许可 |
| ASR-13 | 请求／返回及跨标签权限、会话和来源变化不借旧响应展示正文，原守卫不放宽 |
| ASR-14 | 严格复盘回链先核四项身份及原答，不匹配不展示整份报告，旧链接兼容 |
| ASR-15 | 观察定位只滚动／聚焦，保持保稿守卫，不触发决定／动作或开放命令 |
| ASR-16 | 键盘／焦点／窄屏、完整限定长文、空态／错误恢复及视觉验收 |

全局命令面板、显式选取来源后的跨域问答清单及S8生命周期／文件交换／交付继续推进；本批不是完整产品验收。
