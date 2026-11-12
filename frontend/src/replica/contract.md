# v12 Reference Frontend Contract

Date: 2026-11-12. Scope: deterministic, frontend-only reference states.

- Entry: `/replica.html?scene=01` through `scene=30`.
- Design viewport: 1536 x 1024 CSS pixels, DPR 1.
- Do not start services, install dependencies, add unit tests, or access backend APIs.
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
- Actual screenshots and visual/interaction acceptance remain pending user deployment.
- Final production output: `frontend/dist-replica-v12-20261112-final/`, with `replica.html` and existing `index.html`.
- Per-page evidence and uncertain source regions: `docs/交互设计/v12参考场景对应表.md`.
