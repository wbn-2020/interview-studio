import { isTextImportPath } from './knowledgeTextPath'

export type ZipTextEntry = { fileName: string; sourcePath: string; buffer: ArrayBuffer }
type ArchiveEntry = {
  fileName: string; sourcePath: string; directory: boolean; name: Uint8Array<ArrayBuffer>
  version: number; flags: number; method: number; crc: number; compressed: number; size: number
  offset: number; data: number
}

const maxArchive = 8_388_608, maxEntry = 400_000, maxTotal = 8_000_000
const crcTable = Uint32Array.from({ length: 256 }, (_, index) => {
  let crc = index
  for (let bit = 0; bit < 8; bit++) crc = crc & 1 ? 0xedb88320 ^ crc >>> 1 : crc >>> 1
  return crc >>> 0
})
function invalid(message: string): never { throw new Error(`ZIP：${message}`) }
function current(stillCurrent: () => boolean) {
  if (!stillCurrent()) throw new Error('文件读取条件已变化，本次ZIP选择未保留，请重新选择。')
}
function checksum(bytes: Uint8Array, stillCurrent: () => boolean): number {
  let crc = 0xffffffff
  for (let index = 0; index < bytes.length; index++) {
    if (!(index & 0x3fff)) current(stillCurrent)
    crc = crcTable[(crc ^ bytes[index]!) & 0xff]! ^ crc >>> 8
  }
  return (crc ^ 0xffffffff) >>> 0
}

async function inflate(bytes: Uint8Array<ArrayBuffer>, expected: number, stillCurrent: () => boolean): Promise<ArrayBuffer> {
  let decompressor: DecompressionStream
  try { decompressor = new DecompressionStream('deflate-raw') }
  catch { invalid('当前浏览器不支持所需的原始Deflate解压。请在受信任工具中解压后使用文本目录选择。') }
  let position = 0, pulls = 0
  const input = new ReadableStream<Uint8Array<ArrayBuffer>>({
    async pull(controller) {
      current(stillCurrent)
      if (position === bytes.length) { controller.close(); return }
      // 限制每次送入原生解压器的数据，并让出事件循环以接收隐藏或权限变化。
      if (++pulls % 32 === 0) {
        await new Promise<void>(resolve => setTimeout(resolve, 0))
        current(stillCurrent)
      }
      const end = Math.min(position + 1024, bytes.length)
      controller.enqueue(bytes.subarray(position, end)); position = end
    },
  })
  const reader = input.pipeThrough(decompressor).getReader(), output = new Uint8Array(expected)
  let written = 0, complete = false
  try {
    for (;;) {
      current(stillCurrent)
      const result = await reader.read()
      current(stillCurrent)
      if (result.done) break
      if (result.value.byteLength > expected - written) invalid('实际解压大小超过清单声明或允许范围，整包未保留。')
      output.set(result.value, written); written += result.value.byteLength
    }
    if (written !== expected) invalid('实际解压大小与清单不一致，整包未保留。')
    complete = true
    return output.buffer
  } catch (error) {
    if (error instanceof TypeError) invalid('Deflate数据损坏、不完整或带多余尾部；可另行解压后使用目录入口。')
    throw error
  } finally {
    if (!complete) { try { await reader.cancel() } catch { /* 保留首个解析错误。 */ } }
    reader.releaseLock()
  }
}

export async function readTextZip(buffer: ArrayBuffer, stillCurrent: () => boolean): Promise<ZipTextEntry[]> {
  current(stillCurrent)
  if (buffer.byteLength < 22 || buffer.byteLength > maxArchive) invalid('压缩包须为1至8388608字节的普通单卷ZIP。')
  const bytes = new Uint8Array(buffer), view = new DataView(buffer)
  const u16 = (offset: number) => view.getUint16(offset, true)
  const u32 = (offset: number) => view.getUint32(offset, true)
  function range(offset: number, length: number, limit = bytes.length) {
    if (offset < 0 || length < 0 || offset + length > limit || limit > bytes.length) invalid('条目范围越界或ZIP结构不完整。')
  }
  function extra(offset: number, length: number) {
    const end = offset + length, ids = new Set<number>()
    range(offset, length)
    while (offset < end) {
      range(offset, 4, end)
      const id = u16(offset), size = u16(offset + 2)
      if (ids.has(id)) invalid('重复扩展字段不能安全解释。')
      ids.add(id); offset += 4; range(offset, size, end)
      if (id === 0x0001 || id === 0x9901 || id === 0x0017 || id === 0x7075 || id === 0x0008) {
        invalid('不支持ZIP64、加密或替代路径／编码扩展；请解压后使用文本目录入口。')
      }
      offset += size
    }
  }
  let eocd = -1
  for (let offset = bytes.length - 22; offset >= Math.max(0, bytes.length - 65_557); offset--) {
    if (!(offset & 0x3ff)) current(stillCurrent)
    if (u32(offset) !== 0x06054b50 || offset + 22 + u16(offset + 20) !== bytes.length) continue
    if (eocd !== -1) invalid('结束记录有歧义，不能安全选择清单。')
    eocd = offset
  }
  if (eocd === -1) invalid('未找到完整ZIP结束记录，不支持分卷或附加尾部。')
  const count = u16(eocd + 10), centralSize = u32(eocd + 12), centralOffset = u32(eocd + 16)
  if (u16(eocd + 4) !== 0 || u16(eocd + 6) !== 0 || u16(eocd + 8) !== count
    || count < 1 || count > 128 || centralSize === 0xffffffff || centralOffset === 0xffffffff) {
    invalid('只支持单卷、最多128条中央目录记录的普通ZIP，不支持ZIP64。')
  }
  range(centralOffset, centralSize, eocd)
  if (centralOffset + centralSize !== eocd) invalid('中央目录与结束记录之间存在不支持的数据。')
  const entries: ArchiveEntry[] = [], identities = new Map<string, boolean>()
  let cursor = centralOffset, total = 0, files = 0
  for (let index = 0; index < count; index++) {
    current(stillCurrent); range(cursor, 46, eocd)
    if (u32(cursor) !== 0x02014b50) invalid('中央目录记录不完整。')
    const creator = u16(cursor + 4) >>> 8, version = u16(cursor + 6), flags = u16(cursor + 8)
    const method = u16(cursor + 10), crc = u32(cursor + 16), compressed = u32(cursor + 20), size = u32(cursor + 24)
    const nameSize = u16(cursor + 28), extraSize = u16(cursor + 30), commentSize = u16(cursor + 32)
    const attributes = u32(cursor + 38), offset = u32(cursor + 42)
    if (version < 10 || version > 20 || flags & ~0x080e || method !== 0 && method !== 8
      || method === 0 && flags & 0x0006 || method === 8 && version < 20 || u16(cursor + 34) !== 0
      || compressed === 0xffffffff || size === 0xffffffff || offset === 0xffffffff) {
      invalid('不支持加密、分卷、ZIP64、特殊标志或该压缩方法／版本。')
    }
    range(cursor + 46, nameSize + extraSize + commentSize, eocd)
    if (!nameSize || nameSize > 2400) invalid('条目名称为空或过长。')
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameSize)
    if (!(flags & 0x0800) && name.some(value => value > 0x7f)) invalid('未声明UTF-8的非ASCII名称不做编码猜测。')
    let path: string
    try { path = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(name) }
    catch { invalid('条目名称不是有效UTF-8。') }
    const directory = path.endsWith('/'), sourcePath = directory ? path.slice(0, -1) : path
    const fileName = sourcePath.slice(sourcePath.lastIndexOf('/') + 1)
    if (path.length > 600 || !isTextImportPath(sourcePath, fileName) || identities.has(sourcePath)) {
      invalid('条目路径无效、超出600字符／32段或完全重复。')
    }
    // 只解释已知平台的普通文件／目录属性，避免将符号链接等特殊条目当成笔记。
    const kind = attributes >>> 16 & 0xf000, dos = attributes & 0xff
    if (![0, 3, 10, 19].includes(creator) || kind !== 0 && kind !== 0x8000 && kind !== 0x4000
      || attributes & 0x0448 || kind === 0x8000 && directory || kind === 0x4000 && !directory
      || Boolean(dos & 0x10) && !directory || directory && version < 20) {
      invalid('只支持普通文件及目录，不支持符号链接、设备或特殊平台条目。')
    }
    extra(cursor + 46 + nameSize, extraSize)
    if (directory) {
      if (method !== 0 || size !== 0 || compressed !== 0 || crc !== 0) invalid('目录条目必须为空的Stored记录。')
    } else {
      if (++files > 20 || fileName.length > 200 || !/\.(md|markdown|txt)$/i.test(fileName)) {
        invalid('整包只能包含1至20份Markdown／TXT文本；附件或不支持文件不会静默跳过。')
      }
      if (size < 1 || size > maxEntry || compressed < 1 || size > compressed * 200 || (total += size) > maxTotal) {
        invalid('文件解压声明超过单份400000字节、总8000000字节或200倍比率限制。')
      }
      if (method === 0 && compressed !== size) invalid('Stored条目的压缩和解压大小不一致。')
    }
    identities.set(sourcePath, directory)
    entries.push({ fileName, sourcePath, directory, name, version, flags, method, crc, compressed, size, offset, data: 0 })
    cursor += 46 + nameSize + extraSize + commentSize
  }
  if (cursor !== eocd || !files) invalid('中央目录大小／计数不符，或没有受支持文本文件。')
  for (const entry of entries) {
    const segments = entry.sourcePath.split('/')
    for (let depth = 1; depth < segments.length; depth++) {
      if (identities.get(segments.slice(0, depth).join('/')) === false) invalid('同一路径同时被当成文件及上级目录。')
    }
  }
  const ordered = [...entries].sort((left, right) => left.offset - right.offset)
  cursor = 0
  for (let index = 0; index < ordered.length; index++) {
    current(stillCurrent)
    const entry = ordered[index]!, end = ordered[index + 1]?.offset ?? centralOffset
    if (entry.offset !== cursor || end <= entry.offset) invalid('条目重叠、存在空洞或不支持的自解压前缀。')
    range(entry.offset, 30, end)
    if (u32(entry.offset) !== 0x04034b50 || u16(entry.offset + 4) !== entry.version
      || u16(entry.offset + 6) !== entry.flags || u16(entry.offset + 8) !== entry.method) invalid('局部头与中央目录不一致。')
    const nameSize = u16(entry.offset + 26), extraSize = u16(entry.offset + 28)
    range(entry.offset + 30, nameSize + extraSize, end)
    if (nameSize !== entry.name.length || entry.name.some((value, at) => value !== bytes[entry.offset + 30 + at])) {
      invalid('局部名称与中央目录不一致。')
    }
    extra(entry.offset + 30 + nameSize, extraSize)
    const crc = u32(entry.offset + 14), compressed = u32(entry.offset + 18), size = u32(entry.offset + 22)
    const descriptor = Boolean(entry.flags & 0x0008)
    if (descriptor ? crc !== 0 || compressed !== 0 || size !== 0
      : crc !== entry.crc || compressed !== entry.compressed || size !== entry.size) {
      invalid('局部CRC或大小与标准描述符／中央目录不一致。')
    }
    entry.data = entry.offset + 30 + nameSize + extraSize
    range(entry.data, entry.compressed, end)
    const dataEnd = entry.data + entry.compressed
    if (descriptor) {
      // 无签名描述符的CRC也可能等于签名；按完整边界及元数据匹配，不能只看首DWORD。
      let matches = 0
      if (dataEnd + 12 === end && u32(dataEnd) === entry.crc && u32(dataEnd + 4) === entry.compressed
        && u32(dataEnd + 8) === entry.size) matches++
      if (dataEnd + 16 === end && u32(dataEnd) === 0x08074b50 && u32(dataEnd + 4) === entry.crc
        && u32(dataEnd + 8) === entry.compressed && u32(dataEnd + 12) === entry.size) matches++
      if (matches !== 1) invalid('数据描述符缺失、有歧义或与中央目录不一致。')
    } else if (dataEnd !== end) invalid('条目尾部存在不支持的数据或空洞。')
    cursor = end
  }
  if (cursor !== centralOffset) invalid('局部条目与中央目录范围不连续。')
  const result: ZipTextEntry[] = []
  for (const entry of entries) {
    current(stillCurrent)
    const compressed = bytes.subarray(entry.data, entry.data + entry.compressed)
    const output = entry.method === 0 ? compressed.slice().buffer : await inflate(compressed, entry.size, stillCurrent)
    current(stillCurrent)
    if (output.byteLength !== entry.size || checksum(new Uint8Array(output), stillCurrent) !== entry.crc) {
      invalid('实际解压大小或CRC32校验失败，整包未保留。')
    }
    if (!entry.directory) result.push({ fileName: entry.fileName, sourcePath: entry.sourcePath, buffer: output })
  }
  current(stillCurrent)
  return result
}
