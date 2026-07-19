import api from '@/api'

export function fetchKnowledgeDocuments(params) {
  return api.get('/knowledge/documents', { params })
}

export function uploadKnowledgeDocument({ file, title, category, tags }) {
  const formData = new FormData()
  formData.append('file', file)
  if (title) formData.append('title', title)
  if (category) formData.append('category', category)
  if (tags) formData.append('tags', tags)
  return api.post('/knowledge/documents/upload', formData)
}

export function fetchKnowledgeDocumentStatus(id) {
  return api.get(`/knowledge/documents/${id}/status`)
}

export function reprocessKnowledgeDocument(id) {
  return api.post(`/knowledge/documents/${id}/reprocess`)
}

export function deleteKnowledgeDocument(id) {
  return api.delete(`/knowledge/documents/${id}`)
}

export function askKnowledgeQuestion(payload) {
  return api.post('/knowledge/qa/ask', payload)
}

export function fetchKnowledgeRecords(params) {
  return api.get('/knowledge/qa/records', { params })
}

export function submitKnowledgeFeedback(payload) {
  return api.post('/knowledge/qa/feedback', payload)
}
