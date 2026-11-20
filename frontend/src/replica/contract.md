# v12 Reference Frontend Contract

Date: 2026-11-20. Scope: deterministic, frontend-only reference states.

## 当前视觉验收标准

用户于2026-11-20明确调整要求：复刻大致UI，整体出入不要太大；字体和细微颜色差异不要求完全1:1。

- 核对整体构图、导航位置、主要面板及信息层级，保证主要内容与操作可见，没有明显错位、遮挡或遗漏。
- 字体身份、字形、细微色差、图标笔画和背景纹理可以存在差异；不再为逐像素一致单独开展校准批次。
- 参考图差异指标只作辅助诊断，不作为通过门槛或完成百分比；历史“严格1:1未通过”不再阻塞本范围交付。
- 以服务器实拍的逐页视觉复核和已配置前端交互检查共同验收。仍须如实区分前端演示与真实业务、持久化和AI服务的验收范围。
- 既有几何断言用于识别意外回归；有明确布局调整时，应按实际修改及视觉复核更新对应断言，历史精确值不构成新增UI要求。

## Reference implementation boundaries

- Entry: `/replica.html?scene=01` through `scene=30`.
- Design viewport: 1536 x 1024 CSS pixels, DPR 1.
- Do not start local services, install dependencies, add unit tests, or access backend APIs from these reference scenes.
- Reference directory: `C:\Users\Administrator\Desktop\pict\interview_studio\v12_晨昏线iOS`.
- Only page images 01 through 30 are evidence. Each must be opened individually.
- Existing business application and user changes remain intact.
- Root uses existing Vue 3 and Vite. No new dependency.
- `ReplicaApp.vue` owns backdrop, sidebar, top header, and selection of scene component.
- Each scene component receives `scene: number` and emits `navigate` with a number, plus optional `notify` with a string.
- Scene components render inside `.r-main`, an absolute positioned region from the sidebar boundary to x=1536, y=0 to y=1024.
- Global sidebar boundaries by scene: 02=248, 03=208, 04=240, 05=250, 06=240, 07=260, 08=236, 09=118, 10=220, 11=236, 12=224, 13=212, 14=230, 15=220, 16=220, 17=226, 18=230, 19=240, 20=216, 21=222, 22=222, 23=244, 24=208, 25=212, 26=224, 27=216, 28=240, 30=228.
- Header occupies y=20..95 for ordinary scenes. Content components own page title, tabs and all page panels. Scene 17 owns its custom header. Scenes 01 and 29 have no shared shell.
- Layout coordinates within each component subtract the sidebar boundary from reference image x coordinates.
- Shared `RIcon.vue`: `name: string`, `size?: number` (default 24), `strokeWidth?: number` (default 1.7). Standard Lucide icon names.
- Shared `RBrand.vue`: `large?: boolean`, `variant?: number`.
- Shared CSS: `.r-panel`, `.r-btn`, `.r-primary`, `.r-icon-btn`, `.r-muted`, `.r-faint`, `.r-success`, `.r-amber`, `.r-pill`, `.r-row`, `.r-between`, `.r-tabs`, `.r-active`, `.r-input`, `.r-title`, `.r-subtitle`, `.r-rule`.
- Color defaults: text `#f5efe5`, muted `#aaa59f`, faint `#77736e`, accent `#e5a266`, green `#73cbb1`, background `#0b0a08`, panels `#1c1915` with 1px warm gray border. Page CSS may override.
- Default font: `"Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif`. No bundled font matching the source has been found.
- Use scoped CSS for page groups. No external images, reference images, or screenshot slices.
- Initial state resets from the selected scene on full reload; user actions are in-memory only.
- Animations, blinking carets and force-layout simulations must not affect screenshots.
- Ambiguous or illegible source text must be reported by exact file and region, not silently invented.
- Capture actual screenshots and validate interactions on the existing deployed server under the standing project authorization. See `docs/技术架构/项目生图与部署约定.md`.
- Build outputs use a new batch directory under `artifacts/frontend/`; preserve existing artifacts. The reference entry is `replica.html`; the existing business entry is `index.html`.
- Per-page evidence and uncertain source regions: `docs/交互设计/v12参考场景对应表.md`.
