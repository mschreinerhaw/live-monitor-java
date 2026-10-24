import { isTerminal, operationKey } from './model.js'

// Keep each request bound to the application that initiated it, even after selection changes.
export function createLogFollower(api, onData, onError, timers = globalThis) {
  let current = null, timer = null
  function stop() { current = null; if (timer !== null) timers.clearInterval(timer); timer = null }
  function start(appId) {
    stop()
    const scope = { appId, pending: null, busy: false }; current = scope
    async function tick() {
      if (current !== scope || scope.busy) return
      scope.busy = true
      try {
        const task = scope.pending ? await api.task(scope.pending.id) : await api.operate({ action: 'logs', resource_id: appId, parameters: {}, idempotency_key: operationKey() })
        if (current !== scope) return
        scope.pending = task
        if (isTerminal(task.status)) {
          if (task.status !== 'SUCCEEDED') throw new Error(task.message)
          onData(JSON.parse(task.result_json).logs, appId); scope.pending = null
        }
      } catch (error) { if (current === scope) { stop(); onError(error) } } finally { scope.busy = false }
    }
    timer = timers.setInterval(tick, 2500)
    return tick()
  }
  return { start, stop }
}
