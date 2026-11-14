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
        checks.push({ check: 'inbox-source-selection-sort-checkbox-read-navigation-refresh', scene: scene.id, passed: true })
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
        interactionStatus: interactionError ? '失败' : ['09', '25', '30'].includes(scene.id) ? '本页已配置交互检查通过' : '仅初态与刷新检查通过', interactionError,
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
