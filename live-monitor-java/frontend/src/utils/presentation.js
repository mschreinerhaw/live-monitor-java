import DOMPurify from 'dompurify'

export const deliveryLabels = {
  PENDING: '排队中', SENDING: '发送中', FAILED: '等待重试', SUCCESS: '渠道已受理', FINAL_FAILED: '最终失败',
  pending: '排队中', success: '渠道已受理', failed: '发送失败'
}
export const deliveryLabel = status => deliveryLabels[status] || status || '未知状态'
export const deliveryTone = status => ['SUCCESS', 'success'].includes(status) ? 'badge-up' : ['FINAL_FAILED', 'failed'].includes(status) ? 'badge-down' : status === 'FAILED' ? 'badge-warn' : 'badge-info'
export function plainMessage(content) {
  const template = document.createElement('template')
  template.innerHTML = DOMPurify.sanitize(String(content || ''), { ALLOWED_TAGS: [], ALLOWED_ATTR: [] })
  return (template.content.textContent || '').replace(/\s+/g, ' ').trim()
}
