<template>
  <section><h3>渠道发送参数</h3><p>编辑时密码、Token 和 Secret 留空会保留原值。测试结果与生产投递记录分别查看。</p>
    <div class="form-row"><label v-for="field in fields" :key="field.key" class="form-group">{{ field.label }}<input v-if="field.type === 'number'" v-model.number="form[field.key]" type="number" class="form-control"><input v-else v-model="form[field.key]" :type="field.type || 'text'" :autocomplete="field.type === 'password' ? 'new-password' : 'off'" class="form-control"></label></div>
    <template v-if="form.channelType === 'sms'"><label class="form-group">备用短信网关（每行一个）<textarea v-model="smsUrls" class="form-control"></textarea></label><label><input v-model="form.sms_password_is_md5" type="checkbox">短信密码已为 MD5</label></template>
    <template v-if="form.channelType === 'email'"><label><input v-model="form.smtp_auth" type="checkbox">SMTP 认证</label><label><input v-model="form.smtp_use_tls" type="checkbox">STARTTLS</label><label><input v-model="form.smtp_use_ssl" type="checkbox">SSL</label></template>
    <template v-if="form.channelType === 'http'"><label class="form-group">HTTP URL<input v-model="form.webhook_url" type="url" required class="form-control"></label><label class="form-group">请求方法<select v-model="form.http_method" class="form-control"><option>POST</option><option>GET</option></select></label><label class="form-group">请求头（JSON 对象）<textarea v-model="form.http_headers" class="form-control" placeholder='{"Content-Type":"application/json"}'></textarea></label><label class="form-group">请求体模板<textarea v-model="form.http_body" class="form-control" placeholder='{"message":"${message}"}'></textarea></label></template>
    <label v-if="form.channelType === 'dingtalk'"><input v-model="form.dingtalk_at_all" type="checkbox">钉钉 @所有人</label><label v-if="form.channelType === 'wecom'"><input v-model="form.wecom_at_all" type="checkbox">企业微信 @所有人</label>
    <h3>代理设置</h3><label class="form-group">代理方式<select v-model="form.proxy_type" class="form-control"><option value="none">不使用代理</option><option value="http">HTTP</option><option value="socks5">SOCKS5</option></select></label>
    <div v-if="form.proxy_type && form.proxy_type !== 'none'" class="form-row"><label class="form-group">代理主机<input v-model="form.proxy_host" class="form-control" required></label><label class="form-group">代理端口<input v-model.number="form.proxy_port" type="number" min="1" max="65535" required class="form-control"></label><label class="form-group">代理用户名<input v-model="form.proxy_username" class="form-control"></label><label class="form-group">代理密码<input v-model="form.proxy_password" type="password" class="form-control"></label></div>
    <label><input v-model="form.enabled" type="checkbox">启用渠道</label>
  </section>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ form: { type: Object, required: true } })
const definitions = {
  sms: [['sms_password','短信密码','password'],['sms_api_token','短信 Token','password'],['sms_ext_code','短信扩展码'],['sms_rstype','返回格式']],
  email: [['smtp_user','SMTP 用户名'],['smtp_password','SMTP 密码','password'],['smtp_from','发件人'],['alert_cc','抄送人'],['smtp_ssl_trust','SSL 信任主机（可留空）']],
  dingtalk: [['dingtalk_secret','钉钉签名 Secret','password'],['dingtalk_at_mobiles','@手机号（逗号分隔）']],
  wecom: [['wecom_mentioned_list','@成员 ID'],['wecom_mentioned_mobiles','@手机号']]
}
const fields = computed(() => (definitions[props.form.channelType] || []).map(([key,label,type]) => ({key,label,type})))
const smsUrls = computed({get:() => (props.form.sms_api_urls || []).join('\n'),set: value => {props.form.sms_api_urls = value.split('\n').map(item=>item.trim()).filter(Boolean)}})
</script>
