import api from '@/api'

export function fetchKnowledgeDocuments(params) {
  return api.get('/knowledge/documents', { params })
}

export function uploadKnowledgeDocument({ file, title, category, tags, visibility, allowedUserIds = [] }) {
  const formData = new FormData()
  formData.append('file', file)
  if (title) formData.append('title', title)
  if (category) formData.append('category', category)
  if (tags) formData.append('tags', tags)
  if (visibility) formData.append('visibility', visibility)
  allowedUserIds.forEach(userId => formData.append('allowedUserIds', userId))
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

export function fetchKnowledgeSessions() {
  return api.get('/knowledge/qa/sessions')
}

export function deleteKnowledgeSession(sessionId) {
  return api.delete(`/knowledge/qa/sessions/${sessionId}`)
}

export function submitKnowledgeFeedback(payload) {
  return api.post('/knowledge/qa/feedback', payload)
}

export function fetchMyReservationAssistantContext() {
  return api.get('/knowledge/tools/reservation-context/me')
}

export function fetchAgentRuns(params) {
  return api.get('/knowledge/agent-runs', { params })
}

export function fetchAgentRunSteps(traceId) {
  return api.get(`/knowledge/agent-runs/${traceId}/steps`)
}

export function findAvailableResourceSlots(params) {
  return api.get('/knowledge/tools/resource-availability', { params })
}

export function previewReservationCancellation(reservationId) {
  return api.get(`/knowledge/tools/cancellation-preview/${reservationId}`)
}
