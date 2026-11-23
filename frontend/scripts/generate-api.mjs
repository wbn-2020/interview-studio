import { readFile, writeFile } from 'node:fs/promises'
import openapiTS, { astToString, COMMENT_HEADER } from 'openapi-typescript'

const source = new URL('../../docs/技术架构/openapi-s7.json', import.meta.url)
const output = new URL('../src/api/schema.d.ts', import.meta.url)

// 直接传文档对象，避免 Windows 中文路径被解析器当作已编码的文件名。
const document = JSON.parse(await readFile(source, 'utf8'))
const schema = await openapiTS(document, { cwd: new URL('.', source), silent: true })
await writeFile(output, COMMENT_HEADER + astToString(schema), 'utf8')
console.log('Generated src/api/schema.d.ts from openapi-s7.json')
