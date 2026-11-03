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

## 5. 仍需后续验证

- 模拟追问和证据回链是否真的改善练习体验。
- AI对开放系统设计的判断稳定性与误报。
- Java后端起步模板之外，其他岗位的训练组织方式。
- 各仓库当前部署要求、依赖、安全与许可证版本；本轮调研不替代采用前审查。
