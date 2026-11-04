# 问阶：同类产品与 GitHub 开源调研

核验日期：2026-11-03（北京时间）。方法：只读核对公开官网、GitHub 仓库身份、README 和许可证原文；未下载、运行或部署第三方应用。下表的功能是公开说明，不等于运行验证。

## 1. 官方产品对照

| 产品 | 已核对定位 | 对问阶有价值的参考 | 边界 |
|---|---|---|---|
| [面试鸭](https://www.mianshiya.com/)／[AI面试](https://ai.mianshiya.com/) | 题库与路线；AI主页展示岗位模拟、简历预测和专项训练 | 训练资源与目标岗位关联，专项练习的入口组织 | 不复制商业题库，不推测评分算法或学习效果 |
| [面试猫](https://offermore.cc/) | 官网同时宣传实时面试／笔试辅助、模拟训练、知识库和总结 | 面试前模拟及事后复盘的任务衔接 | 本产品不做正式面试暗中代答；不引用营销准确率和录用案例作为证据 |

面试／求职题材有明确使用场景，但不能仅凭竞品存在推断付费意愿或用户规模。问阶的产品判断放在可用旅程与证据式反馈上。

## 2. 开源参考

更新口径为核验时 GitHub 仓库 API 的 `pushed_at` UTC 快照，**不是稳定版本日期、最新源码提交日期或质量排名**。

| 项目 | README明确说明 | 许可与出处 | 核验时最近推送 | 借鉴与限制 |
|---|---|---|---|---|
| [Liftoff](https://github.com/Tameyer41/liftoff) | 选择模拟、录制回答、音频转写、生成反馈 | [MIT](https://github.com/Tameyer41/liftoff/blob/main/LICENSE.md) | [2023-12-23](https://api.github.com/repos/Tameyer41/liftoff) | 集中式“选择→作答→反馈”有参考价值；不能假定已有实时多轮追问或当前依赖兼容性 |
| [OpenResume](https://github.com/xitanggg/open-resume) | 简历导入、结构化编辑、实时 PDF 预览和解析查看 | [AGPL-3.0](https://github.com/xitanggg/open-resume/blob/main/LICENSE) | [2024-10-29](https://api.github.com/repos/xitanggg/open-resume) | 输入与成品同步可见；不是AI面试底座，解析结果不等于ATS通过率 |
| [Reactive Resume](https://github.com/reactive-resume/reactive-resume) | 当前README包含 Write／Design／Check、实时预览、行级检查、AI建议接受／拒绝和投递管理 | [MIT](https://github.com/reactive-resume/reactive-resume/blob/main/LICENSE) | [2026-11-02](https://api.github.com/repos/reactive-resume/reactive-resume) | 借鉴上下文编辑与确认式修改；旧 `AmruthPillai/Reactive-Resume` 已迁移，不混用旧版本印象 |
| [JobSync](https://github.com/Gsync/jobsync) | README声明投递、简历评审、JD匹配、面试轮次、侧栏AI和新增岗位确认卡；未运行 | [MIT](https://github.com/Gsync/jobsync/blob/main/LICENSE) | [2026-11-01](https://api.github.com/repos/Gsync/jobsync) | 借鉴岗位—材料—面试关联；不照搬大盘和招聘抓取。自托管不等于调用模型时数据不外发 |
| [System Design Primer](https://github.com/donnemartin/system-design-primer) | 需求约束、高层设计、组件细化、扩展权衡以及题目与示例解法 | [CC BY 4.0](https://github.com/donnemartin/system-design-primer/blob/master/LICENSE.txt) | [2026-09-15](https://api.github.com/repos/donnemartin/system-design-primer) | 学习资源而非白板应用；可借鉴题目结构，内容复用须署名和标明修改，外链单独核权 |

### README来源

- [Liftoff README](https://github.com/Tameyer41/liftoff/blob/main/README.md)
- [OpenResume README](https://github.com/xitanggg/open-resume/blob/main/README.md)
- [Reactive Resume README](https://github.com/reactive-resume/reactive-resume/blob/main/README.md)
- [JobSync README](https://github.com/Gsync/jobsync/blob/main/README.md)
- [System Design Primer README](https://github.com/donnemartin/system-design-primer/blob/master/README.md)

作者演示或官网入口：[Liftoff](https://demo.useliftoff.com)、[OpenResume](https://open-resume.com)、[Reactive Resume](https://rxresu.me)、[JobSync](https://jobsync.ca)。本轮未验证这些演示能否正常运行。

## 3. 采用的设计取舍

1. **当前目标岗位贯穿页面。**材料、题目、会话、图版本、报告和复练不是互不相干的模块。
2. **个人材料是可确认的证据。**模仿上下文工具的确认式编辑，不让模型替用户捏造项目经历。
3. **训练先作答，复盘回链依据。**Liftoff的集中会话值得借鉴，但项目追问和架构图反馈需要另做版本与证据绑定。
4. **系统设计采用结构化节点白板。**先保证节点、连线、用户解释与追问联动，再扩充自由绘画；不宣称市场上没有同类产品。
5. **简历排版与求职状态从轻。**保留资料和目标关联，不在首阶段完整重做简历设计平台或招聘CRM。
6. **示例题自主编写。**公开学习资源只作为选题结构参考，具体复用另做版权和许可检查。

## 4. 许可证处理

MIT代码复用仍需保留版权和许可声明。AGPL代码改造／网络服务涉及源代码提供等义务，需要正式评估；不能默认可闭源商用。文档许可不自动覆盖商标、官网素材、第三方资产或外链。

本轮原型是新写的零依赖交互示例，没有复制上述仓库源码或商业题库。

## 5. 主流功能覆盖调研

核对日期：2026-11-03。只读官网、官方帮助和公开价格／隐私说明，未登录或运行竞品。以下是公开可确认的功能，不证明追问质量、评分有效性、真实历史界面或删除效果。免费／付费权益可能变化，不将促销与营销数字作为产品依据。

| 产品 | 可确认的功能 | 融入问阶的任务 | 官方依据 |
|---|---|---|---|
| Teal | 岗位和面试轮次、准备清单、多份材料、跨版本差异与修改同步 | 目标是准备容器；材料修改有范围和确认 | [轮次与清单](https://help.tealhq.com/en/articles/9530138-job-tracker-interviewing)、[材料版本](https://help.tealhq.com/en/articles/9457698-making-changes-across-resumes) |
| Huntr | JD／简历生成准备问题、岗位组织、账号数据导出 | 岗位要求关联练习，用户数据可带走；生成建议不当作个人事实 | [准备问题](https://help.huntr.co/en/articles/10697703-ai-interview-questions)、[导出](https://help.huntr.co/en/articles/11757717-download-export-your-board) |
| Jobscan | 岗位定制模拟、时长／答题限制、自定义问题、音视频回答、回看和历史 | 模拟可配置，回答内容与表达反馈分开 | [AI Interview Practice](https://www.jobscan.co/ai-interview-practice) |
| interviewing.io | 技术真人模拟、AI coding／system design、题目与学习指南 | 需求澄清、推理、取舍、技术深挖；指南是教学框架，不等于已实现演练流程 | [产品](https://interviewing.io/)、[系统设计框架](https://interviewing.io/guides/system-design-interview/part-three) |
| Yoodli | 岗位／行为角色扮演、动态追问、结构与表达反馈、后续训练建议 | 上一回答影响追问；反馈能转下一练习，不以流畅度代替技术正确性 | [面试训练](https://yoodli.ai/use-cases/interview-preparation) |
| Big Interview | 方法课程、故事框架、Answer Builder、专项录制练习和 AI 反馈 | 学知识 → 组织自己的故事 → 练专项 → 完整模拟 | [学习](https://www.biginterview.com/platform/learn-to-interview)、[练习](https://www.biginterview.com/platform/practice-with-interview-simulator) |
| Final Round AI，限面试前／后 | Goal、材料和准备清单、模拟、Debrief、History、Re-drill | 目标 → 训练 → 复盘 → 针对性复练，排除正式面试实时代答能力 | [产品关联](https://www.finalroundai.com/)、[面试前模拟](https://www.finalroundai.com/ai-mock-interview) |
| 面试鸭及官方 AI 入口 | 技术／岗位／公司／难度题目分类、路线、综合与专项训练、自我介绍、项目、HR | 中文工程求职不只有八股：知识、场景、项目、行为与 HR 分层组织 | [题库](https://www.mianshiya.com/)、[AI 入口](https://ai.mianshiya.com/) |

Exponent 页面返回 403，Interview Query 返回 429，本次不确认当前功能或套餐。未登录就不能确认个人历史、真实导出范围、动态追问效果与精确额度；组织报表也不能当作个人版能力。

### 融合原则

1. 八模块完整覆盖见 [产品范围](01-product.md#5-范围安排)，不是八个互不相关的工具。
2. 学习路线、专项训练和完整模拟是不同任务；同一事实、能力标签和题目可贯穿三者。
3. 个人事实先确认、再用于个性化训练；AI 润色与事实认证分开。Teal 也提醒生成指标需用户核对：[官方说明](https://help.tealhq.com/en/articles/9519206-using-ai-bullets)。
4. 复盘的出口是同题重答、变式或补知识；保留来源与尝试，而不只改变一个完成标记。
5. 只保留面试准备所需的岗位／轮次记录，不将投递 CRM、社区或自动招聘操作塞入主线。

### UI 与画布参考

| 参考 | 本次官方资料可确认的能力 | 对问阶的设计价值 |
|---|---|---|
| [Linear](https://linear.app/) | 按状态组织工作、任务正文与活动／关联信息 | 主界面围绕当前任务，辅助信息进入详情；不是照搬项目管理后台 |
| [FigJam](https://www.figma.com/figjam/diagramming-tool/) | 形状拖放、连接线、网格吸附、无限画布、实时协作 | 画布优先，工具清楚、节点与连接可理解；本次未核验完整工具栏或缩放细节 |
| [Miro](https://miro.com/capabilities/diagrams/) | 技术图形库、图表类型、分层与专注模式 | 复杂系统有语义分组与局部聚焦；本次不确认页面未写明的导出／历史操作 |
| [Eraser](https://docs.eraser.io/docs/architecture-diagram-syntax) | 唯一标识与显示标签分离、带方向／标签的边、嵌套分组 | 语义结构与视觉布局分开，图可以被追问和复盘引用 |
| [Eraser 元素编辑](https://docs.eraser.io/docs/draggable-edits-beta)／[导出](https://docs.eraser.io/docs/export) | 选中节点／组／箭头后移动或调整尺寸；画布、选区及多格式导出 | 选中反馈、局部工具与导出范围可见；不据此推断拖拽回写代码 |
| [tldraw](https://tldraw.dev/features/out-of-the-box-whiteboard) | 选择、相机导航、缩放、适配、小地图、吸附、箭头、撤销重做 | 借鉴成熟画布交互；[SDK 生产许可](https://tldraw.dev/community/license)另有要求，不默认采用 |
| [Vue Flow](https://vueflow.dev/guide/vue-flow/config.html) | 拖拽、选择、连接、缩放／平移、网格和受控状态 | 保持 Vue 技术路线；历史版本、保存与证据校验由应用实现，不由画布库保证 |

上述公开资料不是账号内编辑器的视觉走查，不宣称主流审美已被实测验证。不复制品牌、官网图片、商业模板或题目。具体 UI 与白板规则见 [界面设计](02-ui.md)。

### 数据与许可边界

- Huntr 分享 board 与账号导出不是同一授权范围，问阶导出／分享须说明具体对象。
- Yoodli 不同套餐的训练数据使用条件不同；Final Round AI 的政策包含特定目的的数据处理。不能把竞品“保护隐私”宣传当作默认不训练或不外发的证据。
- 只借鉴功能与交互模式。组件许可证、题目内容、商标素材和服务条款分别检查。

## 6. 仍需后续验证

- 模拟追问和证据回链是否真的改善练习体验。
- AI对开放系统设计的判断稳定性与误报。
- Java后端起步模板之外，其他岗位的训练组织方式。
- 各仓库当前部署要求、依赖、安全与许可证版本；本轮调研不替代采用前审查。
- 问阶现有原型的真实浏览器视觉／完整交互仍未通过；见 [验证记录](06-validation.md)。
