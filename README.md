# 问阶 · Interview Studio

面试求职准备工作台：以目标岗位为上下文，让个人经历、知识练习、项目答辩、架构设计和复盘形成一条连续的准备流程。

## 查看设计

- [可点击 UI 原型](prototype/index.html)
- [模块、业务流程与数据流](prototype/flows.html)
- [产品设计与范围](docs/01-product.md)
- [界面与交互设计](docs/02-ui.md)
- [流程图源稿](docs/03-flows.md)
- [同类产品与开源调研](docs/04-research.md)
- [后续开发边界](docs/05-development.md)
- [验证记录](docs/06-validation.md)

可直接用浏览器打开 `prototype/index.html`。若希望通过本地地址查看，在父目录运行：

```bash
node ../design-tools/serve.mjs
```

默认入口为 `http://127.0.0.1:8765/interview-studio/prototype/`；若端口已占用，可用 `--port 8766`。服务只监听本机地址。

## 当前交付

这是设计基线与交互原型。岗位、个人材料、题目和反馈均为自拟示例，不代表用户真实企业资料或实际面试评价。原型没有接入语音或模型，没有上传真实简历，也没有存储 API 密钥。

产品面向面试前准备和面试后复盘，正式面试中的实时代答不属于范围。原型编辑数据仅保存在当前浏览器，不等同于正式应用的可靠存储。

产品工作名与目录名已经确定，尚未做商标或域名可用性核查。后续应用代码可在本目录内开发；`prototype/` 保留为独立设计参考，不作为正式工程的架构模板。
