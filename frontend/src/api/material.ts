import type { Schema } from './client'

export type MaterialKind = Schema['MaterialKind']
export type AttachmentState = Schema['MaterialAttachmentState']
export type UploadState = Schema['MaterialUploadState']
export type ParseState = Schema['MaterialRunState']
export type MaterialStatus = Schema['MaterialStatus']
export type MaterialUpload = Schema['MaterialUpload']
export type MaterialRun = Schema['MaterialParseRun']
export type AttachmentSummary = Schema['MaterialAttachmentSummary']
export type Attachment = Schema['MaterialAttachment']
export type AttachmentPage = Schema['MaterialAttachmentPage']
export type MaterialRunPage = Schema['MaterialRunPage']
export type MaterialSegment = Schema['MaterialSegment']
export type MaterialSegmentPage = Schema['MaterialSegmentPage']
export type MaterialExcerpt = Schema['MaterialExcerpt']
export type MaterialExcerptPage = Schema['MaterialExcerptPage']

export const materialKindLabels: Record<MaterialKind, string> = { PDF: 'PDF 文档', DOCX: 'Word 文档', PNG: 'PNG 图片', JPEG: 'JPEG 图片' }
export const attachmentLabels: Record<AttachmentState, string> = { RESERVED: '尚未上传完成', READY: '原件已保存' }
export const uploadLabels: Record<UploadState, string> = { RESERVED: '等待传输', RECEIVING: '正在接收', SUCCEEDED: '传输成功', FAILED: '传输失败', INTERRUPTED: '传输中断' }
export const parseLabels: Record<ParseState, string> = { QUEUED: '排队中', RUNNING: '处理中', SUCCEEDED: '处理完成', FAILED: '处理失败', UNSUPPORTED: '不支持此内容', INTERRUPTED: '处理中断' }
export const maxLocalFileBytes = 20 * 1024 * 1024
export function bytesLabel(bytes: number) { return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MiB` : bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${bytes} B` }
export function materialFileKind(name: string): MaterialKind | undefined {
  const extension = name.split('.').pop()?.toLowerCase()
  return extension === 'pdf' ? 'PDF' : extension === 'docx' ? 'DOCX' : extension === 'png' ? 'PNG' : extension === 'jpg' || extension === 'jpeg' ? 'JPEG' : undefined
}
export function materialContentUrl(id: string) { return `/api/v1/materials/attachments/${encodeURIComponent(id)}/content` }
export function materialPreviewUrl(id: string) { return `/api/v1/materials/runs/${encodeURIComponent(id)}/preview` }
