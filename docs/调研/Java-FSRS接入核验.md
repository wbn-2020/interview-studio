# Java-FSRS 接入核验

| 项目 | 内容 |
|---|---|
| 日期／版本 | 2026-11-08 · v1.0 |
| 状态 | 只读源码与官方制品证据；未执行算法或集成验证 |
| 适用范围 | S7 主动回忆排程适配；对应 [PRD MEM-01–07](../需求文档/产品需求文档.md) 与 [技术选型 §7.1](../技术架构/技术选型与决策.md) |
| 结论边界 | 支持按固定版本编写适配器；不代表 Java 21、依赖收敛、排程正确性或学习效果已验收 |

## 1. 固定版本与证据

核验对象为 `io.github.open-spaced-repetition:fsrs:1.0.0`，未按“最新版本”替换已有选型。GitHub 官方标签 `v1.0.0` 指向 `c561ee6c56239b621a0a45b16a6c2e688441eae5`；该提交 `build.gradle` 声明版本 `1.0.0`、Java 17，LICENSE 为 MIT。[F01][F02][F03]

Maven Central 的 POM 和 sources JAR 均返回 HTTP 200。只在内存中读取 sources JAR，未保存、安装或执行其中代码；其中六个核心类与固定提交对应源码一致，比较时仅统一换行与末尾空白。源制品 SHA-256：[F04][F05]

```text
822C1932FCC7D7A2224CD97BBDD651F208BF2C8D66FB44DAB995C07D3FB5257B
```

六个类为 `Scheduler`、`Card`、`ReviewLog`、`Rating`、`State`、`CardAndReviewLog`。此检查确认发布源代码对应关系，不是二进制可重现构建或运行证明。

## 2. 适配器所需 API 与时间语义

包名为 `io.github.openspacedrepetition`。下表根据固定源码及其 Lombok 声明整理，未执行编译。[F06][F07][F08][F09]

| 对象／入口 | 接入要求 |
|---|---|
| `Scheduler.builder()` | 可设置 `parameters(double[])`、`desiredRetention(double)`、`learningSteps(Duration[])`、`relearningSteps(Duration[])`、`maximumInterval(int)`、`enableFuzzing(boolean)`，随后 `build()` |
| `Card.builder()` | 可恢复 `cardId(Integer)`、`state(State)`、`step(Integer)`、`stability(Double)`、`difficulty(Double)`、`due(Instant)`、`lastReview(Instant)`，随后 `build()` |
| `reviewCard(Card, Rating, Instant, Integer)` | 返回 `CardAndReviewLog`；使用显式服务器评分时间，避免省略参数时使用内部 `Instant.now()` |
| 返回结果 | `result.card()` 为修改后的副本，`result.reviewLog()` 为库日志；原输入卡先经复制，再计算 |
| 评分 | `AGAIN(1)`／`HARD(2)`／`GOOD(3)`／`EASY(4)`，映射“忘记／困难／记得／熟悉” |
| 状态 | `LEARNING`／`REVIEW`／`RELEARNING`；新卡为 LEARNING、step=0、lastReview=null，未有稳定性和难度 |
| 排程持久化 | 独立保存 state、step、stability、difficulty、due、lastReview，并另存业务修订号、算法实现版本及参数版本 |
| 时间 | `Instant`，README 明确 UTC；经过天数为 `ChronoUnit.DAYS.between`，按完整24小时计算，不按用户日历换日；用户时区用于展示和每日预算 |
| 间隔 | 学习步骤为 `Duration`；长期间隔为整数天，`maximumInterval` 单位为天；due=评分时间+间隔 |
| 耗时 | `reviewDuration` 是可空 Integer，源码仅原样写日志且不参与计算；已读 README／源码未明确单位。建议产品保存有单位的 `elapsedMs`，初版向库传 null |

上游 `cardId` 为 int，缺省会把当前毫秒时间强转 int 并 `Thread.sleep(1)`。适配器必须显式赋值；该字段只参与库日志关联，不参与排程计算。业务 UUID／long ID 保持独立，不能截断业务 ID 来建立持久化关联。使用内部占位 ID 时，业务事件仍记录真实 cardId。[F07][F08]

## 3. 初始参数与源码限制

建议固定一个不可变参数配置：上游默认21项权重、desiredRetention=0.9、learningSteps=[1分钟,10分钟]、relearningSteps=[10分钟]、maximumInterval=36500，**enableFuzzing=false**。每个事件记录实际参数配置及版本；参数优化暂不提供，上游此版本没有 optimizer。[F06][F09]

| 边界 | 已读源码证据／建议 |
|---|---|
| 分钟级复习 | 新卡首次 AGAIN→1分钟、HARD→5分30秒、GOOD→10分钟、EASY→REVIEW；这是代码推导，未运行。队列须按实际 due 时间处理 |
| 同日评分 | 距上次不足24小时使用短期稳定性分支；不能把本地跨午夜等同算法经过一天 |
| 多步重新学习缺陷 | `Scheduler.java:591–599` 的 RELEARNING／HARD／step=0／多步分支错误读取 `learningSteps[0/1]`。两组步骤不同时会算错，学习步骤不足两项时可能越界；初版锁定单个重新学习步骤以避开该分支 |
| 参数与状态校验 | 上游 builder 未提供充分业务校验。适配器校验参数长度／有限数值、状态字段完整性、步骤范围、正间隔及评分时间不早于 lastReview；异常不得写入部分排程 |
| 共享可变对象 | Scheduler 保存数组引用，复制构造器共享 Random；构建时给独立数组，构建后不修改或向外暴露；关闭 fuzzing 便于重放 |
| 概率输出 | 有 `getCardRetrievability`，但不作为产品已验证的记忆概率展示；遵守 MEM-07 |

## 4. Jackson 依赖建议

FSRS 官方 POM 仅声明两个 runtime 依赖：`com.fasterxml.jackson.core:jackson-databind:2.15.3` 与 `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2`。[F04]

本次读取项目 backend/pom.xml，父版本为 Spring Boot 4.1.2。官方 Boot 4.1.2 BOM 导入 Jackson 2 BOM 2.21.7 和 Jackson 3 BOM 3.1.7。[F10]

**建议暂不添加 exclusions，由项目父 BOM 管理 Jackson 2 依赖。**若以后排除并显式重加，仍须保留兼容的 Jackson 2 运行依赖；不能只保留 Jackson 3 并假定该库可加载。核心类仍引用 Jackson 2 类型和注解，尚无移除依赖后的类加载证据。Lombok 为上游编译期依赖，项目无需因此新增 Lombok。[F02][F06–F08]

库的 JSON 方法创建局部 Jackson 2 ObjectMapper。产品使用自己的领域 DTO 存储、输出，不调用库的 `toJson/fromJson`，不向前端暴露库对象；此边界不等于已证明可移除库依赖。

## 5. 待验证事项

- 未安装依赖、未执行 Maven dependency tree／编译／测试、未运行下载代码，未启动服务；未访问凭据或服务器。
- 实际 Maven 依赖收敛、Java 21 编译及运行、Jackson 2／3 共存、算法参考序列与失败恢复仍待实施验证。
- 服务端需验证评分事件与排程原子更新、幂等／并发、分钟级队列、时区预算，以及来源更正／归档后的暂停与显式恢复。源码检查不能替代运行证据。
- 本次 `web.run` 未返回可引用正文，以上外部证据由透明只读 HTTPS 获取 GitHub 官方 API 与 Maven Central 内容；未运行参考项目或上游测试。

## 6. 官方来源

访问日期均为 2026-11-08。F06–F08 的行号按固定提交源文件计算；README 的行为描述已与源代码交叉检查。

| ID | 官方 URL／内容 |
|---|---|
| F01 | https://api.github.com/repos/open-spaced-repetition/java-fsrs/tags?per_page=30 —— v1.0.0 与提交对应 |
| F02 | https://github.com/open-spaced-repetition/java-fsrs/blob/c561ee6c56239b621a0a45b16a6c2e688441eae5/build.gradle —— 版本、Java17、依赖 |
| F03 | https://github.com/open-spaced-repetition/java-fsrs/blob/c561ee6c56239b621a0a45b16a6c2e688441eae5/LICENSE —— MIT |
| F04 | https://repo.maven.apache.org/maven2/io/github/open-spaced-repetition/fsrs/1.0.0/fsrs-1.0.0.pom —— 发布坐标与依赖 |
| F05 | https://repo.maven.apache.org/maven2/io/github/open-spaced-repetition/fsrs/1.0.0/fsrs-1.0.0-sources.jar —— 六个核心类逐份对照 |
| F06 | https://github.com/open-spaced-repetition/java-fsrs/blob/c561ee6c56239b621a0a45b16a6c2e688441eae5/src/main/java/io/github/openspacedrepetition/Scheduler.java —— 排程、步骤与边界 |
| F07 | https://github.com/open-spaced-repetition/java-fsrs/blob/c561ee6c56239b621a0a45b16a6c2e688441eae5/src/main/java/io/github/openspacedrepetition/Card.java —— 卡状态、ID、复制 |
| F08 | https://github.com/open-spaced-repetition/java-fsrs/blob/c561ee6c56239b621a0a45b16a6c2e688441eae5/src/main/java/io/github/openspacedrepetition/ReviewLog.java —— 日志字段与 JSON |
| F09 | https://github.com/open-spaced-repetition/java-fsrs/blob/c561ee6c56239b621a0a45b16a6c2e688441eae5/README.md —— 评分、参数、UTC、无优化器 |
| F10 | https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom —— 两代 Jackson BOM |
