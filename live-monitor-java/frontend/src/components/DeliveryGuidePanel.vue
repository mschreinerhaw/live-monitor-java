<template>
  <section class="ops-panel delivery-guide">
    <h2>部署交付向导</h2><p>按实际采集和任务结果检查交付进度。保存配置后，还需要执行连接检测、校验与发布。</p>
    <ol><li v-for="step in steps" :key="step.key"><span class="ops-badge" :class="{ warning: !step.done }">{{ step.done ? '已验证' : '待完成' }}</span><div><h3>{{ step.title }}</h3><p>{{ step.detail }}</p></div><router-link :to="'/ops/' + step.route">{{ step.action }} →</router-link></li></ol>
    <details><summary>首次交付与运行要求</summary><p>将控制面部署到常驻主机，持久保存数据库、加密身份及备份目录。通过主机 IP 或 HTTPS 域名访问，服务器仅开放平台端口和受限 SSH Agent。</p><p>先在原有“主机维护”登记主机及加密凭据，再在服务器管理关联主机、公钥及环境。目标机安装固定命令 Agent 并配置镜像来源、挂载目录、发布包与 Secret 引用白名单。前端不持有 Docker 凭据。</p><p>单容器使用应用部署表单；多服务应用使用批准发布包及 SHA256。先校验，再确认维护窗口与数据兼容性后发布。依赖数据恢复与应用镜像回滚分别验收。</p><p>配置告警规则与人员授权后，在隔离应用执行故障恢复、人工停机和备份恢复演练。生产实例不自动注入故障。页面显示“待完成”的项不能作为交付已通过。</p></details>
  </section>
</template>
<script setup>
import { computed } from 'vue'
import { deliverySteps } from '../ops/delivery'
const props = defineProps({ state: Object, monitoring: Object, tasks: Array })
const steps = computed(() => deliverySteps(props.state, props.monitoring, props.tasks))
</script>
<style scoped>.delivery-guide ol{list-style:none;padding:0}.delivery-guide li{display:flex;align-items:center;gap:16px;border-bottom:1px solid var(--border-color);padding:16px 0}.delivery-guide li div{flex:1;min-width:0}.delivery-guide li p{margin:5px 0;font-size:12px}.delivery-guide a{font-size:12px;white-space:nowrap}@media(max-width:700px){.delivery-guide li{flex-wrap:wrap}.delivery-guide li div{flex-basis:65%}}</style>
