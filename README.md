# 问阶 · Interview Studio

面试求职准备工作台：以目标岗位为上下文，让个人经历、知识练习、项目答辩、架构设计和复盘形成一条连续的准备流程。

## 当前设计入口（2026-11-04）

1. [PRD v0.4 · AI文字首期与已选决定](PRD.md)
2. [AI产品调研 · 8个代表及功能取舍](docs/11-ai-product-research.md)
3. [AI文字面试体验规格](docs/12-ai-experience.md)
4. [AI文字交互预览](design/ai/index.html) · [能力边界与审阅路径](design/ai/README.md)
5. [系统设计与技术选型 v1.1](docs/08-system-design.md)
6. [两套种子题 · B01／P01](docs/09-content-samples.md)
7. [无模型文字核心原型](design/core/index.html) · [说明](design/core/README.md)
8. [开发就绪审查](docs/10-development-readiness.md) · [四版UI候选](design/ui/index.html) · [项目状态](PROJECT_STATUS.md)

首个用户版采用**AI生成／选题→文字专项／短模拟→证据反馈→独立复练／对照**；个人单实例，AI默认未启用直到配置／费用／外发授权齐备，自检为退路。当前只有设计与合成预览，**未接真实模型、未正式开发**。直接设计，不使用工作流，不更新历史 `plan.md`。

新AI预览和无模型原型均只用本页内存，刷新清空，不收密钥，不填真实材料。可直接用普通浏览器打开 `design/ai/index.html`，无需安装；其定时分段及固定评价不是AI／SSE。助手浏览器检查空快照、截图超时，三宽度／完整GUI及独立审阅未完成，详见就绪记录。设计认可不等于工程、费用、外发或上线授权。

## 保留的历史设计与原型

以下资料保留，不追加为当前首期承诺；原型没有被本轮替换。

- [可点击 UI 原型](prototype/index.html)
- [模块、业务流程与数据流](prototype/flows.html)
- [产品设计与范围](docs/01-product.md)
- [界面与交互设计](docs/02-ui.md)
- [流程图源稿](docs/03-flows.md)
- [同类产品与开源调研](docs/04-research.md)
- [后续开发边界](docs/05-development.md)
- [验证记录](docs/06-validation.md)
- [当前项目状态与下一步](PROJECT_STATUS.md)

可直接用浏览器打开 `prototype/index.html`。若希望通过本地地址查看，在父目录运行：

```bash
node ../design-tools/serve.mjs
```

默认入口为 `http://127.0.0.1:8765/interview-studio/prototype/`；若端口已占用，可用 `--port 8766`。服务只监听本机地址。

## 当前交付

这是设计基线与交互原型。岗位、个人材料、题目和反馈均为自拟示例，不代表用户真实企业资料或实际面试评价。原型没有接入语音或模型，没有上传真实简历，也没有存储 API 密钥。

产品面向面试前准备和面试后复盘，正式面试中的实时代答不属于范围。原型编辑数据仅保存在当前浏览器，不等同于正式应用的可靠存储。

产品工作名与目录名已经确定，尚未做商标或域名可用性核查。后续应用代码可在本目录内开发；`prototype/` 保留为独立设计参考，不作为正式工程的架构模板。
