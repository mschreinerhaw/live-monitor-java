export function parsePairs(text) {
  const result = {}
  for (const line of text.split('\n').filter(v => v.trim())) {
    const position = line.indexOf('=')
    if (position < 1) throw new Error('变量格式应为 NAME=value，每行一项')
    const key = line.slice(0, position).trim()
    if (Object.hasOwn(result, key)) throw new Error('变量名重复：' + key)
    result[key] = line.slice(position + 1)
  }
  return result
}
export function formatPairs(value = {}) { return Object.entries(value).map(([k, v]) => `${k}=${v}`).join('\n') }
export function isTerminal(status) { return ['SUCCEEDED', 'FAILED', 'INTERRUPTED'].includes(status) }
export function operationKey(provider = globalThis.crypto) {
  if (typeof provider.randomUUID === 'function') return provider.randomUUID()
  const bytes = new Uint8Array(16); provider.getRandomValues(bytes)
  return Array.from(bytes, n => n.toString(16).padStart(2, '0')).join('')
}
export function uniqueRuntimeContainers(items) {
  return [...new Map(items.filter(item => !String(item.container_id || '').startsWith('external:')).map(item => [item.container_id || `${item.host_id || ''}:${item.name || item.app_id}`, item])).values()]
}
