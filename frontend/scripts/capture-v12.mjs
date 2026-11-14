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
      if (scene.id === '17') {
        const geometry = {}
        for (const [selector, expected] of Object.entries({
          '.r-sidebar': [19, 23, 198, 946], '.training-frame': [226, 24, 1292, 926],
          '.writing-topic': [241, 116, 949, 61], '.writing-editor': [249, 318, 941, 542],
          '.writing-conditions': [1209, 114, 302, 368], '.writing-footer': [250, 875, 943, 57],
          '.writing-condition-button': [841, 875, 181, 57],
        })) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '17面板几何不符：' + selector)
        }
        const heading = await page.locator('.writing-question h1').evaluate(node => ({
          text: node.textContent, size: getComputedStyle(node).fontSize, weight: getComputedStyle(node).fontWeight,
          overflow: node.scrollWidth > node.clientWidth,
        }))
        ensure(heading.text === '缓存删除失败后，你会先确认哪些条件？' && heading.size === '35px'
          && heading.weight === '500' && !heading.overflow, '17题面文字、字重或溢出不符')
        ensure(await page.locator('.writing-topic span').textContent() === '分布式系统 · 异常恢复'
          && (await page.locator('.writing-timer').textContent()).trim() === '18:42'
          && (await page.locator('.writing-saved').textContent()).trim() === '已保存 14:32', '17固定题目分类、计时或保存状态不符')
        ensure(JSON.stringify(await page.locator('.writing-conditions dd').allTextContents())
          === JSON.stringify(['Java 后端', '未审', '未开启', '30 分钟', '作答中不可打开参考']), '17五项条件内容或顺序不符')
        ensure(await page.locator('.r-brand path').count() === 4 && await page.locator('.r-brand circle').count() === 0
          && await page.locator('.r-nav-item.r-selected span').textContent() === '训练', '17品牌或侧栏选择不符')
        checks.push({ check: 'writing-question-fixed-time-five-conditions-panel-geometry', scene: scene.id, geometry, heading, passed: true })
        const writes = []
        const watchWrite = request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push({ method: request.method(), url: request.url() }) }
        page.on('request', watchWrite)
        const draft = page.getByRole('textbox', { name: '本题回答', exact: true })
        ensure(await draft.inputValue() === '' && await draft.isEditable()
          && await page.locator('.fixed-caret').count() === 1, '17初态应为空白作答且光标静止')
        await page.getByRole('button', { name: '提交本题', exact: true }).click()
        ensure(await page.getByRole('status').textContent() === '请先填写本题回答'
          && new URL(page.url()).searchParams.get('scene') === '17', '17空答不应直接提交')
        await draft.fill('先明确问题背景')
        ensure((await page.locator('.writing-count').textContent()).startsWith('7 字')
          && await page.locator('.fixed-caret').count() === 0, '17输入、字数或静止光标未更新')
        const conditions = page.getByRole('button', { name: '查看本次条件', exact: true })
        await conditions.click()
        ensure(await conditions.getAttribute('aria-expanded') === 'false' && await page.locator('.writing-conditions').count() === 0, '17条件未收起')
        await conditions.focus(); await page.keyboard.press('Enter')
        ensure(await conditions.getAttribute('aria-expanded') === 'true' && await page.locator('.writing-conditions').isVisible()
          && await draft.inputValue() === '先明确问题背景', '17键盘展开条件丢失草稿')
        await page.getByRole('button', { name: '暂停', exact: true }).click()
        ensure(await draft.isDisabled() && await page.getByRole('button', { name: '提交本题', exact: true }).isDisabled()
          && await page.getByRole('button', { name: '继续', exact: true }).getAttribute('aria-pressed') === 'true', '17暂停未阻止输入及提交')
        await page.getByRole('button', { name: '继续作答', exact: true }).click()
        ensure(await draft.isEditable() && await draft.inputValue() === '先明确问题背景'
          && (await page.locator('.writing-timer').textContent()).trim() === '18:42', '17继续作答丢失草稿或改变固定时间')
        await page.locator('.writing-search').click()
        ensure(await page.getByRole('status').textContent() === '训练中搜索权限待确认'
          && await page.getByRole('dialog').count() === 0, '17未定义搜索不应绕过资料访问边界')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '17输入、暂停、条件及提示操作后刷新未恢复初态')
        for (const [selector, target] of [['.writing-back', '14'], ['.writing-end', '19'], ['.r-sidebar > a', '02']]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '17导航未同步地址：' + selector)
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '17导航返回后加载失败')
          await ready()
        }
        await draft.fill('先确认删除请求的结果和业务约束')
        await page.getByRole('button', { name: '提交本题', exact: true }).click()
        await page.locator('.r-canvas[data-scene="18"]').waitFor({ state: 'visible' })
        ensure(new URL(page.url()).searchParams.get('scene') === '18', '17固定态提交未进入追问场景')
        ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '17提交返回后加载失败')
        await ready(); await assertFrame(captured, '17提交或导航返回后未恢复参考初态')
        page.off('request', watchWrite)
        ensure(writes.length === 0, '17固定态不应写入业务接口')
        checks.push({ check: 'writing-draft-pause-conditions-keyboard-empty-submit-navigation-refresh', scene: scene.id, passed: true, businessWrites: writes,
          limitations: '只验证固定参考态的内存输入和场景切换；不将静态自动保存标签当作持久化，不验证真实训练提交、计时、结束、通知或个人菜单业务' })
      }
      if (scene.id === '19') {
        const geometry = {}
        for (const [selector, expected] of Object.entries({
          '.r-sidebar': [20, 20, 205, 976], '.r-topbar': [244, 20, 1270, 71],
          '.report-summary': [261, 258, 1247, 112], '.report-question-panel': [261, 398, 775, 491],
          '.report-end-conditions': [1061, 398, 447, 325], '.report-ai': [1061, 742, 447, 139],
        })) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '19面板几何不符：' + selector)
        }
        const title = await page.locator('.report-title h1').evaluate(node => ({
          text: node.textContent, size: getComputedStyle(node).fontSize, weight: getComputedStyle(node).fontWeight,
        }))
        ensure(title.text === '本次训练' && title.size === '60px' && title.weight === '600', '19标题不符')
        ensure((await page.locator('.report-title p').textContent()).trim() === '独立专项 · 11.10 14:32–14:50', '19固定训练时间不符')
        ensure(JSON.stringify((await page.locator('.report-stat strong').allTextContents()).map(value => value.trim()))
          === JSON.stringify(['2 题', '1 题', '1 题']), '19提交、跳过与草稿计数不符')
        ensure(JSON.stringify(await page.locator('.report-question-copy strong').allTextContents())
          === JSON.stringify(['缓存删除失败后如何恢复？', '发奖接口如何保证幂等？', '系统设计取舍']), '19三道题内容或顺序不符')
        ensure(JSON.stringify(await page.locator('.report-question-copy small').allTextContents())
          === JSON.stringify(['已提交 / 2 条观察', '已提交 / 1 条观察', '跳过']), '19逐题状态不符')
        ensure(JSON.stringify(await page.locator('.report-end-conditions dd').allTextContents())
          === JSON.stringify(['18 分钟', '1 次', '无', '部分完成']), '19结束条件不符')
        ensure(await page.locator('.report-question-row > button[aria-expanded="false"]').count() === 3
          && await page.locator('.report-expanded-copy').count() === 0
          && await page.locator('.r-brand path').count() === 3 && await page.locator('.r-brand circle').count() === 0,
          '19品牌或折叠初态不符')
        checks.push({ check: 'report-three-questions-counts-fixed-time-conditions-geometry', scene: scene.id, geometry, title, passed: true })
        const writes = []
        const watchWrite = request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push({ method: request.method(), url: request.url() }) }
        page.on('request', watchWrite)
        const rows = page.locator('.report-question-row > button')
        await rows.nth(0).click()
        ensure(await rows.nth(0).getAttribute('aria-expanded') === 'true'
          && await page.locator('.report-expanded-copy p').textContent() === '已提交 / 2 条观察'
          && await page.getByRole('status').textContent() === '题目 01 的详细观察待确认', '19展开未保持已知状态与待确认边界')
        await page.getByRole('button', { name: '查看观察', exact: true }).click()
        ensure(await page.getByRole('status').textContent() === '详细观察待确认', '19详细观察不应伪造评价')
        await rows.nth(1).focus(); await page.keyboard.press('Enter')
        ensure(await rows.nth(0).getAttribute('aria-expanded') === 'false'
          && await rows.nth(1).getAttribute('aria-expanded') === 'true'
          && await page.locator('.report-expanded-copy').count() === 1, '19键盘切换题目未保持单项展开')
        await rows.nth(1).press('Enter')
        ensure(await page.locator('.report-expanded-copy').count() === 0, '19题目未收起')
        for (const [selector, message] of [
          ['.report-ai button', 'AI 评价重试待确认'],
          ['.report-bottom-actions .ts-button:not(.primary)', '逐题报告入口待确认'],
          ['.report-bottom-actions .primary', '导出摘要格式与内容待确认'],
        ]) {
          await page.locator(selector).click()
          ensure(await page.getByRole('status').textContent() === message
            && new URL(page.url()).searchParams.get('scene') === '19', '19未定义业务不应伪装完成：' + selector)
        }
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '19展开、键盘或待确认操作后刷新未恢复参考初态')
        for (const [selector, target] of [
          ['.report-top-actions .ts-button:not(.primary)', '02'], ['.report-top-actions .primary', '21'],
          ['.report-draft', '17'], ['.r-sidebar > a', '02'], ['.r-top-search', '25'],
        ]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '19导航未同步地址：' + selector)
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '19导航返回加载失败')
          await ready()
        }
        await assertFrame(captured, '19导航返回后未恢复参考初态')
        page.off('request', watchWrite)
        ensure(writes.length === 0, '19固定态不应写入业务接口')
        checks.push({ check: 'report-expand-keyboard-pending-boundaries-navigation-refresh', scene: scene.id, passed: true, businessWrites: writes,
          limitations: '只验证固定参考态；详细观察、AI重试、逐题报告和导出仍待确认。导航不代表正式训练或复盘业务验收，不创建或保存示例数据' })
      }
      if (scene.id === '20') {
        const geometry = {}
        for (const [selector, expected] of Object.entries({
          '.r-sidebar': [0, 10, 216, 996], '.design-canvas': [234, 259, 866, 699],
          '.design-details': [1111, 253, 402, 705], '.design-actions': [1143, 107, 365, 57],
        })) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '20面板或操作组几何不符：' + selector)
        }
        const initialNodes = await page.locator('.architecture-node').evaluateAll(nodes => nodes.map(node => ({
          id: node.getAttribute('data-node'), label: node.querySelector('text').textContent,
          transform: node.getAttribute('transform'), width: node.querySelector('rect').getAttribute('width'),
          height: node.querySelector('rect').getAttribute('height'),
        })))
        ensure(JSON.stringify(initialNodes) === JSON.stringify([
          { id: 'entry', label: '入口', transform: 'translate(137, 62)', width: '173', height: '69' },
          { id: 'inventory', label: '库存服务', transform: 'translate(121, 225)', width: '177', height: '69' },
          { id: 'order', label: '订单服务', transform: 'translate(355, 225)', width: '176', height: '69' },
          { id: 'reward', label: '奖励服务', transform: 'translate(589, 225)', width: '171', height: '69' },
          { id: 'config', label: '配置中心', transform: 'translate(46, 400)', width: '171', height: '69' },
          { id: 'queue', label: '消息队列', transform: 'translate(355, 400)', width: '176', height: '69' },
          { id: 'reconcile', label: '对账任务', transform: 'translate(355, 549)', width: '176', height: '69' },
          { id: 'monitor', label: '监控', transform: 'translate(629, 549)', width: '150', height: '69' },
        ]), '20节点文字、坐标或尺寸不符')
        const descriptionLines = await page.locator('.design-description p').evaluate(node => {
          const rows = new Map(), text = node.firstChild
          for (let index = 0; index < text.textContent.length; index++) {
            const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
            const y = range.getBoundingClientRect().y
            rows.set(y, (rows.get(y) ?? '') + text.textContent[index])
          }
          return [...rows.values()]
        })
        ensure(JSON.stringify(descriptionLines) === JSON.stringify([
          '设计一个秒杀发奖系统的整体架构，需考虑', '高并发下的发奖一致性、容错恢复以及可观',
          '测性。请画出核心服务、数据流和关键异步', '链路，并说明设计思路。',
        ]), '20题目说明换行与参考不符')
        ensure(JSON.stringify(await page.locator('.design-constraints li').allTextContents())
          === JSON.stringify(['峰值 5k QPS', '不重复发奖', '允许最终一致', '必须可验证恢复']), '20约束文字或顺序不符')
        ensure(await page.locator('.architecture-links > path').count() === 7
          && await page.locator('.architecture-svg > path[stroke-dasharray]').count() === 2
          && await page.locator('.architecture-node.selected').count() === 0, '20连线数或选中初态不符')
        ensure(await page.locator('.design-title > span').textContent() === '图 v2 · 未提交'
          && await page.locator('.r-top-status .r-icon-btn').count() === 2, '20版本状态或双通知按钮不符')
        checks.push({ check: 'design-eight-nodes-seven-links-four-constraints-fixed-geometry-text-lines', scene: scene.id,
          geometry, nodes: initialNodes, descriptionLines, passed: true })
        const writes = []
        const watchWrite = request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push({ method: request.method(), url: request.url() }) }
        page.on('request', watchWrite)
        const entryNode = page.locator('[data-node="entry"]')
        const entryLink = page.locator('.architecture-links > path').first()
        const beforeEntryLink = await entryLink.getAttribute('d')
        await entryNode.focus(); await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowDown')
        ensure(await entryNode.getAttribute('transform') === 'translate(145, 70)'
          && await entryLink.getAttribute('d') !== beforeEntryLink, '20方向键移动或连线跟随失败')
        const queue = page.locator('[data-node="queue"]'), box = await queue.boundingBox()
        ensure(box, '20消息队列节点不可见')
        const beforeQueueLink = await page.locator('.architecture-links > path').nth(3).getAttribute('d')
        await page.mouse.move(box.x + 80, box.y + 34); await page.mouse.down()
        await page.mouse.move(box.x + 112, box.y + 50, { steps: 4 }); await page.mouse.up()
        const movedQueue = await queue.evaluate(node => {
          const matrix = node.transform.baseVal.consolidate().matrix
          return [matrix.e, matrix.f]
        })
        ensure(Math.abs(movedQueue[0] - 387) < .05 && Math.abs(movedQueue[1] - 416) < .05
          && await page.locator('.architecture-links > path').nth(3).getAttribute('d') !== beforeQueueLink,
          '20拖动坐标转换或连线跟随失败')
        await page.getByRole('button', { name: '保存修改后的架构图和说明', exact: true }).click()
        ensure(await page.getByRole('status').textContent() === '图与说明已在本次页面内保留；持久保存待确认'
          && await page.getByRole('button', { name: '保存草稿', exact: true }).isVisible(), '20保存应明确仅本次页面保留')
        const mainTabs = page.getByRole('navigation', { name: '系统设计视图', exact: true })
        const asideTabs = page.getByRole('navigation', { name: '设计条件视图', exact: true })
        await mainTabs.getByRole('button', { name: '说明', exact: true }).click()
        await page.getByRole('textbox', { name: '系统设计说明', exact: true }).fill('补偿链路需验证恢复结果。')
        await asideTabs.getByRole('button', { name: '说明', exact: true }).click()
        ensure(await page.getByRole('textbox', { name: '图文说明', exact: true }).inputValue() === '补偿链路需验证恢复结果。', '20两处说明未共享页面草稿')
        await page.getByRole('textbox', { name: '图文说明', exact: true }).fill('图与说明仅在本次页面保留。')
        ensure(await page.getByRole('textbox', { name: '系统设计说明', exact: true }).inputValue() === '图与说明仅在本次页面保留。', '20说明反向编辑未同步')
        await mainTabs.getByRole('button', { name: '版本', exact: true }).click()
        await page.locator('.design-version-list .design-version').click()
        ensure(await page.getByRole('status').textContent() === '图 v2 版本详情待确认', '20版本详情不应伪造')
        await asideTabs.getByRole('button', { name: '版本', exact: true }).click()
        await page.locator('.design-aside-note .design-version').click()
        ensure(await page.getByRole('status').textContent() === '版本记录待确认', '20版本记录不应伪造')
        await asideTabs.getByRole('button', { name: '约束', exact: true }).click()
        await page.locator('.design-fixed').click()
        ensure(await page.getByRole('status').textContent() === '图与说明固定规则待确认', '20固定规则边界不符')
        await page.locator('.design-actions .primary').click()
        ensure(await page.getByRole('status').textContent() === '图文答案提交待确认', '20提交不能伪装成真实业务完成')
        for (const [title, message] of [['通知', '暂无新通知'], ['提醒', '暂无新提醒']]) {
          await page.getByTitle(title, { exact: true }).click()
          ensure(await page.getByRole('status').textContent() === message, '20通知按钮不可操作')
        }
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '20拖动、键盘、说明或页签操作后刷新未恢复固定参考初态')
        ensure(JSON.stringify(await page.locator('.architecture-node').evaluateAll(nodes => nodes.map(node => node.getAttribute('transform'))))
          === JSON.stringify(initialNodes.map(node => node.transform)), '20刷新后节点漂移')
        for (const [selector, target] of [['.r-sidebar > a', '02'], ['.r-top-search', '25']]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '20导航未同步地址')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '20导航返回加载失败'); await ready()
        }
        await assertFrame(captured, '20导航返回后参考初态不符')
        page.off('request', watchWrite)
        ensure(writes.length === 0, '20固定参考态不应写入业务接口')
        checks.push({ check: 'design-drag-keyboard-linked-paths-tabs-draft-pending-navigation-refresh', scene: scene.id,
          movedQueue, businessWrites: writes, passed: true,
          limitations: '仅固定参考状态；说明与图的持久保存、图文答案提交及版本详情待确认，未写入正式业务数据' })
      }
      if (scene.id === '21') {
        const geometry = {}
        for (const [selector, expected] of Object.entries({
          '.r-sidebar': [0, 0, 221, 1022], '.rv21-original': [249, 331, 441, 564],
          '.rv21-observations': [709, 331, 485, 564], '.rv21-improvement': [1212, 331, 299, 564],
          '.rv21-improvement-input': [1235, 436, 254, 114],
        })) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '21面板或输入框几何不符：' + selector)
        }
        const original = page.locator('.rv21-answer'), originalText = await original.locator('p').allTextContents()
        const originalLines = await original.locator('p').evaluateAll(nodes => nodes.map(node => {
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
        ensure(JSON.stringify(originalLines) === JSON.stringify([
          ['在删除缓存时，如果出现缓存未命中，', '会直接返回，不需要额外处理。'],
          ['为了保证数据一致性，我考虑在删除', '缓存前先检查数据是否存在，如果不', '存在则跳过删除操作。'],
        ]), '21原答逐行文字与参考不符')
        const actionLines = await page.getByRole('textbox', { name: '改进动作', exact: true }).evaluate(node => {
          const style = getComputedStyle(node), mirror = document.createElement('div')
          for (const name of ['font', 'width', 'padding', 'border', 'box-sizing', 'letter-spacing', 'word-break', 'overflow-wrap']) {
            mirror.style.setProperty(name, style.getPropertyValue(name))
          }
          Object.assign(mirror.style, { position: 'fixed', visibility: 'hidden', whiteSpace: 'pre-wrap' })
          mirror.textContent = node.value
          document.body.append(mirror)
          try {
            const text = mirror.firstChild, rows = new Map()
            for (let index = 0; index < text.textContent.length; index++) {
              if (text.textContent[index] === '\n') continue
              const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
              const y = range.getBoundingClientRect().y
              rows.set(y, (rows.get(y) ?? '') + text.textContent[index])
            }
            return [...rows.values()]
          } finally { mirror.remove() }
        })
        ensure(JSON.stringify(actionLines) === JSON.stringify(['下一次说明恢复判据及', '验证步骤']), '21改进动作未保持两行')
        const tabText = await page.locator('.rv21-tabs button').evaluateAll(nodes => nodes.map(node => {
          const text = [...node.childNodes].find(child => child.nodeType === Node.TEXT_NODE && child.textContent.trim())
          const range = document.createRange(); range.selectNodeContents(text)
          const rects = [...range.getClientRects()], box = node.getBoundingClientRect()
          return { text: text.textContent, lines: rects.length, inside: rects.every(rect => rect.x >= box.x && rect.right <= box.right) }
        }))
        ensure(tabText.every(tab => tab.lines === 1 && tab.inside), '21页签文字换行或超出按钮边界')
        ensure(JSON.stringify(await page.locator('.rv21-decision').allTextContents()) === JSON.stringify(['有异议', '接受', '接受'])
          && await page.locator('.rv21-observation.selected .rv21-observation-number').textContent() === '1'
          && await page.locator('.rv21-tabs button[aria-current="page"]').textContent() === '回答与反馈', '21反馈、选择或页签初态不符')
        const lastObservation = await page.locator('.observation-3').boundingBox()
        ensure(lastObservation && lastObservation.y + lastObservation.height === 894, '21第三项观察未延伸至面板底部')
        checks.push({ check: 'review-original-five-lines-three-decisions-two-line-action-fixed-geometry', scene: scene.id,
          geometry, originalLines, actionLines, tabText, passed: true })
        const writes = []
        const watchWrite = request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push({ method: request.method(), url: request.url() }) }
        page.on('request', watchWrite)
        for (const [index, decision] of [[0, '接受'], [1, '有异议'], [2, '暂不判断']]) {
          await page.locator('.rv21-decision').nth(index).click()
          await page.getByRole('group', { name: `观察 ${index + 1} 的反馈决定`, exact: true }).getByRole('button', { name: decision, exact: true }).click()
          ensure(await page.locator('.rv21-decision').nth(index).textContent() === decision
            && await page.locator('.rv-feedback-menu').count() === 0, '21反馈决定未更新或菜单未关闭')
        }
        await page.locator('.rv21-decision').first().focus(); await page.keyboard.press('Enter')
        ensure(await page.locator('.rv-feedback-menu').count() === 1, '21键盘未打开反馈菜单')
        await page.keyboard.press('Escape')
        ensure(await page.locator('.rv-feedback-menu').count() === 0, '21 Escape未关闭反馈菜单')
        await page.getByRole('button', { name: '选中观察 3', exact: true }).click()
        ensure(await page.locator('.observation-3').getAttribute('class').then(value => value.includes('selected')), '21观察数字未切换选择')
        await page.locator('.rv21-source-tags').getByRole('button', { name: '观察 02', exact: true }).click()
        ensure(await page.getByRole('button', { name: '选中观察 2', exact: true }).getAttribute('aria-pressed') === 'true', '21来源未定位观察02')
        await page.getByRole('button', { name: '原文 02，定位观察 01', exact: true }).click()
        ensure(await page.getByRole('button', { name: '选中观察 1', exact: true }).getAttribute('aria-pressed') === 'true', '21原文浮标未定位观察01')
        const input = page.getByRole('textbox', { name: '改进动作', exact: true })
        await input.fill('补充重试上限及恢复验证步骤')
        await page.getByRole('button', { name: '加入今日', exact: true }).click()
        ensure(await page.getByRole('button', { name: '已加入今日', exact: true }).getAttribute('aria-pressed') === 'true'
          && await page.getByRole('status').textContent() === '本次页面已标记加入今日；持久安排待确认', '21加入动作未明确页面状态边界')
        await page.getByRole('button', { name: '已加入今日', exact: true }).click()
        ensure(await page.getByRole('status').textContent() === '本次页面已取消标记；持久安排待确认', '21未取消本次页面标记')
        ensure(JSON.stringify(await original.locator('p').allTextContents()) === JSON.stringify(originalText), '21反馈交互改写了原答')
        const tabs = page.getByRole('navigation', { name: '复盘视图', exact: true })
        await tabs.getByRole('button', { name: '来源', exact: true }).click()
        ensure(await page.locator('.rv21-source-view > p').first().textContent() === '缓存删除失败以后 / 第 2 次提交', '21来源身份不符')
        await page.getByRole('button', { name: '返回回答与反馈', exact: true }).click()
        ensure(await input.inputValue() === '补充重试上限及恢复验证步骤', '21切换来源丢失页面动作草稿')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '21反馈、动作、定位及页签操作后刷新未恢复固定初态')
        for (const [selector, target] of [
          ['.rv21-tabs button:nth-child(2)', '22'], ['.rv21-knowledge', '10'],
          ['.rv21-practice', '17'], ['.r-top-search', '25'], ['.r-sidebar > a', '02'],
        ]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '21导航未同步地址：' + selector)
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '21导航返回加载失败'); await ready()
        }
        await assertFrame(captured, '21导航返回后未恢复参考初态')
        page.off('request', watchWrite)
        ensure(writes.length === 0, '21固定参考状态不应写入业务接口')
        checks.push({ check: 'review-decisions-keyboard-evidence-action-draft-tabs-navigation-refresh', scene: scene.id,
          businessWrites: writes, passed: true,
          limitations: '仅固定参考状态；反馈决定与改进动作只保留本次页面，持久安排、知识沉淀和正式复练业务待确认，未写入正式数据' })
      }
      if (scene.id === '22') {
        const geometry = {}
        for (const [selector, expected] of Object.entries({
          '.r-sidebar': [17, 24, 205, 973], '.r-top-search': [636, 34, 522, 59],
          '.rv22-old': [250, 259, 442, 525], '.rv22-conditions': [712, 259, 288, 525],
          '.rv22-new': [1020, 259, 481, 525], '.rv22-judgment': [250, 812, 1251, 156],
        })) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '22面板或框架几何不符：' + selector)
        }
        const answerLines = await page.locator('.rv22-answer-list li').evaluateAll(nodes => nodes.map(node => {
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
        ensure(JSON.stringify(answerLines) === JSON.stringify([
          ['监听连接异常，触发重试逻辑；'],
          ['使用指数退避策略，避免频繁重试；'],
          ['设置最大重试次数，防止无限重试。'],
          ['监听异常并触发重试，建议使用指数', '退避；'],
          ['设置合理的最大重试次数，避免无限重试。'],
        ]), '22新旧回答逐行内容不符')
        const conditionValues = await page.locator('.rv22-conditions dd').evaluateAll(nodes => nodes.map(node => node.innerText))
        ensure(JSON.stringify(conditionValues) === JSON.stringify(['未变', '已看参考 →\n未开启', '12 → 9 分钟', '有 → 无']), '22条件变化内容不符')
        const radios = page.getByRole('radiogroup', { name: '处理情况', exact: true })
        ensure(await radios.getByRole('radio').count() === 4
          && await radios.getByRole('radio', { name: '未处理', exact: true }).getAttribute('aria-checked') === 'true'
          && await page.getByRole('textbox', { name: '判断依据', exact: true }).inputValue() === '说明了恢复判据，但未说明重试上限',
          '22判断或依据初态不符')
        ensure(await page.locator('.r-top-search span').textContent() === '⌘K 搜索题目、知识点...'
          && await page.locator('.rv22-old .rv22-panel-heading').textContent() === '上次回答 · 11.08'
          && await page.locator('.rv22-new .rv22-panel-heading').textContent() === '本次复练 · 11.11', '22搜索文案或固定日期不符')
        checks.push({ check: 'compare-two-native-lists-five-items-conditions-judgment-fixed-geometry', scene: scene.id,
          geometry, answerLines, conditionValues, passed: true })
        const beforeAnswers = await page.locator('.rv22-answer-panel').allTextContents(), writes = []
        const watchWrite = request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push({ method: request.method(), url: request.url() }) }
        page.on('request', watchWrite)
        for (const option of ['已处理', '部分处理', '未处理', '无法判断']) {
          await radios.getByRole('radio', { name: option, exact: true }).click()
          ensure(await radios.locator('[aria-checked="true"]').count() === 1
            && await radios.getByRole('radio', { name: option, exact: true }).getAttribute('aria-checked') === 'true', '22四项判断未保持单选')
        }
        await radios.getByRole('radio', { name: '无法判断', exact: true }).focus()
        for (const [key, expected] of [['ArrowRight', '已处理'], ['ArrowLeft', '无法判断'], ['Home', '已处理'], ['End', '无法判断'], ['ArrowUp', '未处理'], ['ArrowDown', '无法判断']]) {
          await page.keyboard.press(key)
          ensure(await radios.getByRole('radio', { name: expected, exact: true }).evaluate(node => node === document.activeElement && node.getAttribute('aria-checked') === 'true'), '22判断键盘状态或焦点不符：' + key)
        }
        await page.getByRole('textbox', { name: '判断依据', exact: true }).fill('恢复判据与重试上限需分别验证')
        await page.getByRole('button', { name: '保存判断', exact: true }).click()
        ensure(await page.getByRole('status').textContent() === '判断已在本次页面内保留；持久保存待确认', '22保存不能伪装为持久业务完成')
        for (const keyword of ['重试机制', '退避策略', '最大次数']) {
          await page.locator('.rv22-keywords').getByRole('button', { name: keyword, exact: true }).click()
          ensure(await page.getByRole('status').textContent() === keyword, '22关键词不可操作')
        }
        ensure(JSON.stringify(await page.locator('.rv22-answer-panel').allTextContents()) === JSON.stringify(beforeAnswers), '22判断操作改写了新旧回答')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '22判断、键盘、依据及保存后刷新未恢复参考初态')
        for (const [selector, target] of [['.rv22-source', '21'], ['.r-top-search', '25'], ['.r-sidebar > a', '02']]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '22来源或框架导航未同步地址')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '22导航返回加载失败'); await ready()
        }
        await assertFrame(captured, '22导航返回后未恢复固定初态')
        page.off('request', watchWrite)
        ensure(writes.length === 0, '22固定状态不应写入业务接口')
        checks.push({ check: 'compare-four-judgments-keyboard-basis-save-boundary-source-navigation-refresh', scene: scene.id,
          businessWrites: writes, passed: true,
          limitations: '仅固定参考态；判断与依据只在本次页面保留，正式持久保存及非空复练业务待服务器验收，未写入示例数据' })
      }
      if (scene.id === '23') {
        const geometry = {}
        for (const [selector, expected] of Object.entries({
          '.r-sidebar': [0, 0, 244, 1024], '.r-top-search': [595, 27, 503, 56],
          '.rv23-confidence': [276, 266, 725, 175], '.rv23-record': [276, 440, 725, 514],
          '.rv23-create': [1012, 266, 493, 688],
        })) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '23面板或框架几何不符：' + selector)
        }
        const fragments = page.locator('.rv23-fragment')
        const expectedFragments = ['缓存删除失败后如何恢复？', '先查删除结果，再补偿......', '似乎追问了重试上限']
        ensure(JSON.stringify(await fragments.locator('.rv23-fragment-number').allTextContents()) === JSON.stringify(['01', '02', '03'])
          && JSON.stringify(await fragments.locator('h3').allTextContents()) === JSON.stringify(['问题', '我的回答片段', '面试官反馈（回忆）'])
          && JSON.stringify(await fragments.locator('p').allTextContents()) === JSON.stringify(expectedFragments), '23三片段初态文字不符')
        const suggestionLines = await page.locator('.rv23-create-copy > span').evaluateAll(nodes => nodes.map(node => {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), rows = new Map()
          let text
          while ((text = walker.nextNode())) {
            for (let index = 0; index < text.textContent.length; index++) {
              if (text.textContent[index] === '\n') continue
              const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
              const y = range.getBoundingClientRect().y
              rows.set(y, (rows.get(y) ?? '') + text.textContent[index])
            }
          }
          return [...rows.values()]
        }))
        ensure(JSON.stringify(suggestionLines) === JSON.stringify([
          ['这道题涉及缓存一致性与数据恢复，', '建议标记为待审题。'],
          ['对缓存删除后的恢复策略不够熟悉，', '需要补充相关知识。'],
          ['下次重点练习缓存一致性相关问题，', '并准备重试策略。'],
        ]), '23右栏三项说明必须各两行且文字完整')
        const confidence = page.getByRole('radiogroup', { name: '回忆自信程度', exact: true })
        ensure(await confidence.getByRole('radio').count() === 3
          && await confidence.getByRole('radio', { name: '有些模糊', exact: true }).getAttribute('aria-checked') === 'true'
          && await page.locator('.rv23-complete').evaluate(node => getComputedStyle(node).color === 'rgb(255, 248, 240)')
          && await page.locator('.r-saved svg').evaluate(node => getComputedStyle(node).fill === 'none')
          && await page.locator('.rv23-record-source').textContent() === '来源：回忆记录 · 非逐字实录',
          '23确定度、按钮字色、空心保存标记或回忆来源身份不符')
        checks.push({ check: 'recollection-three-fragments-two-line-suggestions-confidence-source-fixed-geometry', scene: scene.id,
          geometry, suggestionLines, passed: true })
        const writes = []
        const watchWrite = request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push({ method: request.method(), url: request.url() }) }
        page.on('request', watchWrite)
        for (const option of ['记得较清楚', '有些模糊', '不确定']) {
          await confidence.getByRole('radio', { name: option, exact: true }).click()
          ensure(await confidence.locator('[aria-checked="true"]').count() === 1
            && await confidence.getByRole('radio', { name: option, exact: true }).getAttribute('tabindex') === '0', '23确定度未保持单选和唯一焦点入口')
        }
        await confidence.getByRole('radio', { name: '不确定', exact: true }).focus()
        for (const [key, expected] of [['ArrowRight', '记得较清楚'], ['ArrowLeft', '不确定'], ['Home', '记得较清楚'], ['End', '不确定'], ['ArrowUp', '有些模糊'], ['ArrowDown', '不确定']]) {
          await page.keyboard.press(key)
          ensure(await confidence.getByRole('radio', { name: expected, exact: true }).evaluate(node => node === document.activeElement && node.getAttribute('aria-checked') === 'true'), '23确定度键盘或焦点不符：' + key)
        }
        for (let index = 0; index < 3; index++) {
          const fragment = fragments.nth(index)
          await fragment.getByRole('button').click()
          const editor = fragment.getByRole('textbox')
          ensure(await editor.evaluate(node => node === document.activeElement), '23片段编辑未获得焦点')
          await editor.fill(expectedFragments[index] + '（待核对）')
          if (index === 0) await page.keyboard.press('Control+Enter')
          else if (index === 1) await page.keyboard.press('Escape')
          else await fragment.getByRole('button').click()
          ensure(await fragment.locator('p').textContent() === expectedFragments[index] + '（待核对）', '23片段编辑值未保留')
        }
        await page.getByRole('button', { name: '保存草稿', exact: true }).click()
        ensure(await page.getByRole('status').textContent() === '草稿仅在本次页面内保留；持久保存待确认', '23草稿提示不能伪装为持久化')
        await page.getByRole('button', { name: '完成记录', exact: true }).click()
        ensure(await page.getByRole('status').textContent() === '本次页面已结束编辑；记录提交与持久保存待确认', '23完成提示不能伪装为提交成功')
        await page.getByRole('button', { name: '回忆身份', exact: true }).click()
        ensure(await page.getByRole('status').textContent() === '回忆身份：回忆记录 · 非逐字实录', '23回忆不能冒充逐字原答')
        await confidence.getByRole('radio', { name: '有些模糊', exact: true }).click()
        await page.getByRole('button', { name: '条件不完整', exact: true }).click()
        ensure(await confidence.getByRole('radio', { name: '有些模糊', exact: true }).getAttribute('aria-checked') === 'true', '23条件标签不应改写自信程度')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '23编辑、确定度、标签、草稿和完成后刷新未恢复初态')
        for (const [index, target, title] of [[0, '14', '待审题'], [1, '10', '知识缺口'], [2, '17', '复练动作']]) {
          await page.locator('.rv23-create-item').nth(index).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target
            && await page.getByRole('status').textContent() === `已打开相关页面；由回忆创建${title}的持久流程待确认`, '23相关页面导航或创建边界不符')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '23相关页面返回失败'); await ready()
        }
        for (const [selector, target] of [['.r-top-search', '25'], ['.r-sidebar > a', '02']]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '23框架导航未同步地址')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '23框架导航返回失败'); await ready()
        }
        await assertFrame(captured, '23导航返回后未恢复初态')
        page.off('request', watchWrite)
        ensure(writes.length === 0, '23固定态不应写入业务接口')
        checks.push({ check: 'recollection-confidence-keyboard-fragment-edit-save-boundary-identity-navigation-refresh', scene: scene.id,
          businessWrites: writes, passed: true,
          limitations: '仅本次页面内确定度和片段编辑；持久保存、提交、创建及正式非空回忆业务待确认／服务器验收' })
      }
      if (scene.id === '24') {
        const geometry = {}
        for (const [selector, expected] of Object.entries({
          '.r-sidebar': [0, 106, 208, 918], '.r-top-search': [715, 32, 399, 56],
          '.rv24-directions': [231, 270, 226, 617], '.rv24-evidence': [472, 270, 672, 617],
          '.rv24-suggestions': [1159, 270, 353, 617], '.rv24-note': [231, 923, 1281, 65],
        })) {
          const box = await page.locator(selector).boundingBox()
          geometry[selector] = box && [box.x, box.y, box.width, box.height]
          ensure(JSON.stringify(geometry[selector]) === JSON.stringify(expected), '24面板或框架几何不符：' + selector)
        }
        const matrix = page.locator('.rv24-evidence-table')
        const expectedRows = [
          ['异常与恢复', ['11.10', '11.09', '11.11', '- -- -']],
          ['职责边界', ['11.08', '11.10', '- -- -', '11.11']],
          ['缓存一致性', ['- -- -', '11.09', '11.11', '- -- -']],
          ['系统设计取舍', ['11.07', '- -- -', '11.10', '- -- -']],
        ]
        const rows = await matrix.locator('tbody tr').evaluateAll(nodes => nodes.map(node => [
          node.querySelector('th').textContent, [...node.querySelectorAll('td')].map(cell => cell.textContent.trim()),
        ]))
        ensure(JSON.stringify(rows) === JSON.stringify(expectedRows)
          && JSON.stringify(await matrix.locator('thead th > span').allTextContents()) === JSON.stringify(['回忆', '独立作答', '复练', '受审观察'])
          && await matrix.locator('.rv24-evidence-date').count() === 10 && await matrix.locator('.rv24-no-evidence').count() === 6,
          '24四行证据、日期、十项来源或六项无记录不符')
        const sidebarLines = await page.locator('.r-nav-item > span').evaluateAll(nodes => nodes.map(node => {
          const range = document.createRange(); range.selectNodeContents(node)
          return { text: node.textContent, lines: new Set([...range.getClientRects()].map(box => box.y)).size, fits: node.scrollWidth <= node.clientWidth }
        }))
        ensure(sidebarLines.every(item => item.lines === 1 && item.fits)
          && sidebarLines.some(item => item.text === '最近访问')
          && await page.locator('.r-nav-item.r-selected').textContent() === '成长'
          && await page.locator('.r-nav-item.r-selected').evaluate(node => getComputedStyle(node).borderRadius === '32px')
          && await page.locator('.r-avatar').allTextContents().then(items => items.length === 2 && items.every(text => text === '林')),
          '24侧栏应全部单行、成长胶囊选中且保留林头像')
        const suggestionLines = await page.locator('.rv24-suggestion-text').evaluateAll(nodes => nodes.map(node => {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), lines = new Map()
          let text
          while ((text = walker.nextNode())) for (let i = 0; i < text.textContent.length; i++) {
            if (text.textContent[i] === '\n') continue
            const range = document.createRange(); range.setStart(text, i); range.setEnd(text, i + 1)
            const y = range.getBoundingClientRect().y
            lines.set(y, (lines.get(y) ?? '') + text.textContent[i])
          }
          return [...lines.values()]
        }))
        ensure(JSON.stringify(suggestionLines) === JSON.stringify([
          ['梳理异常恢复的判定标准', '（重试、降级、告警）。'],
          ['明确核心服务与周边服务的', '职责边界与协作方式。'],
          ['围绕高并发、高可用场景进行', '系统设计复练。'],
        ]), '24建议文字与两行换行不符')
        ensure((await page.locator('.rv24-note').textContent()).replace(/\s/g, '') === '证据可追溯·不输出能力总分'
          && await page.locator('.rv24-evidence-filters input:checked').count() === 4
          && await page.locator('.rv24-suggestion .priority[aria-pressed="false"]').count() === 3,
          '24证据开关、建议初态或不输出能力总分边界不符')
        checks.push({ check: 'growth-four-row-ten-source-matrix-two-line-suggestions-single-line-sidebar-fixed-geometry',
          scene: scene.id, geometry, sidebarLines, rows, suggestionLines, passed: true })
        const writes = []
        const watchWrite = request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push({ method: request.method(), url: request.url() }) }
        page.on('request', watchWrite)
        const directions = page.getByRole('radiogroup', { name: '目标方向', exact: true })
        for (const label of ['技术深度', '工程实践', '系统设计', '项目经验']) {
          await directions.getByRole('radio', { name: label, exact: true }).click()
          ensure(await directions.locator('[aria-checked="true"]').count() === 1
            && await directions.getByRole('radio', { name: label, exact: true }).getAttribute('tabindex') === '0', '24方向选择未保持单选或唯一焦点入口')
        }
        for (const [key, label] of [['Home', '技术深度'], ['ArrowUp', '项目经验'], ['ArrowDown', '技术深度'], ['End', '项目经验'], ['ArrowLeft', '系统设计'], ['ArrowRight', '项目经验']]) {
          await page.keyboard.press(key)
          ensure(await directions.getByRole('radio', { name: label, exact: true }).evaluate(node => node === document.activeElement && node.getAttribute('aria-checked') === 'true'),
            '24方向键盘或焦点不符：' + key)
        }
        ensure(await page.getByRole('status').textContent() === '项目经验的证据分类规则待确认；当前保留固定参考数据', '24不能伪装为已实现其他方向的真实分类')
        const filters = page.locator('.rv24-evidence-filters')
        for (const [index, name] of ['回忆', '作答', '复练', '受审观察'].entries()) {
          await filters.getByRole('checkbox', { name, exact: true }).uncheck()
          ensure(await matrix.locator('thead th').count() === 4 - index, '24取消证据类型未同步表格列')
        }
        await page.locator('.rv24-filter').click()
        ensure(await page.locator('.rv24-filter-menu input:checked').count() === 0, '24两处筛选未共享选择')
        for (const name of ['回忆', '作答', '复练', '受审观察']) await page.locator('.rv24-filter-menu').getByRole('checkbox', { name, exact: true }).check()
        ensure(await filters.locator('input:checked').count() === 4 && await matrix.locator('thead th').count() === 5, '24菜单筛选未恢复完整证据矩阵')
        await page.keyboard.press('Escape')
        ensure(await page.locator('.rv24-filter-menu').count() === 0
          && await page.locator('.rv24-filter').evaluate(node => node === document.activeElement), '24筛选Escape关闭或焦点恢复失败')
        await page.locator('.rv24-target').click()
        ensure(await page.locator('.rv24-target-menu').isVisible(), '24目标菜单未打开')
        await page.keyboard.press('Escape')
        ensure(await page.locator('.rv24-target-menu').count() === 0
          && await page.locator('.rv24-target').evaluate(node => node === document.activeElement), '24目标菜单Escape关闭或焦点恢复失败')
        await page.locator('.rv24-target').click()
        await page.locator('.rv24-target-menu button').click()
        ensure(await page.getByRole('status').textContent() === '固定参考目标：Java 后端 · 技术一面；其他目标待确认', '24固定目标边界不符')
        for (let index = 0; index < 3; index++) {
          const button = page.locator('.rv24-suggestion .priority').nth(index)
          await button.click()
          ensure(await button.getAttribute('aria-pressed') === 'true'
            && await page.getByRole('status').textContent() === '优先级标记仅在本次页面内保留；持久安排待确认', '24建议优先级或持久边界不符')
          await button.click()
        }
        await page.locator('.rv24-suggestion').first().getByRole('button', { name: '忽略', exact: true }).click()
        ensure(await page.locator('.rv24-suggestion').count() === 2, '24忽略建议未隐藏')
        await page.getByRole('button', { name: '恢复已隐藏建议', exact: true }).click()
        ensure(await page.locator('.rv24-suggestion').count() === 3, '24建议未恢复')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '24选择、筛选和建议操作后刷新未恢复参考初态')
        for (const [name, target] of [['异常与恢复，回忆，11.10', '23'], ['异常与恢复，独立作答，11.09', '17'],
          ['异常与恢复，复练，11.11', '22'], ['职责边界，受审观察，11.11', '21']]) {
          await page.getByRole('button', { name, exact: true }).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '24证据身份导航不符')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '24来源导航返回加载失败'); await ready()
        }
        for (const [selector, target] of [['.r-top-search', '25'], ['.r-sidebar > a', '02']]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '24框架导航未同步地址')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '24框架导航返回加载失败'); await ready()
        }
        await assertFrame(captured, '24来源和框架导航返回后未恢复固定初态')
        page.off('request', watchWrite)
        ensure(writes.length === 0, '24固定状态不应写入业务接口')
        checks.push({ check: 'growth-direction-keyboard-shared-column-filters-suggestion-boundary-source-navigation-refresh',
          scene: scene.id, businessWrites: writes, passed: true,
          limitations: '固定参考方向与证据数据；其他方向分类、优先级持久化及正式非空成长业务待确认／服务器验收' })
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
        const background = page.locator('.sp-task-table')
        const taskRows = background.locator('tbody tr')
        const initialLayout = await page.evaluate(() => {
          const rect = selector => { const { x, y, width, height } = document.querySelector(selector).getBoundingClientRect(); return [x, y, width, height] }
          return { table: rect('.sp-task-table'), topSearch: rect('.r-top-search'),
            navigation: [...document.querySelectorAll('.r-nav-item > span')].map(node => {
              const range = document.createRange(); range.selectNodeContents(node)
              return { text: node.textContent, lines: range.getClientRects().length }
            }),
            brandCircles: document.querySelectorAll('.r-emblem > circle').length,
            breadcrumb: document.querySelector('.r-breadcrumb')?.textContent,
            priorities: [...document.querySelectorAll('.sp-task-table tbody td:nth-child(3)')].map(node => node.textContent),
          }
        })
        ensure(JSON.stringify(initialLayout.table) === JSON.stringify([231,375,1273,535])
          && JSON.stringify(initialLayout.topSearch) === JSON.stringify([609,21,496,55]), '25背景任务表或顶栏位置不符')
        ensure(initialLayout.navigation.every(item => item.lines === 1) && initialLayout.brandCircles === 1
          && initialLayout.breadcrumb === '今日任务总览', '25侧栏换行、品牌外圆或面包屑不符')
        ensure(await taskRows.count() === 5 && JSON.stringify(initialLayout.priorities) === JSON.stringify(['高','中','高','低','中']), '25五条背景任务或优先级不符')
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
        await input.press('Escape')
        const taskSearch = page.getByRole('textbox', { name: '筛选今日任务', exact: true })
        const allTasks = page.getByRole('checkbox', { name: '全选任务', exact: true })
        await taskSearch.fill('缓存')
        ensure(await taskRows.count() === 1, '25背景任务筛选未生效')
        await allTasks.check()
        ensure(await page.getByRole('checkbox', { name: '缓存一致性', exact: true }).isChecked(), '25可见任务全选未选中筛选结果')
        await taskSearch.fill('')
        ensure(await taskRows.count() === 5 && await allTasks.evaluate(node => node.indeterminate && !node.checked), '25清空筛选未恢复五行或部分选择')
        await page.getByRole('checkbox', { name: '深度理解线程池', exact: true }).check()
        await taskSearch.fill('缓存')
        await allTasks.uncheck()
        await taskSearch.fill('')
        ensure(await page.getByRole('checkbox', { name: '深度理解线程池', exact: true }).isChecked()
          && !await page.getByRole('checkbox', { name: '缓存一致性', exact: true }).isChecked(), '25取消可见全选改变隐藏任务状态')
        await taskSearch.fill('不存在的任务')
        ensure(await taskRows.count() === 0 && await allTasks.evaluate(node => !node.checked && !node.indeterminate), '25无匹配任务全选状态不符')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        ensure(await dialog.isVisible() && await input.inputValue() === '缓存'
          && await page.getByRole('checkbox', { name: '全选任务', exact: true }).evaluate(node => !node.checked && !node.indeterminate), '25刷新未恢复打开面板及未选任务')
        await assertFrame(captured, '25背景任务交互后刷新未恢复静止初态')
        checks.push({ check: 'command-shell-five-tasks-filter-visible-selection-refresh', scene: scene.id, initialLayout, passed: true })
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
        const detailPaint = await detail.evaluate(node => {
          const box = node.getBoundingClientRect()
          return { box: [box.x, box.y, box.width, box.height], backgroundClip: getComputedStyle(node).backgroundClip }
        })
        ensure(JSON.stringify(detailPaint.box) === JSON.stringify([1011, 233, 501, 701])
          && detailPaint.backgroundClip === 'padding-box', '30详情面板几何或背景绘制边界不符')
        checks.push({ check: 'inbox-detail-padding-clip-geometry', scene: scene.id, ...detailPaint, passed: true })
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
        interactionStatus: interactionError ? '失败' : checks.some(check => check.scene === scene.id && check.passed) ? '本页已配置交互检查通过' : '仅初态与刷新检查通过', interactionError,
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
