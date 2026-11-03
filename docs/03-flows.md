# 问阶：模块、业务流程与数据流

图中服务端、模型任务和可靠存储是正式应用的目标设计。原型只展示本地交互与固定样例。可视化版本见 [流程页面](../prototype/flows.html)。

## 1. 信息架构／模块图

```mermaid
flowchart TB
    A[当前目标岗位] --> B[准备空间]
    A --> C[个人材料与确认事实]
    A --> D[题库与知识练习]
    A --> E[知识／项目模拟面试]
    A --> F[架构演练]
    C -->|授权上下文| E
    D --> E
    E --> G[复盘与复练]
    F --> G
    G -->|可执行的复练任务| B
    G --> D
    G --> E
    G --> F
    H[模型与数据] -.->|调用授权与数据出口| A
```

## 2. 准备业务流程／用户旅程

```mermaid
flowchart LR
    A[添加目标岗位或选择模板] --> B[确认JD要求]
    B --> C[确认个人材料]
    C --> D{选择训练}
    D -->|知识| E[回答单题或模拟]
    D -->|项目| F[基于事实追问]
    D -->|设计| G[画图与解释取舍]
    E --> H[证据式复盘]
    F --> H
    G --> H
    H --> I{用户确认改进动作}
    I -->|加入复练| J[有来源的训练任务]
    I -->|标记争议| K[保留不确定判断]
    J --> D
```

材料不是基础训练的强制门槛；当用户选择个性化项目训练时，使用已确认且有角色边界的事实。

## 3. 模拟面试数据流／DFD

```mermaid
flowchart LR
    subgraph U[用户侧]
      A[目标／材料／问答界面]
      H[反馈审阅与复练选择]
    end
    subgraph S[服务端]
      B[权限与上下文检查]
      C[会话快照与任务]
      F[输出结构与引用校验]
      G[问题／反馈候选]
      I[报告与复练保存]
      V[(岗位／确认事实／回答／任务)]
    end
    subgraph X[外部服务：用户授权后]
      D[模型 API]
    end
    A -->|目标ID、回答、模式、会话版本| B
    V -->|JD快照、授权事实、已有问答| C
    B --> C
    C -->|必要上下文| D
    D -->|问题与反馈提议| F
    F --> G
    G -->|回答引用、事实依据、不确定判断| H
    H -->|确认动作| I
    I --> V
    V -->|新任务与保存状态| A
```

## 4. 白板版本与追问数据流

```mermaid
flowchart LR
    A[节点／连线／用户解释] --> B[保存图版本]
    B --> C[图快照＋题目约束]
    C --> D[模型追问]
    D --> E[图元素与引用校验]
    E --> F[用户回答／修改图]
    F -->|回答绑定原图版本| G[问答记录]
    F -->|改图| B
    G --> H[复盘问题定位]
    B --> H
    H --> I[重练并比较新旧方案]
```

### 数据不变量

- 每条图追问绑定确切版本与节点ID，不能指向模糊的“当前图”。
- 节点删除后，历史快照仍可回放；新报告检查引用是否有效。
- 同一用户保存动作通过请求ID去重；报告与复练不重复生成。
- 项目事实默认私密且待确认，不能由模型自动升级为真实经历。
- 反馈可标不确定或争议，不强制给开放设计问题一个标准答案。

## 5. 核心对象关系

```mermaid
erDiagram
    USER ||--o{ TARGET_JOB : owns
    TARGET_JOB ||--o{ JOB_VERSION : versions
    USER ||--o{ EVIDENCE_FACT : owns
    TARGET_JOB }o--o{ EVIDENCE_FACT : authorizes
    TARGET_JOB ||--o{ PRACTICE_SESSION : contextualizes
    PRACTICE_SESSION ||--o{ ANSWER : contains
    PRACTICE_SESSION ||--o{ DIAGRAM_VERSION : contains
    PRACTICE_SESSION ||--o{ FEEDBACK_ITEM : produces
    FEEDBACK_ITEM }o--o{ ANSWER : cites
    FEEDBACK_ITEM }o--o{ DIAGRAM_VERSION : cites
    FEEDBACK_ITEM ||--o{ REVIEW_TASK : proposes
    REVIEW_TASK }o--o{ PRACTICE_SESSION : practiced_in
```

对象关系是设计草图，不是已执行DDL。岗位、材料、会话和报告保留独立版本，删除与保留策略需一致设计。

## 6. 图修改后追问时序

```mermaid
sequenceDiagram
    actor Candidate as 练习者
    participant UI as 白板与问答
    participant API as 会话服务
    participant Model as 模型
    Candidate->>UI: 修改图并解释原因
    UI->>API: 保存图版本v2及说明
    API-->>UI: v2保存状态
    API->>Model: 题目约束＋v2图快照＋必要问答
    Model-->>API: 新追问与目标节点引用
    API->>API: 校验引用与输出结构
    API-->>UI: 绑定v2的新追问
    Note over UI,API: v1追问保留原图，不能被倒改
```
