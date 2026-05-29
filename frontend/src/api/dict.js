import api from '@/api'

/**
 * 根据字典类型获取字典数据列表
 */
export function fetchDictList(type) {
  return api.get('/dict/data/list', { params: { type } })
}

/**
 * 获取所有字典类型列表
 */
export function fetchDictTypeList() {
  return api.get('/dict/type/list')
}

/**
 * 分页查询字典数据
 */
export function fetchDictPage(params = {}) {
  return api.get('/dict/data/page', { params })
}

/**
 * 获取字典数据详情
 */
export function fetchDictById(id) {
  return api.get(`/dict/data/${id}`)
}

/**
 * 添加字典数据
 */
export function createDict(data) {
  return api.post('/dict/data', data)
}

/**
 * 更新字典数据
 */
export function updateDict(data) {
  return api.put('/dict/data', data)
}

/**
 * 删除字典数据
 */
export function deleteDict(id) {
  return api.delete(`/dict/data/${id}`)
}
