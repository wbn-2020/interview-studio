// 原图未提供独立行星素材。共享固定种子的自有纹理，保持正式入口和固定态的坐标一致。
export function drawRecentHorizon(canvas: HTMLCanvasElement | null) {
  if (!canvas) return
  canvas.width = 1312
  canvas.height = 1024
  const context = canvas.getContext('2d')
  if (!context) return
  const ctx = context
  let seed = 260112
  const random = () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0
    return seed / 4294967296
  }
  const planetPath = () => {
    ctx.beginPath()
    ctx.moveTo(-100, 260)
    ctx.bezierCurveTo(320, 191, 731, 114, 1007, 135)
    ctx.bezierCurveTo(1135, 145, 1255, 179, 1415, 244)
  }
  ctx.clearRect(0, 0, 1312, 1024)
  const atmosphere = ctx.createRadialGradient(765, 156, 20, 765, 156, 590)
  atmosphere.addColorStop(0, '#54779a40')
  atmosphere.addColorStop(0.65, '#2344621e')
  atmosphere.addColorStop(1, '#16314900')
  ctx.fillStyle = atmosphere
  ctx.fillRect(0, 100, 1312, 175)
  ctx.save()
  planetPath()
  ctx.lineTo(1415, 365)
  ctx.lineTo(-100, 365)
  ctx.closePath()
  ctx.clip()
  // 固定坐标、种子和采样尺度，不依赖动画、系统时间或设备随机数。
  const noise = (x: number, y: number) => {
    const hash = (a: number, b: number) => {
      let value = Math.imul(a, 374761393) ^ Math.imul(b, 668265263) ^ 260112
      value = Math.imul(value ^ value >>> 13, 1274126177)
      return ((value ^ value >>> 16) >>> 0) / 4294967295
    }
    const x0 = Math.floor(x), y0 = Math.floor(y), fx = x - x0, fy = y - y0
    const sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy)
    const top = hash(x0, y0) * (1 - sx) + hash(x0 + 1, y0) * sx
    const bottom = hash(x0, y0 + 1) * (1 - sx) + hash(x0 + 1, y0 + 1) * sx
    return top * (1 - sy) + bottom * sy
  }
  const cubic = (t: number, a: number, b: number, c: number, d: number) =>
    (1 - t) ** 3 * a + 3 * (1 - t) ** 2 * t * b + 3 * (1 - t) * t * t * c + t ** 3 * d
  const texture = document.createElement('canvas')
  texture.width = 1312; texture.height = 190
  const paint = texture.getContext('2d')
  if (!paint) { ctx.restore(); return }
  const pixels = paint.createImageData(1312, 190)
  for (let x = 0; x < 1312; x++) {
    const second = x > 1007, xs = second ? [1007, 1135, 1255, 1415] : [-100, 320, 731, 1007]
    const ys = second ? [135, 145, 179, 244] : [260, 191, 114, 135]
    let low = 0, high = 1
    for (let step = 0; step < 12; step++) {
      const middle = (low + high) / 2
      if (cubic(middle, xs[0]!, xs[1]!, xs[2]!, xs[3]!) < x) low = middle
      else high = middle
    }
    const rimY = cubic((low + high) / 2, ys[0]!, ys[1]!, ys[2]!, ys[3]!)
    const warm = Math.max(0, Math.min(1, (x - 650) / 550))
    for (let y = 0; y < 190; y++) {
      const depth = Math.max(0, y + 125 - rimY)
      const broad = noise(x * .012, depth * .12)
      const cloud = noise(x * .055 + depth * .04, depth * .48) * .6
        + noise(x * .18, depth * 1.4) * .27 + noise(x * .55, depth * 3) * .13
      const light = (.35 + broad * .5 + cloud * .65) * Math.exp(-depth / 40)
      const sun = 34 * Math.exp(-(((x - 1006) / 370) ** 2)) * Math.exp(-depth / 65)
      const index = (y * 1312 + x) * 4
      pixels.data[index] = 10 + (48 + warm * 39) * light + sun
      pixels.data[index + 1] = 10 + (63 + warm * 3) * light + sun * .72
      pixels.data[index + 2] = 12 + (88 - warm * 26) * light + sun * .56
      pixels.data[index + 3] = Math.round(255 * Math.min(1, Math.max(0, (295 - y - 125) / 55)))
    }
  }
  paint.putImageData(pixels, 0, 0)
  ctx.drawImage(texture, 0, 125)
  ctx.restore()
  const rim = ctx.createLinearGradient(0, 0, 1312, 0)
  rim.addColorStop(0, '#3e6a8655')
  rim.addColorStop(0.4, '#a2b5cebb')
  rim.addColorStop(0.73, '#ffe0c9')
  rim.addColorStop(1, '#df9660a0')
  ctx.save()
  planetPath()
  ctx.strokeStyle = rim
  ctx.lineWidth = 1.4
  ctx.shadowColor = '#e8b48d'
  ctx.shadowBlur = 7
  ctx.stroke()
  ctx.restore()
  const sunlight = ctx.createRadialGradient(1006, 135, 0, 1006, 135, 51)
  sunlight.addColorStop(0, '#fff5dd')
  sunlight.addColorStop(0.04, '#fff1d2eb')
  sunlight.addColorStop(0.16, '#ffd3a78a')
  sunlight.addColorStop(1, '#c7884500')
  ctx.fillStyle = sunlight
  ctx.fillRect(955, 84, 102, 102)
  ctx.save()
  ctx.translate(1006, 135)
  for (let i = 0; i < 8; i++) {
    ctx.rotate(Math.PI / 4)
    const beam = ctx.createLinearGradient(0, 0, 0, 31)
    beam.addColorStop(0, '#ffeed77d')
    beam.addColorStop(1, '#ffe6c000')
    ctx.fillStyle = beam
    ctx.beginPath()
    ctx.moveTo(-1, 0)
    ctx.lineTo(0, 33)
    ctx.lineTo(1, 0)
    ctx.fill()
  }
  ctx.restore()
  ctx.beginPath()
  ctx.moveTo(-80, 1070)
  ctx.bezierCurveTo(500, 930, 940, 904, 1400, 1040)
  ctx.lineTo(1400, 1100)
  ctx.lineTo(-80, 1100)
  ctx.closePath()
  const lower = ctx.createLinearGradient(0, 948, 0, 1040)
  lower.addColorStop(0, '#75553e55')
  lower.addColorStop(0.16, '#2c201777')
  lower.addColorStop(1, '#090807')
  ctx.fillStyle = lower
  ctx.fill()
  ctx.beginPath()
  ctx.moveTo(-80, 1070)
  ctx.bezierCurveTo(500, 930, 940, 904, 1400, 1040)
  ctx.strokeStyle = '#d4a07336'
  ctx.lineWidth = 1.5
  ctx.stroke()
  ctx.save()
  ctx.beginPath()
  ctx.moveTo(-80, 1070)
  ctx.bezierCurveTo(500, 930, 940, 904, 1400, 1040)
  ctx.lineTo(1400, 1100); ctx.lineTo(-80, 1100); ctx.closePath(); ctx.clip()
  for (let index = 0; index < 7000; index++) {
    const x = random() * 1312, y = 950 + random() * 74
    ctx.fillStyle = `rgba(157,112,78,${random() * .065})`
    ctx.fillRect(x, y, 1 + random() * 7, .5 + random() * 2)
  }
  ctx.restore()
}
