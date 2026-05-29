import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchDictList } from '@/api/dict'

export const useDictStore = defineStore('dict', () => {
  // 字典数据缓存
  const dictCache = ref({})

  /**
   * 获取字典数据（带缓存）
   */
  async function getDictData(type) {
    // 如果已缓存，直接返回
    if (dictCache.value[type]) {
      return dictCache.value[type]
    }

    // 否则请求并缓存
    try {
      const data = await fetchDictList(type)
      dictCache.value[type] = data
      return data
    } catch (error) {
      console.error(`获取字典数据失败: ${type}`, error)
      return []
    }
  }

  /**
   * 获取字典标签（根据值获取显示文本）
   */
  async function getDictLabel(type, value) {
    const dictData = await getDictData(type)
    const item = dictData.find(d => d.dictValue === value)
    return item?.dictLabel || value
  }

  /**
   * 获取字典映射对象 { value: label }
   */
  async function getDictMap(type) {
    const dictData = await getDictData(type)
    const map = {}
    dictData.forEach(item => {
      map[item.dictValue] = item.dictLabel
    })
    return map
  }

  /**
   * 清除缓存
   */
  function clearCache() {
    dictCache.value = {}
  }

  return {
    dictCache,
    getDictData,
    getDictLabel,
    getDictMap,
    clearCache
  }
})
