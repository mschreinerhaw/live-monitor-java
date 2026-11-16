<template>
  <section class="channel-advanced">
    <h3>渠道发送参数</h3>
    <p>编辑时密码、Token 和 Secret 留空会保留原值。测试结果与生产投递记录分别查看。</p>
    <div class="form-row">
      <label v-for="field in fields" :key="field.key" class="form-group"
        >{{ field.label
        }}<input
          v-if="field.type === 'number'"
          v-model.number="form[field.key]"
          type="number"
          class="form-control" /><input
          v-else
          v-model="form[field.key]"
          :type="field.type || 'text'"
          :autocomplete="field.type === 'password' ? 'new-password' : 'off'"
          class="form-control"
      /></label>
    </div>
    <template v-if="form.channelType === 'sms'"
      ><label class="form-group"
        >备用短信网关（每行一个）<textarea v-model="smsUrls" class="form-control"></textarea></label
      ><label class="option-switch"
        ><input v-model="form.sms_password_is_md5" type="checkbox" />短信密码已为 MD5</label
      ></template
    >
    <RecipientListEditor
      v-if="form.channelType === 'email'"
      v-model="form.alert_cc"
      label="抄送人"
      kind="email"
      placeholder="cc@example.com"
    />
    <template v-if="form.channelType === 'email'"
      ><div class="channel-switches">
        <label class="option-switch"
          ><input v-model="form.smtp_auth" type="checkbox" />SMTP 认证</label
        ><label class="option-switch"
          ><input v-model="form.smtp_use_tls" type="checkbox" />STARTTLS</label
        ><label class="option-switch"
          ><input v-model="form.smtp_use_ssl" type="checkbox" />SSL</label
        >
      </div></template
    >
    <template v-if="form.channelType === 'http'"
      ><label class="form-group"
        >HTTP URL<input
          v-model="form.webhook_url"
          type="url"
          required
          class="form-control" /></label
      ><label class="form-group"
        >请求方法<select v-model="form.http_method" class="form-control">
          <option>POST</option>
          <option>GET</option>
        </select></label
      ><label class="form-group"
        >请求头（JSON 对象）<textarea
          v-model="form.http_headers"
          class="form-control"
          placeholder='{"Content-Type":"application/json"}'
        ></textarea></label
      ><label class="form-group"
        >请求体模板<textarea
          v-model="form.http_body"
          class="form-control"
          placeholder='{"message":"${message}"}'
        ></textarea></label
    ></template>
    <RecipientListEditor
      v-if="form.channelType === 'dingtalk'"
      v-model="form.dingtalk_at_mobiles"
      label="钉钉提醒手机号"
      kind="phone"
      placeholder="13800000000"
    />
    <RecipientListEditor
      v-if="form.channelType === 'wecom'"
      v-model="form.wecom_mentioned_list"
      label="企业微信成员"
      kind="member"
      placeholder="zhangsan"
    />
    <RecipientListEditor
      v-if="form.channelType === 'wecom'"
      v-model="form.wecom_mentioned_mobiles"
      label="企业微信提醒手机号"
      kind="phone"
      placeholder="13800000000"
    />
    <label v-if="form.channelType === 'dingtalk'" class="option-switch"
      ><input v-model="form.dingtalk_at_all" type="checkbox" />钉钉 @所有人</label
    ><label v-if="form.channelType === 'wecom'" class="option-switch"
      ><input v-model="form.wecom_at_all" type="checkbox" />企业微信 @所有人</label
    >
    <h3>代理设置</h3>
    <label class="form-group"
      >代理方式<select v-model="form.proxy_type" class="form-control">
        <option value="none">不使用代理</option>
        <option value="http">HTTP</option>
        <option value="socks5">SOCKS5</option>
      </select></label
    >
    <div v-if="form.proxy_type && form.proxy_type !== 'none'" class="form-row">
      <label class="form-group"
        >代理主机<input v-model="form.proxy_host" class="form-control" required /></label
      ><label class="form-group"
        >代理端口<input
          v-model.number="form.proxy_port"
          type="number"
          min="1"
          max="65535"
          required
          class="form-control" /></label
      ><label class="form-group"
        >代理用户名<input v-model="form.proxy_username" class="form-control" /></label
      ><label class="form-group"
        >代理密码<input v-model="form.proxy_password" type="password" class="form-control"
      /></label>
    </div>
    <label class="option-switch"><input v-model="form.enabled" type="checkbox" />启用渠道</label>
  </section>
</template>
<script setup>
import { computed } from 'vue'
import RecipientListEditor from './RecipientListEditor.vue'
const props = defineProps({ form: { type: Object, required: true } })
const definitions = {
  sms: [
    ['sms_password', '短信密码', 'password'],
    ['sms_api_token', '短信 Token', 'password'],
    ['sms_ext_code', '短信扩展码'],
    ['sms_rstype', '返回格式'],
  ],
  email: [
    ['smtp_user', 'SMTP 用户名'],
    ['smtp_password', 'SMTP 密码', 'password'],
    ['smtp_from', '发件人'],
    ['smtp_ssl_trust', 'SSL 信任主机（可留空）'],
  ],
  dingtalk: [['dingtalk_secret', '钉钉签名 Secret', 'password']],
  wecom: [],
}
const fields = computed(() =>
  (definitions[props.form.channelType] || []).map(([key, label, type]) => ({ key, label, type }))
)
const smsUrls = computed({
  get: () => (props.form.sms_api_urls || []).join('\n'),
  set: (value) => {
    props.form.sms_api_urls = value
      .split('\n')
      .map((item) => item.trim())
      .filter(Boolean)
  },
})
</script>

<style scoped>
.channel-advanced {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}
.channel-advanced h3 {
  font-size: 14px;
  color: #284865;
  border-top: 1px solid #e3ebf5;
  padding-top: 18px;
  margin: 4px 0 0;
}
.channel-advanced > p {
  font-size: 11px;
  color: #7d8fa0;
  line-height: 1.6;
  margin: -10px 0 0;
}
.channel-advanced .form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.channel-advanced .form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  font-size: 12px;
  margin: 0;
}
.channel-advanced .form-control {
  width: 100%;
  box-sizing: border-box;
}
.channel-switches {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 12px 14px;
  background: #f5f9fe;
  border: 1px solid #e1eaf5;
  border-radius: 7px;
}
.option-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #4c6176;
  cursor: pointer;
}
.channel-advanced input[type='checkbox'] {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: #0284c7;
  flex: none;
}
@media (max-width: 600px) {
  .channel-advanced .form-row {
    grid-template-columns: 1fr;
  }
  .channel-switches {
    gap: 14px 20px;
  }
}
</style>
