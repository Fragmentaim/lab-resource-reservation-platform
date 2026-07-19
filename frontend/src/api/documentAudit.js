import api from '@/api'

/** 仅管理员可查看的知识库文档处理审计时间线。 */
export function fetchDocumentProcessEvents(id) {
  return api.get(`/knowledge/documents/${id}/process-events`)
}
