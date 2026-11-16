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
  // 参考图包含原生滚动条，移除无头浏览器默认的隐藏参数才能记录真实控件。
  browser = await chromium.launch({ headless: true, ignoreDefaultArgs: ['--hide-scrollbars'], ...(values.channel === 'chromium' ? {} : { channel: values.channel }),
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
    const requestStarted = new Map(), recentRequests = []
    const recordRequest = event => {
      recentRequests.push(event)
      if (recentRequests.length > 80) recentRequests.shift()
    }
    // 仅记录公开参考入口的请求路径与耗时；保留超时证据，不放宽加载或像素断言。
    page.on('request', request => {
      const startedAt = Date.now(), url = new URL(request.url())
      requestStarted.set(request, { startedAt, method: request.method(), path: url.pathname, resourceType: request.resourceType() })
      recordRequest({ event: 'request', at: new Date(startedAt).toISOString(), path: url.pathname, resourceType: request.resourceType() })
    })
    for (const event of ['requestfinished', 'requestfailed']) page.on(event, request => {
      const info = requestStarted.get(request)
      if (!info) return
      recordRequest({ event, at: new Date().toISOString(), ...info, durationMs: Date.now() - info.startedAt,
        ...(event === 'requestfailed' ? { error: request.failure()?.errorText } : {}) })
      requestStarted.delete(request)
    })
    page.on('pageerror', error => pageErrors.push(error.message))
    page.on('response', response => {
      const url = new URL(response.url())
      recordRequest({ event: 'response', at: new Date().toISOString(), path: url.pathname, status: response.status() })
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
      if (scene.id === '01') {
        const layout = await page.evaluate(() => {
          const box = selector => { const r=document.querySelector(selector).getBoundingClientRect();return [r.x,r.y,r.width,r.height] }
          return {
            boxes:Object.fromEntries(['.lt-login','.lt-introduction .r-emblem','.lt-features','.lt-login-form','.lt-login-name','.lt-login-password','.lt-enter','.lt-reconnect'].map(s=>[s,box(s)])),
            features:[...document.querySelectorAll('.lt-features li')].map(n=>[n.querySelector('h2').textContent,n.querySelector('p').textContent]),
            brandPaths:document.querySelectorAll('.lt-introduction .r-emblem path').length,
            starFilled:[...document.querySelectorAll('.lt-introduction .r-emblem path')].some(n=>n.getAttribute('fill')==='#e4ad76'),
            graphNodes:(document.querySelector('.lt-features li:nth-child(2) path').getAttribute('d').match(/Z/g)||[]).length,
            composited:getComputedStyle(document.querySelector('.lt-login')).willChange,
            horizon:document.querySelector('.lt-dawn-horizon > g > path:last-child').getAttribute('d'),
            horizonMask:document.querySelector('.lt-dawn-horizon > g').getAttribute('mask'),
            englishSize:getComputedStyle(document.querySelector('.r-brand-word small')).fontSize,
            description:document.querySelector('.lt-brand-description').textContent,
            headings:[...document.querySelectorAll('.lt-login-form h1,.lt-login-subtitle')].map(n=>n.textContent),
          }
        })
        for (const [selector, expected] of [
          ['.lt-login',[0,0,1536,1024]],['.lt-introduction .r-emblem',[105,179,150,145]],
          ['.lt-features',[105,463,513,368]],['.lt-login-form',[838,139,627,745]],
          ['.lt-login-name',[888,346,527,85]],['.lt-login-password',[888,460,527,85]],
          ['.lt-enter',[888,586,527,76]],['.lt-reconnect',[888,680,527,76]],
        ]) ensure(JSON.stringify(layout.boxes[selector])===JSON.stringify(expected),'01几何不符：'+selector)
        ensure(layout.brandPaths===4&&layout.starFilled&&layout.graphNodes===5&&layout.composited==='transform'
          &&layout.horizon==='M-20 485C280 366 535 402 850 548'&&layout.horizonMask==='url(#lt-horizon-mask)'
          &&layout.englishSize==='35px'&&layout.description==='知识、准备、训练与复盘，留在一个工作台。'
          &&JSON.stringify(layout.headings)===JSON.stringify(['登录','进入你的个人空间'])
          &&JSON.stringify(layout.features)===JSON.stringify([['独立知识库','概念、笔记与出处'],['知识星图','看见真实关联'],['文字训练','从空白回答到复盘'],['成长证据','有记录、也有待验证']]),'01品牌、图标、原文或静止背景不符')
        await writeJson(path.join(values.output,'records','01-login-layout.json'),layout)
        checks.push({check:'login-split-brand-five-node-graph-four-features-fixed-horizon-geometry',scene:scene.id,layout,passed:true})
        const apiRequests=[],onRequest=request=>{if(new URL(request.url()).pathname.startsWith('/api/'))apiRequests.push(request.method()+' '+new URL(request.url()).pathname)}
        page.on('request',onRequest)
        await page.locator('.lt-login-name input').fill('参考页面演示')
        await page.locator('.lt-login-password input').fill('仅供页面内存演示')
        const toggle=page.locator('.lt-password-toggle')
        await toggle.click()
        ensure(await toggle.getAttribute('aria-pressed')==='true'&&await page.locator('.lt-login-password input').getAttribute('type')==='text','01密码显隐按钮未显示')
        await toggle.press('Enter')
        ensure(await toggle.getAttribute('aria-pressed')==='false'&&await page.locator('.lt-login-password input').getAttribute('type')==='password','01密码显隐键盘未隐藏')
        checks.push({check:'login-password-mouse-keyboard-visibility',scene:scene.id,passed:true})
        await page.locator('.lt-reconnect').click()
        ensure(await page.getByRole('status').textContent()==='示例页面未发起服务器连接检查或网络请求'&&!apiRequests.length,'01重连未保持示例边界')
        checks.push({check:'login-reconnect-explicit-no-api-boundary',scene:scene.id,passed:true})
        await page.locator('.lt-login-password input').press('Enter')
        await page.locator('.r-canvas[data-scene="02"]').waitFor({state:'visible'})
        ensure(new URL(page.url()).searchParams.get('scene')==='02'&&!apiRequests.length,'01表单键盘提交未进入02或发起认证')
        ensure((await page.goto(entry,{waitUntil:'networkidle'}))?.ok(),'01返回加载失败');await ready()
        ensure(await page.locator('.lt-login-name input').inputValue()===''&&await page.locator('.lt-login-password input').inputValue()===''
          &&await page.locator('.lt-login-password input').getAttribute('type')==='password','01返回未清空展示输入')
        await page.reload({waitUntil:'networkidle'});await ready()
        await assertFrame(captured,'01显隐／重连／键盘提交后刷新未恢复初态')
        ensure(!apiRequests.length,'01示例交互发送了API请求')
        page.off('request',onRequest)
        checks.push({check:'login-keyboard-submit-memory-reset-return-refresh-no-api',scene:scene.id,passed:true})
      }
      if (scene.id === '02') {
        const layout = await page.evaluate(() => {
          const box = selector => {
            const r = document.querySelector(selector).getBoundingClientRect()
            return [r.x, r.y, r.width, r.height]
          }
          return {
            boxes: Object.fromEntries(['.r-sidebar', '.r-sidebar > a', '.r-top-search', '.r-top-status > .r-avatar',
              '.r-sidebar-avatar .r-avatar', '.lt-day-summary', '.lt-continue', '.lt-task-panel', '.lt-interview-panel'].map(selector => [selector, box(selector)])),
            navigation: [...document.querySelectorAll('.r-nav-item span')].map(node => {
              const range = document.createRange(); range.selectNodeContents(node)
              return { text: node.textContent, lines: range.getClientRects().length }
            }),
            rows: [...document.querySelectorAll('.lt-task-table tbody tr')].map(row => [...row.cells].map(cell => cell.textContent)),
            date: document.querySelector('.lt-today-date').textContent,
            steps: [...document.querySelectorAll('.lt-interview-steps button')].map(node => ({ text: node.textContent.trim(), selected: node.getAttribute('aria-pressed') })),
            ring: document.querySelector('.lt-ring-progress').getAttribute('stroke-dasharray'),
            orbit: document.querySelector('.lt-practice-orbit ellipse').getAttribute('transform'),
            brandPaths: document.querySelectorAll('.r-emblem path').length,
            savedColor: getComputedStyle(document.querySelector('.r-saved')).color,
            typography: Object.fromEntries(['.lt-today-date p:first-child','.lt-practice-copy h3','.lt-task-link','.lt-interview-calendar'].map(selector=>{
              const s=getComputedStyle(document.querySelector(selector))
              return [selector,{fontSize:s.fontSize,paddingBottom:s.paddingBottom}]
            })),
            taskTextTransform: getComputedStyle(document.querySelector('.lt-task-link > span')).transform,
            orbitMask: getComputedStyle(document.querySelector('.lt-practice-orbit')).maskImage,
            orbitOpacity: getComputedStyle(document.querySelector('.lt-practice-orbit > ellipse')).strokeOpacity,
            firstStepOutline: document.querySelector('.lt-interview-steps .r-icon path').getAttribute('fill-rule'),
          }
        })
        for (const [selector, expected] of [
          ['.r-sidebar',[17,21,218,983]], ['.r-sidebar > a',[32,47,183,54]], ['.r-top-search',[262,28,718,55]],
          ['.r-top-status > .r-avatar',[1441,28,54,56]], ['.lt-day-summary',[262,221,1243,71]],
          ['.lt-continue',[262,311,795,263]], ['.lt-task-panel',[262,592,795,385]], ['.lt-interview-panel',[1076,311,429,666]],
        ]) ensure(JSON.stringify(layout.boxes[selector]) === JSON.stringify(expected),'02布局不符：'+selector)
        ensure(layout.brandPaths===4 && layout.navigation.length===10 && layout.navigation.every(item=>item.lines===1)
          && layout.savedColor==='rgb(192, 196, 185)' && layout.ring==='3 1' && layout.orbit==='rotate(-40 615 185)','02品牌、导航、保存、进度环或固定轨道不符')
        ensure(layout.typography['.lt-today-date p:first-child'].fontSize==='27.5px'
          && layout.typography['.lt-practice-copy h3'].fontSize==='29px'
          && layout.typography['.lt-task-link'].fontSize==='19px'
          && layout.typography['.lt-interview-calendar'].paddingBottom==='6px'
          && layout.taskTextTransform==='matrix(0.95, 0, 0, 1, 0, 0)'
          && layout.orbitMask!=='none' && layout.orbitOpacity==='0.7' && layout.firstStepOutline==='evenodd','02局部字面、日期牌、轨道渐隐或准备清单轮廓不符')
        await writeJson(path.join(values.output,'records','02-today-layout.json'),layout)
        checks.push({check:'today-brand-navigation-topbar-avatars-summary-three-panels-fixed-orbit-geometry',scene:scene.id,layout,passed:true})
        ensure(layout.rows.length===4 && layout.rows.map(row=>row[0]).join('|')==='到期回忆 · 缓存一致性|整理失败路径 · 分布式系统|独立复练 · 系统设计|确认面试安排 · 字节跳动'
          && layout.rows.map(row=>row[2]).join('|')==='3 分钟|5 分钟|12 分钟|5 分钟'
          && layout.date.includes('2026年11月11日 周三') && layout.date.includes('4 项待处理 · 预计 42 分钟')
          && JSON.stringify(layout.steps)===JSON.stringify([{text:'复习重点',selected:'true'},{text:'刷题巩固',selected:'false'},{text:'模拟面试',selected:'false'}]),'02固定文案、任务四行或三步初态不符')
        const tabs=page.getByRole('tablist',{name:'任务筛选'}).getByRole('tab')
        await tabs.nth(1).click()
        ensure(await page.locator('.lt-task-table tbody tr').count()===2,'02到期筛选行数错误')
        await tabs.nth(1).press('ArrowRight')
        ensure(await tabs.nth(2).getAttribute('aria-selected')==='true' && await page.locator('.lt-task-table tbody tr').count()===1,'02方向键筛选错误')
        await tabs.nth(2).press('ArrowRight')
        ensure(await tabs.first().getAttribute('aria-selected')==='true' && await page.locator('.lt-task-table tbody tr').count()===4,'02筛选没有循环返回')
        for(let index=1;index<3;index++) {
          await page.locator('.lt-interview-steps button').nth(index).click()
          ensure(await page.locator('.lt-interview-steps button').nth(index).getAttribute('aria-pressed')==='true','02准备步骤未切换')
        }
        await page.reload({waitUntil:'networkidle'}); await ready()
        await assertFrame(captured,'02筛选和准备步骤刷新后未恢复')
        checks.push({check:'today-four-tasks-filters-keyboard-three-steps-fixed-data-refresh',scene:scene.id,passed:true})
        await page.locator('.lt-quick-button').click()
        const dialog=page.getByRole('dialog',{name:'快速记录'}),input=dialog.getByRole('textbox',{name:'记录内容'})
        ensure(await input.evaluate(node=>node===document.activeElement),'02快速记录未自动聚焦')
        const close=dialog.getByRole('button',{name:'关闭快速记录'}),save=dialog.getByRole('button',{name:'保存记录'})
        await close.focus(); await close.press('Shift+Tab')
        ensure(await save.evaluate(node=>node===document.activeElement),'02弹层反向焦点未循环')
        await save.press('Tab')
        ensure(await close.evaluate(node=>node===document.activeElement),'02弹层正向焦点未循环')
        await input.fill('准备要点仅在本页记录')
        await input.press('Escape')
        ensure(await dialog.count()===0 && await page.locator('.lt-quick-button').evaluate(node=>node===document.activeElement),'02Escape关闭或返回焦点错误')
        await page.locator('.lt-quick-button').click(); await save.click()
        ensure(await dialog.count()===0 && await page.getByRole('status').textContent()==='快速记录已保留在本次页面内存中','02快速记录内存边界错误')
        await page.getByRole('textbox',{name:'记录面试准备要点'}).fill('明确恢复判据')
        await page.getByRole('button',{name:'保存面试准备要点'}).click()
        ensure(await page.getByRole('status').textContent()==='面试准备要点已保留在本次页面内存中','02面试要点内存边界错误')
        await page.reload({waitUntil:'networkidle'}); await ready()
        await assertFrame(captured,'02记录保存后刷新未恢复初态')
        checks.push({check:'today-quick-record-focus-trap-escape-local-note-boundaries-refresh',scene:scene.id,passed:true})
        for(const [selector,target] of [
          ['.lt-focus-button','16'],['.lt-resume-button','22'],['.lt-previous-button','19'],
          ['.lt-summary-interview','08'],['.lt-summary-sources','06'],['.lt-interview-overview','08'],
          ['.lt-task-table tbody tr:nth-child(1) .lt-task-link','16'],['.lt-task-table tbody tr:nth-child(2) .lt-task-link','11'],
          ['.lt-task-table tbody tr:nth-child(3) .lt-task-more','22'],['.lt-task-table tbody tr:nth-child(4) .lt-task-more','08'],
          ['.r-top-search','25'],['.r-sidebar-avatar','28'],
        ]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({state:'visible'})
          ensure(new URL(page.url()).searchParams.get('scene')===target,'02入口未进入对应场景')
          ensure((await page.goto(entry,{waitUntil:'networkidle'}))?.ok(),'02返回加载失败'); await ready()
        }
        await assertFrame(captured,'02十二入口返回后初态不符')
        checks.push({check:'today-focus-practice-answer-interview-source-task-command-avatar-navigation-return',scene:scene.id,passed:true})
      }
      if (scene.id === '03') {
        const layout = await page.evaluate(() => {
          const selectors = ['.r-sidebar', '.r-sidebar > a', '.r-top-search', '.r-top-status > .r-avatar',
            '.r-sidebar-avatar .r-avatar', '.p-actions .r-btn:first-child', '.p-actions .r-btn:last-child',
            '.target-list', '.requirements', '.recent-materials', '.target-route', '.target-budget']
          const box = node => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height] }
          const heading = document.querySelector('.target-name h2').getBoundingClientRect()
          const segmented = document.querySelector('.target-name .r-segmented').getBoundingClientRect()
          return {
            boxes: Object.fromEntries(selectors.map(selector => [selector, box(document.querySelector(selector))])),
            brandPaths: document.querySelectorAll('.r-emblem path').length,
            brandCircles: document.querySelectorAll('.r-emblem circle').length,
            navigation: [...document.querySelectorAll('.r-nav-item span')].map(node => {
              const range = document.createRange(); range.selectNodeContents(node)
              return { text: node.textContent, lines: range.getClientRects().length }
            }),
            headingClear: heading.right < segmented.left,
            rows: [...document.querySelectorAll('.requirements tbody tr')].map(node => [...node.cells].map(cell => cell.textContent)),
            columns: [...document.querySelectorAll('.requirements col')].map(node => node.getBoundingClientRect().width),
            dates: [...document.querySelectorAll('.timeline-item strong')].map(node => node.textContent),
            dots: [...document.querySelectorAll('.timeline-dot')].map(box),
            materials: [...document.querySelectorAll('.recent-materials button')].map(node => node.textContent),
            overflow: [...document.querySelectorAll('.target-item strong,.requirements td,.recent-materials button,.timeline-item strong')]
              .some(node => node.scrollWidth > node.clientWidth + 1),
          }
        })
        for (const [selector, expected] of [
          ['.r-sidebar',[0,0,208,994]], ['.r-sidebar > a',[34,34,160,56]], ['.r-top-search',[645,26,500,53]],
          ['.r-top-status > .r-avatar',[1455,26,47,52]], ['.r-sidebar-avatar .r-avatar',[32,919,53,57]],
          ['.p-actions .r-btn:first-child',[1173,135,169,57]], ['.p-actions .r-btn:last-child',[1360,135,142,57]],
          ['.target-list',[223,284,295,694]], ['.requirements',[529,429,630,327]], ['.recent-materials',[529,765,630,213]],
          ['.target-route',[1184,284,329,495]], ['.target-budget',[1184,788,329,190]],
        ]) ensure(JSON.stringify(layout.boxes[selector]) === JSON.stringify(expected), '03布局不符：' + selector)
        ensure(layout.brandPaths === 2 && layout.brandCircles === 2 && layout.headingClear && !layout.overflow
          && layout.navigation.length === 10 && layout.navigation.every(item => item.lines === 1), '03品牌、导航换行或标题／内容溢出')
        await writeJson(path.join(values.output,'records','03-target-layout.json'),layout)
        checks.push({check:'target-brand-three-columns-single-line-navigation-title-table-materials-route-budget-geometry',scene:scene.id,layout,passed:true})
        const typography = await page.evaluate(() => {
          const styles = Object.fromEntries(['.r-scene-03','.p-title','.prep-tabs button','.prep-tabs .r-active','.target-list h3',
            '.target-item strong','.target-item small','.target-name h2','.target-meta','.requirements .r-table','.requirements td',
            '.requirements td:nth-child(2)','.requirements .r-pill','.recent-materials','.recent-materials h3','.recent-materials button',
            '.recent-materials .r-muted','.timeline-item strong','.timeline-item.current strong','.target-budget b','.r-nav-separator'].map(selector => {
              const node = document.querySelector(selector), s = getComputedStyle(node)
              return [selector, {weight:s.fontWeight,size:s.fontSize,stroke:s.webkitTextStrokeWidth,top:s.top,
                transform:s.transform,marginTop:s.marginTop,marginBottom:s.marginBottom,padding:s.padding,
                height:s.height,background:s.backgroundImage}]
            }))
          return {styles,materialRows:[...document.querySelectorAll('.recent-materials button')].map(node => node.getBoundingClientRect().height)}
        })
        const ts = typography.styles
        ensure(ts['.r-scene-03'].weight === '300' && ts['.p-title'].weight === '500' && ts['.p-title'].size === '58px'
          && ts['.p-title'].stroke === '0.9px' && ts['.p-title'].transform === 'matrix(0.94, 0, 0, 1, 0, 0)'
          && ts['.prep-tabs button'].weight === '500' && ts['.prep-tabs .r-active'].stroke === '0.2px'
          && ['.target-list h3','.target-name h2'].every(selector => ts[selector].weight === '500' && ts[selector].stroke === '0.4px')
          && ['.target-item strong','.target-item small'].every(selector => ts[selector].weight === '400' && ts[selector].top === '-2px')
          && ts['.target-meta'].weight === '400' && ts['.requirements .r-table'].marginTop === '14px'
          && ts['.requirements td'].weight === '300' && ts['.requirements td'].height === '47px'
          && ts['.requirements td'].padding === '6px 3px' && ts['.requirements td:nth-child(2)'].weight === '400'
          && ts['.requirements .r-pill'].size === '19px' && ts['.requirements .r-pill'].weight === '400'
          && ts['.recent-materials'].padding === '16px 34px 18px 21px' && ts['.recent-materials h3'].top === '1px'
          && ts['.recent-materials button'].weight === '400' && ts['.recent-materials .r-muted'].size === '20px'
          && typography.materialRows.join(',') === '47,47,47' && ts['.timeline-item strong'].weight === '400'
          && ts['.timeline-item.current strong'].weight === '500' && ts['.timeline-item.current strong'].size === '21px'
          && ts['.timeline-item.current strong'].top === '-1px' && ts['.target-budget b'].weight === '500'
          && ts['.r-nav-separator'].marginBottom === '27px','03字重、表格基线、材料留白或路线／次导航间距不符')
        await writeJson(path.join(values.output,'records','03-target-typography.json'),typography)
        checks.push({check:'target-typography-table-baselines-material-spacing-current-route-secondary-navigation',scene:scene.id,typography,passed:true})
        ensure(JSON.stringify(layout.rows) === JSON.stringify([
          ['分析异常与恢复','2 条','● 有证据','11.08'], ['讲清本人职责','1 条','● 待补','11.06'],
          ['缓存一致性','3 条','● 有证据','11.10'], ['系统设计取舍','0 条','● 无记录','—'],
        ]) && layout.columns.slice(0,3).join(',') === '217,149,159'
          && layout.dates.length === 4 && layout.materials.length === 3
          && layout.dots.map(box=>box[1]).join(',') === '374,476,588,690', '03四行要求、表列或四阶段路线不符')
        checks.push({check:'target-four-requirements-three-materials-four-stages-fixed-data',scene:scene.id,passed:true})
        for (const [index, title, keyboard] of [[1,'美团 · 后端开发','Enter'],[2,'通用 · Java 基础',' ']]) {
          const item = page.locator('.target-item').nth(index)
          await item.focus(); await item.press(keyboard)
          ensure(await item.getAttribute('aria-pressed') === 'true' && (await page.locator('.target-name h2').textContent()).trim() === title,
            '03键盘选择未同步目标')
          ensure(await page.locator('.target-unconfirmed').isVisible()
            && await page.locator('.requirements,.recent-materials,.target-route,.target-budget,.company-bars').count() === 0,
            '03未定义目标借用了首个目标详情')
        }
        await page.locator('.target-item').first().click()
        await page.locator('.target-name .r-segmented button').last().click()
        ensure(await page.getByRole('status').getByText('历史目标内容待确认',{exact:true}).isVisible(), '03历史内容边界未说明')
        await page.reload({waitUntil:'networkidle'}); await ready()
        await assertFrame(captured,'03选择／历史边界后刷新未恢复参考初态')
        checks.push({check:'target-keyboard-selection-no-borrowed-details-history-boundary-refresh',scene:scene.id,passed:true})
        for (const [selector, target] of [
          ['.prep-tabs button:nth-child(2)','06'], ['.prep-tabs button:nth-child(3)','07'], ['.prep-tabs button:nth-child(4)','08'],
          ['.target-budget .r-btn','07'], ['.recent-materials button:first-of-type','23'], ['.recent-materials button:nth-of-type(2)','06'],
          ['.r-sidebar > a','02'], ['.r-top-search','25'],
        ]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({state:'visible'})
          ensure(new URL(page.url()).searchParams.get('scene') === target,'03入口没有进入对应场景')
          ensure((await page.goto(entry,{waitUntil:'networkidle'}))?.ok(),'03返回加载失败'); await ready()
        }
        await page.locator('.requirements tbody tr').first().focus()
        await page.locator('.requirements tbody tr').first().press('Enter')
        await page.locator('.r-canvas[data-scene="24"]').waitFor({state:'visible'})
        ensure((await page.goto(entry,{waitUntil:'networkidle'}))?.ok(),'03要求证据返回加载失败'); await ready()
        await assertFrame(captured,'03页签、材料、预算、证据、品牌或搜索返回初态不符')
        checks.push({check:'target-tabs-materials-budget-evidence-keyboard-brand-command-navigation-return',scene:scene.id,passed:true})
      }
      if (scene.id === '04') {
        const layout = await page.evaluate(() => {
          const boxes = Object.fromEntries(['.r-sidebar', '.r-sidebar > a', '.r-top-search', '.r-sidebar-avatar', '.project-tree', '.fact-body', '.fact-links', '.expressions'].map(selector => {
            const box = document.querySelector(selector).getBoundingClientRect()
            return [selector, [box.x, box.y, box.width, box.height]]
          }))
          const navigation = [...document.querySelectorAll('.r-nav-item span')].map(node => {
            const range = document.createRange(); range.selectNodeContents(node)
            return { text: node.textContent, lines: range.getClientRects().length }
          })
          const heading = document.querySelector('.fact-heading h3').getBoundingClientRect()
          const badges = document.querySelector('.fact-heading > div').getBoundingClientRect()
          return { boxes, navigation, headingClear: heading.right <= badges.left, selected: document.querySelectorAll('.project-leaf.selected').length }
        })
        for (const [selector, expected] of [
          ['.r-sidebar', [0, 0, 239, 1013]], ['.r-sidebar > a', [37, 36, 174, 65]], ['.r-top-search', [671, 24, 468, 61]],
          ['.r-sidebar-avatar', [40, 917, 58, 66]], ['.project-tree', [262, 246, 246, 707]], ['.fact-body', [517, 246, 611, 612]],
          ['.fact-links', [517, 867, 611, 86]], ['.expressions', [1144, 246, 369, 707]],
        ]) ensure(JSON.stringify(layout.boxes[selector]) === JSON.stringify(expected), '04布局不符：' + selector)
        ensure(layout.navigation.length === 10 && layout.navigation.every(item => item.lines === 1) && layout.headingClear && layout.selected === 1, '04导航换行、标题重叠或选中不符')
        checks.push({ check: 'fact-three-columns-single-line-navigation-brand-search-avatar-geometry', scene: scene.id, layout, passed: true })
        const details = await page.evaluate(() => {
          const title = document.querySelector('.prep-facts .p-title')
          const buttons = [...document.querySelectorAll('.prep-facts .p-actions .r-btn')].map(node => {
            const box = node.getBoundingClientRect()
            return { text: node.textContent, width: box.width, height: box.height }
          })
          const fields = [...document.querySelectorAll('.fact-body dd')].map(node => ({
            text: node.innerText, breaks: node.querySelectorAll('br').length, whiteSpace: getComputedStyle(node).whiteSpace,
          }))
          const cube = document.querySelector('.r-nav-item.r-selected svg path')
          return { titleSize: getComputedStyle(title).fontSize, buttons, fields, cubeIsOutline: !cube.hasAttribute('fill'),
            infoIsFilled: document.querySelector('.fact-foot svg path').getAttribute('fill') === 'currentColor' }
        })
        ensure(details.titleSize === '48px' && details.buttons.length === 2
          && details.buttons[0].width === 121 && details.buttons[1].width === 161 && details.buttons.every(item => item.height === 55)
          && details.cubeIsOutline && details.infoIsFilled, '04标题、按钮尺寸或专用图标不符')
        ensure(JSON.stringify(details.fields.map(item => item.breaks)) === '[0,1,1,1]'
          && details.fields.every(item => item.whiteSpace === 'nowrap')
          && details.fields[3].text === '问题已修复，补发任务已完成，用户可正常\n领取奖励；关联日志与监控截图已留存', '04事实正文或换行漂移')
        checks.push({ check: 'fact-typography-control-width-outline-icons-original-body-lines', scene: scene.id, details, passed: true })
        const typography = await page.evaluate(() => {
          const selectors = ['.r-scene-04', '.prep-facts .p-title', '.facts-tabs button.r-active', '.project-tree h3', '.fact-heading h3', '.expressions > h2', '.expression-row', '.expression-preview h3', '.expression-actions .r-btn']
          const styles = Object.fromEntries(selectors.map(selector => {
            const node = document.querySelector(selector), style = getComputedStyle(node)
            return [selector, { weight: style.fontWeight, size: style.fontSize, stroke: style.webkitTextStrokeWidth }]
          }))
          const title = document.querySelector('.prep-facts .p-title'), titleStyle = getComputedStyle(title)
          const tabStyle = getComputedStyle(document.querySelector('.facts-tabs button.r-active'))
          const body = [...document.querySelectorAll('.fact-body dd')].map(node => {
            const style = getComputedStyle(node)
            return { size: style.fontSize, weight: style.fontWeight, lineHeight: style.lineHeight, transform: style.transform, origin: style.transformOrigin }
          })
          return { styles, title: { top: titleStyle.top, left: titleStyle.left, transform: titleStyle.transform },
            tabPadding: tabStyle.paddingBottom, body, source: getComputedStyle(document.querySelector('.fact-source')).backgroundImage,
            links: getComputedStyle(document.querySelector('.fact-links button')).backgroundImage }
        })
        for (const [selector, weight] of [
          ['.r-scene-04', '300'], ['.prep-facts .p-title', '500'], ['.facts-tabs button.r-active', '500'],
          ['.project-tree h3', '400'], ['.fact-heading h3', '500'], ['.expressions > h2', '500'],
          ['.expression-row', '300'], ['.expression-preview h3', '400'], ['.expression-actions .r-btn', '500'],
        ]) ensure(typography.styles[selector].weight === weight, '04字重不符：' + selector)
        ensure(typography.styles['.prep-facts .p-title'].stroke === '0.6px'
          && typography.styles['.fact-heading h3'].stroke === '0.15px'
          && typography.styles['.expressions > h2'].stroke === '0.2px'
          && typography.styles['.project-tree h3'].size === '21px'
          && typography.title.top === '92px' && typography.title.left === '27px'
          && typography.title.transform === 'matrix(1, 0, 0, 1.1, 0, 0)' && typography.tabPadding === '19px',
        '04标题、目录字面或页签基线不符')
        ensure(typography.body.length === 4 && typography.body.every(item => item.size === '23px' && item.weight === '400'
          && item.lineHeight === '35px' && item.transform === 'matrix(0.913044, 0, 0, 1, 0, 0)' && item.origin === '0px 0px'),
        '04事实正文的字高、行宽或换行基线不符')
        ensure(typography.source === 'linear-gradient(110deg, rgba(255, 255, 255, 0.043), rgba(255, 255, 255, 0.027))'
          && typography.links === 'linear-gradient(120deg, rgb(25, 22, 19), rgb(21, 19, 17))', '04来源或关联入口底色不符')
        checks.push({ check: 'fact-scoped-type-weights-body-aspect-tab-baseline-source-link-surfaces', scene: scene.id, typography, passed: true })
        const status = await page.evaluate(() => {
          const box = node => { const b = node.getBoundingClientRect(); return [b.x, b.y, b.width, b.height] }
          const pills = [...document.querySelectorAll('.fact-heading .r-pill')].map(node => ({
            text: node.textContent, box: box(node), size: getComputedStyle(node).fontSize,
            padding: getComputedStyle(node).padding, background: getComputedStyle(node).backgroundImage,
          }))
          const dot = document.querySelector('.fact-status-dot')
          return { pills, dot: { box: box(dot), hidden: dot.getAttribute('aria-hidden'), background: getComputedStyle(dot).backgroundImage },
            headingHeight: document.querySelector('.fact-heading').getBoundingClientRect().height,
            bodyTop: document.querySelector('.fact-body dd').getBoundingClientRect().top }
        })
        ensure(status.headingHeight === 50.59375 && status.bodyTop === 409.59375
          && status.pills.length === 2 && status.pills[0].text === '待核实 1 项' && status.pills[1].text === '参与排查'
          && JSON.stringify(status.pills[0].box) === '[825,297.796875,144,49]'
          && JSON.stringify(status.pills[1].box) === '[977,297.796875,127,49]'
          && status.pills.every(item => item.size === '18px' && item.padding === '0px')
          && status.pills[0].background === 'linear-gradient(110deg, rgb(74, 50, 27), rgb(52, 36, 20))'
          && status.dot.box[2] === 13 && status.dot.box[3] === 15 && status.dot.hidden === 'true'
          && status.dot.background === 'linear-gradient(120deg, rgb(255, 197, 128), rgb(238, 166, 84))',
        '04状态标签、圆点或正文基线不符')
        checks.push({ check: 'fact-status-native-dot-pill-geometry-stable-body-baseline', scene: scene.id, status, passed: true })
        const previewStyle = await page.evaluate(() => {
          const node = document.querySelector('.expression-preview p'), b = node.getBoundingClientRect()
          return { box: [b.x, b.y, b.width, b.height], transform: getComputedStyle(node).transform,
            actions: [...document.querySelectorAll('.expression-actions .r-btn')].map(button => {
              const s = getComputedStyle(button), a = button.getBoundingClientRect()
              return { weight: s.fontWeight, stroke: s.webkitTextStrokeWidth, height: a.height }
            }) }
        })
        ensure(previewStyle.box[0] === 1185 && previewStyle.box[1] === 623.09375 && previewStyle.box[2] === 289
          && previewStyle.transform === 'matrix(1, 0, 0, 1.09, 2, 2)'
          && previewStyle.actions.length === 2 && previewStyle.actions.every(item => item.weight === '500' && item.stroke === '0.2px' && item.height === 55),
        '04预览文字基线或编辑／新建版本字重不符')
        checks.push({ check: 'fact-preview-baseline-action-weight-stroke-geometry', scene: scene.id, previewStyle, passed: true })
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
        for (const [selector, target] of [
          ['.r-sidebar > a', '02'], ['.facts-tabs button:nth-child(2)', '05'], ['.facts-tabs button:nth-child(3)', '06'],
          ['.fact-source', '06'], ['.fact-links button:first-child', '05'], ['.fact-links button:last-child', '19'],
        ]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '04入口未进入对应场景：' + selector)
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '04返回加载失败')
          await ready()
        }
        await assertFrame(captured, '04各入口返回后初态不符')
        checks.push({ check: 'fact-brand-tabs-source-expression-training-navigation-return', scene: scene.id, passed: true })
      }
      if (scene.id === '05') {
        const expressionLayout = await page.evaluate(() => {
          const selectors = ['.expression-editor', '.version-history', '.version-diff', '.expression-tabs', '.r-top-search', '.r-top-status>.r-avatar', '.r-sidebar-avatar .r-avatar', '.r-sidebar > a', '.r-emblem']
          return {
            boxes: Object.fromEntries(selectors.map(selector => {
              const box = document.querySelector(selector).getBoundingClientRect()
              return [selector, [box.x, box.y, box.width, box.height]]
            })),
            titleSize: getComputedStyle(document.querySelector('.prep-expression .p-title')).fontSize,
            composition: getComputedStyle(document.querySelector('.r-scene-05')).willChange,
            saveColor: getComputedStyle(document.querySelector('.version-history>.r-primary')).color,
            nav: [...document.querySelectorAll('.r-nav-item')].map(node => {
              const box = node.getBoundingClientRect(), label = node.querySelector('span').getBoundingClientRect()
              return { text: node.textContent, within: label.right <= box.right, height: label.height }
            }),
            trainingPath: document.querySelectorAll('.r-nav-item')[3].querySelector('path').getAttribute('d'),
            prose: [...document.querySelectorAll('.expression-prose p')].map(node => ({ text: node.textContent, breaks: node.querySelectorAll('br').length })),
            unresolved: document.querySelectorAll('.expression-prose .unreadable[aria-label="原图此处文字待确认"]').length,
          }
        })
        for (const [selector, expected] of [
          ['.expression-editor', [269, 229, 933, 580]], ['.version-history', [1211, 229, 309, 580]],
          ['.version-diff', [269, 821, 1251, 170]], ['.expression-tabs', [270, 166, 321, 51]],
          ['.r-top-search', [696, 21, 378, 53]], ['.r-top-status>.r-avatar', [1451, 25, 41, 44]],
          ['.r-emblem', [40, 49.5, 47, 55]], ['.r-sidebar-avatar .r-avatar', [42, 923, 49, 52]],
        ]) ensure(expressionLayout.boxes[selector]?.every((value, index) => Math.abs(value - expected[index]) <= 1), '05几何不符：' + selector)
        ensure(expressionLayout.titleSize === '44px' && expressionLayout.composition === 'transform'
          && expressionLayout.saveColor === 'rgb(255, 246, 232)' && expressionLayout.nav.length === 10
          && expressionLayout.nav.every(item => item.within && item.height <= 34)
          && expressionLayout.trainingPath.startsWith('M5 3a1')
          && expressionLayout.prose.map(row => row.breaks).join(',') === '1,2,1'
          && expressionLayout.unresolved === 1, '05标题、按钮、导航图标、原换行或待确认边界不符')
        await writeJson(path.join(values.output, 'records', '05-expression-layout.json'), expressionLayout)
        checks.push({ check: 'expression-three-panels-brand-topbar-tabs-prose-and-save-paint', scene: scene.id, passed: true })
        const expressionPaint = await page.evaluate(() => {
          const selectors = ['.r-scene-05', '.prep-expression .p-title', '.expression-text h2', '.expression-directory strong',
            '.expression-tabs button.r-active', '.expression-prose p', '.expression-byline', '.expression-text .r-rule',
            '.expression-outline p', '.expression-outline>div span', '.history-item p', '.version-history',
            '.diff-heading>strong', '.diff-columns', '.diff-columns .added', '.diff-columns p>span:last-child', '.r-nav-separator']
          return Object.fromEntries(selectors.map(selector => {
            const node = document.querySelector(selector), style = getComputedStyle(node), box = node.getBoundingClientRect()
            return [selector, { weight: style.fontWeight, size: style.fontSize, stroke: style.webkitTextStrokeWidth,
              transform: style.transform, marginTop: style.marginTop, marginBottom: style.marginBottom,
              paddingLeft: style.paddingLeft, background: style.backgroundImage, clipPath: style.clipPath,
              gap: style.gap, box: [box.x, box.y, box.width, box.height] }]
          }))
        })
        ensure(expressionPaint['.r-scene-05'].weight === '300'
          && expressionPaint['.prep-expression .p-title'].weight === '500'
          && expressionPaint['.prep-expression .p-title'].stroke === '0.6px'
          && expressionPaint['.prep-expression .p-title'].transform === 'matrix(0.96, 0, 0, 1, 0, 0)'
          && expressionPaint['.expression-text h2'].weight === '500'
          && expressionPaint['.expression-text h2'].transform === 'matrix(0.98, 0, 0, 1, 0, 0)'
          && expressionPaint['.expression-directory strong'].weight === '500'
          && expressionPaint['.expression-tabs button.r-active'].weight === '500'
          && expressionPaint['.expression-prose p'].size === '19px'
          && expressionPaint['.expression-prose p'].weight === '300'
          && expressionPaint['.expression-byline'].gap === '11px'
          && Math.abs(expressionPaint['.expression-text .r-rule'].box[1] - 358.484375) < 1
          && expressionPaint['.expression-text .r-rule'].box[2] === 497
          && expressionPaint['.expression-outline p'].size === '17px'
          && expressionPaint['.expression-outline>div span'].size === '0px'
          && expressionPaint['.expression-outline>div span'].clipPath.startsWith('polygon(')
          && expressionPaint['.history-item p'].weight === '400'
          && expressionPaint['.version-history'].background.includes('rgb(30, 26, 22)')
          && expressionPaint['.diff-heading>strong'].size === '21px'
          && expressionPaint['.diff-columns'].size === '19px'
          && expressionPaint['.diff-columns .added'].paddingLeft === '15px'
          && expressionPaint['.diff-columns p>span:last-child'].transform === 'matrix(0.96, 0, 0, 1, 0, 0)'
          && expressionPaint['.r-nav-separator'].marginTop === '22px'
          && expressionPaint['.r-nav-separator'].marginBottom === '26px', '05字重、分隔线、提纲、版本记录或对比内距不符')
        await writeJson(path.join(values.output, 'records', '05-expression-paint.json'), expressionPaint)
        checks.push({ check: 'expression-typography-rule-outline-diff-spacing-and-panel-paint', scene: scene.id, passed: true })
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
        for (const [selector, target] of [
          ['.r-sidebar > a', '02'], ['.expression-tabs button:first-child', '04'],
          ['.expression-tabs button:last-child', '06'], ['.history-count:first-of-type', '04'],
          ['.history-count:last-of-type', '19'],
        ]) {
          const link = selector.startsWith('.history-count')
            ? page.locator('.history-count').nth(target === '04' ? 0 : 1) : page.locator(selector)
          await link.click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '05入口未进入对应场景')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '05返回加载失败')
          await ready()
        }
        await assertFrame(captured, '05各入口返回后初态不符')
        checks.push({ check: 'expression-brand-tabs-facts-training-navigation-return', scene: scene.id, passed: true })
      }
      if (scene.id === '06') {
        const library = page.locator('.file-library'), viewer = page.locator('.pdf-viewer')
        const layout = await page.evaluate(() => {
          const box = selector => {
            const r = document.querySelector(selector).getBoundingClientRect()
            return [r.x, r.y, r.width, r.height]
          }
          return {
            sidebar: box('.r-sidebar'), brandHitbox: box('.r-sidebar > a'), topbar: box('.r-topbar'), search: box('.r-top-search'),
            avatar: box('.r-top-status > .r-avatar'), viewer: box('.pdf-viewer'), parsing: box('.parsing'),
            brandPaths: document.querySelectorAll('.r-brand path').length,
            breadcrumbPaths: document.querySelectorAll('.r-material-breadcrumb-icon path').length,
            composition: getComputedStyle(document.querySelector('.r-scene-06')).willChange,
            titleSize: getComputedStyle(document.querySelector('.prep-materials .p-title')).fontSize,
            paperSize: getComputedStyle(document.querySelector('.pdf-paper p')).fontSize,
            navigation: [...document.querySelectorAll('.r-nav-item')].map(node => {
              const r = node.getBoundingClientRect()
              return { text: node.textContent.trim(), height: r.height, scroll: node.scrollWidth, width: r.width }
            }),
            toolbarOverflow: document.querySelector('.pdf-toolbar').scrollWidth > document.querySelector('.pdf-toolbar').clientWidth,
            paragraphs: [...document.querySelectorAll('.pdf-paper p')].map(node => ({ text: node.textContent, breaks: node.querySelectorAll('br').length })),
          }
        })
        const sameBox = (actual, expected) => actual.length === 4 && actual.every((value, i) => Math.abs(value - expected[i]) < 1)
        ensure(sameBox(layout.sidebar, [14,20,219,960]) && sameBox(layout.brandHitbox, [35,50,174,55]) && sameBox(layout.topbar, [254,21,1266,65])
          && sameBox(layout.search, [660,29,463,50]) && sameBox(layout.avatar, [1457,31.5,42,44])
          && sameBox(layout.viewer, [588,196,563,784]) && sameBox(layout.parsing, [1164,196,356,784]),
          '06侧栏、顶栏、头像或三栏几何不符')
        ensure(layout.brandPaths === 4 && layout.breadcrumbPaths === 2 && layout.composition === 'transform'
          && layout.titleSize === '50px' && layout.paperSize === '15px'
          && layout.navigation.length === 10 && layout.navigation.every(item => item.height === 56 && item.scroll <= item.width + 1)
          && !layout.toolbarOverflow && layout.paragraphs.every(item => item.breaks === 1), '06品牌、单行导航、工具栏或正文换行不符')
        await writeJson(path.join(values.output, 'records', '06-materials-layout.json'), layout)
        checks.push({ check: 'attachment-brand-breadcrumb-shell-three-columns-type-toolbar', scene: scene.id, layout, passed: true })
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
        const typography = await page.evaluate(() => {
          const style = selector => {
            const node = document.querySelector(selector), s = getComputedStyle(node), r = node.getBoundingClientRect()
            return { weight: s.fontWeight, size: s.fontSize, lineHeight: s.lineHeight, stroke: s.webkitTextStrokeWidth,
              transform: s.transform, leftPadding: s.paddingLeft, display: s.display, decoration: s.boxDecorationBreak,
              box: [r.x, r.y, r.width, r.height] }
          }
          const mark = document.querySelector('.parsing-content mark')
          return {
            root: style('.r-scene-06'), title: style('.prep-materials .p-title'), brand: style('.r-brand-word'),
            selected: style('.r-nav-item.r-selected'), library: style('.file-library h3'), parsing: style('.parsing h3'),
            paper: style('.pdf-page-wrap'), heading: style('.pdf-paper h2'), subheading: style('.pdf-paper h3'),
            body: style('.pdf-paper p'), list: style('.pdf-paper ul'), item: style('.pdf-paper li'),
            excerpt: style('.parsing-content mark'), action: style('.parsing-content section:nth-child(3) p'),
            excerptBreaks: mark.parentElement.querySelectorAll('br').length,
            excerptFragments: [...mark.getClientRects()].map(r => [r.x, r.y, r.width, r.height]),
          }
        })
        ensure(typography.root.weight === '300' && typography.title.weight === '650'
          && typography.title.transform === 'matrix(0.98, 0, 0, 1, 0, 0)' && sameBox(typography.title.box, [266,107,98,65])
          && typography.brand.weight === '500' && typography.selected.weight === '500'
          && [typography.library, typography.parsing].every(s => s.weight === '500' && s.stroke === '0.4px')
          && sameBox(typography.paper.box, [721,272,429,684]) && typography.heading.weight === '650'
          && typography.subheading.weight === '600' && typography.body.size === '15px' && typography.body.weight === '400'
          && typography.list.leftPadding === '21px' && typography.item.leftPadding === '10px'
          && typography.list.transform === 'matrix(0.98, 0, 0, 1, 0, 0)'
          && typography.excerpt.display === 'inline' && typography.excerpt.decoration === 'clone'
          && typography.excerpt.size === '16px' && typography.excerpt.weight === '400'
          && typography.action.lineHeight === '24.5px' && typography.excerptBreaks === 1
          && typography.excerptFragments.length === 2
          && typography.excerptFragments[1][2] < typography.excerptFragments[0][2]
          && typography.excerptFragments.every(r => Math.abs(r[0] - 1214) < 1),
          '06字重、纸面基线、原生列表标记间距或两行摘录高亮不符')
        checks.push({ check: 'attachment-typography-native-list-spacing-inline-highlight-fragments', scene: scene.id, typography, passed: true })
        const attachmentIcons = await page.evaluate(() => {
          const icon = selector => {
            const node = document.querySelector(selector), s = getComputedStyle(node), r = node.getBoundingClientRect()
            return { box: [r.x, r.y, r.width, r.height], strokeWidth: s.strokeWidth,
              paths: [...node.querySelectorAll('path')].map(p => p.getAttribute('d')) }
          }
          return { fit: icon('.pdf-toolbar [title="适应页面"] svg'), fileMore: icon('.file-item:first-of-type > svg') }
        })
        ensure(sameBox(attachmentIcons.fit.box, [1071,220,26,26])
          && JSON.stringify(attachmentIcons.fit.paths) === JSON.stringify([
            'M8 3H3v5M16 3h5v5M21 16v5h-5M8 21H3v-5', 'm4 4 5 5m11-5-5 5m5 11-5-5M4 20l5-5',
          ])
          && sameBox(attachmentIcons.fileMore.box, [537.5,416.34375,22,22])
          && attachmentIcons.fileMore.strokeWidth === '4px'
          && JSON.stringify(attachmentIcons.fileMore.paths) === JSON.stringify(['M12 4h.01','M12 12h.01','M12 20h.01']),
          '06适应页面四向箭头或文件行三点图标不符')
        checks.push({ check: 'attachment-fit-arrows-and-file-more-icon-geometry', scene: scene.id, attachmentIcons, passed: true })
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
        for (const [selector, target] of [['.parsing footer .r-primary', '04'], ['.r-sidebar > a', '02'], ['.r-top-search', '25']]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '06导航入口未进入目标场景')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '06返回加载失败')
          await ready()
        }
        await assertFrame(captured, '06采用事实、品牌和搜索返回后初态不符')
        checks.push({ check: 'attachment-facts-brand-command-navigation-return', scene: scene.id, passed: true })
      }
      if (scene.id === '07') {
        const layout = await page.evaluate(() => {
          const box = selector => {
            const r = document.querySelector(selector).getBoundingClientRect()
            return [r.x, r.y, r.width, r.height]
          }
          const canvas = document.querySelector('.r-task-horizon')
          const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data
          const colors = new Set()
          for (let i = 0; i < pixels.length; i += 4) colors.add(`${pixels[i]},${pixels[i+1]},${pixels[i+2]}`)
          return {
            sidebar: box('.r-sidebar'), brand: box('.r-sidebar > a'), search: box('.r-top-search'),
            avatar: box('.r-top-status > .r-avatar'), bottomAvatar: box('.r-sidebar-avatar .r-avatar'),
            stats: box('.task-stats'), pending: box('.pending-tasks'), scheduled: box('.scheduled-tasks'), preview: box('.schedule-preview'),
            statsColumns: [...document.querySelectorAll('.task-stats > .r-row')].map(node => node.getBoundingClientRect().x),
            brandPaths: document.querySelectorAll('.r-brand path').length, brandEllipse: document.querySelectorAll('.r-brand ellipse').length,
            canvas: [canvas.width, canvas.height], canvasColors: colors.size,
            composition: getComputedStyle(document.querySelector('.r-scene-07')).willChange,
            saved: (() => { const s = getComputedStyle(document.querySelector('.r-saved svg')); return [s.backgroundColor, s.padding] })(),
            navigation: [...document.querySelectorAll('.r-nav-item')].map(node => ({
              text: node.textContent.trim(), height: node.getBoundingClientRect().height, overflow: node.scrollWidth > node.clientWidth + 1,
            })),
            pendingTitles: [...document.querySelectorAll('.pending-task h3')].map(node => node.textContent),
            scheduledDates: [...document.querySelectorAll('.scheduled-row > span')].map(node => node.textContent),
            taskStatusPaths: [...document.querySelectorAll('.scheduled-row button > svg')].map(node => node.querySelectorAll('path').length),
            loads: [...document.querySelectorAll('.week-bars i')].map(node => node.style.height),
            previewRows: [...document.querySelectorAll('.preview-task')].map(node => node.getBoundingClientRect().height),
            cancel: box('.schedule-preview > .r-btn:last-child'),
            overflow: [...document.querySelectorAll('.pending-task p,.scheduled-row button p,.preview-note')].some(node => node.scrollWidth > node.clientWidth + 1),
          }
        })
        const sameBox = (actual, expected) => actual.length === 4 && actual.every((value, i) => Math.abs(value - expected[i]) < 1)
        ensure(sameBox(layout.sidebar, [0,22,256,995]) && sameBox(layout.brand, [35,53,185,62])
          && sameBox(layout.search, [656,31,454,50]) && sameBox(layout.avatar, [1451,35,49,50])
          && sameBox(layout.bottomAvatar, [38,943,47,48]) && sameBox(layout.stats, [276,255,1228,118])
          && sameBox(layout.pending, [276,394,437,592]) && sameBox(layout.scheduled, [726,394,409,592])
          && sameBox(layout.preview, [1148,394,356,592]), '07侧栏、品牌命中框、顶栏或三栏几何不符')
        ensure(JSON.stringify(layout.statsColumns) === '[305,651,1057]' && layout.brandPaths === 4 && layout.brandEllipse === 1
          && JSON.stringify(layout.canvas) === '[254,181]' && layout.canvasColors > 1000 && layout.composition === 'transform'
          && JSON.stringify(layout.saved) === '["rgba(0, 0, 0, 0)","0px"]'
          && layout.navigation.length === 10 && layout.navigation.every(item => !item.overflow) && !layout.overflow,
          '07统计分隔、地平线、品牌、保存标记或单行内容不符')
        ensure(layout.previewRows.every(height => Math.abs(height - 47.5) < 1)
          && layout.cancel[1] + layout.cancel[3] < layout.preview[1] + layout.preview[3] - 10,
          '07排期行高或底部按钮越界')
        await writeJson(path.join(values.output, 'records', '07-tasks-layout.json'), layout)
        checks.push({ check: 'tasks-brand-horizon-shell-stats-and-three-columns', scene: scene.id, layout, passed: true })
        ensure(JSON.stringify(layout.pendingTitles) === '["项目追问演练","系统设计取舍","缓存一致性补证"]'
          && JSON.stringify(layout.scheduledDates) === '["11.09","11.10","11.12"]'
          && JSON.stringify(layout.taskStatusPaths) === '[2,2,2]'
          && JSON.stringify(layout.loads) === '["72%","72%","68%","58%","22%","0%","0%"]'
          && await page.locator('.task-tabs button[aria-current="page"]').textContent() === '任务'
          && await page.locator('.preview-task').count() === 3
          && await page.locator('.pending-task[aria-pressed="false"]').count() === 3, '07任务、日期、状态、负载或选中初态不符')
        checks.push({ check: 'tasks-fixed-content-counts-status-loads-and-tab', scene: scene.id, passed: true })
        const first = page.locator('.pending-task').first()
        await first.click()
        ensure(await first.getAttribute('aria-pressed') === 'true', '07任务未响应选择')
        await first.press('Enter')
        ensure(await first.getAttribute('aria-pressed') === 'false', '07任务未响应Enter取消选择')
        await first.press('Space')
        ensure(await first.getAttribute('aria-pressed') === 'true', '07任务未响应空格选择')
        await page.locator('.schedule-preview > .r-btn').last().click()
        ensure(await page.locator('.pending-task[aria-pressed="false"]').count() === 3, '07取消安排未清除本次选择')
        for (const [selector, message] of [
          ['.p-actions .r-btn:first-child', '建议生成需要另行确认，不调用模型'],
          ['.p-actions .r-btn:last-child', '新建任务流程待确认'],
          ['.schedule-preview > .r-primary', '安排已保存到本次页面状态'],
        ]) {
          await page.locator(selector).click()
          ensure(await page.getByRole('status').textContent() === message, '07未确认业务或页面内保存边界提示不符')
        }
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '07任务选择、取消与边界操作后刷新未恢复初态')
        checks.push({ check: 'tasks-selection-keyboard-cancel-local-boundaries-refresh', scene: scene.id, passed: true })
        for (const [selector, target] of [
          ['.task-tabs button:first-child', '03'], ['.task-tabs button:nth-child(2)', '06'], ['.task-tabs button:last-child', '08'],
          ['.scheduled-row:nth-of-type(1) button', '04'], ['.scheduled-row:nth-of-type(2) button', '20'], ['.scheduled-row:nth-of-type(3) button', '08'],
          ['.schedule-preview button[title="编辑安排"]', '08'], ['.r-sidebar > a', '02'], ['.r-top-search', '25'],
        ]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({ state: 'visible' })
          ensure(new URL(page.url()).searchParams.get('scene') === target, '07导航未进入对应场景')
          ensure((await page.goto(entry, { waitUntil: 'networkidle' }))?.ok(), '07返回加载失败')
          await ready()
        }
        await assertFrame(captured, '07页签、来源、编辑、品牌与搜索导航返回后初态不符')
        checks.push({ check: 'tasks-tabs-source-edit-brand-command-navigation-return', scene: scene.id, passed: true })
      }
      if (scene.id === '08') {
        const layout = await page.evaluate(() => {
          const box = selector => {
            const r = document.querySelector(selector).getBoundingClientRect()
            return [r.x, r.y, r.width, r.height]
          }
          return {
            sidebar: box('.r-sidebar'), brand: box('.r-sidebar > a'), search: box('.r-top-search'),
            avatar: box('.r-top-status > .r-avatar'), bottomAvatar: box('.r-sidebar-avatar .r-avatar'),
            calendar: box('.calendar-panel'), event: box('.calendar-event'), eventCard: box('.calendar-event article'),
            reschedule: box('.reschedule'), reminder: box('.calendar-reminder'), adjust: box('.reschedule .adjust'),
            brandPaths: document.querySelectorAll('.r-brand path').length,
            dates: [...document.querySelectorAll('.calendar-days button > span')].map(node => ({
              day: node.textContent, box: (() => { const r = node.getBoundingClientRect(); return [r.x,r.y,r.width,r.height] })(),
            })),
            actions: [...document.querySelectorAll('.p-actions .r-btn')].map(node => node.getBoundingClientRect().width),
            navigation: [...document.querySelectorAll('.r-nav-item')].map(node => ({
              text: node.textContent.trim(), overflow: node.scrollWidth > node.clientWidth + 1,
            })),
            save: getComputedStyle(document.querySelector('.r-saved')).color,
            composition: getComputedStyle(document.querySelector('.r-canvas')).willChange,
            overflow: [...document.querySelectorAll('.reschedule > p,.calendar-event article p,.calendar-reminder p')]
              .some(node => node.scrollWidth > node.clientWidth + 1),
          }
        })
        const sameBox = (actual, expected) => actual.length === 4 && actual.every((value, i) => Math.abs(value - expected[i]) < 1)
        ensure(sameBox(layout.sidebar,[0,0,235,1020]) && sameBox(layout.brand,[35,36,177,55])
          && sameBox(layout.search,[829,28,328,51]) && sameBox(layout.avatar,[1455,27,51,53])
          && sameBox(layout.bottomAvatar,[40,920,62,64]) && sameBox(layout.calendar,[257,204,786,505])
          && sameBox(layout.event,[257,708,786,246]) && sameBox(layout.reschedule,[1062,204,448,530])
          && sameBox(layout.reminder,[1062,751,448,203]) && sameBox(layout.eventCard,[276,777.8,752,116]),
          '08侧栏、品牌命中框、顶部或日历／事件／改期／提醒几何不符')
        ensure(layout.brandPaths === 7 && layout.navigation.length === 10 && layout.navigation.every(item=>!item.overflow)
          && layout.composition === 'transform' && layout.save === 'rgb(205, 197, 188)' && !layout.overflow
          && JSON.stringify(layout.actions) === '[133,167]' && await page.locator('.r-top-search kbd').count() === 0
          && layout.adjust[1] > 643 && layout.adjust[1] + layout.adjust[3] < layout.reschedule[1] + layout.reschedule[3] - 15,
          '08单行导航、品牌、保存标记、按钮或内容边界不符')
        const firstRow = layout.dates.slice(0,7)
        ensure(layout.dates.length === 22 && firstRow.every((item,i)=>sameBox(item.box,[[282,356.8,47,47],[395,356.8,47,47],
          [514,356.8,47,47],[630,356.8,47,47],[744,356.8,47,47],[854,356.8,47,47],[964,356.8,47,47]][i]))
          && await page.getByRole('button',{name:'2026年11月12日',exact:true}).getAttribute('aria-pressed') === 'true'
          && await page.locator('.calendar-days .marked').count() === 2
          && await page.locator('.reschedule-task[aria-pressed="false"]').count() === 3,
          '08日期列距、固定选中日期、标记或关联任务初态不符')
        await writeJson(path.join(values.output,'records','08-schedule-layout.json'),layout)
        checks.push({check:'schedule-shell-calendar-date-columns-fixed-selection-event-preview-reminder-geometry',scene:scene.id,layout,passed:true})
        const writes = []
        const onRequest = request => {
          if (new URL(request.url()).pathname.startsWith('/api/') && !['GET','HEAD','OPTIONS'].includes(request.method())) writes.push(request.method())
        }
        page.on('request',onRequest)
        await page.getByRole('button',{name:'2026年11月18日',exact:true}).click()
        ensure(await page.locator('.calendar-event').textContent() === '11月18日 周三HR 面 · 待确认；时间及安排详情待确认'
          && await page.locator('.calendar-event article').count() === 0,'08其他日期借用了12日面试内容')
        await page.getByRole('button',{name:'2026年11月12日',exact:true}).click()
        const firstTask = page.locator('.reschedule-task').first()
        await firstTask.click()
        ensure(await firstTask.getAttribute('aria-pressed') === 'true','08关联任务点击未选择')
        await firstTask.press('Enter')
        ensure(await firstTask.getAttribute('aria-pressed') === 'false','08关联任务Enter未取消')
        await firstTask.press('Space')
        ensure(await firstTask.getAttribute('aria-pressed') === 'true','08关联任务空格未选择')
        await page.locator('.p-actions .r-primary').click()
        ensure(await page.getByRole('status').textContent() === '新建面试安排流程待确认' && writes.length === 0,
          '08新建边界提示或无业务写入不符')
        await page.reload({waitUntil:'networkidle'}); await ready()
        await assertFrame(captured,'08日期、任务和新建边界操作后刷新未恢复初态')
        checks.push({check:'schedule-date-selection-task-keyboard-new-entry-boundary-refresh',scene:scene.id,businessWrites:writes,passed:true})
        await page.getByTitle('下个月',{exact:true}).click()
        ensure(await page.locator('.calendar-title h2').textContent() === '2026年12月'
          && await page.locator('.calendar-days button').count() === 31
          && await page.locator('.calendar-days small').count() === 0
          && await page.locator('.calendar-event article').count() === 0,'08月历未真实切换或错误迁移参考数据')
        await page.getByTitle('下个月',{exact:true}).press('Enter')
        ensure(await page.locator('.calendar-title h2').textContent() === '2027年1月','08跨年月份错误')
        await page.getByTitle('上个月',{exact:true}).click()
        await page.getByTitle('上个月',{exact:true}).click()
        ensure(await page.locator('.calendar-title h2').textContent() === '2026年11月'
          && await page.getByRole('button',{name:'2026年11月12日',exact:true}).getAttribute('aria-pressed') === 'true','08返回参考月份未恢复日期')
        await page.locator('.p-actions .r-btn').first().click()
        ensure(await page.getByRole('button',{name:'2026年11月11日',exact:true}).getAttribute('aria-pressed') === 'true'
          && await page.locator('.calendar-event article').count() === 0,'08今天未使用固定展示日期')
        await page.reload({waitUntil:'networkidle'}); await ready()
        await assertFrame(captured,'08月份、跨年、今天操作后刷新未恢复初态')
        checks.push({check:'schedule-month-year-rollover-today-no-invented-events-refresh',scene:scene.id,passed:true})
        for(const [selector,target] of [['.calendar-event article .r-btn','03'],['.reschedule .adjust','07'],
          ['.r-sidebar > a','02'],['.r-top-search','25']]) {
          await page.locator(selector).click()
          await page.locator(`.r-canvas[data-scene="${target}"]`).waitFor({state:'visible'})
          ensure(new URL(page.url()).searchParams.get('scene') === target,'08导航未进入对应场景')
          ensure((await page.goto(entry,{waitUntil:'networkidle'}))?.ok(),'08返回加载失败')
          await ready()
        }
        await assertFrame(captured,'08准备、排期、品牌与搜索返回初态不符')
        ensure(writes.length === 0,'08固定场景向正式库写入数据')
        page.off('request',onRequest)
        checks.push({check:'schedule-preparation-adjust-brand-command-navigation-return',scene:scene.id,passed:true})
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
        const appearance = await page.evaluate(() => {
          const style = selector => {
            const node = document.querySelector(selector), s = getComputedStyle(node), b = node.getBoundingClientRect()
            return { weight: s.fontWeight, stroke: s.webkitTextStrokeWidth, size: s.fontSize, willChange: s.willChange,
              paddingBottom: s.paddingBottom, marginTop: s.marginTop, marginBottom: s.marginBottom,
              gap: s.gap, transform: s.transform, background: s.backgroundImage, box: [b.x, b.y, b.width, b.height] }
          }
          return Object.fromEntries(['.r-scene-21', '.r-sidebar', '.r-sidebar nav', '.r-nav-item.r-secondary',
            '.r-nav-separator', '.rv21 .rv-heading h1', '.rv21-original', '.rv21-original h2', '.rv21-answer',
            '.rv21-tabs button.selected', '.rv21-observation-copy', '.rv21-observation-copy strong',
            '.rv21-observation.observation-3', '.rv21-actions .r-btn', '.rv21-add'].map(selector => [selector, style(selector)]))
        })
        ensure(appearance['.r-scene-21'].weight === '300' && appearance['.r-scene-21'].willChange === 'transform'
          && appearance['.rv21 .rv-heading h1'].weight === '500' && appearance['.rv21 .rv-heading h1'].stroke === '0.6px'
          && appearance['.rv21-original h2'].weight === '500' && appearance['.rv21-original h2'].stroke === '0.2px'
          && appearance['.rv21-actions .r-btn'].weight === '400' && appearance['.rv21-add'].weight === '400',
        '21正文、标题描边或操作字重不符')
        ensure(appearance['.rv21-answer'].marginTop === '49px'
          && appearance['.rv21-tabs button.selected'].weight === '500' && appearance['.rv21-tabs button.selected'].paddingBottom === '8px'
          && appearance['.rv21-observation-copy'].transform === 'matrix(1, 0, 0, 1, 0, -1)'
          && appearance['.rv21-observation-copy strong'].weight === '300'
          && appearance['.r-nav-item.r-secondary'].size === '23px' && appearance['.r-nav-item.r-secondary'].gap === '18px'
          && appearance['.r-nav-separator'].marginTop === '24px' && appearance['.r-nav-separator'].marginBottom === '29px'
          && appearance['.r-sidebar nav'].box[0] === 22 && appearance['.r-sidebar nav'].box[1] === 139,
        '21正文、页签、观察或侧栏基线不符')
        ensure(appearance['.r-sidebar'].background === 'radial-gradient(at 20% 72%, rgba(95, 73, 53, 0.267), rgba(0, 0, 0, 0) 75%), linear-gradient(110deg, rgb(19, 16, 12), rgb(16, 14, 11))'
          && appearance['.rv21-original'].background === 'radial-gradient(at 15% 0px, rgba(91, 70, 53, 0.098), rgba(0, 0, 0, 0) 65%), linear-gradient(120deg, rgb(28, 24, 20), rgb(24, 20, 16) 80%)'
          && appearance['.rv21-observation.observation-3'].background === 'linear-gradient(125deg, rgba(33, 28, 22, 0.25), rgba(22, 19, 15, 0.133))',
        '21侧栏、原答或第三条观察背景不符')
        checks.push({ check: 'review-typography-baselines-navigation-and-panel-lighting', scene: scene.id, appearance, passed: true })
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
        const selectedObservationBackground = await page.locator('.rv21-observation.selected').evaluate(node => getComputedStyle(node).backgroundImage)
        await page.getByRole('button', { name: '选中观察 3', exact: true }).click()
        ensure(await page.locator('.observation-3').getAttribute('class').then(value => value.includes('selected'))
          && await page.locator('.observation-3').evaluate(node => getComputedStyle(node).backgroundImage) === selectedObservationBackground,
        '21观察数字未切换选择或选中色被默认底色覆盖')
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
        const typography = await page.evaluate(() => {
          const style = selector => {
            const node = document.querySelector(selector), s = getComputedStyle(node)
            return { fontWeight: s.fontWeight, color: s.color, gap: s.gap, willChange: s.willChange }
          }
          const navigation = [...document.querySelectorAll('.r-nav-item span')].map(node => {
            const range = document.createRange(); range.selectNodeContents(node)
            return { text: node.textContent, lines: range.getClientRects().length }
          })
          const oldList = document.querySelector('.rv22-old .rv22-answer-list')
          const marker = getComputedStyle(oldList.querySelector('li'), '::marker')
          const avatar = document.querySelector('.r-sidebar-avatar .r-avatar').getBoundingClientRect()
          return { navigation, nativeList: {
            oldMarginTop: getComputedStyle(oldList).marginTop,
            newMarginTop: getComputedStyle(document.querySelector('.rv22-new .rv22-answer-list')).marginTop,
            markerSize: marker.fontSize, markerFamily: marker.fontFamily,
          }, avatar: [avatar.x, avatar.y, avatar.width, avatar.height],
          panelPaint: Object.fromEntries(['.rv22-old', '.rv22-conditions', '.rv22-new', '.rv22-judgment']
            .map(selector => [selector, getComputedStyle(document.querySelector(selector)).backgroundImage])),
          styles: Object.fromEntries([
            '.r-scene-22', '.r-breadcrumb', '.r-nav-item', '.rv22 .rv-heading h1',
            '.rv22-old .rv22-panel-heading', '.rv22-old .rv22-answer-heading',
            '.rv22-conditions .rv22-panel-heading', '.rv22-judgment h2',
          ].map(selector => [selector, style(selector)])) }
        })
        ensure(typography.styles['.r-scene-22'].willChange === 'transform'
          && typography.styles['.r-breadcrumb'].color === 'rgb(187, 183, 175)'
          && typography.styles['.r-nav-item'].gap === '14px'
          && typography.styles['.rv22 .rv-heading h1'].fontWeight === '500'
          && ['.rv22-old .rv22-panel-heading', '.rv22-old .rv22-answer-heading',
            '.rv22-conditions .rv22-panel-heading', '.rv22-judgment h2'].every(selector => typography.styles[selector].fontWeight === '500')
          && typography.navigation.length === 10 && typography.navigation.every(item => item.lines === 1),
        '22文字合成、标题字重、面包屑或单行导航不符')
        ensure(typography.nativeList.oldMarginTop === '10px' && typography.nativeList.newMarginTop === '14px'
          && typography.nativeList.markerSize === '24px' && typography.nativeList.markerFamily.startsWith('Arial')
          && typography.avatar[0] === 40 && typography.avatar[2] === 57 && typography.avatar[3] === 61
          && ['.rv22-old', '.rv22-conditions', '.rv22-judgment'].every(selector => typography.panelPaint[selector].includes('rgb(29, 27, 23)'))
          && !typography.panelPaint['.rv22-new'].includes('rgb(29, 27, 23)'), '22原生编号、列表间距、头像或分区材质不符')
        checks.push({ check: 'compare-composited-type-heading-weights-breadcrumb-single-line-navigation', scene: scene.id, ...typography, passed: true })
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
        const detailType = await page.evaluate(() => Object.fromEntries([
          '.r-scene-22', '.rv22 .rv-heading h1', '.rv22-answer-intro', '.rv22-conditions dt',
          '.rv22-keywords button', '.rv22-judgment-options > button.selected', '.rv22-save',
        ].map(selector => {
          const s = getComputedStyle(document.querySelector(selector))
          return [selector, { weight: s.fontWeight, size: s.fontSize, stroke: s.webkitTextStrokeWidth,
            transform: s.transform, background: s.backgroundImage }]
        })))
        ensure(detailType['.r-scene-22'].weight === '300'
          && detailType['.rv22 .rv-heading h1'].size === '49px' && detailType['.rv22 .rv-heading h1'].stroke === '0.6px'
          && detailType['.rv22-answer-intro'].weight === '300' && detailType['.rv22-answer-intro'].size === '22px'
          && detailType['.rv22-answer-intro'].transform === 'matrix(1, 0, 0, 1, 0, 1)'
          && detailType['.rv22-conditions dt'].weight === '400' && detailType['.rv22-keywords button'].size === '20px'
          && ['.rv22-judgment-options > button.selected', '.rv22-save']
            .every(selector => detailType[selector].weight === '500' && detailType[selector].stroke === '0.3px')
          && detailType['.rv22-save'].background === 'linear-gradient(120deg, rgb(239, 191, 142), rgb(195, 137, 82) 75%, rgb(189, 130, 76))',
        '22正文字重、引导句基线、关键词字号或选中／保存按钮样式不符')
        checks.push({ check: 'compare-copy-keywords-selected-save-typography-gradient', scene: scene.id, detailType, passed: true })
        const detailGeometry = await page.evaluate(() => {
          const selectors = ['.rv22-old .rv22-answer-list', '.rv22-new .rv22-answer-list', '.rv22-keywords', '.rv22-basis', '.rv22-save']
          const boxes = Object.fromEntries(selectors.map(selector => {
            const b = document.querySelector(selector).getBoundingClientRect()
            return [selector, [b.x, b.y, b.width, b.height]]
          }))
          return { boxes, conditionMargin: getComputedStyle(document.querySelector('.rv22-conditions dl > div:nth-child(2)')).marginBottom,
            listTransform: getComputedStyle(document.querySelector('.rv22-old .rv22-answer-list')).transform }
        })
        ensure(Object.entries({
          '.rv22-old .rv22-answer-list': [277, 451.984375, 388, 137.625],
          '.rv22-new .rv22-answer-list': [1047, 456.984375, 427, 117.5625],
          '.rv22-keywords': [277, 693, 354, 46], '.rv22-basis': [895, 872, 434, 69], '.rv22-save': [1370, 872, 112, 59],
        }).every(([selector, box]) => box.every((n, i) => Math.abs(detailGeometry.boxes[selector][i] - n) < .02))
          && detailGeometry.conditionMargin === '16px' && detailGeometry.listTransform === 'matrix(1, 0, 0, 1, 0, -1)',
        '22新旧回答列表、关键词、条件间距或底部操作几何不符')
        checks.push({ check: 'compare-answer-list-baselines-keywords-conditions-footer-geometry', scene: scene.id, detailGeometry, passed: true })
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
        const recollectionStyles = await page.locator('.r-canvas').evaluate(canvas => {
          const style = selector => getComputedStyle(canvas.querySelector(selector))
          return {
            willChange: getComputedStyle(canvas).willChange,
            titleWeight: style('.rv23 h1').fontWeight,
            subtitleMargin: style('.rv23 .rv-subheading').marginTop,
            numberFont: style('.rv23-fragment-number').fontFamily,
            fragmentWeight: style('.rv23-fragment h3').fontWeight,
            suggestionWeight: style('.rv23-create-copy strong').fontWeight,
            suggestionMargin: style('.rv23-create-copy strong').marginBottom,
            selectedWeight: style('.rv23-confidence-options button.selected').fontWeight,
            sidebarSurface: style('.r-sidebar').backgroundImage,
            navigation: [...canvas.querySelectorAll('.r-nav-item')].map(node => {
              const range = document.createRange(); range.selectNodeContents(node.querySelector('span'))
              return { gap: getComputedStyle(node).gap, whiteSpace: getComputedStyle(node).whiteSpace, textLines: range.getClientRects().length }
            }),
          }
        })
        ensure(recollectionStyles.willChange === 'transform' && recollectionStyles.titleWeight === '500'
          && recollectionStyles.subtitleMargin === '5px' && recollectionStyles.numberFont === 'Arial, sans-serif'
          && recollectionStyles.fragmentWeight === '500' && recollectionStyles.suggestionWeight === '500'
          && recollectionStyles.suggestionMargin === '4px' && recollectionStyles.selectedWeight === '500'
          && recollectionStyles.sidebarSurface.includes('rgba(112, 77, 59, 0.46)')
          && recollectionStyles.navigation.length === 10
          && recollectionStyles.navigation.every(item => item.gap === '15px' && item.whiteSpace === 'nowrap' && item.textLines === 1),
          '23文字合成、字重、编号、说明间距、侧栏光照或十项单行导航不符')
        checks.push({ check: 'recollection-compositing-type-spacing-sidebar-surface-single-line-navigation', scene: scene.id,
          styles: recollectionStyles, passed: true })
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
        const style = await page.evaluate(() => {
          const read = selector => {
            const s = getComputedStyle(document.querySelector(selector))
            return { weight: s.fontWeight, size: s.fontSize, stroke: s.webkitTextStrokeWidth, gap: s.gap,
              background: s.backgroundImage, family: s.fontFamily, lineHeight: s.lineHeight, padding: s.padding }
          }
          return Object.fromEntries([
            '.rv24 h1', '.rv24 h2', '.rv24-suggestion h3', '.rv24-evidence-filters legend',
            '.rv24-evidence-table tbody th', '.rv24-note strong', '.r-nav-item', '.r-nav-item.r-secondary',
            '.rv24-suggestion-source', '.rv24-suggestion-text', '.rv24-suggestion-actions button',
            '.rv24-suggestion .priority', '.rv24-suggestion:nth-of-type(2) .priority',
            '.rv24-directions', '.rv24-evidence', '.rv24-suggestions',
          ].map(selector => [selector, read(selector)]))
        })
        ensure(style['.rv24 h1'].weight === '650' && style['.rv24 h1'].size === '54px'
          && ['.rv24 h2', '.rv24-suggestion h3', '.rv24-evidence-filters legend', '.rv24-evidence-table tbody th', '.rv24-note strong']
            .every(selector => style[selector].weight === '500' && style[selector].stroke === '0.15px')
          && style['.r-nav-item'].size === '23px' && style['.r-nav-item'].gap === '16px'
          && style['.r-nav-item.r-secondary'].size === '22px' && style['.r-nav-item.r-secondary'].gap === '16px'
          && ['.rv24-suggestion-source', '.rv24-suggestion-text'].every(selector =>
            style[selector].size === '15px' && style[selector].family === '"Microsoft YaHei", sans-serif')
          && style['.rv24-suggestion-text'].lineHeight === '22.5px'
          && style['.rv24-suggestion-actions button'].size === '16px' && style['.rv24-suggestion-actions button'].padding === '0px 19px'
          && style['.rv24-suggestion .priority'].background === 'linear-gradient(110deg, rgb(106, 75, 49), rgb(135, 83, 45))'
          && style['.rv24-suggestion:nth-of-type(2) .priority'].background === 'linear-gradient(110deg, rgb(88, 75, 55), rgb(102, 77, 49))'
          && ['.rv24-directions', '.rv24-evidence'].every(selector =>
            style[selector].background === 'linear-gradient(110deg, rgb(23, 21, 18), rgb(18, 17, 15))')
          && style['.rv24-suggestions'].background === 'linear-gradient(115deg, rgb(25, 22, 17), rgb(18, 17, 14))',
          '24标题层次、侧栏字距、建议说明／按钮或分区底色不符')
        checks.push({ check: 'growth-heading-weights-navigation-spacing-suggestion-copy-buttons-panel-surfaces',
          scene: scene.id, style, passed: true })
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
        const typography = await page.evaluate(() => {
          const selectors = ['.r-scene-18', '.follow-title h1', '.follow-title h1 > span', '.follow-original p',
            '.follow-original h2', '.follow-candidate h2', '.follow-candidate h3', '.candidate-actions .ts-button', '.follow-end.ts-button']
          const styles = Object.fromEntries(selectors.map(selector => {
            const s = getComputedStyle(document.querySelector(selector))
            return [selector, { size: s.fontSize, weight: s.fontWeight, stroke: s.webkitTextStrokeWidth, transform: s.transform, willChange: s.willChange }]
          }))
          const node = document.querySelector('.follow-candidate h3'), walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), rows = new Map()
          let text
          while ((text = walker.nextNode())) for (let i = 0; i < text.length; i++) {
            const range = document.createRange(); range.setStart(text, i); range.setEnd(text, i + 1)
            const y = range.getBoundingClientRect().y
            rows.set(y, (rows.get(y) ?? '') + text.textContent[i])
          }
          return { styles, questionLines: [...rows.values()] }
        })
        const styles18 = typography.styles
        ensure(styles18['.r-scene-18'].weight === '300' && styles18['.r-scene-18'].willChange === 'transform'
          && styles18['.follow-title h1'].size === '32px' && styles18['.follow-title h1 > span'].weight === '400'
          && styles18['.follow-original p'].size === '23.5px' && styles18['.follow-original p'].weight === '300'
          && styles18['.follow-original p'].transform === 'matrix(1, 0, 0, 1, 0, 1)'
          && ['.follow-title h1', '.follow-original h2', '.follow-candidate h2', '.follow-candidate h3', '.candidate-actions .ts-button', '.follow-end.ts-button']
            .every(selector => styles18[selector].weight === '500' && styles18[selector].stroke === '0.3px')
          && JSON.stringify(typography.questionLines) === JSON.stringify(['如果补偿请求也超时，你如何判断', '是否应该继续重试？']), '18字重、标题字号、正文基线或追问两行不符')
        checks.push({ check: 'follow-up-typography-body-baseline-question-lines', scene: scene.id, typography, passed: true })
        const layout = await page.evaluate(() => {
          const selectors = ['.follow-original', '.follow-candidate', '.follow-candidate h3', '.candidate-evidence', '.candidate-actions']
          const boxes = Object.fromEntries(selectors.map(selector => {
            const b = document.querySelector(selector).getBoundingClientRect()
            return [selector, [b.x, b.y, b.width, b.height]]
          }))
          const evidence = getComputedStyle(document.querySelector('.candidate-evidence'))
          const action = getComputedStyle(document.querySelector('.candidate-actions'))
          const primary = getComputedStyle(document.querySelector('.candidate-actions .primary'))
          return { boxes, evidencePadding: evidence.paddingLeft, actionsMargin: action.marginTop,
            primaryFlex: primary.flexGrow, primaryBackground: primary.backgroundImage }
        })
        ensure(Object.entries({
          '.follow-original': [253, 195, 695, 713], '.follow-candidate': [961, 195, 552, 713],
          '.follow-candidate h3': [998, 300.796875, 485, 94], '.candidate-evidence': [990, 425.796875, 496, 83],
          '.candidate-actions': [990, 620.390625, 496, 71],
        }).every(([selector, box]) => box.every((n, i) => Math.abs(layout.boxes[selector][i] - n) < .02))
          && layout.evidencePadding === '24px' && layout.actionsMargin === '47px' && layout.primaryFlex === '1.05'
          && layout.primaryBackground === 'linear-gradient(120deg, rgb(239, 191, 142), rgb(195, 137, 82) 75%, rgb(189, 130, 76))', '18两栏、引用条、追问位置或按钮几何／渐变不符')
        checks.push({ check: 'follow-up-panels-evidence-actions-geometry-gradient', scene: scene.id, layout, passed: true })
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
        const listPresentation = await page.evaluate(() => {
          const measure = selector => {
            const node = document.querySelector(selector), box = node.getBoundingClientRect(), style = getComputedStyle(node)
            return { box: [box.x, box.y, box.width, box.height], weight: style.fontWeight, background: style.backgroundImage,
              fontSize: style.fontSize, color: style.color, transform: style.transform, stroke: style.webkitTextStrokeWidth,
              paddingInline: style.paddingInline, gap: style.gap, border: style.borderColor, shadow: style.boxShadow }
          }
          return Object.fromEntries(['.r-scene-10', '.r-nav-item.r-selected', '.kl-views button.r-active', '.kl-folders',
            '.kl-table', '.kl-detail', '.kl-table tbody tr:first-child .r-pill', '.kl-table .r-pill.amber',
            '.kl-excerpt', '.kl-tags', '.kl-tags .r-pill', '.kl-detail > .r-primary'].map(selector => [selector, measure(selector)]))
        })
        ensure(listPresentation['.r-scene-10'].weight === '300'
          && listPresentation['.r-nav-item.r-selected'].background === 'linear-gradient(120deg, rgba(162, 101, 49, 0.533), rgba(80, 50, 31, 0.65))'
          && listPresentation['.kl-views button.r-active'].background === 'linear-gradient(130deg, rgba(103, 65, 32, 0.66), rgba(62, 39, 24, 0.74))'
          && listPresentation['.r-nav-item.r-selected'].border.startsWith('rgba(174, 123, 74,')
          && ['247, 196, 146', '228, 156, 85', '7px', '15px'].every(value => listPresentation['.r-nav-item.r-selected'].shadow.includes(value))
          && listPresentation['.kl-views button.r-active'].shadow.includes('156, 105, 59'), '10字重或两处选中底色、边框与阴影不符')
        for (const [selector, expected] of [['.kl-folders', [232, 251, 248, 676]], ['.kl-table', [491, 251, 641, 676]], ['.kl-detail', [1144, 251, 373, 685]]]) {
          ensure(listPresentation[selector].box.every((value, index) => Math.abs(value - expected[index]) < .1), '10三栏外框发生漂移：' + selector)
        }
        ensure(listPresentation['.kl-table tbody tr:first-child .r-pill'].transform === 'matrix(1, 0, 0, 1, 0, -4)'
          && listPresentation['.kl-table tbody tr:first-child .r-pill'].box[1] >= 406
          && listPresentation['.kl-table tbody tr:first-child .r-pill'].box[1] <= 408
          && listPresentation['.kl-table .r-pill.amber'].fontSize === '18px'
          && listPresentation['.kl-table .r-pill.amber'].paddingInline === '10px', '10表格主题标签尺寸或基线不符')
        checks.push({ check: 'knowledge-list-selection-surfaces-column-geometry-topic-baseline', scene: scene.id, listPresentation, passed: true })
        const detailCopy = await page.locator('.kl-detail').evaluate(detail => {
          const excerpt = detail.querySelector('.kl-excerpt'), range = document.createRange(); range.selectNodeContents(excerpt)
          return { title: detail.querySelector('h2').textContent, excerpt: excerpt.textContent, lines: range.getClientRects().length,
            tags: [...detail.querySelectorAll('.kl-tags .r-pill')].map(node => node.textContent),
            tagBoxes: [...detail.querySelectorAll('.kl-tags .r-pill')].map(node => node.getBoundingClientRect().toJSON()),
            actions: [...detail.querySelectorAll(':scope > .r-btn')].map(node => node.textContent) }
        })
        ensure(detailCopy.title === '缓存更新失败时怎样定位'
          && detailCopy.excerpt === '在分布式缓存场景中，更新失败往往不是单点问题，而是涉及多层原因。可以从以下几个方向进行排查：...'
          && detailCopy.lines === 3 && JSON.stringify(detailCopy.tags) === JSON.stringify(['缓存', '分布式', '一致性'])
          && JSON.stringify(detailCopy.actions) === JSON.stringify(['打开笔记', '创建回忆卡']), '10详情原文、三行摘要、概念或操作文案不符')
        ensure(listPresentation['.kl-excerpt'].color === 'rgb(214, 207, 198)' && listPresentation['.kl-excerpt'].weight === '300'
          && listPresentation['.kl-tags'].gap === '18px' && listPresentation['.kl-tags .r-pill'].paddingInline === '15px'
          && listPresentation['.kl-detail > .r-primary'].weight === '500'
          && listPresentation['.kl-detail > .r-primary'].stroke === '0.3px', '10详情摘要、标签间距或操作字重不符')
        const [tagLeft, tagTop, tagWidth, tagHeight] = listPresentation['.kl-tags'].box
        ensure(detailCopy.tagBoxes.every((box, index, boxes) => Math.abs(box.y - tagTop) < .1
          && box.x >= tagLeft && box.right <= tagLeft + tagWidth && box.bottom <= tagTop + tagHeight
          && (!index || Math.abs(box.x - boxes[index - 1].right - 18) < .1)), '10三个概念标签换行、重叠或越界')
        checks.push({ check: 'knowledge-list-detail-copy-three-lines-tags-action-style', scene: scene.id, detailCopy, passed: true })
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
        const detailTypography = await page.evaluate(() => {
          const style = selector => {
            const node = document.querySelector(selector), s = getComputedStyle(node), b = node.getBoundingClientRect()
            return { weight: s.fontWeight, size: s.fontSize, stroke: s.webkitTextStrokeWidth, line: s.lineHeight,
              box: [b.x, b.y, b.width, b.height] }
          }
          return {
            root: style('.r-scene-12'), title: style('.kd-heading h1'), section: style('.kd-left h3'),
            definition: style('.kd-definition p'), note: style('.kd-main-note p'), open: style('.kd-main-note .r-pill'),
            path: style('.kd-path .r-pill'), action: style('.kd-actions .r-primary'), date: style('.kd-recent button span:first-child'),
            evidence: style('.kd-evidence-row h3'), selected: style('.kd-evidence-tabs button.selected'),
            rows: [...document.querySelectorAll('.kd-evidence-row')].map(node => {
              const b = node.getBoundingClientRect(), s = getComputedStyle(node)
              return { y: b.y, height: b.height, paddingBottom: s.paddingBottom, border: s.borderBottomColor }
            }),
            footer: style('.kd-evidence footer'),
          }
        })
        ensure(detailTypography.root.weight === '300' && detailTypography.title.weight === '500'
          && detailTypography.title.stroke === '0.6px' && detailTypography.title.box[0] === 372
          && detailTypography.section.weight === '500' && detailTypography.section.stroke === '0.3px'
          && detailTypography.path.weight === '500' && detailTypography.path.stroke === '0.15px'
          && detailTypography.action.weight === '400' && detailTypography.selected.weight === '500'
          && detailTypography.evidence.weight === '400' && detailTypography.evidence.stroke === '0.05px', '12标题、正文或操作字重不符')
        ensure(JSON.stringify(detailTypography.definition.box) === '[270,277,662,150]'
          && JSON.stringify(detailTypography.note.box) === '[270,514,648,66]' && detailTypography.note.line === '33px'
          && JSON.stringify(detailTypography.open.box) === '[763,552,153,35]' && detailTypography.open.size === '17px'
          && detailTypography.date.weight === '400' && detailTypography.date.box[1] === 949, '12正文、笔记入口或日期基线不符')
        ensure(JSON.stringify(detailTypography.rows.map(row => [row.y, row.height, row.paddingBottom]))
          === '[[359,151,"24px"],[510,149,"22px"],[659,149,"22px"]]'
          && detailTypography.rows[2].border === 'rgba(0, 0, 0, 0)'
          && JSON.stringify(detailTypography.footer.box) === '[988,841,496,48]', '12证据累计行高或底部分隔线不符')
        checks.push({ check: 'detail-typography-copy-baselines-note-link-evidence-row-rhythm', scene: scene.id, detailTypography, passed: true })
        const scrollbarPixels = [...await sharp(captured).toColourspace('srgb')
          .extract({ left: 927, top: 300, width: 5, height: 1 }).removeAlpha().raw().toBuffer()]
        ensure(scrollbarPixels.length === 15 && scrollbarPixels.every((value, index) =>
          index % 3 === 0 ? value > 55 && value < 80 : index % 3 === 1 ? value > 48 && value < 70 : value > 44 && value < 65), '12原生滚动条未在实际截图中绘出')
        await definitionScroll.focus()
        await definitionScroll.press('End')
        await page.waitForFunction(() => document.querySelector('.kd-definition p').scrollTop > 0)
        await definitionScroll.press('Home')
        await page.waitForFunction(() => document.querySelector('.kd-definition p').scrollTop === 0)
        await definitionScroll.evaluate(node => node.blur())
        checks.push({ check: 'detail-native-scrollbar-visible-pixels-and-keyboard', scene: scene.id, scrollbarPixels, passed: true })
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
        const boardDescription = await inspector.locator('textarea').evaluate(node => {
          const b = node.getBoundingClientRect(), s = getComputedStyle(node)
          return { value: node.value, box: [b.x, b.y, b.width, b.height], padding: s.padding,
            clientHeight: node.clientHeight, scrollHeight: node.scrollHeight, clientWidth: node.clientWidth }
        })
        ensure(boardDescription.value === '画布线不会自动成为\n正式知识关系'
          && JSON.stringify(boardDescription.box) === '[1277,494,216,73]' && boardDescription.padding === '9px 14px 8px'
          && boardDescription.clientHeight === 71 && boardDescription.scrollHeight === 71 && boardDescription.clientWidth === 214, '13说明原文、外框或初态溢出不符')
        checks.push({ check: 'whiteboard-description-two-lines-native-input-no-overflow', scene: scene.id, boardDescription, passed: true })
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
        const typography = await page.evaluate(() => {
          const get = selector => {
            const node = document.querySelector(selector), style = getComputedStyle(node)
            return { weight: style.fontWeight, size: style.fontSize, stroke: style.webkitTextStrokeWidth, top: style.top,
              left: style.left, margin: style.marginTop, lineHeight: style.lineHeight }
          }
          return Object.fromEntries(['.r-scene-11', '.kn-heading h1', '.kn-heading p', '.kn-actions button', '.kn-folders > button',
            '.kn-notes > button strong', '.kn-notes > button p', '.kn-prose h2', '.kn-prose p', '.kn-prose ul',
            '.kn-relations h3', '.kn-tags', '.kn-tags button', '.kn-backlink + .kn-backlink'].map(selector => [selector, get(selector)]))
        })
        ensure(typography['.r-scene-11'].weight === '300'
          && typography['.kn-heading h1'].weight === '500' && typography['.kn-heading h1'].stroke === '0.6px'
          && typography['.kn-heading h1'].top === '2px'
          && typography['.kn-heading p'].weight === '400' && typography['.kn-heading p'].margin === '13px'
          && typography['.kn-actions button'].weight === '400' && typography['.kn-folders > button'].top === '-3px'
          && typography['.kn-notes > button strong'].weight === '400' && typography['.kn-notes > button p'].weight === '400'
          && typography['.kn-prose h2'].weight === '500' && typography['.kn-prose h2'].stroke === '0.3px'
          && typography['.kn-prose h2'].left === '2px' && typography['.kn-prose p'].left === '2px'
          && typography['.kn-prose p'].size === '19.4px' && typography['.kn-prose p'].lineHeight === '32px'
          && typography['.kn-prose ul'].top === '-6px' && typography['.kn-prose ul'].lineHeight === '43px'
          && typography['.kn-relations h3'].weight === '500' && typography['.kn-relations h3'].stroke === '0.15px'
          && typography['.kn-tags'].margin === '22px' && typography['.kn-tags button'].weight === '500'
          && typography['.kn-tags button'].stroke === '0.1px' && typography['.kn-backlink + .kn-backlink'].margin === '15px',
          '11字重、标题／正文／清单基线、概念标签或反向链接间距不符')
        checks.push({ check: 'note-typography-title-body-list-baselines-tags-backlink-spacing', scene: scene.id, typography, passed: true })
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
        const commandSurface = await page.evaluate(() => {
          const get = (selector, pseudo) => {
            const s = getComputedStyle(document.querySelector(selector), pseudo)
            return { size: s.fontSize, top: s.top, left: s.left, gap: s.gap, height: s.height, width: s.width,
              padding: s.padding, background: s.backgroundImage, border: s.borderBottomWidth, pointerEvents: s.pointerEvents }
          }
          return { header: get('.r-scene-25', '::before'), navigation: get('.r-sidebar nav'), primary: get('.r-nav-item'),
            secondary: get('.r-nav-item.r-secondary'), status: get('.r-top-status'), modal: get('.sp-command-modal'),
            intro: get('.sp-task-intro'), action: get('.sp-task-intro .r-btn'),
            left: get('.sp-task-table', '::before'), right: get('.sp-task-table', '::after'),
            button: (() => { const b = document.querySelector('.sp-task-intro .r-btn').getBoundingClientRect(); return [b.x, b.y, b.width, b.height] })() }
        })
        ensure(commandSurface.header.height === '94px' && commandSurface.header.left === '212px'
          && commandSurface.header.border === '1px' && commandSurface.header.pointerEvents === 'none'
          && commandSurface.navigation.top === '153px' && commandSurface.primary.size === '23px'
          && commandSurface.secondary.size === '22px' && commandSurface.status.gap === '26px'
          && commandSurface.modal.background === 'linear-gradient(110deg, rgba(28, 25, 22, 0.96), rgba(25, 22, 21, 0.97))'
          && commandSurface.action.size === '20px' && commandSurface.action.padding === '8px 16px'
          && commandSurface.action.gap === '8px' && JSON.stringify(commandSurface.button) === JSON.stringify([1289,285.5,146,49])
          && commandSurface.left.left === '-1px' && commandSurface.left.top === '23px' && commandSurface.left.width === '181px'
          && commandSurface.right.left === '179px' && commandSurface.right.top === '-1px'
          && commandSurface.left.pointerEvents === 'none' && commandSurface.right.pointerEvents === 'none',
          '25顶栏、导航／保存区、面板材质、任务按钮或左右上沿不符')
        checks.push({ check: 'command-header-navigation-status-modal-surface-task-action-asymmetric-background',
          scene: scene.id, commandSurface, passed: true })
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
      if (scene.id === '26') {
        const layout = await page.evaluate(() => {
          const box = selector => {
            const node = document.querySelector(selector), rect = node.getBoundingClientRect()
            return [rect.x, rect.y, rect.width, rect.height]
          }
          const canvas = document.querySelector('.recent-horizon'), pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data
          const colors = new Set()
          for (let index = 0; index < pixels.length; index += 64) if (pixels[index + 3]) colors.add(`${pixels[index]},${pixels[index + 1]},${pixels[index + 2]}`)
          return {
            filters: box('.recent-filters'), table: box('.recent-table'), detail: box('.recent-detail'),
            sidebar: box('.r-sidebar'), search: box('.r-top-search'), state: box('.recent-state-box'),
            rows: [...document.querySelectorAll('.recent-object-name > span:last-child')].map(node => node.textContent.trim()),
            counts: [...document.querySelectorAll('.recent-count')].map(node => node.textContent.trim()),
            navigation: [...document.querySelectorAll('.r-sidebar .r-nav-item')].map(node => ({ text: node.textContent.trim(), height: node.getBoundingClientRect().height,
              labelHeight: node.querySelector('span').getBoundingClientRect().height })),
            selected: document.querySelector('.r-nav-item.r-selected')?.textContent.trim(),
            bookmark: document.querySelector('.r-saved path')?.getAttribute('d'), brandPaths: document.querySelectorAll('.r-brand path').length,
            canvas: [canvas.width, canvas.height], canvasColors: colors.size,
            horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
          }
        })
        ensure(JSON.stringify(layout.filters) === JSON.stringify([239,287,209,601])
          && JSON.stringify(layout.table) === JSON.stringify([465,287,721,613])
          && JSON.stringify(layout.detail) === JSON.stringify([1202,287,313,613])
          && JSON.stringify(layout.sidebar) === JSON.stringify([21,130,201,867])
          && JSON.stringify(layout.search) === JSON.stringify([662,31,441,58])
          && JSON.stringify(layout.state) === JSON.stringify([1225,598,267,64]), '26面板、侧栏、搜索或状态位置不符')
        ensure(JSON.stringify(layout.rows) === JSON.stringify(['缓存一致性','缓存删除失败以后','字节跳动 · 技术一面','营销交易平台-项目复盘.pdf','系统设计取舍'])
          && layout.counts.join(',') === '18,6,4,3,3,2' && layout.selected === '最近访问'
          && layout.navigation.length === 10 && layout.navigation.every(item => item.labelHeight < item.height)
          && layout.bookmark === 'M5 2h14v20l-7-5-7 5Z' && layout.brandPaths === 4
          && JSON.stringify(layout.canvas) === '[1312,1024]' && layout.canvasColors > 1000 && !layout.horizontalOverflow, '26行内容、分类计数、单行导航、图标或画布不符')
        ensure(await page.locator('.recent-accessibility input').isChecked()
          && await page.locator('.recent-table-row.selected .recent-object-name').innerText() === '缓存一致性'
          && await page.locator('.recent-detail h2').innerText() === '缓存一致性', '26初始选择或可访问开关不符')
        checks.push({ check: 'recent-five-rows-counts-single-line-shell-fixed-canvas-geometry', scene: scene.id, layout, passed: true })
        const recentType = await page.evaluate(() => Object.fromEntries([
          '.r-scene-26', '.recent-heading h1', '.recent-heading p', '.recent-table-row .utility-tag', '.recent-open',
        ].map(selector => {
          const s = getComputedStyle(document.querySelector(selector))
          return [selector, { weight: s.fontWeight, size: s.fontSize, stroke: s.webkitTextStrokeWidth,
            transform: s.transform, padding: s.paddingLeft }]
        })))
        ensure(recentType['.r-scene-26'].weight === '300'
          && recentType['.recent-heading h1'].weight === '500' && recentType['.recent-heading h1'].size === '54px'
          && recentType['.recent-heading h1'].stroke === '0.6px'
          && recentType['.recent-heading p'].transform === 'matrix(1, 0, 0, 1, 0, 4)'
          && recentType['.recent-table-row .utility-tag'].weight === '500'
          && recentType['.recent-table-row .utility-tag'].padding === '13px'
          && recentType['.recent-open'].weight === '500' && recentType['.recent-open'].stroke === '0.3px'
          && recentType['.recent-open'].transform === 'matrix(1, 0, 0, 1, 0, 2)',
        '26正文字重、标题描边、说明基线、标签内距或打开按钮样式不符')
        checks.push({ check: 'recent-copy-heading-subtitle-tags-open-typography-baselines', scene: scene.id, recentType, passed: true })
        const recentSurface = await page.evaluate(() => {
          const canvas = document.querySelector('.recent-horizon'), detail = document.querySelector('.recent-detail')
          const open = document.querySelector('.recent-open').getBoundingClientRect()
          const tag = document.querySelector('.recent-table-row .utility-tag').getBoundingClientRect()
          return { mask: getComputedStyle(canvas).maskImage, detailColor: getComputedStyle(detail).backgroundColor,
            columns: getComputedStyle(document.querySelector('.recent-table-row')).gridTemplateColumns,
            open: [open.x, open.y, open.width, open.height], tag: [tag.x, tag.y, tag.width, tag.height] }
        })
        ensure(recentSurface.mask === 'linear-gradient(90deg, rgba(0, 0, 0, 0), rgb(0, 0, 0) 36%)'
          && recentSurface.detailColor === 'rgb(21, 18, 15)'
          && recentSurface.columns === '260px 118px 160px 109px 19px'
          && JSON.stringify(recentSurface.open) === '[1225,758,265,57]'
          && recentSurface.tag.every((v, i) => Math.abs(v - [748,379.59375,64,42.296875][i]) < .02),
        '26地平线渐隐、详情底色、列宽、标签或打开按钮几何不符')
        checks.push({ check: 'recent-horizon-mask-detail-surface-tag-and-open-geometry', scene: scene.id, recentSurface, passed: true })
        const writes = []
        const onWrite = request => {
          if (new URL(request.url()).pathname.startsWith('/api/') && !['GET','HEAD','OPTIONS'].includes(request.method())) writes.push(request.method())
        }
        page.on('request', onWrite)
        const categories = page.locator('.recent-filters')
        await categories.locator('.recent-category').filter({ hasText: '知识' }).click()
        ensure(await page.locator('.recent-table-row').count() === 2, '26知识分类未筛出两项')
        await page.locator('.recent-table-row').first().focus()
        await page.keyboard.press('ArrowDown')
        ensure(await page.locator('.recent-detail h2').innerText() === '系统设计取舍', '26方向键未更新预览')
        await page.keyboard.press('Home')
        ensure(await page.locator('.recent-detail h2').innerText() === '缓存一致性', '26Home未恢复首项')
        await page.keyboard.press('End')
        ensure(await page.locator('.recent-detail h2').innerText() === '系统设计取舍', '26End未选择末项')
        await categories.locator('.recent-category').first().click()
        const typeButton = page.locator('.recent-type-button')
        await typeButton.click()
        await page.getByRole('menuitemradio', { name: '资料', exact: true }).focus()
        await page.keyboard.press('Escape')
        ensure(!await page.getByRole('menu').count() && await typeButton.evaluate(node => node === document.activeElement), '26类型菜单Esc未关闭／归还焦点')
        await typeButton.click()
        await page.getByRole('menuitemradio', { name: '资料', exact: true }).click()
        ensure(await page.locator('.recent-table-row').count() === 1 && await page.locator('.recent-detail h2').innerText() === '营销交易平台-项目复盘.pdf', '26资料类型或预览不符')
        await categories.locator('.recent-category').filter({ hasText: '知识' }).click()
        ensure(!await page.locator('.recent-table-row').count() && !await page.locator('.recent-detail').count()
          && await page.locator('.utility-empty').innerText() === '当前分类没有可显示的访问记录', '26组合筛选空态未清除预览')
        await categories.locator('.recent-category').first().click()
        await page.locator('.recent-open').click()
        await page.locator('.r-canvas[data-scene="06"]').waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await typeButton.click()
        await page.getByRole('menuitemradio', { name: '独立复练', exact: true }).click()
        await page.locator('.recent-open').click()
        await page.locator('.r-canvas[data-scene="21"]').waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await page.locator('.recent-accessibility input').uncheck()
        ensure(await page.locator('.recent-table-row').count() === 5, '26现有可访问样例被错误过滤')
        await page.locator('.recent-accessibility input').check()
        await page.getByRole('button', { name: '清除历史', exact: true }).click()
        ensure(!await page.locator('.recent-table-row').count() && !await page.locator('.recent-detail').count()
          && await page.locator('.utility-empty').innerText() === '暂无最近访问记录'
          && (await page.locator('.recent-count').allTextContents()).every(value => value === '0'), '26页面内清除边界不符')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '26分类／类型／选择／清除后刷新未恢复原始画面')
        await page.locator('.r-top-search').click()
        await page.getByRole('dialog', { name: '全局命令面板' }).waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await page.getByRole('link', { name: '问阶今日', exact: true }).click()
        await page.locator('.r-canvas[data-scene="02"]').waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '26导航返回未恢复固定种子画布及选择初态')
        page.off('request', onWrite)
        ensure(!writes.length, '26固定参考态出现意外业务写入')
        checks.push({ check: 'recent-category-type-keyboard-preview-clear-boundary-navigation-refresh', scene: scene.id, passed: true,
          businessWrites: writes, limitations: '固定条目均可访问，开关未验证真实权限差异；清除仅当前页面内存，不代替正式历史删除；原行星素材与字体未获得' })
      }
      if (scene.id === '27') {
        const layout = await page.evaluate(() => {
          const box = selector => { const rect = document.querySelector(selector).getBoundingClientRect(); return [rect.x, rect.y, rect.width, rect.height] }
          const singleLine = selector => [...document.querySelectorAll(selector)].every(node => {
            const range = document.createRange(); range.selectNodeContents(node)
            return new Set([...range.getClientRects()].filter(rect => rect.width > 0).map(rect => Math.round(rect.y))).size === 1
          })
          return {
            sidebar: box('.r-sidebar'), search: box('.r-top-search'), queue: box('.task-queue'),
            detail: box('.task-detail'), authorization: box('.task-authorization'), title: box('.task-heading h1'),
            navigation: [...document.querySelectorAll('.r-nav-item')].map(node => node.textContent.trim()),
            selected: [...document.querySelectorAll('.r-nav-item.r-selected')].map(node => node.textContent.trim()),
            steps: [...document.querySelectorAll('.task-step > span:last-child')].map(node => node.textContent),
            singleLine: singleLine('.r-nav-item > span') && singleLine('.task-step > span:last-child'),
            taskNames: [...document.querySelectorAll('.task-item-name')].map(node => node.textContent),
            statuses: [...document.querySelectorAll('.task-queue-item > .task-status')].map(node => node.textContent),
            sources: [...document.querySelectorAll('.task-input-list > div')].map(node => node.textContent),
            times: [...document.querySelectorAll('.task-item-time')].map(node => node.textContent),
            brandDisc: document.querySelector('.r-brand circle')?.getAttribute('fill'),
            brandHittable: (() => { const brand = document.querySelector('.r-sidebar > a'), rect = brand.getBoundingClientRect(); return rect.width > 0 && rect.height > 0 && brand.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)) })(),
            topAvatar: getComputedStyle(document.querySelector('.r-top-status > .r-avatar')).backgroundImage,
            bottomAvatar: getComputedStyle(document.querySelector('.r-sidebar-avatar .r-avatar')).backgroundImage,
          }
        })
        ensure(JSON.stringify(layout.sidebar) === '[0,0,216,1024]' && JSON.stringify(layout.search) === '[713,27,449,55]'
          && JSON.stringify(layout.queue) === '[235,236,334,709]' && JSON.stringify(layout.detail) === '[587,236,516,709]'
          && JSON.stringify(layout.authorization) === '[1121,236,386,709]' && layout.title[0] === 252 && layout.title[1] === 114
          && layout.title[2] === 210 && layout.singleLine, '27外壳／三栏／标题几何或导航／阶段单行不符')
        ensure(layout.navigation.join(',') === '今日,准备,知识,训练,AI 任务,复盘,成长,题库,回忆卡,最近访问,AI 任务'
          && layout.selected.join(',') === 'AI 任务' && layout.steps.join(',') === '任务接收,校验引用,发送中'
          && layout.taskNames.join(',') === '知识整理,出题候选,来源问答,关系候选'
          && layout.statuses.join(',') === '处理中,成功,失败,费用待核对'
          && layout.sources.join(',') === '知识v4,笔记v3' && layout.times.join(',') === '今天 20:18,今天 19:47,今天 19:32,'
          && layout.brandDisc === '#27231f' && layout.brandHittable && layout.topAvatar.includes('61, 125, 184')
          && layout.bottomAvatar.includes('80, 91, 131'), '27固定文案／版本／顺序／重复导航或品牌／头像不符')
        checks.push({ check: 'ai-authorization-three-panels-single-line-stages-shell-and-initial-data', scene: scene.id, ...layout, passed: true })
        const appearance = await page.evaluate(() => {
          const style = (selector, pseudo = null) => {
            const s = getComputedStyle(document.querySelector(selector), pseudo)
            return { weight: s.fontWeight, stroke: s.webkitTextStrokeWidth, size: s.fontSize, color: s.color,
              background: s.backgroundImage, shadow: s.boxShadow, height: s.height, top: s.top, marginTop: s.marginTop }
          }
          return { type: Object.fromEntries(['.task-heading h1', '.task-filter-button', '.task-item-name',
            '.task-detail-heading h2', '.task-step.current', '.task-detail h3', '.task-cost-note strong',
            '.task-continuous h3', '.task-confirm'].map(selector => [selector, style(selector)])),
            selected: style('.r-nav-item.r-selected'), secondary: style('.r-nav-item.r-secondary'),
            separator: style('.r-nav-separator'), header: style('.r-topbar', '::before'),
            queue: style('.task-queue'), detail: style('.task-detail'), authorization: style('.task-authorization'),
            cancel: style('.task-detail-actions .utility-button:first-child') }
        })
        ensure(Object.values(appearance.type).every(s => s.weight === '500' && s.stroke === '0.3px')
          && appearance.selected.color === 'rgb(255, 241, 220)' && appearance.secondary.size === '21px'
          && appearance.separator.marginTop === '20px' && appearance.header.height === '82px' && appearance.header.top === '-6px'
          && appearance.header.background.includes('rgba(27, 24, 20, 0.25)') && appearance.header.background.includes('rgba(27, 24, 20, 0.533)')
          && appearance.queue.background === 'linear-gradient(120deg, rgb(25, 22, 19), rgb(27, 24, 21))'
          && appearance.detail.background.includes('rgba(29, 25, 22, 0.796)')
          && appearance.authorization.background.includes('rgba(84, 48, 27, 0.125)') && appearance.authorization.background.includes('rgb(21, 18, 15)')
          && appearance.cancel.background.includes('rgba(41, 35, 30, 0.85)')
          && appearance.type['.task-confirm'].background.includes('rgb(203, 149, 100)')
          && appearance.type['.task-confirm'].shadow.includes('inset'), '27字重／导航选中颜色／顶栏底层或面板／按钮光照不符')
        checks.push({ check: 'ai-authorization-typography-selected-navigation-header-panel-and-button-surfaces',
          scene: scene.id, ...appearance, passed: true })
        const writes = []
        const onWrite = request => { if (new URL(request.url()).pathname.startsWith('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) writes.push({ method: request.method(), path: new URL(request.url()).pathname }) }
        page.on('request', onWrite)
        const filter = page.locator('.task-filter-button'), tasks = page.locator('.task-queue-item')
        const continuous = page.getByRole('switch', { name: '持续授权', exact: true })
        ensure(await filter.getAttribute('aria-expanded') === 'false' && await continuous.getAttribute('aria-checked') === 'false'
          && await tasks.first().getAttribute('aria-pressed') === 'true' && await page.locator('.task-detail h2').innerText() === '知识整理 · 缓存一致性', '27初始筛选／授权／选择不符')
        await filter.click()
        await page.getByRole('menuitemradio', { name: '成功', exact: true }).focus()
        await page.keyboard.press('Escape')
        ensure(!await page.getByRole('menu').count() && await filter.evaluate(node => node === document.activeElement), '27 Esc未关闭筛选或归还焦点')
        for (const [status, title] of [['成功', '出题候选 · 算法与并发'], ['失败', '来源问答 · Spring 源码'], ['费用待核对', '关系候选 · 微服务架构']]) {
          await filter.click()
          await page.getByRole('menuitemradio', { name: status, exact: true }).click()
          ensure(await tasks.count() === 1 && await page.locator('.task-detail h2').innerText() === title
            && !await page.locator('.task-input-list').count() && await page.locator('.task-confirm').isDisabled(), `27 ${status}筛选或详情边界不符`)
        }
        await filter.click(); await page.getByRole('menuitemradio', { name: '全部状态', exact: true }).click()
        await tasks.nth(2).click()
        ensure(await page.locator('.task-detail h2').innerText() === '来源问答 · Spring 源码', '27点击任务未切换详情')
        await tasks.first().click(); await continuous.click()
        ensure(await continuous.getAttribute('aria-checked') === 'true', '27持续授权开关未打开')
        await continuous.click()
        await page.getByRole('button', { name: '刷新', exact: true }).click()
        ensure(await tasks.count() === 4 && await tasks.first().getAttribute('aria-pressed') === 'true', '27刷新破坏当前选择')
        await page.getByRole('button', { name: '取消任务', exact: true }).click()
        ensure(await tasks.first().locator('.task-status').innerText() === '已取消' && await page.locator('.task-confirm').isDisabled(), '27页面内取消状态未同步')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '27取消与筛选后刷新未恢复参考初态')
        await page.getByRole('button', { name: '确认并发送', exact: true }).click()
        ensure(await tasks.first().locator('.task-status').innerText() === '已确认发送'
          && await page.getByRole('button', { name: '取消任务', exact: true }).isDisabled(), '27页面内发送确认未同步或取消未锁定')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '27发送确认后刷新未恢复参考初态')
        await page.getByRole('button', { name: '查看任务记录', exact: true }).click()
        ensure(await page.getByRole('status').innerText() === '知识整理 · 缓存一致性 · 处理中', '27任务记录提示未使用当前身份')
        await page.locator('.r-top-search').click()
        await page.getByRole('dialog', { name: '全局命令面板' }).waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await page.getByRole('link', { name: '问阶今日', exact: true }).click()
        await page.locator('.r-canvas[data-scene="02"]').waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '27导航返回未恢复初态')
        page.off('request', onWrite)
        ensure(!writes.length, '27固定参考态出现意外业务写入')
        checks.push({ check: 'ai-authorization-filter-escape-focus-selection-toggle-cancel-send-boundary-navigation-refresh', scene: scene.id,
          passed: true, businessWrites: writes, limitations: '筛选、取消、发送确认与持续授权仅页面内存；未调用真实AI或写入任务／授权；不可辨认小字留空，原字体和品牌素材未取得' })
      }
      if (scene.id === '28') {
        const layout = await page.evaluate(() => {
          const box = selector => { const rect = document.querySelector(selector).getBoundingClientRect(); return [rect.x, rect.y, rect.width, rect.height] }
          const brand = document.querySelector('.r-sidebar > a'), rect = brand.getBoundingClientRect()
          return {
            sidebar: box('.r-sidebar'), search: box('.r-top-search'), directory: box('.settings-navigation'),
            appearance: box('.settings-appearance'), data: box('.settings-data'),
            title: box('.settings-heading h1'), brand: box('.r-sidebar > a'),
            brandHittable: brand.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)),
            navigation: [...document.querySelectorAll('.r-nav-item')].map(node => node.textContent.trim()),
            categories: [...document.querySelectorAll('.settings-navigation > button')].map(node => node.textContent.trim()),
            rows: [...document.querySelectorAll('.settings-data-overview > div')].map(node => node.textContent.trim()),
            selected: [...document.querySelectorAll('.settings-row button.selected')].map(node => node.textContent.trim()),
            primaryIcons: [...document.querySelectorAll('.r-nav-item:not(.r-secondary) > svg > path:first-child')].map(node => node.getAttribute('d')),
            singleLine: [...document.querySelectorAll('.r-nav-item > span')].every(node => node.getBoundingClientRect().height < 40),
          }
        })
        ensure(JSON.stringify(layout.sidebar) === '[19,20,212,976]' && JSON.stringify(layout.search) === '[540,32,456,52]'
          && JSON.stringify(layout.directory) === '[264,247,230,666]' && JSON.stringify(layout.appearance) === '[508,245,644,691]'
          && JSON.stringify(layout.data) === '[1164,245,352,691]' && layout.title[0] === 264
          && layout.brandHittable && layout.singleLine, '28品牌命中、三栏或外壳几何不符')
        ensure(layout.navigation.join(',') === '今日,准备,知识,训练,复盘,成长,题库,回忆卡,最近访问,AI 任务'
          && layout.categories.join(',') === '外观与数据,阅读与写作,动效,时区,保存与恢复,导入 / 导出,数据用量,安全'
          && layout.rows.join(',') === '笔记18,概念42,关系68,附件2.4 GB'
          && layout.selected.join(',') === '深色,舒适,15,完整'
          && layout.primaryIcons[1].startsWith('M6 2h12') && layout.primaryIcons[3].startsWith('M5 3a1')
          && layout.primaryIcons[4].startsWith('M3 11a9') && layout.primaryIcons[5].startsWith('m2 18'), '28文案、初态、次序或专用导航轮廓不符')
        checks.push({ check: 'settings-reference-three-panels-shell-icons-and-defaults', scene: scene.id, ...layout, passed: true })
        const material = await page.evaluate(() => {
          const style = selector => {
            const value = getComputedStyle(document.querySelector(selector))
            return { size: value.fontSize, weight: value.fontWeight, stroke: value.webkitTextStrokeWidth,
              gap: value.gap, margin: value.marginRight, background: value.backgroundImage, shadow: value.boxShadow }
          }
          return { title: style('.settings-heading h1'), navigation: style('.r-nav-item'),
            secondary: style('.r-nav-item.r-secondary'), saved: style('.r-saved'),
            category: style('.settings-navigation > button'), directory: style('.settings-navigation'),
            selectedCategory: style('.settings-navigation > button.selected'), appearance: style('.settings-appearance'),
            selected: [...document.querySelectorAll('.utility-segment > button.selected')].map(node => getComputedStyle(node).backgroundImage),
            unselected: style('.settings-motion-row .utility-segment > button:not(.selected)'),
            statistic: style('.settings-data-overview dd') }
        })
        ensure(material.title.weight === '500' && material.title.stroke === '0.6px'
          && material.navigation.size === '21px' && material.navigation.weight === '300'
          && material.secondary.size === '20px' && material.saved.margin === '10px'
          && material.category.gap === '16px' && material.statistic.weight === '400'
          && material.directory.background.includes('rgb(30, 24, 19)')
          && material.appearance.background.includes('rgb(26, 21, 17)')
          && material.selectedCategory.shadow.includes('8px')
          && material.selected.length === 4 && material.selected.every(value => value.includes('rgb(214, 159, 108)') && value.includes('rgb(117, 73, 43) 80%'))
          && material.unselected.background.includes('linear-gradient'), '28字重、图文间距、保存区或面板／分段光照不符')
        checks.push({ check: 'settings-typography-spacing-panels-and-segment-lighting', scene: scene.id, ...material, passed: true })
        const writes = []
        const onWrite = request => { if (new URL(request.url()).pathname.startsWith('/api/') && !['GET','HEAD','OPTIONS'].includes(request.method())) writes.push(new URL(request.url()).pathname) }
        page.on('request', onWrite)
        for (const [name, choices] of [['主题外观', ['跟随系统','浅色','深色']], ['内容宽度', ['宽','全宽','舒适']], ['正文字号', ['16','17','15']], ['动效', ['轻量','静止','完整']]]) {
          const group = page.getByRole('group', { name, exact: true })
          for (const choice of choices) {
            await group.getByRole('button', { name: choice, exact: true }).click()
            ensure(await group.locator('button[aria-pressed="true"]').count() === 1
              && await group.locator('button[aria-pressed="true"]').innerText() === choice, `28 ${name}单选失败`)
          }
        }
        const reduced = page.getByRole('switch', { name: '减少动效', exact: true })
        ensure(await reduced.getAttribute('aria-checked') === 'false', '28减少动效初态错误')
        await reduced.click(); ensure(await reduced.getAttribute('aria-checked') === 'true', '28开关未打开'); await reduced.click()
        const timezone = page.locator('.settings-timezone-row .settings-select'), interval = page.locator('.settings-save-row .settings-select')
        for (const [trigger, menu, choice] of [[timezone, '时区', 'UTC'], [interval, '自动保存间隔', '5 分钟']]) {
          await trigger.click()
          await page.getByRole('menu', { name: menu, exact: true }).getByRole('menuitemradio', { name: choice, exact: true }).focus()
          await page.keyboard.press('Escape')
          ensure(!await page.getByRole('menu').count() && await trigger.evaluate(node => node === document.activeElement), `28 ${menu} Esc或焦点错误`)
          await trigger.click()
          const menuBox = await page.getByRole('menu', { name: menu, exact: true }).boundingBox()
          ensure(menuBox && menuBox.y >= 0 && menuBox.y + menuBox.height <= 1024, `28 ${menu}溢出视口`)
          await page.getByRole('menuitemradio', { name: choice, exact: true }).click()
          ensure((await trigger.innerText()).trim() === choice && !await page.getByRole('menu').count(), `28 ${menu}选择未同步`)
        }
        await timezone.click(); await interval.click()
        ensure(!await page.getByRole('menu', { name: '时区', exact: true }).count()
          && await page.getByRole('menu', { name: '自动保存间隔', exact: true }).count() === 1, '28两个菜单未互斥')
        await page.keyboard.press('Escape')
        const directory = page.getByRole('navigation', { name: '设置分类', exact: true })
        for (const [name, selector] of [['阅读与写作','.settings-font-row'], ['动效','.settings-motion-row'], ['时区','.settings-timezone-row'], ['保存与恢复','.settings-save-row'], ['导入 / 导出','.settings-data'], ['数据用量','.settings-data']]) {
          await directory.getByRole('button', { name, exact: true }).click()
          ensure(await directory.locator('[aria-pressed="true"]').count() === 1
            && await page.locator(selector).evaluate(node => node.classList.contains('utility-focused')), `28 ${name}定位失败`)
        }
        await directory.getByRole('button', { name: '安全', exact: true }).click()
        ensure(await page.getByRole('status').innerText() === '安全设置的详细状态尚待补充参考', '28安全边界未明确')
        for (const format of ['完整导出','Markdown 导出','JSON Canvas 导出']) {
          await page.locator('.settings-export-section > button').filter({ hasText: format }).click()
          ensure(await page.getByRole('status').innerText() === `已选择${format}，当前复刻页面不生成或导出数据`, '28导出边界不符')
        }
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '28偏好与菜单操作后刷新未恢复参考初态')
        await page.locator('.r-top-search').click()
        await page.getByRole('dialog', { name: '全局命令面板', exact: true }).waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await page.getByRole('link', { name: '问阶今日', exact: true }).click()
        await page.locator('.r-canvas[data-scene="02"]').waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '28导航返回未恢复参考初态')
        page.off('request', onWrite)
        ensure(!writes.length, '28固定参考态出现业务写入')
        checks.push({ check: 'settings-segments-switch-menus-escape-focus-categories-export-boundary-navigation-refresh', scene: scene.id, passed: true,
          businessWrites: writes, limitations: '固定偏好仅当前组件内存；不导出实际数据，不替代正式设置修订、持久保存或完整视觉验收；原字体与品牌素材缺失' })
      }
      if (scene.id === '09') {
          const graphLayout = await page.evaluate(() => {
            const box = selector => {
              const r = document.querySelector(selector).getBoundingClientRect()
              return [r.x, r.y, r.width, r.height]
            }
            const p = document.querySelector('.graph-definition p'), text = p.firstChild, lines = new Map()
            for (let i = 0; i < text.length; i++) {
              const range = document.createRange(); range.setStart(text, i); range.setEnd(text, i + 1)
              const y = range.getBoundingClientRect().y
              lines.set(y, (lines.get(y) ?? '') + text.textContent[i])
            }
            return {
              lines: [...lines.values()], detail: box('.graph-detail'), tabs: box('.graph-detail-tabs'),
              definition: box('.graph-definition'), notes: box('.graph-core-notes'),
              evidence: box('.graph-evidence > button'), candidateToggle: box('.graph-candidate-toggle'),
              labels: [...document.querySelectorAll('.graph-node-label text')].map(node => node.textContent),
              transform: document.querySelector('.graph-map > g:last-child').getAttribute('transform'),
              selectedTab: document.querySelector('.graph-detail-tabs .selected').textContent,
            }
          })
          ensure(JSON.stringify(graphLayout.lines) === JSON.stringify([
            '缓存一致性是指在分布式系统中，缓存与数据',
            '源之间的数据保持一致的能力，常见策略包括 ',
            'Cache Aside、Write Through、Write Back 等。',
          ]), '09定义未保持参考三行')
          ensure(JSON.stringify(graphLayout.detail) === '[1094,131,415,777]'
            && JSON.stringify(graphLayout.tabs) === '[1095,263,413,49]'
            && JSON.stringify(graphLayout.definition) === '[1118,312,367,190]'
            && JSON.stringify(graphLayout.notes) === '[1118,502,367,187]'
            && JSON.stringify(graphLayout.evidence) === '[1118,748,367,58]'
            && JSON.stringify(graphLayout.candidateToggle) === '[157,744,211,31]',
          '09详情、笔记、证据或筛选几何不符')
          ensure(graphLayout.labels.join(',') === '分布式,JVM,数据库,Spring,项目'
            && graphLayout.transform === 'translate(585 469) scale(1) translate(-703 -469)'
            && graphLayout.selectedTab === '概览', '09初始节点、视图或标签不符')
          checks.push({ check: 'graph-definition-three-lines-detail-filter-geometry-fixed-nodes', scene: scene.id, graphLayout, passed: true })
          const graphStyle = await page.evaluate(() => {
            const style = selector => {
              const s = getComputedStyle(document.querySelector(selector))
              return { weight: s.fontWeight, size: s.fontSize, stroke: s.webkitTextStrokeWidth }
            }
            return { body: style('.r-canvas'), label: style('.graph-node-label text'),
              core: style('.graph-core text'), title: style('.graph-detail-title h1'),
              definition: style('.graph-definition p'), practice: style('.graph-practice') }
          })
          ensure(graphStyle.body.weight === '300' && graphStyle.label.weight === '400'
            && graphStyle.core.weight === '500' && graphStyle.title.weight === '500'
            && graphStyle.title.stroke === '0.3px' && graphStyle.definition.size === '18px'
            && graphStyle.practice.weight === '500', '09实际字重、字号或描边不符')
          checks.push({ check: 'graph-typography-computed-style', scene: scene.id, graphStyle, passed: true })
          const graphLight = await page.evaluate(() => ({
            filter: getComputedStyle(document.querySelector('.graph-filters')).backgroundImage,
            tools: getComputedStyle(document.querySelector('.graph-bottom-tools')).backgroundImage,
            haloRadius: document.querySelector('.graph-core > circle').getAttribute('r'),
            haloOpacity: [...document.querySelectorAll('#g9-halo stop')].map(node => node.getAttribute('stop-opacity')),
            corePath: document.querySelector('.graph-core path:not([filter])').getAttribute('d'),
            detailPath: document.querySelector('.graph-detail-star path').getAttribute('d'),
          }))
          ensure(graphLight.filter === 'linear-gradient(120deg, rgba(24, 21, 15, 0.91), rgba(17, 16, 13, 0.91))'
            && graphLight.tools === 'linear-gradient(115deg, rgba(41, 30, 12, 0.5), rgba(12, 11, 9, 0.92) 60%)'
            && graphLight.haloRadius === '150' && graphLight.haloOpacity.join(',') === '.28,.12,0'
            && graphLight.corePath === 'M703 443C707 459 712 465 726 469C712 473 707 478 703 494C698 478 694 474 680 469C694 464 698 459 703 443Z'
            && graphLight.detailPath === 'M24 1C28 18 32 22 47 27C32 31 28 35 24 53C20 35 16 31 1 27C16 22 20 18 24 1Z',
          '09筛选、图例光照或弧面星形不符')
          checks.push({ check: 'graph-panel-light-halo-and-curved-star-paths', scene: scene.id, graphLight, passed: true })
          const graphPolish = await page.evaluate(() => {
            const style = selector => getComputedStyle(document.querySelector(selector))
            const paths = selector => [...document.querySelectorAll(selector + ' path')].map(path => path.getAttribute('d'))
            return {
              labelFont: style('.graph-node-label text').fontFamily,
              titleSize: style('.graph-detail-title h1').fontSize,
              titleOffset: style('.graph-detail-title h1').transform,
              titleGap: style('.graph-detail-title').columnGap,
              zoomWidth: style('.graph-zoom-value').minWidth,
              zoomSize: style('.graph-zoom-value').fontSize,
              practiceBackground: style('.graph-practice').backgroundImage,
              orbitOpacity: document.querySelector('.graph-backdrop > path').getAttribute('stroke-opacity'),
              definitionIcon: paths('.graph-definition h2 svg'),
              notesIcon: paths('.graph-core-notes h2 svg'),
              evidenceIcon: paths('.graph-evidence h2 svg'),
              practiceIcon: paths('.graph-practice svg'),
            }
          })
          ensure(graphPolish.labelFont === 'Arial, "Microsoft YaHei", sans-serif'
            && graphPolish.titleSize === '28.5px' && graphPolish.titleOffset === 'matrix(1, 0, 0, 1, 0, -3)'
            && graphPolish.titleGap === '19px' && graphPolish.zoomWidth === '66px' && graphPolish.zoomSize === '20px'
            && graphPolish.practiceBackground === 'linear-gradient(120deg, rgb(172, 126, 56), rgb(173, 121, 50) 67%, rgb(168, 117, 48))'
            && graphPolish.orbitOpacity === '.19'
            && JSON.stringify(graphPolish.definitionIcon) === JSON.stringify(['M6 2h9l5 5v15H6a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1Z', 'M14 2v6h6M8 12h8M8 16h5M2 5v17a2 2 0 0 0 2 2h11'])
            && JSON.stringify(graphPolish.notesIcon) === JSON.stringify(['M5 2h13a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H5Z', 'M2 5h3M2 10h3M2 15h3M8 6h8M8 10h8M8 14h5M8 18h3'])
            && JSON.stringify(graphPolish.evidenceIcon) === JSON.stringify(['m9 15 6-6', 'm10 7 2-3a4 4 0 0 1 6 6l-2 3M14 17l-2 3a4 4 0 0 1-6-6l2-3'])
            && JSON.stringify(graphPolish.practiceIcon) === JSON.stringify(['M19 12a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z', 'M12 2v5M12 17v5M2 12h5M17 12h5', 'm12 9 1 2 2 1-2 1-1 2-1-2-2-1 2-1Z']),
          '09标签字体、详情标题、专用图标、缩放间距或按钮底色不符')
          checks.push({ check: 'graph-label-font-title-offset-detail-icons-zoom-and-practice', scene: scene.id, graphPolish, passed: true })
          const graphContours = await page.evaluate(() => {
            const mark = document.querySelector('.graph-brand-emblem')
            const style = getComputedStyle(mark)
            return {
              links: [...document.querySelectorAll('.graph-primary-links path')].map(node => node.getAttribute('d')),
              candidate: document.querySelector('.graph-candidate > path').getAttribute('d'),
              markPaths: [...mark.querySelectorAll('path')].map(node => [node.getAttribute('d'), node.getAttribute('fill')]),
              markRing: ['r', 'stroke', 'stroke-width'].map(name => mark.querySelector('circle').getAttribute(name)),
              markTransform: style.transform, markFilter: style.filter,
            }
          })
          ensure(JSON.stringify(graphContours.links) === JSON.stringify([
            'M628 338Q667 374 690 434', 'M735 447Q814.5 373.5 877 334', 'M688 482Q618 548 545 592',
          ]) && graphContours.candidate === 'M732 491Q808 556 879 607'
            && JSON.stringify(graphContours.markPaths) === JSON.stringify([
              ['m30 30 21 15 22-17-17 23 12 20-21-16-19 15 16-23Z', '#d6a965'],
              ['M50 0C53 34 58 42 100 50C59 54 54 62 50 100C46 62 40 55 0 50C39 45 45 36 50 0Z', '#e3b873'],
              ['M50 0 51 46 100 50 55 52 50 100 48 55 0 50 44 48Z', '#f5d58d'],
              ['m50 40 8 10-8 10-8-10Z', '#73542e'], ['m50 44 3 6-3 6-3-6Z', '#e8bf76'],
            ]) && graphContours.markRing.join(',') === '41,#d3a65f,1.8'
            && graphContours.markTransform === 'matrix(1, 0, 0, 1, 3, 0)'
            && graphContours.markFilter === 'drop-shadow(rgba(228, 184, 113, 0.2) 0px 0px 2px)',
          '09依赖曲线或专用罗盘标志轮廓不符')
          checks.push({ check: 'graph-dependency-curve-and-scoped-compass-contours', scene: scene.id, graphContours, passed: true })
          const graphDetailFit = await page.evaluate(() => {
            const style = selector => getComputedStyle(document.querySelector(selector))
            const meta = style('.graph-detail-heading > p'), count = style('.graph-detail-heading > p span')
            const notes = style('.graph-core-notes ul'), star = style('.graph-detail-star')
            const bullet = getComputedStyle(document.querySelector('.graph-core-notes li'), '::before')
            const views = [...document.querySelectorAll('.graph-view-switch > button')]
            return {
              metadata: { padding: meta.paddingTop, transform: meta.transform, margin: count.marginLeft, spacing: count.wordSpacing },
              notes: { size: notes.fontSize, transform: notes.transform, lineHeight: notes.lineHeight, bulletMargin: bullet.margin },
              star: { width: star.width, height: star.height, transform: star.transform },
              viewSizes: views.map(node => [getComputedStyle(node).fontSize, node.getBoundingClientRect().width]),
              starStroke: style('.graph-view-switch > button:first-child svg').strokeWidth,
              listPath: views[1].querySelector('path').getAttribute('d'),
              listDots: [...views[1].querySelectorAll('circle')].map(node => ['cx', 'cy', 'r'].map(name => node.getAttribute(name))),
              boardRect: ['x', 'y', 'width', 'height', 'rx'].map(name => views[2].querySelector('rect').getAttribute(name)),
              boardPath: views[2].querySelector('path').getAttribute('d'),
            }
          })
          ensure(JSON.stringify(graphDetailFit.metadata) === JSON.stringify({
            padding: '4px', transform: 'matrix(1, 0, 0, 1, 1, 0)', margin: '20px', spacing: '6px',
          }) && JSON.stringify(graphDetailFit.notes) === JSON.stringify({
            size: '17.3px', transform: 'matrix(1, 0, 0, 1, 0, -2)', lineHeight: '35px', bulletMargin: '0px 15px 2px 3px',
          }) && JSON.stringify(graphDetailFit.star) === JSON.stringify({
            width: '44px', height: '49px', transform: 'matrix(1, 0, 0, 1, 3, 2)',
          }) && JSON.stringify(graphDetailFit.viewSizes) === '[["20px",124],["20px",129],["20px",129]]'
            && graphDetailFit.starStroke === '2.3px' && graphDetailFit.listPath === 'M9 5h12M9 12h12M9 19h12'
            && JSON.stringify(graphDetailFit.listDots) === '[["3.5","5","1.4"],["3.5","12","1.4"],["3.5","19","1.4"]]'
            && graphDetailFit.boardRect.join(',') === '2,3,20,18,.7' && graphDetailFit.boardPath === 'M5 6h14v12H5Z',
          '09详情星形、版本间距、笔记基线或视图切换几何不符')
          checks.push({ check: 'graph-detail-star-metadata-notes-and-view-control-fit', scene: scene.id, graphDetailFit, passed: true })
          const detailTabs = page.getByRole('navigation', { name: '知识详情标签' })
          for (const tab of ['笔记', '关系', '练习', '证据', '概览']) {
            await detailTabs.getByRole('button', { name: tab, exact: true }).click()
            ensure(await detailTabs.locator('.selected').textContent() === tab, '09详情标签未切换：' + tab)
          }
          await page.getByRole('button', { name: '分布式', exact: true }).click()
          ensure(await page.locator('.graph-detail-title h1').textContent() === '分布式', '09节点选择未更新详情')
          await page.locator('.graph-map').getByRole('button', { name: '缓存一致性', exact: true }).click()
          const graphSearch = page.getByRole('textbox', { name: '搜索知识点', exact: true })
          await graphSearch.fill('JVM')
          ensure(await page.locator('.graph-node-label').count() === 1
            && await page.locator('.graph-node-label text').textContent() === 'JVM', '09知识搜索未筛选节点')
          await graphSearch.fill('')
          ensure(await page.locator('.graph-node-label').count() === 5, '09清空搜索未恢复节点')
          await page.mouse.move(900, 900); await page.mouse.down()
          await page.mouse.move(930, 920); await page.mouse.up()
          ensure((await page.locator('.graph-map > g:last-child').getAttribute('transform')).startsWith('translate(615 489)'),
            '09画布平移未更新')
          await page.getByRole('button', { name: '重置星图视图' }).click()
          ensure(await page.locator('.graph-map > g:last-child').getAttribute('transform') === graphLayout.transform,
            '09重置未恢复节点坐标')
          checks.push({ check: 'graph-tabs-node-selection-search-pan-reset', scene: scene.id, passed: true })
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
      if (scene.id === '29') {
        const layout = await page.evaluate(() => {
          const definition = document.querySelector('.mobile-definition p'), text = definition.firstChild, rows = new Map()
          for (let index = 0; index < text.length; index++) {
            const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1)
            const y = range.getBoundingClientRect().y
            rows.set(y, (rows.get(y) ?? '') + text.textContent[index])
          }
          const box = selector => { const rect = document.querySelector(selector).getBoundingClientRect(); return [rect.x, rect.y, rect.width, rect.height] }
          return {
            phones: [...document.querySelectorAll('.mobile-phone')].map(node => [node.offsetLeft, node.offsetTop, node.offsetWidth, node.offsetHeight]),
            labels: [...document.querySelectorAll('.mobile-bottom-nav > button > span')].map(node => node.textContent),
            times: [...document.querySelectorAll('.phone-time')].map(node => node.textContent),
            definitionLines: [...rows.values()], question: document.querySelector('.mobile-recall-question').innerText,
            detail: box('.mobile-detail-content'), tabs: box('.mobile-detail-tabs'), drawer: box('.mobile-recall-drawer'),
            answer: box('.mobile-recall-answer'), rating: box('.mobile-rating-options'),
            brandPaths: document.querySelectorAll('.mobile-home-header .r-emblem path').length,
            menuOpen: !!document.querySelector('.mobile-detail-menu'),
            selectedTab: document.querySelector('.mobile-detail-tabs > .selected')?.textContent,
            selectedRating: document.querySelectorAll('.mobile-rating-options [aria-pressed="true"]').length,
            count: document.querySelector('.mobile-count').textContent,
            answerValue: document.querySelector('.mobile-recall-answer').value,
            completeDisabled: document.querySelector('.mobile-complete').disabled,
            horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
          }
        })
        ensure(JSON.stringify(layout.phones) === '[[61,62,447,916],[541,42,459,951],[1033,65,451,912]]'
          && layout.labels.join(',') === '今日,知识,训练,复盘,成长' && layout.times.join(',') === '9:41,9:41,9:41'
          && layout.brandPaths === 4 && !layout.horizontalOverflow, '29三手机坐标、时间、品牌或底部导航不符')
        ensure(JSON.stringify(layout.definitionLines) === JSON.stringify([
          '缓存一致性是指在分布式系统中，多个缓存', '实例之间的数据保持一致的能力，避免出现', '脏数据或数据不一致的问题。',
        ]) && layout.question === '缓存删除失败后，\n如何确认恢复？'
          && layout.selectedTab === '概览' && !layout.menuOpen && !layout.selectedRating
          && layout.count === '2' && layout.answerValue === '' && layout.completeDisabled, '29三行定义、两行题面或默认状态不符')
        ensure(JSON.stringify(layout.detail) === '[577,363,385,511]'
          && JSON.stringify(layout.tabs) === '[577,280,384,56]' && JSON.stringify(layout.drawer) === '[1048,190,421,771]'
          && JSON.stringify(layout.answer) === '[1071,463,375,237]' && JSON.stringify(layout.rating) === '[1072,742,373,92]',
        '29详情、标签、抽屉、答案或评分几何不符')
        checks.push({ check: 'mobile-three-phones-fixed-geometry-copy-lines-initial-state', scene: scene.id, layout, passed: true })
        const mobileStyle = await page.evaluate(() => {
          const style = selector => {
            const node = document.querySelector(selector), s = getComputedStyle(node)
            return { weight:s.fontWeight, size:s.fontSize, margin:s.marginTop, transform:s.transform, background:s.backgroundImage }
          }
          const wave = getComputedStyle(document.querySelector('.mobile-continue'), '::after')
          return { body:style('.mobile-scene'), definition:style('.mobile-definition p'), title:style('.mobile-continue-copy strong'),
            task:style('.mobile-task-copy'), note:style('.mobile-linked-note > button'), screen:style('.phone-today .phone-screen'),
            practice:style('.mobile-practice'), wave:{background:wave.backgroundImage,shadow:wave.boxShadow} }
        })
        ensure(mobileStyle.body.weight === '300' && mobileStyle.definition.size === '18.4px'
          && mobileStyle.definition.margin === '16px' && mobileStyle.definition.weight === '300'
          && mobileStyle.title.size === '22.5px' && mobileStyle.title.weight === '600'
          && mobileStyle.task.transform === 'matrix(1, 0, 0, 1, 0, 1)' && mobileStyle.note.margin === '15px'
          && mobileStyle.wave.background.startsWith('repeating-radial-gradient(') && mobileStyle.wave.shadow === 'none'
          && mobileStyle.screen.background.includes('60px 115px') && mobileStyle.screen.background.includes('60px 130px')
          && mobileStyle.practice.background === 'linear-gradient(112deg, rgb(211, 156, 106), rgb(188, 128, 83) 52%, rgb(168, 106, 67))',
        '29正文字面、学习标题、细纹、局部光照或按钮渐变不符')
        checks.push({ check:'mobile-type-baselines-wave-light-and-practice-surface',scene:scene.id,mobileStyle,passed:true })
        const mobileRefinement = await page.evaluate(() => {
          const style = selector => {
            const node = document.querySelector(selector), s = getComputedStyle(node), b = node.getBoundingClientRect()
            return { weight:s.fontWeight,size:s.fontSize,stroke:s.webkitTextStrokeWidth,transform:s.transform,
              box:[b.x,b.y,b.width,b.height],text:node.innerText }
          }
          return { time:style('.phone-time'), brand:style('.mobile-home-header .r-brand-word small'),
            subtitle:style('.mobile-home-title p'), heading:style('.mobile-detail-title'),
            question:style('.mobile-recall-question'), recent:style('.mobile-recent-note strong'),
            note:style('.mobile-linked-note strong'), practice:style('.mobile-practice') }
        })
        ensure(mobileRefinement.time.weight === '500' && mobileRefinement.time.size === '20px'
          && mobileRefinement.brand.weight === '300' && mobileRefinement.recent.weight === '300'
          && mobileRefinement.note.weight === '300' && mobileRefinement.heading.weight === '500'
          && mobileRefinement.heading.size === '39.5px' && mobileRefinement.heading.stroke === '0.6px'
          && mobileRefinement.question.weight === '500' && mobileRefinement.question.size === '36.5px'
          && mobileRefinement.question.stroke === '0.6px', '29状态栏、笔记与知识／回忆标题字重或字面不符')
        checks.push({ check:'mobile-status-notes-and-title-weight-stroke',scene:scene.id,mobileRefinement,passed:true })
        ensure(mobileRefinement.subtitle.transform === 'matrix(1, 0, 0, 1, 0, -4)'
          && mobileRefinement.question.transform === 'matrix(1, 0, 0, 1, 0, 1)'
          && JSON.stringify(mobileRefinement.heading.box) === '[578,205,197.5,60]'
          && JSON.stringify(mobileRefinement.question.box) === '[1072,323,377,104]'
          && JSON.stringify(mobileRefinement.note.box) === '[648,617,265,28]'
          && JSON.stringify(mobileRefinement.practice.box) === '[578,882,383,59]'
          && mobileRefinement.question.text === '缓存删除失败后，\n如何确认恢复？',
        '29说明／题面基线、笔记／练习按钮几何或题面换行不符')
        checks.push({ check:'mobile-subtitle-question-baselines-and-preserved-detail-geometry',scene:scene.id,passed:true })
        const writes = [], onWrite = request => {
          if (new URL(request.url()).pathname.startsWith('/api/') && !['GET','HEAD','OPTIONS'].includes(request.method())) writes.push(request.method() + ' ' + new URL(request.url()).pathname)
        }
        page.on('request', onWrite)
        const tabs = page.getByRole('navigation', { name: '知识点标签', exact: true })
        await tabs.getByRole('button', { name: '关系', exact: true }).click()
        ensure(await page.locator('.mobile-unconfirmed').innerText() === '关系内容待确认', '29未定义关系被当成已确认内容')
        await tabs.getByRole('button', { name: '练习', exact: true }).click()
        ensure(await page.locator('.mobile-exercise-content').isVisible(), '29练习标签未切换')
        await tabs.getByRole('button', { name: '概览', exact: true }).click()
        const more = page.getByRole('button', { name: '知识点更多操作', exact: true })
        await more.click()
        ensure(await more.getAttribute('aria-expanded') === 'true' && await page.locator('.mobile-detail-menu button').count() === 2, '29更多菜单未打开')
        await page.locator('.mobile-detail-menu button').first().focus()
        await page.keyboard.press('Escape')
        ensure(!await page.locator('.mobile-detail-menu').count() && await more.evaluate(node => node === document.activeElement), '29菜单Esc未关闭或焦点未归还')
        const review = page.locator('.mobile-task-row').first().getByRole('button', { name: '完成复习：缓存一致性', exact: true })
        await review.click()
        ensure(await page.locator('.mobile-count').innerText() === '1', '29任务完成未同步计数')
        await page.getByRole('button', { name: '取消完成复习：缓存一致性', exact: true }).click()
        ensure(await page.locator('.mobile-count').innerText() === '2', '29取消完成未同步计数')
        await page.getByRole('button', { name: '收起回忆卡', exact: true }).click()
        ensure(!await page.locator('.mobile-recall-drawer').count(), '29抽屉未收起')
        await page.locator('.mobile-reopen-recall').click()
        const answer = page.getByRole('textbox', { name: '回忆卡答案', exact: true })
        await answer.fill('先确认数据库状态，再检查删除结果和重试记录。')
        const ratings = page.getByRole('group', { name: '回忆程度', exact: true })
        for (const label of ['忘记','困难','记得','熟悉']) {
          await ratings.getByRole('button', { name: label, exact: true }).click()
          ensure(await ratings.locator('[aria-pressed="true"]').count() === 1
            && await ratings.getByRole('button', { name: label, exact: true }).getAttribute('aria-pressed') === 'true', '29评分不是互斥单选')
        }
        await page.keyboard.press('Home')
        ensure(await ratings.getByRole('button', { name: '忘记', exact: true }).getAttribute('aria-pressed') === 'true', '29评分Home无效')
        await page.keyboard.press('ArrowLeft')
        ensure(await ratings.getByRole('button', { name: '熟悉', exact: true }).evaluate(node => node === document.activeElement && node.getAttribute('aria-pressed') === 'true'), '29评分方向键循环无效')
        await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowDown')
        ensure(await ratings.getByRole('button', { name: '困难', exact: true }).getAttribute('aria-pressed') === 'true', '29评分右／下方向键无效')
        await page.keyboard.press('ArrowUp'); await page.keyboard.press('End')
        ensure(await ratings.getByRole('button', { name: '熟悉', exact: true }).getAttribute('aria-pressed') === 'true', '29评分上方向键或End无效')
        await page.getByRole('button', { name: '完成', exact: true }).click()
        ensure(await page.getByRole('button', { name: '已完成', exact: true }).isDisabled()
          && await answer.evaluate(node => node.readOnly) && await page.locator('.mobile-count').innerText() === '1'
          && (await page.locator('.r-toast').innerText()).includes('未写入正式记录'), '29页面内完成边界或任务计数不符')
        await page.getByRole('button', { name: '重置回忆卡', exact: true }).click()
        ensure(await answer.inputValue() === '' && !await answer.evaluate(node => node.readOnly)
          && !await ratings.locator('[aria-pressed="true"]').count() && await page.getByRole('button', { name: '完成', exact: true }).isDisabled(), '29重置未清空草稿和评分')
        await page.reload({ waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '29页签／任务／抽屉／评分后刷新未恢复原始三手机画面')
        for (const [label, destination] of [['今日','02'],['知识','10'],['训练','17'],['复盘','21'],['成长','24']]) {
          await page.getByRole('navigation', { name: '移动端主导航', exact: true }).getByRole('button', { name: label, exact: true }).click()
          await page.locator(`.r-canvas[data-scene="${destination}"]`).waitFor()
          await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        }
        await page.locator('.mobile-recent-note').click()
        await page.locator('.r-canvas[data-scene="11"]').waitFor()
        await page.goto(entry, { waitUntil: 'networkidle' }); await ready()
        await assertFrame(captured, '29导航返回未恢复三手机默认画面')
        page.off('request', onWrite)
        ensure(!writes.length, '29固定展示意外写入正式业务')
        checks.push({ check: 'mobile-tabs-menu-escape-tasks-drawer-ratings-keyboard-reset-navigation-refresh', scene: scene.id,
          businessWrites: writes, passed: true, limitations: '评分、作答和任务仅在展示页内存变化；关系内容和持久业务待确认，原字体与品牌素材未获得' })
      }
      if (scene.id === '30') {
        const list = page.getByRole('region', { name: '待整理内容' })
        const sources = page.getByRole('complementary', { name: '收件箱来源' })
        const detail = page.getByRole('complementary', { name: '内容详情' })
        const inboxGeometry = await page.evaluate(() => {
          const box = selector => {
            const node = document.querySelector(selector), r = node.getBoundingClientRect()
            return [r.x, r.y, r.width, r.height]
          }
          const navigation = [...document.querySelectorAll('.r-sidebar .r-nav-item')].map(node => {
            const span = node.querySelector('span'), range = document.createRange()
            range.selectNodeContents(span)
            return { text: span.textContent, lines: range.getClientRects().length }
          })
          return {
            sources: box('.sp-inbox-sources'), list: box('.sp-inbox-list'),
            avatar: box('.r-sidebar-avatar .r-avatar'), navigation,
            sourceCount: document.querySelectorAll('.sp-inbox-sources > button').length,
            selectedSources: document.querySelectorAll('.sp-inbox-sources > button[aria-pressed="true"]').length,
            selectedRows: document.querySelectorAll('.sp-inbox-entry[aria-pressed="true"]').length,
            checkedRows: document.querySelectorAll('.sp-inbox-list input:checked').length,
          }
        })
        ensure(JSON.stringify(inboxGeometry.sources) === '[243,233,233,701]'
          && JSON.stringify(inboxGeometry.list) === '[486,233,514,701]'
          && JSON.stringify(inboxGeometry.avatar) === '[38,917,57,61]',
          '30来源栏、列表或头像坐标偏离固定构图')
        ensure(inboxGeometry.navigation.length === 10 && inboxGeometry.navigation.every(item => item.lines === 1)
          && inboxGeometry.sourceCount === 5 && inboxGeometry.selectedSources === 1
          && inboxGeometry.selectedRows === 1 && inboxGeometry.checkedRows === 0, '30初始导航、筛选或选择状态不符')
        checks.push({ check: 'inbox-columns-avatar-single-line-navigation-initial-state', scene: scene.id, ...inboxGeometry, passed: true })
        const detailPaint = await detail.evaluate(node => {
          const box = node.getBoundingClientRect()
          return { box: [box.x, box.y, box.width, box.height], backgroundClip: getComputedStyle(node).backgroundClip }
        })
        ensure(JSON.stringify(detailPaint.box) === JSON.stringify([1011, 233, 501, 701])
          && detailPaint.backgroundClip === 'padding-box, padding-box', '30详情面板几何或背景绘制边界不符')
        checks.push({ check: 'inbox-detail-padding-clip-geometry', scene: scene.id, ...detailPaint, passed: true })
        const inboxTypography = await page.evaluate(() => {
          const style = selector => {
            const node = document.querySelector(selector), s = getComputedStyle(node)
            return { weight: s.fontWeight, size: s.fontSize, stroke: s.webkitTextStrokeWidth,
              transform: s.transform, width: s.width, background: s.backgroundImage, gap: s.gap, margin: s.margin }
          }
          return {
            body: style('.r-scene-30'), title: style('.sp-inbox-heading h1'),
            entries: [...document.querySelectorAll('.sp-inbox-entry')].map(node => getComputedStyle(node).transform),
            checks: [...document.querySelectorAll('.sp-inbox-list article .r-checkbox')].map(node => getComputedStyle(node).transform),
            all: style('.sp-inbox-list header .r-checkbox'), excerpt: style('.sp-inbox-excerpt p'),
            action: style('.sp-inbox-buttons .r-btn'), secondary: style('.r-nav-item.r-secondary'),
            source: style('.sp-inbox-sources button.selected'), list: style('.sp-inbox-list'), detail: style('.sp-inbox-detail'),
          }
        })
        ensure(inboxTypography.body.weight === '300' && inboxTypography.title.weight === '500'
          && inboxTypography.title.size === '47px' && inboxTypography.title.stroke === '0.6px'
          && JSON.stringify(inboxTypography.entries) === JSON.stringify([-2, -2, -3, -5].map(y => `matrix(1, 0, 0, 1, 0, ${y})`))
          && JSON.stringify(inboxTypography.checks) === JSON.stringify([-6, -6, -6, -10].map(y => `matrix(1, 0, 0, 1, 0, ${y})`))
          && inboxTypography.all.width === '21px' && inboxTypography.excerpt.size === '20.75px'
          && inboxTypography.excerpt.transform === 'matrix(1, 0, 0, 1, 0, 1)'
          && inboxTypography.action.weight === '300' && inboxTypography.secondary.gap === '13px'
          && inboxTypography.source.background.includes('rgba(154, 113, 71, 0.267)')
          && inboxTypography.list.background.includes('rgb(20, 18, 15)')
          && inboxTypography.detail.background.includes('rgba(95, 68, 34, 0.145)'),
          '30字面、四行基线、勾选框或分区光照偏离已核参考状态')
        checks.push({ check: 'inbox-type-row-baselines-checkboxes-surfaces', scene: scene.id, ...inboxTypography, passed: true })
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
        nativeScrollAreas: await page.evaluate(() => [...document.querySelectorAll('.r-canvas *')].flatMap(node => {
          const s = getComputedStyle(node), b = node.getBoundingClientRect()
          return b.width && b.height && ((/^(auto|scroll)$/.test(s.overflowY) && node.scrollHeight > node.clientHeight)
            || (/^(auto|scroll)$/.test(s.overflowX) && node.scrollWidth > node.clientWidth))
            ? [{ tag: node.tagName, className: String(node.className), box: [b.x, b.y, b.width, b.height],
              clientWidth: node.clientWidth, clientHeight: node.clientHeight, scrollWidth: node.scrollWidth, scrollHeight: node.scrollHeight }] : []
        })),
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
    } finally {
      if (failures.some(failure => failure.scene === scene.id)) {
        await writeJson(path.join(values.output, 'records', `${scene.id}-network.json`), {
          recordedAt: new Date().toISOString(), pageErrors,
          pendingRequests: [...requestStarted.values()].map(info => ({ ...info, elapsedMs: Date.now() - info.startedAt })),
          recentRequests,
          limitations: '仅当前复查请求证据，不能反推先前超时根因；加载等待与画面断言未放宽',
        })
      }
      await page.close()
    }
  }
} catch (error) {
  if (!failures.length) failures.push({ error: error.message })
  console.error(error.message)
  process.exitCode = 1
} finally {
  try { await browser?.close() } catch (error) { failures.push({ error: `浏览器关闭失败：${error.message}` }); process.exitCode = 1 }
  await writeJson(path.join(values.output, 'results.json'), { startedAt, finishedAt: new Date().toISOString(), baseUrl: base.origin,
    candidateInputSha256: manifest.inputSha256, browserConfiguration: { channel: values.channel, headless: true, ignoreDefaultArgs: ['--hide-scrollbars'] },
    expected: selectedScenes.length, referenceCount: 30, captured: results.length, checks, failures,
    status: results.length === selectedScenes.length && !failures.length ? '本次范围截图与自动检查完成，待人工逐页复核' : '本次范围取证未完成',
    scenes: results })
}
