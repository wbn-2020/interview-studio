import fs from 'node:fs/promises'
import path from 'node:path'
import dns from 'node:dns/promises'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const { values } = parseArgs({ options: {
  'base-url': { type: 'string' }, references: { type: 'string' }, output: { type: 'string' },
  manifest: { type: 'string' }, 'runtime-modules': { type: 'string' }, scenes: { type: 'string' },
  channel: { type: 'string', default: 'chromium' }, 'allow-self-signed': { type: 'boolean', default: false },
} })
const ensure = (condition, message) => { if (!condition) throw new Error(message) }
const hash = buffer => crypto.createHash('sha256').update(buffer).digest('hex')
const frontend = fileURLToPath(new URL('../', import.meta.url))
const requireProject = createRequire(path.join(frontend, 'package.json'))
const ts = requireProject('typescript')
for (const key of ['base-url', 'references', 'output', 'manifest', 'runtime-modules']) ensure(values[key], `缺少 --${key}`)
for (const key of ['references', 'output', 'manifest', 'runtime-modules']) ensure(path.isAbsolute(values[key]), `--${key} 必须是绝对路径`)
const base = new URL(values['base-url'])
ensure(base.protocol === 'https:' && base.pathname === '/' && !base.search && !base.hash && !base.username && !base.password,
  '--base-url 须为已部署站点的 HTTPS 根地址，不能携带账号或查询参数')
const hostname = base.hostname.replace(/^\[|\]$/g, '')
const addresses = await dns.lookup(hostname, { all: true })
ensure(addresses.length && addresses.every(({ address }) => !/^(?:127\.|0\.|::1$|::$|::ffff:127\.)/i.test(address))
  && !/(?:^|\.)localhost$/i.test(hostname), '验收只允许已部署服务器，不能访问本地或环回地址')
ensure(['chromium', 'chrome', 'msedge'].includes(values.channel), '--channel 只接受 chromium、chrome 或 msedge；不安装浏览器')
const runtime = createRequire(path.join(values['runtime-modules'], '__v12_resolver.cjs'))
const { chromium } = runtime('playwright'), sharp = runtime('sharp')
ensure(typeof sharp().withIccProfile === 'function', '现有 sharp 缺少 sRGB 配置支持；未安装或更改依赖')
const readJson = async file => JSON.parse(await fs.readFile(file, 'utf8'))
const manifest = await readJson(values.manifest)
ensure(manifest.phase === 'artifact' && manifest.inputUnchanged === true && manifest.missingDependencies?.length === 0
  && Array.isArray(manifest.manifest), '必须提供已通过静态检查的制品清单')
const entries = new Map(manifest.manifest.map(entry => [entry.file, entry]))
for (const name of ['index.html', 'replica.html']) ensure(/^[a-f0-9]{64}$/.test(entries.get(name)?.sha256 ?? ''), `清单缺少 ${name}`)

// 从源码解析固定场景，避免维护另一份页面文案和截图命名表。
const sceneSource = await fs.readFile(path.join(frontend, 'src/replica/scenes.ts'), 'utf8')
const sourceReportName = path.basename(values.manifest).replace(/^v12-studio-artifact-(\d{8}-r[1-9]\d*)\.json$/, 'v12-studio-source-$1.json')
ensure(sourceReportName !== path.basename(values.manifest), '制品清单文件名无法定位对应源码记录')
const sourceReport = await readJson(path.join(path.dirname(values.manifest), sourceReportName))
ensure(sourceReport.inputSha256 === manifest.inputSha256
  && sourceReport.inputs?.find(input => input.file === 'frontend/src/replica/scenes.ts')?.sha256 === hash(sceneSource),
  '当前场景清单与候选构建的源码记录不一致')
const ast = ts.createSourceFile('scenes.ts', sceneSource, ts.ScriptTarget.Latest, true)
let sceneArray
function visit(node) {
  if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === 'scenes') {
    const call = node.initializer
    ensure(ts.isCallExpression(call) && ts.isPropertyAccessExpression(call.expression), '场景清单结构无法核对')
    sceneArray = call.expression.expression
  }
  ts.forEachChild(node, visit)
}
visit(ast)
ensure(sceneArray && ts.isArrayLiteralExpression(sceneArray) && sceneArray.elements.length === 30, '需要恰好30个固定场景')
const scenes = sceneArray.elements.map((row, index) => {
  ensure(ts.isArrayLiteralExpression(row) && row.elements.length === 4 && row.elements.every(ts.isStringLiteral), '场景内容必须为固定字符串')
  const [name, title, breadcrumb, initial] = row.elements.map(node => node.text)
  const id = String(index + 1).padStart(2, '0')
  ensure(name.startsWith(`${id}_`) && !/[\\/:*?"<>|]/.test(name), `场景文件名无效：${name}`)
  return { id, name, title, breadcrumb, initial, file: `${name}.png`, screenshot: `${name}_replica.png` }
})
const referenceNames = (await fs.readdir(values.references)).filter(name => /^(?:0[1-9]|[12]\d|30)_.*\.png$/.test(name))
const requestedIds = values.scenes?.split(',') ?? scenes.map(scene => scene.id)
ensure(new Set(requestedIds).size === requestedIds.length && requestedIds.every(id => scenes.some(scene => scene.id === id)),
  '--scenes 只接受不重复的两位编号，例如09,25,30')
const selectedScenes = scenes.filter(scene => requestedIds.includes(scene.id))
ensure(referenceNames.length === 30 && scenes.every(scene => referenceNames.filter(name => name.startsWith(`${scene.id}_`)).length === 1
  && referenceNames.includes(scene.file)), '01至30参考图存在缺失、重复或文件名不符')
for (const scene of scenes) {
  const metadata = await sharp(path.join(values.references, scene.file)).metadata()
  ensure(metadata.width === 1536 && metadata.height === 1024, `参考尺寸不符：${scene.file}`)
}
ensure(!(await fs.lstat(values.output).catch(error => { if (error.code !== 'ENOENT') throw error })), '输出目录已存在；请指定新的目录，旧证据不会覆盖或删除')
const outputParent = await fs.realpath(path.dirname(values.output))
ensure((await fs.stat(outputParent)).isDirectory(), '输出目录的父目录不存在')
await fs.mkdir(values.output)
for (const folder of ['screenshots', 'overlays', 'diffs', 'records']) await fs.mkdir(path.join(values.output, folder))
const writeJson = (file, value) => fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' })
const results = [], checks = [], failures = []
let browser
const startedAt = new Date().toISOString()
try {
  browser = await chromium.launch({ headless: true, ...(values.channel === 'chromium' ? {} : { channel: values.channel }),
    args: ['--force-color-profile=srgb', '--force-device-scale-factor=1', '--disable-gpu'] })
  const context = await browser.newContext({ viewport: { width: 1536, height: 1024 }, deviceScaleFactor: 1,
    colorScheme: 'dark', reducedMotion: 'reduce', ignoreHTTPSErrors: values['allow-self-signed'] })
  // 先核对真实线上入口字节；不拦截网络、不注入候选HTML或本地静态资源。
  for (const name of ['index.html', 'replica.html']) {
    const response = await context.request.get(new URL(name, base).href, { headers: { 'Cache-Control': 'no-cache' } })
    ensure(response.ok(), `${name} 返回 HTTP ${response.status()}`)
    ensure(new URL(response.url()).origin === base.origin, `${name} 跳转到了其他站点`)
    const body = await response.body(), sha256 = hash(body), expectedSha256 = entries.get(name).sha256
    const passed = sha256 === expectedSha256
    checks.push({ check: 'deployed-entry-sha256', file: name, status: response.status(), bytes: body.length,
      sha256, expectedSha256, passed })
    ensure(passed, `${name} 与候选制品不一致，停止取证`)
  }
  for (const scene of selectedScenes) {
    const page = await context.newPage(), pageErrors = [], resourceTasks = []
    page.on('pageerror', error => pageErrors.push(error.message))
    page.on('response', response => {
      const url = new URL(response.url())
      if (url.origin !== base.origin || !url.pathname.startsWith('/assets/')) return
      resourceTasks.push((async () => {
        const file = decodeURIComponent(url.pathname.slice(1)), expected = entries.get(file)
        ensure(expected && /^[a-f0-9]{64}$/.test(expected.sha256), `线上资源未出现在候选清单：${file}`)
        ensure(response.ok() && hash(await response.body()) === expected.sha256, `线上资源内容不符：${file}`)
      })().then(() => null, error => error.message))
    })
    const entry = new URL(`replica.html?scene=${scene.id}`, base).href
    let measuredViewport
    let frameCheck = 0
    async function ready() {
      await page.locator(`.r-canvas[data-scene="${scene.id}"]`).waitFor({ state: 'visible' })
      await page.mouse.move(0, 0)
      await page.evaluate(async () => {
        await document.fonts.ready
        await Promise.all([...document.images].map(image => image.decode()))
        window.scrollTo(0, 0)
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
      })
      const viewport = await page.evaluate(() => ({ width: innerWidth, height: innerHeight, dpr: devicePixelRatio, zoom: visualViewport?.scale }))
      measuredViewport = viewport
      ensure(viewport.width === 1536 && viewport.height === 1024 && viewport.dpr === 1
        && viewport.zoom === 1, `${scene.id} 视口或缩放不符：${JSON.stringify(viewport)}`)
      ensure(new URL(page.url()).origin === base.origin, `${scene.id} 已离开指定服务器`)
    }
    const screenshot = () => page.screenshot({ fullPage: false, animations: 'disabled', caret: 'hide', type: 'png' })
    async function assertFrame(expected, message) {
      const actual = await screenshot()
      frameCheck++
      if (!expected.equals(actual)) {
        const prefix = path.join(values.output, 'records', `${scene.id}-frame-${frameCheck}`)
        await fs.writeFile(`${prefix}-expected.png`, expected, { flag: 'wx' })
        await fs.writeFile(`${prefix}-actual.png`, actual, { flag: 'wx' })
        const first = await sharp(expected).removeAlpha().raw().toBuffer()
        const second = await sharp(actual).removeAlpha().raw().toBuffer()
        let changedPixels = 0, maxChannelDelta = 0, minX = 1536, minY = 1024, maxX = -1, maxY = -1
        for (let offset = 0; offset < first.length; offset += 3) {
          const delta = Math.max(...[0, 1, 2].map(channel => Math.abs(first[offset + channel] - second[offset + channel])))
          if (!delta) continue
          const point = offset / 3, x = point % 1536, y = Math.floor(point / 1536)
          changedPixels++; maxChannelDelta = Math.max(maxChannelDelta, delta)
          minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y)
        }
        await writeJson(`${prefix}.json`, { message, changedPixels, maxChannelDelta, bounds: { minX, minY, maxX, maxY },
          activeElement: await page.evaluate(() => ({ tag: document.activeElement?.tagName, id: document.activeElement?.id })) })
      }
      ensure(expected.equals(actual), message)
    }
    try {
      ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), `${scene.id} 页面加载失败`)
      await ready()
      if (scene.id === '25') {
        ensure(await page.getByRole('dialog', { name: '全局命令面板' }).isVisible(), '25命令面板未打开')
        ensure(await page.getByRole('combobox', { name: '搜索命令' }).evaluate(node => node === document.activeElement && node.value === '缓存'), '25初始查询或焦点不符')
      }
      if (scene.id === '29') ensure(await page.locator('.mobile-phone').count() === 3, '29未保持三手机并列构图')
      const captured = await screenshot()
      await assertFrame(captured, `${scene.id} 连续画面不稳定，不能作为静止验收截图`)
      await page.reload({ waitUntil: 'networkidle' })
      await ready()
      await assertFrame(captured, `${scene.id} 刷新后的参考初态不能恢复`)
      let interactionError = null
      // 交互失败仍保留已核实的初态截图，逐页失败不会掩盖其他页面的真实结果。
      try {
      if (scene.id === '04') {
        const preview = page.locator('.expression-preview p')
        const lines = await preview.evaluate(node => {
          const text = node.firstChild, rows = new Map()
          if (!text || text.nodeType !== Node.TEXT_NODE) throw new Error('04预览文本节点缺失')
          for (let index = 0; index < text.textContent.length; index++) {
            const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
            const y = range.getBoundingClientRect().top
            rows.set(y, (rows.get(y) ?? '') + text.textContent[index])
          }
          return [...rows.values()]
        })
        ensure(lines.length === 2 && lines[0] === '营销交易平台致力于为用户提供', '04预览未保持参考两行')
        const tree = page.locator('.project-tree'), root = tree.getByRole('button', { name: '营销交易平台', exact: true })
        ensure(await root.getAttribute('aria-expanded') === 'true' && await tree.locator('.project-leaf').count() === 3, '04项目目录初态不符')
        await root.click()
        ensure(await root.getAttribute('aria-expanded') === 'false' && await tree.locator('.project-leaf').count() === 0, '04项目目录未收起')
        await root.click()
        await tree.getByRole('button', { name: '缓存治理', exact: true }).click()
        ensure(await page.locator('.fact-heading h3').textContent() === '缓存更新失败后的排查', '04选择事实未同步标题')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '04目录或事实切换后刷新未恢复参考初态')
        checks.push({ check: 'fact-preview-two-lines-directory-selection-refresh', scene: scene.id, lines, passed: true })
      }
      if (scene.id === '05') {
        const directory = page.locator('.expression-directory'), heading = page.locator('.expression-text h2')
        const root = directory.getByRole('button', { name: '项目介绍 3', exact: true })
        ensure(await root.getAttribute('aria-expanded') === 'true' && await directory.locator('.version-link').count() === 3, '05目录初态不符')
        await root.click()
        ensure(await directory.locator('.version-link').count() === 0, '05项目介绍未收起')
        await root.click()
        await directory.getByRole('button', { name: '项目介绍 · v2', exact: true }).click()
        ensure(await heading.textContent() === '项目介绍 v2' && (await page.locator('.history-item.selected strong').textContent()).trim() === 'v2', '05目录选择未同步正文及历史')
        await page.locator('.history-item').first().click()
        ensure(await heading.textContent() === '项目介绍 v3' && await directory.locator('.version-link.selected').textContent() === '项目介绍 · v3', '05历史选择未同步正文及目录')
        await page.locator('.diff-heading').click()
        ensure(await page.locator('.diff-columns').count() === 0, '05版本对比未收起')
        await page.getByRole('button', { name: '对比上一版', exact: true }).click()
        ensure(await page.locator('.diff-columns').isVisible(), '05版本对比未重新展开')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '05目录、版本及对比切换后刷新未恢复参考初态')
        checks.push({ check: 'expression-directory-versions-comparison-refresh', scene: scene.id, passed: true })
      }
      if (scene.id === '06') {
        const library = page.locator('.file-library'), viewer = page.locator('.pdf-viewer')
        ensure(await library.locator('.file-item:visible').count() === 5, '06初始文件行数不符')
        const parsingLists = await page.locator('.parsing-content ul').evaluateAll(lists => lists.map(list => ({
          rows: list.querySelectorAll('li').length,
          overflow: list.scrollWidth > list.clientWidth + 1,
          items: [...list.querySelectorAll('li')].map(item => ({ text: item.textContent, x: item.getBoundingClientRect().x })),
        })))
        ensure(parsingLists.length === 2 && parsingLists.every(list => list.rows === 2 && !list.overflow
          && list.items.every(item => Math.abs(item.x - 1240) < 1)), '06解析列表行数、悬挂缩进或换行范围不符')
        const resultLines = await page.locator('.parsing-content section').nth(3).locator('li').nth(1).evaluate(item => {
          const text = item.firstChild, rows = new Map()
          for (let index = 0; index < text.textContent.length; index++) {
            const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
            const top = range.getBoundingClientRect().top
            rows.set(top, (rows.get(top) ?? '') + text.textContent[index])
          }
          return [...rows.values()].map(line => line.trim())
        })
        ensure(resultLines.length === 2 && resultLines[0] === '支持了 5 大营销活动，交易额同比增长'
          && resultLines[1] === '80%。', '06解析结果第二项换行未匹配参考')
        const libraryBox = await library.boundingBox()
        ensure(libraryBox && libraryBox.x === 253 && libraryBox.y === 196 && libraryBox.width === 323
          && libraryBox.height === 784, '06文件库外框不符')
        checks.push({ check: 'attachment-library-box-and-semantic-lists', scene: scene.id, parsingLists, resultLines, libraryBox, passed: true })
        await viewer.getByRole('button', { name: '下一页', exact: true }).click()
        ensure(await viewer.getByText('第 9 / 24 页', { exact: true }).isVisible(), '06下一页未更新')
        await viewer.getByRole('button', { name: '上一页', exact: true }).click()
        await viewer.getByRole('button', { name: '缩小', exact: true }).click()
        ensure((await viewer.locator('.pdf-zoom').textContent()).trim() === '90%', '06缩小未更新比例')
        await viewer.getByRole('button', { name: '适应页面', exact: true }).click()
        ensure((await viewer.locator('.pdf-zoom').textContent()).trim() === '100%', '06适应页面未复位比例')
        await library.getByRole('button', { name: '文本 6', exact: true }).click()
        ensure(await library.locator('.file-item:visible').count() === 3, '06文本筛选行数不符')
        await page.locator('.parsing-status').click()
        ensure(await page.locator('.parsing-content').count() === 0, '06解析区未折叠')
        await page.locator('.parsing-status').click()
        ensure(await page.locator('.parsing-content').isVisible(), '06解析区未重新展开')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '06页码、缩放、筛选和折叠后刷新未恢复初态')
        checks.push({ check: 'attachment-pagination-zoom-filter-collapse-refresh', scene: scene.id, passed: true })
      }
      if (scene.id === '14') {
        const table = page.locator('.bank-list'), rows = table.locator('tbody tr')
        const geometry = await page.evaluate(() => {
          const rect = selector => {
            const node = document.querySelector(selector), r = node?.getBoundingClientRect()
            return r ? [r.x, r.y, r.width, r.height] : null
          }
          return {
            titleLines: document.querySelector('.bank-detail h2')?.innerText.split('\n'),
            headings: [...document.querySelectorAll('.bank-list th')].map(node => node.textContent?.trim()),
            filters: rect('.bank-filters'), table: rect('.bank-list'), detail: rect('.bank-detail'),
            search: rect('.bank-search'), filterButton: rect('.bank-tools > .ts-button'), create: rect('.bank-tools > .primary'),
            separator: rect('.r-nav-separator'),
            selected: document.querySelector('.r-sidebar .r-selected')?.textContent?.trim(),
            sidebarLabels: [...document.querySelectorAll('.r-nav-item')].map(node => node.textContent?.trim()),
            selectedRow: document.querySelector('.bank-list tr.selected')?.textContent?.trim(),
            brandPaths: document.querySelectorAll('.r-brand path').length,
            briefcasePaths: [...document.querySelectorAll('.r-nav-item')].find(node => node.textContent?.trim() === '准备')?.querySelectorAll('path').length,
          }
        })
        const closeBox = (actual, expected) => actual?.length === 4 && actual.every((value, index) => Math.abs(value - expected[index]) <= 1)
        ensure(JSON.stringify(geometry.titleLines) === JSON.stringify(['缓存删除失败后', '如何恢复？'])
          && JSON.stringify(geometry.headings) === JSON.stringify(['题目', '主题', '标准', '来源', '最近'])
          && await rows.count() === 5 && geometry.selected === '训练' && geometry.selectedRow.startsWith('缓存删除失败后如何恢复？'),
          '14标题换行、五列五题或选中状态不符')
        ensure(closeBox(geometry.filters, [238, 230, 204, 750]) && closeBox(geometry.table, [448, 230, 709, 750])
          && closeBox(geometry.detail, [1164, 230, 350, 614]) && closeBox(geometry.search, [912, 112, 324, 54])
          && closeBox(geometry.filterButton, [1249, 112, 128, 54]) && closeBox(geometry.create, [1390, 112, 116, 54])
          && Math.abs(geometry.separator?.[1] - 513) <= 1 && geometry.brandPaths === 2 && geometry.briefcasePaths === 2,
          '14面板、工具组、侧栏分隔或专用图标几何不符')
        checks.push({ check: 'question-bank-five-rows-columns-title-lines-shell-tools-geometry', scene: scene.id, geometry, passed: true })
        const search = page.getByRole('textbox', { name: '搜索题目', exact: true })
        await search.fill('ThreadLocal')
        ensure(await rows.count() === 1 && (await rows.first().textContent()).includes('ThreadLocal'), '14标题搜索未筛选')
        await page.getByRole('button', { name: '重置', exact: true }).click()
        const groups = page.locator('.bank-filter-group')
        await groups.nth(0).getByRole('button', { name: /^分布式\s*8$/ }).click()
        ensure(await rows.count() === 1 && (await rows.first().textContent()).startsWith('缓存删除失败后'), '14主题筛选未生效')
        await page.getByRole('button', { name: '重置', exact: true }).click()
        await groups.nth(2).getByRole('button', { name: '已审', exact: true }).click()
        ensure(await rows.count() === 3, '14标准状态筛选未生效')
        await groups.nth(3).getByRole('button', { name: '导入', exact: true }).click()
        ensure(await table.getByText('没有匹配的题目', { exact: true }).isVisible(), '14组合筛选空态不符')
        await page.getByRole('button', { name: '重置', exact: true }).click()
        await groups.first().getByRole('button', { name: '主题', exact: true }).click()
        ensure(await groups.first().locator('.filter-options').count() === 0, '14主题未收起')
        await groups.first().getByRole('button', { name: '主题', exact: true }).click()
        await page.locator('.bank-tools').getByRole('button', { name: '筛选', exact: true }).click()
        ensure(await page.locator('.bank-filters').count() === 0 && (await table.boundingBox()).x === 238, '14筛选栏未收起')
        await page.locator('.bank-tools').getByRole('button', { name: '筛选', exact: true }).click()
        const sort = table.getByRole('button', { name: '最近', exact: true })
        await sort.click()
        ensure(await sort.getAttribute('aria-pressed') === 'true' && (await rows.first().textContent()).startsWith('解释一次 GC'), '14排序未反转')
        await sort.click()
        await rows.nth(1).focus(); await rows.nth(1).press('Enter')
        ensure(await page.locator('.bank-detail h2').textContent() === 'ThreadLocal 为什么会泄漏？'
          && await page.getByText('题干与标准要求待确认', { exact: true }).isVisible(), '14键盘选择没有同步真实已知题目')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '14搜索、筛选、折叠、排序、选择后刷新未恢复')
        for (const [selector, target] of [['.bank-start', '17'], ['.bank-route-head', '15'], ['.bank-tabs button:nth-child(2)', '15']]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor()
          ensure(new URL(page.url()).searchParams.get('scene') === target, '14题库导航未同步地址')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '14返回加载失败')
          await ready()
        }
        await assertFrame(captured, '14导航返回后初态发生变化')
        checks.push({ check: 'question-bank-search-filters-collapse-sort-keyboard-navigation-refresh', scene: scene.id, passed: true, limitations: '考察项关联、条件说明、加入题单、新建流程及其他题目完整正文待确认；未接正式业务' })
      }
      if (scene.id === '15') {
        const panels = { '.r-sidebar': [18, 22, 202, 973], '.route-list': [236, 235, 324, 747],
          '.route-map': [571, 235, 613, 512], '.route-overview': [571, 759, 613, 223], '.route-details': [1196, 235, 322, 747] }
        const geometry = {}
        for (const [selector, expected] of Object.entries(panels)) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '15面板几何不符：' + selector)
        }
        ensure(await page.locator('.r-tools-heading').textContent() === '工具', '15缺少工具分组标签')
        ensure(await page.locator('.route-list-item').count() === 3 && await page.locator('.route-stage').count() === 5
          && await page.locator('.route-overview tbody tr').count() === 3, '15路线、阶段或检查点数量不符')
        const stages = page.locator('.route-stage')
        ensure(await stages.nth(1).getAttribute('aria-pressed') === 'true'
          && await page.locator('.route-completion p').textContent() === '独立作答 2 题 + 复练 1 次', '15初始阶段或完成条件不符')
        const stageTops = await stages.evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().top))
        ensure(JSON.stringify(stageTops) === JSON.stringify([264, 362, 459, 555, 650]), '15阶段坐标不符')
        ensure(await page.locator('.r-brand circle').count() === 2, '15品牌不应使用带外圈的默认图标')
        checks.push({ check: 'route-five-stages-three-checkpoints-shell-panel-geometry', scene: scene.id, geometry, stageTops, passed: true })
        await stages.nth(2).click()
        ensure(await stages.nth(2).getAttribute('aria-pressed') === 'true' && await page.locator('.route-completion').count() === 0, '15选择阶段未更新')
        await page.locator('.route-overview tbody tr').nth(1).focus()
        await page.keyboard.press('Enter')
        ensure(await stages.nth(1).getAttribute('aria-pressed') === 'true' && await page.locator('.route-completion').isVisible(), '15检查点键盘操作未恢复对应阶段')
        const tabs = page.getByRole('navigation', { name: '题单与路线视图' })
        await tabs.getByRole('button', { name: '题单', exact: true }).click()
        ensure((await tabs.getByRole('button', { name: '题单', exact: true }).getAttribute('class'))?.includes('active'), '15题单标签未切换')
        await tabs.getByRole('button', { name: '路线', exact: true }).click()
        ensure((await tabs.getByRole('button', { name: '路线', exact: true }).getAttribute('class'))?.includes('active'), '15路线标签未切回')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '15阶段或标签操作后刷新未恢复参考初态')
        await tabs.getByRole('button', { name: '题目', exact: true }).click()
        await page.locator('.r-canvas[data-scene="14"]').waitFor({ state: 'visible' })
        ensure(new URL(page.url()).searchParams.get('scene') === '14', '15题目入口未同步地址')
        ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '15返回后加载失败')
        await ready()
        await assertFrame(captured, '15返回路线后初态未恢复')
        checks.push({ check: 'route-stage-checkpoint-keyboard-tabs-navigation-refresh', scene: scene.id, passed: true,
          limitations: '其他路线正文、题单列表内容、新建、筛选和调整顺序流程未在参考图定义，仍为待确认，不视为业务验收通过' })
      }
      if (scene.id === '16') {
        const geometry = {}
        const panels = { '.r-sidebar': [0, 0, 220, 1024], '.recall-queue': [230, 258, 325, 692],
          '.recall-workspace': [565, 258, 624, 692], '.recall-workspace textarea': [593, 529, 568, 266],
          '.recall-card-details': [1199, 258, 314, 692] }
        for (const [selector, expected] of Object.entries(panels)) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '16面板或输入框几何不符：' + selector)
        }
        const heading = page.locator('.recall-workspace h2')
        const lines = await heading.evaluate(node => {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), rows = new Map()
          let text
          while ((text = walker.nextNode())) {
            for (let index = 0; index < text.textContent.length; index++) {
              const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
              const y = range.getBoundingClientRect().y
              rows.set(y, (rows.get(y) ?? '') + text.textContent[index])
            }
          }
          return [...rows.values()]
        })
        ensure(JSON.stringify(lines) === JSON.stringify(['缓存删除失败后，如何确认系统', '已经恢复？']), '16题面未保持参考两行')
        const titles = await page.locator('.recall-queue-item strong').allTextContents()
        ensure(JSON.stringify(titles) === JSON.stringify(['缓存一致性', '分布式事务', 'JVM 内存模型', '索引优化', '消息队列']), '16五张卡片或顺序不符')
        ensure(await page.locator('.recall-queue-item[aria-current="true"] strong').textContent() === '缓存一致性', '16当前卡身份不符')
        ensure(JSON.stringify(await page.locator('.recall-card-details strong').allTextContents()) === JSON.stringify(['11.13', '11.08 · 困难', '11.20']), '16固定复习日期不符')
        ensure((await page.locator('.r-breadcrumb').textContent()).trim() === '回忆卡 / 今日队列', '16面包屑不符')
        checks.push({ check: 'recall-five-cards-two-lines-fixed-dates-panel-geometry', scene: scene.id, geometry, lines, passed: true })
        const writes = []
        const watchWrite = request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push({ method: request.method(), url: request.url() }) }
        page.on('request', watchWrite)
        const draft = page.getByRole('textbox', { name: '回忆内容', exact: true })
        ensure(await draft.inputValue() === '' && await draft.isEditable(), '16初始输入框应为空且可编辑')
        await draft.fill('只检查当前页面的回忆输入，不提交业务数据。')
        await page.getByRole('button', { name: '仅浏览', exact: true }).click()
        ensure(await draft.getAttribute('readonly') !== null && !await draft.isEditable(), '16仅浏览未切换只读')
        await page.getByRole('button', { name: '返回回忆', exact: true }).click()
        ensure(await draft.isEditable() && await draft.inputValue() === '只检查当前页面的回忆输入，不提交业务数据。', '16切回丢失当前草稿')
        const tabs = page.getByRole('navigation', { name: '回忆队列' })
        await tabs.getByRole('button', { name: '到期', exact: true }).click()
        ensure(await tabs.getByRole('button', { name: '到期', exact: true }).getAttribute('aria-pressed') === 'true', '16到期标签未切换')
        await tabs.getByRole('button', { name: '新卡', exact: true }).click()
        ensure(await page.locator('.recall-queue-item').count() === 0 && await page.locator('.recall-empty').textContent() === '新卡队列待确认', '16新卡应保留内容待确认边界')
        await tabs.getByRole('button', { name: '全部', exact: true }).focus()
        await page.keyboard.press('Enter')
        ensure(await page.locator('.recall-queue-item').count() === 5 && await tabs.getByRole('button', { name: '全部', exact: true }).getAttribute('aria-pressed') === 'true', '16键盘未切回全部')
        for (const [selector, message] of [
          ['.recall-workspace footer .primary', '提示答案内容待确认'],
          ['.recall-workspace footer .ts-button:last-child', '下一张回忆卡待确认'],
          ['.recall-source button', '来源笔记入口待确认'],
          ['.recall-next button', '记忆历史待确认'],
          ['.recall-tools .ts-icon-button', '回忆设置待确认'],
          ['.recall-queue-item:nth-of-type(2)', '分布式事务回忆卡正文待确认'],
        ]) {
          await page.locator(selector).click()
          ensure(await page.getByRole('status').textContent() === message, '16待确认操作提示不符：' + selector)
          ensure(await page.locator('.recall-workspace header span:last-child').textContent() === '第 4 / 12 张'
            && await page.locator('.recall-queue-item[aria-current="true"] strong').textContent() === '缓存一致性'
            && await draft.inputValue() === '只检查当前页面的回忆输入，不提交业务数据。', '16待确认操作不应伪造学习进度或修改草稿')
        }
        page.off('request', watchWrite)
        ensure(writes.length === 0, '16固定态操作不应写入业务接口')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '16输入、浏览、队列及待确认操作后刷新未恢复参考初态')
        await page.locator('.r-top-search').click()
        await page.getByRole('dialog', { name: '全局命令面板' }).waitFor({ state: 'visible' })
        ensure(new URL(page.url()).searchParams.get('scene') === '25', '16搜索入口未同步地址')
        ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '16返回后加载失败')
        await ready(); await assertFrame(captured, '16搜索返回后初态未恢复')
        checks.push({ check: 'recall-draft-readonly-tabs-keyboard-pending-navigation-refresh', scene: scene.id, passed: true, businessWrites: writes,
          limitations: '到期标签仅检查状态切换；其他卡正文、答案、跳过、来源身份、记忆历史与设置仍待确认，不视为真实筛选或学习业务验收通过' })
      }
      if (scene.id === '18') {
        const original = page.locator('.follow-original')
        const before = await original.locator('p').allTextContents()
        const lines = await original.locator('p').evaluateAll(nodes => nodes.map(node => {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), rows = new Map()
          let text
          while ((text = walker.nextNode())) {
            for (let index = 0; index < text.textContent.length; index++) {
              const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
              const y = range.getBoundingClientRect().y
              rows.set(y, (rows.get(y) ?? '') + text.textContent[index])
            }
          }
          return [...rows.values()]
        }))
        ensure(JSON.stringify(lines) === JSON.stringify([
          ['在缓存一致性场景中，发生超时后需要结合业务语义判断是否', '继续重试。如果是幂等操作，可以安全重试；如果涉及副作', '用，则需要谨慎评估。'],
          ['补偿机制可以作为兜底方案，但也要考虑补偿的幂等性和成', '本，避免因重复补偿带来数据不一致。'],
        ]), '18原答逐行换行与参考不符')
        const candidate = page.locator('.follow-candidate')
        ensure((await candidate.locator('.candidate-badge').textContent()).trim() === '候选', '18初态不是候选')
        const evidence = candidate.locator('.candidate-evidence span')
        ensure(await evidence.evaluate(node => node.scrollWidth <= node.clientWidth), '18依据文字被额外截断')
        await candidate.getByRole('button', { name: '依据：原答第 2 段 — 补偿失败后再次确认...', exact: true }).click()
        ensure(await original.locator('.original-second.located').count() === 1, '18依据未定位第2段')
        await candidate.getByRole('button', { name: '作为正式追问', exact: true }).click()
        ensure((await candidate.locator('.candidate-badge').textContent()).trim() === '已采用', '18未更新采用状态')
        ensure(await candidate.getByRole('button', { name: '忽略', exact: true }).isDisabled(), '18采用后仍可重复决策')
        ensure(JSON.stringify(await original.locator('p').allTextContents()) === JSON.stringify(before), '18采用改写了原答')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await page.locator('.follow-candidate').getByRole('button', { name: '忽略', exact: true }).click()
        ensure((await page.locator('.candidate-badge').textContent()).trim() === '已忽略', '18未更新忽略状态')
        ensure(JSON.stringify(await original.locator('p').allTextContents()) === JSON.stringify(before), '18忽略改写了原答')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '18定位、采用、忽略后刷新未恢复初态')
        checks.push({ check: 'follow-up-lines-evidence-adopt-ignore-refresh', scene: scene.id, lines, passed: true, scope: 'fixed-reference-state-only' })
        const shell = await page.evaluate(() => {
          const rect = selector => { const b = document.querySelector(selector).getBoundingClientRect(); return { x: b.x, y: b.y, width: b.width, height: b.height } }
          return { sidebar: rect('.r-sidebar'), search: rect('.r-top-search'), selected: document.querySelector('.r-nav-item.r-selected')?.textContent.trim() }
        })
        ensure(shell.sidebar.x === 0 && shell.sidebar.y === 8 && shell.sidebar.width === 229 && shell.sidebar.height === 1008
          && shell.search.x === 744 && shell.search.y === 22 && shell.search.width === 445 && shell.search.height === 64
          && shell.selected === '训练', '18侧栏、搜索栏或导航选中态不符')
        await page.locator('.r-top-search').click()
        ensure(await page.getByRole('dialog', { name: '全局命令面板' }).isVisible(), '18顶栏搜索未打开命令面板')
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await page.getByRole('link', { name: '问阶今日', exact: true }).click()
        ensure(await page.locator('.r-canvas').getAttribute('data-scene') === '02', '18品牌入口未进入今日')
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '18搜索及品牌入口返回后初态不一致')
        checks.push({ check: 'follow-up-shell-geometry-search-brand-return', scene: scene.id, shell, passed: true, scope: 'fixed-reference-state-only' })
      }
      if (scene.id === '10') {
        const geometry = await page.locator('.kl-table').evaluate(table => {
          const headers = [...table.querySelectorAll('th')].map(cell => ({ text: cell.textContent, height: cell.getBoundingClientRect().height }))
          const titles = [...table.querySelectorAll('tbody td:first-child')].map(cell => {
            const range = document.createRange(); range.selectNodeContents(cell)
            return { text: cell.textContent, lines: range.getClientRects().length }
          })
          return { headers, titles }
        })
        ensure(geometry.headers.length === 5 && geometry.headers.every(cell => cell.height <= 62), '10表头出现额外换行')
        ensure(geometry.titles.length === 4 && geometry.titles.slice(1).every(cell => cell.lines === 1), '10后续三条标题未保持单行')
        await page.locator('.kl-table tbody tr').nth(1).click()
        ensure(await page.locator('.kl-detail h2').textContent() === '分布式锁的边界', '10选择记录未同步详情标题')
        await page.locator('.kl-table tbody tr').nth(2).focus()
        await page.keyboard.press('Enter')
        ensure(await page.locator('.kl-detail h2').textContent() === 'JVM 内存模型速记', '10键盘选择记录失败')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '10记录切换后刷新未恢复参考初态')
        for (const [selector, target] of [['.kl-views button:first-child', '09'], ['.kl-views button:last-child', '13'], ['.kl-detail > .r-btn.r-primary', '11'], ['.kl-folders button:last-child', '30']]) {
          await page.locator(selector).click()
          ensure(new URL(page.url()).searchParams.get('scene') === target, `10导航未进入${target}`)
          await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        }
        await assertFrame(captured, '10导航返回后未恢复参考初态')
        checks.push({ check: 'knowledge-table-lines-selection-keyboard-navigation-refresh', scene: scene.id, geometry, passed: true })
      }
      if (scene.id === '12') {
        const detailChrome = await page.evaluate(() => {
          const primary = document.querySelector('.kd-actions .r-primary')
          const tabs = [...document.querySelectorAll('.kd-evidence-tabs button')].map(node => node.getBoundingClientRect().toJSON())
          return {
            sidebarWidth: document.querySelector('.r-sidebar').getBoundingClientRect().width,
            navigationFont: getComputedStyle(document.querySelector('.r-nav-item')).fontSize,
            keyboardHint: document.querySelector('.r-top-search kbd')?.textContent,
            primaryColor: getComputedStyle(primary).color,
            primaryFilled: primary.querySelector('path')?.getAttribute('fill') === 'currentColor',
            symbolFilled: document.querySelector('.kd-symbol path')?.getAttribute('fill') === 'currentColor',
            confirmed: document.querySelector('.kd-confirmed svg')?.getBoundingClientRect().width,
            tabs,
          }
        })
        ensure(detailChrome.sidebarWidth === 222 && detailChrome.navigationFont === '22px' && detailChrome.keyboardHint === '⌘K', '12侧栏或搜索提示不符')
        ensure(detailChrome.primaryColor === 'rgb(255, 241, 226)' && detailChrome.primaryFilled && detailChrome.symbolFilled && detailChrome.confirmed === 21, '12实心图标、白色按钮或确认标记不符')
        ensure(detailChrome.tabs.every((tab, index) => tab.width === 100 && tab.height === 42 && tab.x === 986 + 112 * index), '12证据页签宽度或间距不符')
        checks.push({ check: 'detail-icons-white-practice-search-sidebar-tabs', scene: scene.id, detailChrome, passed: true })
        const detailSurface = await page.evaluate(() => {
          const sections = [...document.querySelectorAll('.kd-left > section')].map(node => {
            const { y, height } = node.getBoundingClientRect()
            return { y, height }
          })
          const definition = document.querySelector('.kd-definition p')
          const count = document.querySelector('.kd-evidence-tabs .selected .kd-evidence-count')
          const submitted = document.querySelector('.kd-practice .r-success')
          return {
            sections, definitionScroll: { client: definition.clientHeight, scroll: definition.scrollHeight, top: definition.scrollTop, overflow: getComputedStyle(definition).overflowY },
            selectedCount: count?.textContent, countRadius: getComputedStyle(count).borderRadius,
            submittedBackground: getComputedStyle(submitted).backgroundImage,
            documentFilled: document.querySelector('.evidence-icon.teal path')?.getAttribute('fill'),
            memoryPaths: document.querySelectorAll('.evidence-icon.amber path').length,
          }
        })
        ensure(JSON.stringify(detailSurface.sections) === JSON.stringify([{ y: 229, height: 218 }, { y: 460, height: 138 }, { y: 610, height: 100 }, { y: 721, height: 168 }, { y: 900, height: 102 }]), '12左栏面板累计位置不符')
        ensure(detailSurface.definitionScroll.overflow === 'scroll' && detailSurface.definitionScroll.scroll > detailSurface.definitionScroll.client && detailSurface.definitionScroll.top === 0, '12定义正文不是可操作的固定初态滚动区')
        ensure(detailSurface.selectedCount === '6' && detailSurface.countRadius === '50%' && detailSurface.submittedBackground.includes('gradient') && detailSurface.documentFilled === 'currentColor' && detailSurface.memoryPaths === 2, '12计数徽标、已提交状态或来源图标不符')
        const definitionScroll = page.getByLabel('定义正文', { exact: true })
        await definitionScroll.evaluate(node => { node.scrollTop = node.scrollHeight })
        ensure(await definitionScroll.evaluate(node => node.scrollTop > 0), '12定义滚动操作无效')
        await definitionScroll.evaluate(node => { node.scrollTop = 0 })
        await page.mouse.move(0, 0)
        checks.push({ check: 'detail-panel-geometry-native-scroll-source-icons-count-status', scene: scene.id, detailSurface, passed: true })
        const paragraphs = await page.locator('.kd-definition p, .kd-main-note p').evaluateAll(nodes => nodes.map(node => {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), lines = new Map()
          while (walker.nextNode()) {
            const text = walker.currentNode
            for (let offset = 0; offset < text.textContent.length; offset++) {
              const range = document.createRange(); range.setStart(text, offset); range.setEnd(text, offset + 1)
              const rect = range.getBoundingClientRect(), key = Math.round(rect.top)
              if (rect.width) lines.set(key, (lines.get(key) || '') + text.textContent[offset])
            }
          }
          return { lines: [...lines.values()], bottom: node.getBoundingClientRect().bottom, panelBottom: node.parentElement.getBoundingClientRect().bottom }
        }))
        ensure(paragraphs.length === 2 && paragraphs[0].lines.length === 4 && paragraphs[1].lines.length === 2 && paragraphs.every(p => p.bottom < p.panelBottom), '12定义或主笔记发生额外换行／截断')
        ensure(await page.locator('.kd-evidence-row').count() === 3, '12证据初态不是三行')
        for (const [tab, title, target] of [['回答 2', '独立复练 · 11.10', '22'], ['事实 2', '项目事实 · 11.06', '04'], ['回忆 2', '面后回忆 · 11.09', '23']]) {
          await page.locator('.kd-evidence-tabs').getByRole('button', { name: tab, exact: true }).click()
          ensure(await page.locator('.kd-evidence-row').count() === 1 && await page.locator('.kd-evidence-row h3').textContent() === title, `12${tab}筛选身份不符`)
          await page.locator('.kd-evidence-row > button').click()
          ensure(new URL(page.url()).searchParams.get('scene') === target, `12${tab}打开了错误原文`)
          await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        }
        await page.locator('.kd-evidence-tabs').getByRole('button', { name: '事实 2', exact: true }).click()
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '12筛选与原文导航后刷新未恢复参考初态')
        checks.push({ check: 'knowledge-definition-lines-evidence-source-identity-navigation-refresh', scene: scene.id, paragraphs, passed: true })
      }
      if (scene.id === '13') {
        const nodes = page.locator('.kb-node'), canvas = page.locator('.kb-canvas')
        const inspector = page.locator('.kb-inspector'), switches = inspector.getByRole('switch')
        const sidebar = await page.locator('.r-sidebar').boundingBox()
        const navigation = await page.locator('.r-nav-item > span').evaluateAll(labels => labels.map(node => {
          const range = document.createRange(); range.selectNodeContents(node)
          return { text: node.textContent, lines: new Set([...range.getClientRects()].filter(rect => rect.width).map(rect => rect.top)).size }
        }))
        ensure(sidebar?.x === 0 && sidebar.y === 0 && sidebar.width === 212 && sidebar.height === 1024
          && navigation.length === 10 && navigation.every(item => item.lines === 1), '13侧栏范围或导航单行状态不符')
        const positions = await nodes.evaluateAll(items => items.map(node => ({ x: parseFloat(node.style.left), y: parseFloat(node.style.top) })))
        ensure(JSON.stringify(positions) === JSON.stringify([
          { x: 133, y: 404 }, { x: 310, y: 404 }, { x: 488, y: 404 }, { x: 666, y: 404 },
          { x: 843, y: 404 }, { x: 497, y: 613 }, { x: 770, y: 613 },
        ]), '13节点初始坐标不符')
        const warningLines = await page.locator('.kb-warning > span').evaluate(node => {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), lines = new Map()
          while (walker.nextNode()) {
            const text = walker.currentNode
            for (let index = 0; index < text.textContent.length; index++) {
              const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
              const rect = range.getBoundingClientRect()
              if (rect.width) lines.set(rect.top, (lines.get(rect.top) ?? '') + text.textContent[index])
            }
          }
          return [...lines.values()]
        })
        ensure(JSON.stringify(warningLines) === JSON.stringify(['预算扣减超时，', '结果未知']), '13黄色提示没有保持参考两行')
        ensure(await switches.count() === 3
          && JSON.stringify(await switches.evaluateAll(items => items.map(node => node.getAttribute('aria-checked')))) === JSON.stringify(['true', 'true', 'false']),
          '13网格、吸附或边界初态不符')
        await switches.nth(0).click()
        ensure(!await canvas.evaluate(node => node.classList.contains('grid')), '13网格开关未生效')
        await switches.nth(0).click()
        await switches.nth(2).click()
        ensure(await canvas.evaluate(node => node.classList.contains('boundary')), '13边界开关未生效')
        await switches.nth(2).click()
        await page.getByRole('button', { name: '放大', exact: true }).click()
        ensure(await page.locator('.kb-zoom > span').textContent() === '110%', '13放大未更新比例')
        await page.getByRole('button', { name: '适应画布', exact: true }).click()
        const originalPaths = await page.locator('.kb-lines > path').evaluateAll(paths => paths.map(node => node.getAttribute('d')))
        const first = await nodes.first().boundingBox()
        ensure(first, '13首节点不可见')
        await page.mouse.move(first.x + first.width / 2, first.y + first.height / 2)
        await page.mouse.down()
        await page.mouse.move(first.x + first.width / 2 + 20, first.y + first.height / 2 + 20, { steps: 2 })
        await page.mouse.up()
        const moved = await nodes.first().evaluate(node => ({ x: parseFloat(node.style.left), y: parseFloat(node.style.top) }))
        ensure(moved.x === 150 && moved.y === 420, '13节点拖动或吸附坐标不符')
        ensure(JSON.stringify(originalPaths) !== JSON.stringify(await page.locator('.kb-lines > path').evaluateAll(paths => paths.map(node => node.getAttribute('d')))), '13节点移动未更新连线')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '13拖动、缩放与设置交互后刷新未恢复参考初态')
        checks.push({ check: 'whiteboard-positions-warning-lines-grid-boundary-zoom-drag-refresh', scene: scene.id, positions, warningLines, sidebar, navigation, passed: true })
      }
      if (scene.id === '11') {
        const layout = await page.evaluate(() => {
          const rect = selector => document.querySelector(selector).getBoundingClientRect().toJSON()
          const chrome = getComputedStyle(document.querySelector('.r-main'), '::before')
          const inner = getComputedStyle(document.querySelector('.knowledge-note-page'), '::before')
          return { panels: ['.kn-folders', '.kn-notes', '.kn-editor', '.kn-relations'].map(rect),
            chrome: { top: chrome.top, right: chrome.right, bottom: chrome.bottom, radius: chrome.borderRadius },
            inner: { top: inner.top, right: inner.right, bottom: inner.bottom, radius: inner.borderRadius },
            counts: [...document.querySelectorAll('.kn-folders > button small')].map(node => ({ text: node.textContent, right: node.getBoundingClientRect().right })),
            tools: document.querySelectorAll('.kn-toolbar button').length,
            savedCheck: Boolean(document.querySelector('.kn-heading .kn-saved-check path')),
            version: Boolean(document.querySelector('.kn-current > i')) && document.querySelector('.kn-current > span')?.textContent === 'v4 当前',
            title: rect('.kn-heading h1'),
          }
        })
        ensure(JSON.stringify(layout.panels.map(({ x, y, width, height }) => [x, y, width, height])) === JSON.stringify([[242,262,177,706],[419,262,248,706],[674,262,537,706],[1217,262,291,706]]), '11四列范围不符')
        ensure(layout.chrome.top === '25px' && layout.inner.top === '113px' && layout.chrome.radius === '26px' && layout.inner.radius === '26px', '11两层应用外框不符')
        ensure(layout.title.width === 451 && layout.tools === 10 && layout.savedCheck && layout.version, '11标题、工具栏、保存或版本标记不符')
        ensure(JSON.stringify(layout.counts.map(item => item.text)) === JSON.stringify(['4','5','6','3']) && Math.max(...layout.counts.map(item => item.right)) - Math.min(...layout.counts.map(item => item.right)) < 1, '11目录计数未靠右对齐')
        checks.push({ check: 'note-four-columns-chrome-title-counts-tools-saved-version', scene: scene.id, layout, passed: true })
        const prose = page.locator('.kn-prose p').first(), initialText = await prose.textContent()
        const lines = await prose.evaluate(node => {
          const text = node.firstChild, rows = new Map()
          if (!text || text.nodeType !== Node.TEXT_NODE) throw new Error('正文文本节点缺失')
          for (let index = 0; index < text.textContent.length; index++) {
            const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
            const y = range.getBoundingClientRect().top
            rows.set(y, (rows.get(y) ?? '') + text.textContent[index])
          }
          return [...rows.values()]
        })
        const referenceLines = ['缓存更新失败通常发生在删除、更新或写入缓存的环节，', '需要结合日志和调用链，定位是缓存操作失败，还是后续', '流程处理异常。']
        const wrapped = JSON.stringify(lines) === JSON.stringify(referenceLines)
        checks.push({ check: 'note-reference-paragraph-lines', scene: scene.id, lines, referenceLines, passed: wrapped })
        ensure(wrapped, '11正文换行与参考三行不符')
        await page.getByRole('button', { name: '阅读', exact: true }).click()
        ensure(await prose.getAttribute('contenteditable') === 'false', '11阅读态仍可编辑')
        await page.getByRole('button', { name: '编辑', exact: true }).click()
        await prose.fill(initialText + '（验收编辑）')
        await page.getByRole('button', { name: '阅读', exact: true }).click()
        ensure(await prose.textContent() === initialText + '（验收编辑）', '11模式切换丢失本次编辑')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '11编辑和阅读切换后刷新未恢复参考初态')
        checks.push({ check: 'note-edit-read-refresh', scene: scene.id, passed: true })
      }
      if (scene.id === '25') {
        const dialog = page.getByRole('dialog', { name: '全局命令面板' })
        const input = dialog.getByRole('combobox', { name: '搜索命令' })
        const options = dialog.getByRole('option')
        const box = await dialog.boundingBox()
        ensure(box && box.x === 396 && box.y === 135 && box.width === 847 && box.height === 728, '25面板位置或尺寸不符')
        ensure(await options.count() === 12 && await options.first().getAttribute('aria-selected') === 'true', '25初始选项数量或首项选择不符')
        const domains = dialog.locator('.sp-domain-pills').getByRole('option')
        ensure(JSON.stringify(await domains.allTextContents()) === JSON.stringify(['知识', '准备', '摘录', '题面', '回答', '反馈', '回忆', 'AI 观察']), '25八域文案或顺序不符')
        const domainRows = await domains.evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().top))
        ensure(new Set(domainRows).size === 1, '25八域没有保持同一排')
        ensure(await dialog.locator('.sp-command-items').evaluate(node => node.scrollTop === 0 && node.scrollHeight <= node.clientHeight), '25初态出现滚动或内容溢出')
        // 合成输入法生命周期，只核对真实组件事件处理；不注入页面或替换服务器资源。
        await input.dispatchEvent('compositionstart', { data: '存' })
        await input.press('Enter')
        await input.press('Escape')
        await input.press('ArrowDown')
        ensure(await dialog.isVisible() && await options.first().getAttribute('aria-selected') === 'true'
          && new URL(page.url()).searchParams.get('scene') === '25', '25输入法确认或取消错误执行命令')
        await input.dispatchEvent('compositionend', { data: '存' })
        for (let i = 0; i < 4; i++) await input.press('ArrowDown')
        ensure(await domains.first().getAttribute('aria-selected') === 'true'
          && await input.getAttribute('aria-activedescendant') === await domains.first().getAttribute('id'), '25方向键没有进入八域选项')
        for (let i = 0; i < 8; i++) await input.press('ArrowDown')
        ensure(await options.first().getAttribute('aria-selected') === 'true', '25向下循环选择失败')
        await input.press('ArrowUp')
        ensure(await domains.last().getAttribute('aria-selected') === 'true', '25向上循环选择失败')
        await input.press('Enter')
        ensure(await page.locator('.r-toast').textContent() === 'AI 观察正文检索待确认；当前面板仅展示固定命令'
          && new URL(page.url()).searchParams.get('scene') === '25', '25八域操作未保留待确认边界')
        const close = dialog.getByRole('button', { name: 'Esc 关闭', exact: true })
        await input.press('Tab')
        ensure(await close.evaluate(node => node === document.activeElement), '25 Tab没有到达关闭按钮')
        await close.press('Tab')
        ensure(await input.evaluate(node => node === document.activeElement), '25 Tab焦点离开面板')
        await input.press('Shift+Tab')
        ensure(await close.evaluate(node => node === document.activeElement), '25反向Tab没有保留焦点')
        await close.press('Shift+Tab')
        await page.keyboard.press('Escape')
        ensure(await dialog.count() === 0, '25 Esc未关闭面板')
        await page.keyboard.press('Control+k')
        await dialog.waitFor({ state: 'visible' })
        ensure(await input.evaluate(node => node === document.activeElement) && await options.first().getAttribute('aria-selected') === 'true', '25重开后焦点或选择不符')
        for (const [shortcut, target] of [['Control+n', '11'], ['Control+Shift+n', '16']]) {
          await input.press(shortcut)
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, `25${shortcut}没有打开对应新建入口`)
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '25快捷键返回后加载失败')
          await ready()
        }
        for (const [index, target] of [[0, '09'], [1, '02']]) {
          await options.nth(index).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '25页面选项未同步场景地址')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '25页面导航返回后加载失败')
          await ready()
        }
        await assertFrame(captured, '25交互后重新访问没有恢复静止初态')
        checks.push({ check: 'command-geometry-domains-ime-keyboard-focus-shortcuts-navigation-refresh', scene: scene.id, passed: true })
      }
      if (scene.id === '09') {
        const candidates = page.getByRole('switch', { name: '显示候选关系' })
        ensure(await candidates.getAttribute('aria-checked') === 'true', '09初始候选关系未打开')
        await candidates.click()
        ensure(await candidates.getAttribute('aria-checked') === 'false' && await page.locator('.graph-candidate').count() === 0,
          '09候选开关未隐藏虚线层')
        await candidates.click()
        ensure(await candidates.getAttribute('aria-checked') === 'true' && await page.locator('.graph-candidate').count() === 1,
          '09候选开关未恢复虚线层')
        await page.getByRole('button', { name: '放大星图', exact: true }).click()
        ensure(await page.locator('.graph-zoom-value').textContent() === '110%', '09放大后比例不符')
        await page.getByRole('button', { name: '重置星图视图' }).click()
        ensure(await page.locator('.graph-zoom-value').textContent() === '100%', '09重置后比例不符')
        await page.getByRole('navigation', { name: '知识视图' }).getByRole('button', { name: '白板', exact: true }).click()
        await page.locator('.r-canvas[data-scene="13"]').waitFor({ state: 'visible' })
        ensure(new URL(page.url()).searchParams.get('scene') === '13', '09白板入口未同步场景地址')
        ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '09白板返回后加载失败')
        await ready()
        await page.getByRole('button', { name: '分布式系统设计与实现.pdf', exact: true }).click()
        await page.locator('.r-canvas[data-scene="06"]').waitFor({ state: 'visible' })
        ensure(new URL(page.url()).searchParams.get('scene') === '06', '09证据没有进入附件资料')
        ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '09附件返回后加载失败')
        await ready()
        await assertFrame(captured, '09交互后重新访问的初态与原截图不符')
        checks.push({ check: 'graph-candidates-zoom-whiteboard-attachment', scene: scene.id, passed: true })
      }
      if (scene.id === '30') {
        const list = page.getByRole('region', { name: '待整理内容' })
        const sources = page.getByRole('complementary', { name: '收件箱来源' })
        const detail = page.getByRole('complementary', { name: '内容详情' })
        const all = page.getByRole('checkbox', { name: '全选收件箱', exact: true })
        const rows = list.locator('article')
        ensure(await rows.count() === 4 && await rows.first().locator('.sp-inbox-entry').getAttribute('aria-pressed') === 'true',
          '30初始行数或首项选择不符')
        ensure(await detail.locator('.sp-inbox-excerpt').textContent() === '缓存删除失败通常有三种情况：1. key 不存在，删除返回 0；2. 网络超时，客户端误判；3. 连接池耗尽，导致删除请求被拒绝。需要根据业务场景选择合适的重试策略...',
          '30初始摘录不符')
        const excerptRows = await detail.locator('.sp-inbox-excerpt').evaluate(node => {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), rows = new Set()
          let right = 0
          while (walker.nextNode()) {
            const text = walker.currentNode
            for (let index = 0; index < text.textContent.length; index++) {
              const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
              const rect = range.getBoundingClientRect()
              if (rect.width) { rows.add(rect.top); right = Math.max(right, rect.right) }
            }
          }
          return { count: rows.size, right, panelRight: node.getBoundingClientRect().right }
        })
        ensure(excerptRows.count === 5 && excerptRows.right < excerptRows.panelRight, '30摘录行数不符或横向截断')
        await rows.first().getByRole('checkbox').check()
        ensure(await all.evaluate(node => node.indeterminate && !node.checked), '30部分勾选没有同步全选中间状态')
        await all.check()
        ensure(await list.getByRole('checkbox').evaluateAll(nodes => nodes.every(node => node.checked)), '30全选没有勾选可见行')
        await sources.getByRole('button', { name: '导入 2', exact: true }).click()
        ensure(await rows.count() === 1 && await rows.first().getByRole('checkbox').isChecked() === false,
          '30来源切换后行数或勾选状态不符')
        ensure(await rows.first().locator('.sp-inbox-entry').getAttribute('aria-pressed') === 'true'
          && await detail.getByRole('heading', { name: '一致性哈希摘录', exact: true }).isVisible()
          && await detail.getByText('该条目的原始摘录待确认', { exact: true }).isVisible(),
          '30来源切换后列表选择与详情不一致')
        await sources.getByRole('button', { name: '全部 7', exact: true }).click()
        await page.getByRole('combobox', { name: '收件箱排序' }).selectOption({ label: '按时间顺序' })
        ensure(await rows.first().locator('strong').textContent() === '对账任务失败案例', '30时间顺序未反转')
        await rows.first().locator('.sp-inbox-entry').click()
        ensure(await detail.getByRole('heading', { name: '对账任务失败案例', exact: true }).isVisible(), '30点击行没有同步详情')
        await page.getByRole('button', { name: '全部标为已读', exact: true }).click()
        ensure(await page.getByRole('button', { name: '全部标为已读', exact: true }).isDisabled()
          && await page.getByText('3 条待整理 · 来自快速记录和导入', { exact: true }).isVisible(),
          '30已读操作没有保持待整理语义')
        await page.reload({ waitUntil: 'networkidle' })
        await ready()
        await assertFrame(captured, '30筛选、排序、勾选和已读交互后刷新未恢复参考初态')
        for (const [name, target] of [['转为笔记', '11'], ['新建概念', '12'], ['创建回忆卡', '16'], ['关联已有知识', '09']]) {
          await page.getByRole('button', { name, exact: true }).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, `30${name}未进入对应场景`)
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), `30${name}返回后加载失败`)
          await ready()
        }
        await assertFrame(captured, '30操作入口返回后初态与截图不符')
        checks.push({ check: 'inbox-source-selection-sort-checkbox-read-navigation-refresh', scene: scene.id, excerptRows, passed: true })
      }
      } catch (error) {
        interactionError = error.message
        failures.push({ scene: scene.id, phase: 'interaction', error: interactionError })
        console.error(`${scene.id} 交互检查失败：${interactionError}`)
        process.exitCode = 1
      }
      const resourceErrors = (await Promise.all(resourceTasks)).filter(Boolean)
      ensure(!pageErrors.length && !resourceErrors.length, [...pageErrors, ...resourceErrors].join('\n'))
      const png = await sharp(captured).withIccProfile('srgb').png().toBuffer()
      const pngMetadata = await sharp(png).metadata()
      ensure(pngMetadata.width === 1536 && pngMetadata.height === 1024 && pngMetadata.space === 'srgb' && pngMetadata.icc?.length,
        `${scene.id} 输出尺寸或sRGB配置无法核对`)
      await fs.writeFile(path.join(values.output, 'screenshots', scene.screenshot), png, { flag: 'wx' })
      const reference = await sharp(path.join(values.references, scene.file)).toColourspace('srgb').removeAlpha().raw().toBuffer()
      const actual = await sharp(png).toColourspace('srgb').removeAlpha().raw().toBuffer()
      ensure(reference.length === 1536 * 1024 * 3 && reference.length === actual.length, `${scene.id} 像素通道不符`)
      const overlay = Buffer.alloc(actual.length), difference = Buffer.alloc(actual.length)
      let absoluteError = 0, differentPixels = 0, pixelsOver10 = 0
      for (let offset = 0; offset < actual.length; offset += 3) {
        let delta = 0
        for (let channel = 0; channel < 3; channel++) {
          const index = offset + channel, distance = Math.abs(reference[index] - actual[index])
          overlay[index] = Math.round((reference[index] + actual[index]) / 2)
          difference[index] = distance
          absoluteError += distance; delta = Math.max(delta, distance)
        }
        if (delta > 0) differentPixels++
        if (delta > 10) pixelsOver10++
      }
      const raw = { width: 1536, height: 1024, channels: 3 }
      for (const [folder, suffix, data] of [['overlays', 'overlay', overlay], ['diffs', 'diff', difference]]) {
        const image = await sharp(data, { raw }).withIccProfile('srgb').png().toBuffer()
        await fs.writeFile(path.join(values.output, folder, `${scene.name}_${suffix}.png`), image, { flag: 'wx' })
      }
      const record = { ...scene, entry, source: 'deployed-browser-render', width: 1536, height: 1024, dpr: 1, colourSpace: 'sRGB',
        screenshotSha256: hash(png), referenceSha256: hash(await fs.readFile(path.join(values.references, scene.file))),
        stable: true, refreshRestored: true, pageErrors, resourcesChecked: resourceTasks.length,
        measuredViewport,
        interactionStatus: interactionError ? '失败' : ['04', '05', '06', '09', '10', '11', '12', '13', '14', '15', '16', '18', '25', '30'].includes(scene.id) ? '本页已配置交互检查通过' : '仅初态与刷新检查通过', interactionError,
        comparison: { pixelExact: differentPixels === 0, differentPixels, pixelsOver10, meanAbsoluteChannelError: absoluteError / actual.length },
        visualReview: '待人工逐页复核；差异数值不替代视觉与完整交互验收' }
      await writeJson(path.join(values.output, 'records', `${scene.id}.json`), record)
      results.push(record)
      console.log(JSON.stringify({ scene: scene.id, screenshot: scene.screenshot, comparison: record.comparison }))
    } catch (error) {
      failures.push({ scene: scene.id, error: error.message })
      console.error(`${scene.id} 取证失败：${error.message}`)
      process.exitCode = 1
    } finally { await page.close() }
  }
} catch (error) {
  if (!failures.length) failures.push({ error: error.message })
  console.error(error.message)
  process.exitCode = 1
} finally {
  try { await browser?.close() } catch (error) { failures.push({ error: `浏览器关闭失败：${error.message}` }); process.exitCode = 1 }
  await writeJson(path.join(values.output, 'results.json'), { startedAt, finishedAt: new Date().toISOString(), baseUrl: base.origin,
    candidateInputSha256: manifest.inputSha256, expected: selectedScenes.length, referenceCount: 30, captured: results.length, checks, failures,
    status: results.length === selectedScenes.length && !failures.length ? '本次范围截图与自动检查完成，待人工逐页复核' : '本次范围取证未完成',
    scenes: results })
}
