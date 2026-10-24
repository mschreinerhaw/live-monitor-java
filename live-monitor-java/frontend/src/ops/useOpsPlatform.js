import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { opsApi } from '../api/ops'
import { store } from '../store'
import { opsSections } from './sections'
import { parsePairs, formatPairs, isTerminal, operationKey } from './model'
import { createLogFollower } from './liveLogs'
export function useOpsPlatform(route) {
  const state = reactive({ host: [], registry: [], application: [], snapshot: [], backup: [], version: [], alert: [], alert_rule: [], grant: [] })
  const capabilities = ref({ host_actions: {}, roles: {} }), tasks = ref([]), audit = ref([]), monitorHosts = ref([])
  const loading = ref(false), busy = ref(false), error = ref(''), activeTask = ref(null), imageResults = ref([]), registryResults = ref(null)
  const liveLogs = ref(''), liveLogApp = ref(null), liveLogTime = ref(null)
  const hostDraft = reactive({ id: '', name: '', monitor_host_id: '', environment: '', group: '', ssh_host_key: '' })
  const registryDraft = reactive({ id: '', name: '', address: '', credential_ref: '' })
  const appDraft = reactive({ health: { command: [] }, ports: [], mounts: [], environment_variables: {}, secret_refs: {} })
  const grantDraft = reactive({ id: '', user_id: '', host_id: '*', environment: '*', role: '', actions: '', enabled: true, ops_only: true })
  const alertRuleDraft = reactive({ id: '', host_id: '', app_id: '', code: '*', severity: 'WARNING', enabled: true })
  const editor = reactive({ env: '', secrets: '', health: '[]', command: '[]' }), imageDraft = reactive({ host_id: '', image: '', registry_id: '', repository: '', tag: '' })
  let stream = null, refreshTimer = null
  const section = computed(() => route.params.section || 'overview')
  const title = computed(() => opsSections.find(s => s.key === section.value)?.label || '容器运维')
  const containers = computed(() => state.snapshot.flatMap(s => (s.data?.containers || []).map(c => ({ ...c, host_id: s.host_id, environment: s.environment, observed_at: s.observed_at }))))
  const overview = computed(() => ({ hosts: state.host.length, running: containers.value.filter(c => c.status === 'running').length,
    abnormal: containers.value.filter(c => !c.healthy && !c.manual_stop).length, versions: state.version.length,
    alerts: state.alert.filter(a => a.status === 'OPEN').length }))
  const permitted = (host, action) => (capabilities.value.host_actions?.[host] || []).includes(action)
  const time = value => value ? new Date(Number(value)).toLocaleString() : '—'
  const age = value => !value || Date.now() - Number(value) > 90000
  const pretty = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2)
  async function guarded(fn) {
    busy.value = true; error.value = ''
    try { return await fn() } catch (e) { error.value = e.message; store.showToast(e.message, 'error') } finally { busy.value = false }
  }
  async function refresh() {
    loading.value = true
    try {
      capabilities.value = await opsApi.capabilities()
      const kinds = Object.keys(state).filter(k => k !== 'grant' || capabilities.value.administrator)
      const results = await Promise.allSettled(kinds.map(k => opsApi.list(k)))
      results.forEach((r, i) => { if (r.status === 'fulfilled') state[kinds[i]] = r.value; else error.value = r.reason.message })
      tasks.value = await opsApi.tasks()
      if (capabilities.value.administrator) {
        const results = await Promise.allSettled([opsApi.monitorHosts(), opsApi.audit()])
        if (results[0].status === 'fulfilled') monitorHosts.value = results[0].value
        if (results[1].status === 'fulfilled') audit.value = results[1].value
      }
    } catch (e) { error.value = e.message } finally { loading.value = false }
  }
  async function resetApp() { Object.keys(appDraft).forEach(k => delete appDraft[k]); Object.assign(appDraft, await opsApi.applicationTemplate()); Object.assign(editor, { env: '', secrets: '', health: '[]', command: '[]' }) }
  function editApp(app) { Object.keys(appDraft).forEach(k => delete appDraft[k]); Object.assign(appDraft, JSON.parse(JSON.stringify(app))); Object.assign(editor, { env: formatPairs(app.environment_variables), secrets: formatPairs(app.secret_refs), health: JSON.stringify(app.health.command), command: JSON.stringify(app.command || []) }) }
  function selectHost() { appDraft.environment = state.host.find(h => h.id === appDraft.host_id)?.environment || '' }
  async function save(kind, value) { return guarded(async () => {
    const payload = { ...value }
    if (kind === 'grant' && !payload.ops_only && state.grant.some(g => g.id === payload.id && g.ops_only)) {
      const confirmation = `ALLOW_LEGACY:${payload.user_id}`
      if (window.prompt(`此操作恢复旧监控接口访问权限，请输入 ${confirmation}`) !== confirmation) return
      payload.confirm_legacy_access = confirmation
    }
    await opsApi.save(kind, payload); await refresh(); store.showToast('已保存', 'success')
  }) }
  async function saveApp() { return guarded(async () => {
    const spec = JSON.parse(JSON.stringify(appDraft)); spec.environment_variables = parsePairs(editor.env); spec.secret_refs = parsePairs(editor.secrets); spec.health.command = JSON.parse(editor.health); spec.command = JSON.parse(editor.command)
    const saved = await opsApi.save('application', spec); editApp(saved); await refresh(); store.showToast('部署配置已保存，请先执行发布前校验', 'success')
  }) }
  function updateTask(value) {
    activeTask.value = value
    if (isTerminal(value.status)) {
      stream?.close(); stream = null
      if (value.status === 'SUCCEEDED' && value.action === 'images') imageResults.value = JSON.parse(value.result_json || '{}').images || []
      if (value.status === 'SUCCEEDED' && value.action === 'registry_catalog') registryResults.value = JSON.parse(value.result_json || '{}')
      if (value.status === 'SUCCEEDED' && value.action === 'resolve_image') imageDraft.image = JSON.parse(value.result_json || '{}').image
      refresh()
    }
  }
  function follow(task) {
    stream?.close(); activeTask.value = task
    if (isTerminal(task.status)) { updateTask(task); return }
    stream = new EventSource('/api/ops/tasks/' + encodeURIComponent(task.id) + '/events')
    stream.addEventListener('task', event => updateTask(JSON.parse(event.data)))
    stream.onerror = () => { stream?.close(); stream = null }
  }
  async function operate(action, resource, parameters = {}) { return guarded(async () => {
    if (!resource) throw new Error('应用或服务器配置不可用，请刷新后确认其纳管状态')
    const request = { action, resource_id: resource.id, parameters, idempotency_key: operationKey() }
    if (!['probe', 'images', 'logs', 'validate', 'registry_catalog', 'resolve_image'].includes(action)) {
      const expected = `${action}:${resource.id}:${resource.environment}`
      const value = window.prompt(`确认 ${action}：${resource.name || resource.id}（${resource.environment}）\n请输入 ${expected}`)
      if (value === null) return
      request.confirmation = value
    }
    const task = await opsApi.operate(request); follow(task); await refresh()
  }) }
  const imageOperation = action => operate(action, state.host.find(h => h.id === imageDraft.host_id), { image: imageDraft.image, registry_id: imageDraft.registry_id, repository: imageDraft.repository, tag: imageDraft.tag })
  function deploy(app, action = 'deploy', parameters = {}) {
    const dataCompatible = app.data_kind === 'stateless' || window.confirm('确认已校验新旧版本的数据兼容性？平台不会回退数据结构，也不会删除原数据。')
    if (dataCompatible) return operate(action, app, { ...parameters, data_compatible: dataCompatible })
  }
  function backupOperation(action, backup) { const app = state.application.find(a => a.id === backup.app_id); return operate(action, app, { backup_id: backup.id }) }
  const acknowledge = id => guarded(async () => { await opsApi.acknowledge(id); await refresh() })
  function roleChanged() { grantDraft.actions = (capabilities.value.roles[grantDraft.role] || []).join(',') }
  function resetDraft(draft) { Object.keys(draft).forEach(k => delete draft[k]) }
  const logFollower = createLogFollower(opsApi, (logs, appId) => {
    if (liveLogApp.value?.id === appId) { liveLogs.value = logs; liveLogTime.value = Date.now() }
  }, e => { error.value = e.message; liveLogApp.value = null })
  function stopLiveLogs() { logFollower.stop(); liveLogApp.value = null }
  function startLiveLogs(app) {
    stopLiveLogs(); liveLogApp.value = app; liveLogs.value = ''; liveLogTime.value = null
    logFollower.start(app.id)
  }
  watch(section, () => { if (section.value !== 'containers') stopLiveLogs(); refresh() })
  onMounted(async () => {
    await refresh(); await guarded(resetApp)
    refreshTimer = setInterval(async () => {
      if (activeTask.value && !isTerminal(activeTask.value.status) && !stream) {
        try { updateTask(await opsApi.task(activeTask.value.id)) } catch (e) { error.value = e.message }
      }
      if (!busy.value && !loading.value) refresh()
    }, 10000)
  })
  onBeforeUnmount(() => { stream?.close(); clearInterval(refreshTimer); stopLiveLogs() })
  return { state, capabilities, tasks, audit, monitorHosts, loading, busy, error, activeTask, hostDraft, registryDraft, appDraft, grantDraft, alertRuleDraft, editor, imageDraft, imageResults, registryResults,
    section, title, containers, overview, permitted, time, age, pretty, refresh, resetApp, editApp, selectHost, save, saveApp, operate, imageOperation, deploy, backupOperation, acknowledge, roleChanged, follow,
    liveLogs, liveLogApp, liveLogTime, startLiveLogs, stopLiveLogs, resetDraft }
}
