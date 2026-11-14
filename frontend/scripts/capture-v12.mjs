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
        checks.push({ check: 'whiteboard-positions-warning-lines-grid-boundary-zoom-drag-refresh', scene: scene.id, positions, warningLines, passed: true })
      }
      if (scene.id === '11') {
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
        interactionStatus: interactionError ? '失败' : ['04', '05', '06', '09', '10', '11', '12', '13', '25', '30'].includes(scene.id) ? '本页已配置交互检查通过' : '仅初态与刷新检查通过', interactionError,
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
