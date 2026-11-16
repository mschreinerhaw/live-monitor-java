export const splitRecipients = value => [...new Set(String(value || '').split(/[,;，；\s]+/).map(v => v.trim()).filter(Boolean))]

const fields = {
  email: [['alert_email', '邮箱'], ['alert_cc', '抄送邮箱']],
  sms: [['alert_mobile', '手机号']],
  dingtalk: [['dingtalk_at_mobiles', '钉钉手机号']],
  wecom: [['wecom_mentioned_list', '企微成员'], ['wecom_mentioned_mobiles', '企微手机号']],
}

export function channelRecipients(channel) {
  return (fields[String(channel.channelType || channel.channel_type || '').toLowerCase()] || []).flatMap(([field, type]) =>
    splitRecipients(channel[field]).map(value => ({ id: `${channel.id}:${field}:${value}`, channelId: channel.id, field, type, value }))
  )
}

export function groupChannelIds(group) {
  return (group.channel_ids || group.channels?.map(c => c.id) || []).map(String)
}

export function projectRecipients(project, groups, channels, book = []) {
  const rowsForIds = ids => book.filter(c=>ids.map(String).includes(String(c.id))).flatMap(c=>[['email','邮箱'],['mobile','手机号'],['wecom_user','企微成员']].filter(([field])=>c[field]).map(([field,type])=>({id:`contact:${c.id}:${field}`,name:c.name,type,value:c[field]+(c.enabled?'':'（已停用）')})))
  if(project.contact_ids?.length)return rowsForIds(project.contact_ids)
  const ids = new Set((project.alert_group_ids || []).map(String)), result=[]
  for(const group of groups.filter(group=>ids.has(String(group.id)))) {
    if(group.contact_ids?.length)result.push(...rowsForIds(group.contact_ids))
    else {const channelIds=new Set(groupChannelIds(group));result.push(...channels.filter(channel=>channelIds.has(String(channel.id))).flatMap(channelRecipients))}
  }
  return [...new Map(result.map(row=>[row.id,row])).values()]
}
