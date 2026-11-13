# 完整导出与恢复格式 v1

日期：2026-11-13。状态：代码与离线入口已实现，待服务器验证；不表示数据库或服务器验收通过。

HTTP 前缀 `/api/v1/lifecycle/archives`。全部通过现有 owner 身份、CSRF 和独立训练资料守卫。

| 方法 | 路径 | 请求与结果 |
|---|---|---|
| GET | `/status` | `DataArchiveDtos.Status` |
| GET | 空路径 | 最近 50 个 `Job`，封装为 `JobPage.items` |
| POST | 空路径 | `Idempotency-Key`；严格单字段 `{confirmedSensitiveData:true}`；202 `Job` |
| GET | `/{id}` | 当前 `Job` |
| GET | `/{id}/content` | 私有 ZIP 下载，`X-Content-SHA256`，无公共链接 |

配置前缀 `studio.dataarchive`：`enabled=false`、`directory=""`、`maximum-bytes=1073741824`、
`maximum-rows=1000000`、`expiry-hours=24`。主应用配置需显式映射部署环境变量。

ZIP 包含 `manifest.json`、每张业务表的 `tables/<name>.ndjson` 和登记的原附件／成功解析输出。
NDJSON 每行是按清单可写字段顺序排列的字符串或 null 数组，保留数字精度及 UTC 数据库时间。
生成列不写入；表定义、字段、主键、外键、每个载荷文件分别核 SHA-256。
隐私已清附件必须逐项列在 `omittedFiles`，恢复时由独立最新账本再次证明遗漏合法。
导出拒绝进行中的模型／附件后台任务；原 worker claim_token 不进入包，已发送终态用固定不可执行占位值。

## 恢复集成

入口主类 `com.wenjie.studio.dataarchive.ArchiveRestoreMain`，由发布 JAR 的
`org.springframework.boot.loader.launch.PropertiesLauncher` 和 `loader.main` 选择，不启动 Spring。
必须设置 `STUDIO_RESTORE_ENVIRONMENT=true`。连接仅读取显式的
`STUDIO_RESTORE_DATABASE_URL`、`STUDIO_RESTORE_DATABASE_USERNAME`、`STUDIO_RESTORE_DATABASE_PASSWORD`。
目标库先由服务器发布步骤执行全部迁移；本工具不运行迁移，不创建或删除数据库。
同时显式配置 `STUDIO_PRIVACY_LEDGER_DIRECTORY`（业务备份之外的最新独立账本）、
`STUDIO_MATERIAL_DIRECTORY` 和 `STUDIO_SEARCH_DIRECTORY`。后两者必须与恢复命令传入的目录、
后续应用的运行目录一致；独立账本目录不能与恢复目录相互包含。
后续应用的 `STUDIO_OWNER_ID` 必须等于恢复的 owner，不能把旧工作区静默挂到另一个身份下。

- `prepare --owner <uuid> --database <name> --confirm-empty-environment <same-name>`
- `preview --owner <uuid> --database <name> --archive <absolute-file> --latest-sequence <n> --latest-sha256 <latest-independent-head>`
- `restore` 在 preview 参数外增加 `--archive-sha256 <preview-sha>`、`--materials <empty-absolute-directory>`、
  `--indexes <empty-absolute-directory>`、`--confirm-empty-environment <same-name>`。
- `finish --owner <uuid> --database <name> --materials <restored-directory> --indexes <new-empty-directory>`
  加 `--latest-sequence`、`--latest-sha256`、`--confirm-sealed-environment <same-name>`，接续已提交正文后的验证阶段。
- `ai-configuration`：仅打印当前公开模型配置和 `configurationFingerprint`，不连接数据库，
  不读取真实模型密钥。输出 `credentialsChecked=false`，不能作为供应商密钥或模型调用验收。
- `confirm-ai --owner <uuid> --database <name> --latest-sequence <n> --latest-sha256 <latest-independent-head>`
  加 `--configuration-fingerprint <from-ai-configuration>`、`--confirm-new-authorizations required`、
  `--confirm-restored-environment <same-name>`。这是恢复后明确解除模型封锁的独立命令；
  只接受业务已开放、全部旧范围授权已撤销、无活动模型任务的恢复环境。确认不会重建旧授权。

恢复进程获得目标库专用 advisory lock；V83 `data_restore_gate` 保持封锁。
仅已迁移空库允许导入，所有 INSERT 都是精确新建；遇外键父记录未就绪分轮重试，其他错误立即失败。
不执行 `FOREIGN_KEY_CHECKS=0`、删除库或覆盖在用数据。失败留下封锁、已创建文件和诊断状态，避免误报回滚了磁盘。
V83 同时绑定实际恢复的附件与索引目录。`finish` 必须沿用原附件目录；失败的索引生成保留，
接续时改用新的空索引目录并同步服务器搜索目录配置，不覆盖旧索引或清理失败副本。

`LedgerArchiveLifecyclePolicy` 同时注册为唯一 Spring bean 和唯一离线 ServiceLoader provider。
它校验 `PrivacyLedger` 完整哈希链及外部最新头，核对导出快照／导入记录的检查点，
在调用方 JDBC 事务中通过生产 `PrivacyScope.replayRedactions` 和 `PrivacyLifecycle.setState`
重放正文清除与对象状态，并按同一已验证链逐条 CAS 推进业务检查点；事务完成前再次验证最新头。
独立账本中的所有不可逆字段定位会再次约束旧包；已清除附件不落入恢复目录。
回忆记录与题单的回收站状态按各自真实修订上限保持归档；恢复访问身份不重新激活旧内容。
`MATERIAL_EXCERPT` 的 `REVOKE`／`RESTORE` 分别重放为摘录的 `REVOKE_AI`／`ALLOW_AI`，
保留本地归档状态，在同一恢复事务中递增生命周期修订并追加合法转换事件。
恢复事件明确标注账本序号、原操作身份及哈希，不补造未保存的原用户理由。
仅重放包内检查点之后的许可事件；已随包保存的历史不重复追加。旧包不存在的摘录仅保留账本状态，
不伪造正文；存在摘录却缺少合法基线、连续历史或最终许可不匹配时拒绝导出／开放。
已恢复的全部旧 AI 范围授权转为撤销、活动复习排程转为手动暂停，均不会自动重启。

`SearchArchiveRecovery` 复用生产 `GenerationIndex`、`SearchAnalyzers` 和 `SearchMapper`，
只向新的空目录建立真实 Lucene 代，排除回收站／清除内容，并逐项核验版本、修订与总数。
验证通过后才确认确切的 outbox 事件并开放业务。缺少账本实现或任一后置条件失败均不能解锁。

`ArchiveServingGate.requireAiAllowed()` 接入 AI 认领和发送前的既有守卫。
该组件在应用启动与 HTTP 层拒绝非 OPEN、owner 不一致或运行目录不匹配的环境；成功恢复仍 `ai_blocked=true`。
执行 `confirm-ai` 后，守卫还会逐次比较应用实际公开配置指纹与确认值；密钥缺失导致配置无效、
公开模型配置变化或确认未完成时仍拒绝发送。密钥轮换须遵循既有规则递增 configVersion。
模型配置不会从包中恢复，每次新的模型用途仍须走正常授权与费用确认。

本契约资源供发布工具核对；公开产品说明、机器 OpenAPI 和部署指令由主会话同步至分类 docs。
