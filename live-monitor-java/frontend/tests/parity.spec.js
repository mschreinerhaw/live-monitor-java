const { test, expect } = require('@playwright/test')
const groups = [{ id: 1, group_name: '邮件组', enabled: true }, { id: 2, group_name: '短信组', enabled: true }]
async function backend(page, override = () => undefined) {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    const custom = await override(request, path, url)
    if (custom !== undefined) { await route.fulfill({ status: typeof custom.status === 'number' ? custom.status : 200, json: custom.body === undefined ? custom : custom.body }); return }
    let body = []
    if (path === '/api/auth/me') body = { authenticated: true, user_id: 'admin', name: '管理员', admin: true }
    if (path === '/api/alert-groups') body = groups
    if (path === '/api/dashboard') body = { summary: {} }
    if (path === '/api/system-metrics') body = {}
    if (path === '/api/alert-notifications' || path === '/api/external/messages/audit') body = { items: [], total: 0, today_total: 0, today_success: 0, today_failed: 0 }
    await route.fulfill({ json: body })
  })
  return errors
}

test('unauthenticated session cannot open protected page', async ({ page }) => {
  await backend(page, (_, path) => path === '/api/auth/me' ? { authenticated: false } : undefined)
  await page.goto('/#/alerts/tasks')
  await expect(page).toHaveURL(/#\/login/)
})

test('durable task details and manual retry show real results', async ({ page }) => {
  let retried = false
  const errors = await backend(page, (request, path) => {
    if (path.endsWith('/retry')) { retried = true; return { task_id: 'new-task' } }
    if (path.endsWith('/attempts')) return [{ id: 'attempt', delivery_status: 'FINAL_FAILED', error_message: 'gateway unavailable', created_at: '2026-11-13' }]
    if (path === '/api/alert-tasks') return { total: 1, counts: [{ delivery_status: 'FINAL_FAILED', total: 1 }], items: [{ id: 'failed-task', event_id: 9, service_name: '支付接口', channel_name: '邮件', delivery_status: retried ? 'PENDING' : 'FINAL_FAILED', attempt_count: 6, last_error: 'gateway unavailable', alert_content: '支付接口 DOWN' }] }
  })
  await page.goto('/#/alerts/tasks')
  await expect(page.getByText('支付接口 / 邮件')).toBeVisible()
  await page.getByRole('button', { name: '发送历史' }).click()
  await expect(page.getByText('支付接口 DOWN')).toBeVisible()
  await page.getByRole('button', { name: '手动重试' }).click()
  await expect(page.getByRole('cell', { name: 'PENDING', exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test('service form persists multiple groups and complete alert rules', async ({ page }) => {
  let saved
  const errors = await backend(page, (request, path) => {
    if (path === '/api/services' && request.method() === 'POST') { saved = request.postDataJSON(); return { id: 10, ...saved } }
  })
  await page.goto('/#/services/new')
  await page.locator('input[maxlength="120"]').fill('支付探测')
  await page.locator('input[type="url"]').first().fill('http://localhost/health')
  await page.getByLabel('邮件组', { exact: true }).check()
  await page.getByLabel('短信组', { exact: true }).check()
  await page.getByLabel('恢复 UP 连续成功次数').fill('3')
  await page.getByLabel('UNKNOWN 连续异常次数').fill('2')
  await page.getByLabel('持续异常提醒间隔').fill('60')
  await page.getByRole('button', { name: '保存监控服务', exact: true }).click()
  await expect.poll(() => saved).toBeTruthy()
  expect(saved.alert_group_ids).toEqual([1, 2]); expect(saved.service_recover_successes).toBe(3)
  expect(saved.unknown_consecutive_failures).toBe(2); expect(saved.alert_repeat_interval_seconds).toBe(60)
  expect(errors).toEqual([])
})

test('host form submits SSH names and detail supports process creation', async ({ page }) => {
  let saved, process
  const errors = await backend(page, (request, path) => {
    if (path === '/api/hosts' && request.method() === 'POST') { saved = request.postDataJSON(); return { id: 1, ...saved } }
    if (path === '/api/hosts/1') return { id: 1, host_name: '应用主机', ip: '127.0.0.1' }
    if (path === '/api/hosts/1/metrics') return { cpu_usage_percent: 12, disk_metrics: [] }
    if (path === '/api/hosts/1/processes' && request.method() === 'POST') { process = request.postDataJSON(); return { id: 5, ...process } }
  })
  await page.goto('/#/hosts')
  await page.getByRole('button', { name: /新增主机|添加主机|新建主机/ }).first().click()
  await page.locator('input[placeholder="例如：app-server-01"]').fill('应用主机')
  await page.locator('input[placeholder="192.168.1.10"]').fill('127.0.0.1')
  await page.getByRole('button', { name: '保存主机', exact: true }).click()
  await expect.poll(() => saved).toBeTruthy()
  expect(saved.ssh_port).toBe(22); expect(saved.ssh_user).toBe('root'); expect(saved).not.toHaveProperty('username')
  await page.goto('/#/hosts/1')
  await page.getByLabel('进程名称', { exact: true }).fill('worker')
  await page.getByLabel('检查命令', { exact: true }).fill('pgrep -f worker')
  await page.getByRole('button', { name: '保存进程', exact: true }).click()
  await expect.poll(() => process).toBeTruthy(); expect(process.check_command).toBe('pgrep -f worker')
  expect(errors).toEqual([])
})

test('external project bindings persist and account password uses real endpoint', async ({ page }) => {
  let project, password
  const errors = await backend(page, (request, path) => {
    if (path === '/api/external-projects' && request.method() === 'POST') { project = request.postDataJSON(); return { id: 4, ...project } }
    if (path === '/api/admin/password') { password = request.postDataJSON(); return { success: true } }
  })
  await page.goto('/#/external-projects')
  await page.getByRole('button', { name: '新建外部项目', exact: true }).click()
  await page.getByLabel('项目名称', { exact: true }).fill('订单系统')
  await page.getByLabel('邮件组', { exact: true }).check()
  await page.getByRole('button', { name: '保存项目' }).click()
  await expect.poll(() => project).toBeTruthy(); expect(project.alert_group_ids).toEqual([1])
  await page.goto('/#/account')
  await page.getByLabel('当前密码', { exact: true }).fill('current')
  await page.getByLabel('新密码', { exact: true }).fill('updated-password')
  await page.getByLabel('确认新密码', { exact: true }).fill('updated-password')
  await page.getByRole('button', { name: '修改密码', exact: true }).click()
  await expect.poll(() => password).toBeTruthy(); expect(password.new_password).toBe('updated-password')
  expect(errors).toEqual([])
})

test('HTTP 200 business failure never displays a successful test notification', async ({ page }) => {
  await backend(page, (_, path) => {
    if (path === '/api/services') return [{ id: 1, service_name: '支付接口', alert_group_ids: [1], service_alert_enabled: true }]
    if (path === '/api/services/1/alert-test') return { success: false, error: 'SMTP authentication failed' }
  })
  await page.goto('/#/alerts/settings')
  await page.getByRole('button', { name: '测试告警', exact: true }).click()
  await expect(page.getByText(/测试告警发送失败.*SMTP authentication failed/)).toBeVisible()
  await expect(page.getByText('测试告警消息已成功投递')).toHaveCount(0)
})

test('SMTP credentials and group policy edits use complete snake case payloads', async ({ page }) => {
  let channel, group
  const errors = await backend(page, (request, path) => {
    if (path === '/api/alert-channels') return [{ id: 3, channel_name: '邮件通道', channel_type: 'email', smtp_host: 'smtp.example.com', smtp_port: 465, alert_email: 'ops@example.com', enabled: true }]
    if (path === '/api/alert-policies') return [{ id: 9, policy_name: '连续失败', trigger_type: 'consecutive_down', trigger_value: '3', enabled: true }]
    if (path === '/api/alert-channels/3') { channel = request.postDataJSON(); return { id: 3 } }
    if (path === '/api/alert-groups/1' && request.method() === 'GET') return { id: 1, group_name: '邮件组', enabled: true, channel_ids: [3], policy_ids: [9] }
    if (path === '/api/alert-groups/1' && request.method() === 'PUT') { group = request.postDataJSON(); return { id: 1 } }
  })
  await page.goto('/#/alerts/settings')
  await page.getByRole('button', { name: '告警渠道与告警组', exact: true }).click()
  await page.getByTitle('编辑渠道', { exact: true }).click()
  await page.getByLabel('SMTP 用户名', { exact: true }).fill('mailer')
  await page.getByLabel('SMTP 密码', { exact: true }).fill('new-smtp-password')
  await page.getByRole('button', { name: '保存渠道', exact: true }).click()
  await expect.poll(() => channel).toBeTruthy()
  expect(channel.channel_name).toBe('邮件通道'); expect(channel.smtp_user).toBe('mailer'); expect(channel.smtp_password).toBe('new-smtp-password')
  await page.getByRole('row').filter({ hasText: '邮件组' }).getByRole('button', { name: '编辑', exact: true }).click()
  await page.getByLabel('启用告警组', { exact: true }).uncheck()
  await page.getByRole('button', { name: '保存告警组', exact: true }).click()
  await expect.poll(() => group).toBeTruthy()
  expect(group.policy_ids).toEqual([9]); expect(group.channel_ids).toEqual([3]); expect(group.enabled).toBe(false)
  expect(errors).toEqual([])
})

test('audit displays backend status and message and sends date filters', async ({ page }) => {
  let parameters
  const errors = await backend(page, (_, path, url) => {
    if (path === '/api/alert-notifications') { parameters = url.searchParams; return { items: [{ id: 1, notify_status: 'success', notify_message: '已投递到邮件网关', service_name: '支付接口', created_at: '2026-11-13T10:00:00' }], total: 1, today_total: 1, today_success: 1, today_failed: 0 } }
  })
  await page.goto('/#/alerts/audit')
  await expect(page.getByRole('cell', { name: '成功', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '已投递到邮件网关', exact: true })).toBeVisible()
  await page.getByLabel('开始', { exact: true }).fill('2026-11-12T00:00')
  await page.getByLabel('结束', { exact: true }).fill('2026-11-14T00:00')
  await page.getByLabel('结束', { exact: true }).press('Tab')
  await expect.poll(() => parameters.get('end_time')).toBe('2026-11-14 00:00')
  expect(parameters.get('start_time')).toBe('2026-11-12 00:00'); expect(parameters.get('page_size')).toBe('20')
  expect(errors).toEqual([])
})

test('all page routes render without runtime errors and direct links keep the page', async ({ page }) => {
  const errors = await backend(page, (_, path) => {
    if (path === '/api/services/1') return { id: 1, service_name: '应用监控', service_type: 'web', endpoint: 'http://localhost', config_json: '{}', last_status: 'DOWN', alert_group_ids: [1] }
    if (path === '/api/hosts/1') return { id: 1, host_name: '应用主机', ip: '127.0.0.1' }
    if (path === '/api/hosts/1/metrics') return { cpu_usage_percent: 10 }
    if (path === '/api/alert-tasks') return { items: [], total: 0, counts: [] }
  })
  for (const [path, heading] of [['/dashboard','监控服务总览'],['/services/1','应用监控'],['/hosts/1','应用主机'],['/data-send','数据发送功能'],['/admin','用户账号与安全审计'],['/alerts/audit','告警通知审计流水'],['/external-projects','外部项目与消息转发'],['/alerts/tasks','告警任务与重试管理']]) {
    await page.goto('/#' + path)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
  }
  await page.goto('/external-projects')
  await expect(page.getByRole('heading', { name: '外部项目与消息转发', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '外部项目与消息', exact: true })).toHaveClass(/active/)
  await page.screenshot({ path: 'test-results/vue3-external-projects.png', fullPage: true })
  expect(errors).toEqual([])
})

test('dashboard renders service status and keeps history in a separate menu', async ({ page }) => {
  const errors = await backend(page, (_, path) => {
    if (path === '/api/services') return [{ id: 1, service_name: '支付接口', service_type: 'web', last_status: 'DOWN', last_response_time_ms: 123, last_message: 'HTTP 503', enabled: true }]
    if (path === '/api/dashboard') return { summary: { total: 1, instances: 1, down: 1, today_alerts: 1 }, recent_alerts: [{ id: 1, service_name: '支付接口', alert_type: 'email', alert_status: 'failed', alert_content: '邮件网关暂时不可用', created_at: '2026-11-13' }] }
  })
  await page.goto('/#/dashboard')
  await expect(page.getByText('运行异常', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('heading', { name: '告警消息列表', exact: true })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '最近告警记录', exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test('user administration sends enabled and new_password and supports deletion', async ({ page }) => {
  let status, password, removed = false
  const errors = await backend(page, (request, path) => {
    if (path === '/api/admin/users') return [{ user_id: 'john', name: 'John', status: 1 }]
    if (path === '/api/admin/users/john/status') { status = request.postDataJSON(); return {} }
    if (path === '/api/admin/users/john/password') { password = request.postDataJSON(); return {} }
    if (path === '/api/admin/users/john' && request.method() === 'DELETE') { removed = true; return {} }
  })
  await page.goto('/#/admin')
  await page.getByRole('button', { name: '禁用', exact: true }).click()
  await expect.poll(() => status).toBeTruthy(); expect(status).toEqual({ enabled: false })
  await page.getByRole('button', { name: '重置密码', exact: true }).click()
  await page.getByPlaceholder('请输入新密码', { exact: true }).fill('new-user-password')
  await page.getByRole('button', { name: '确认修改', exact: true }).click()
  await expect.poll(() => password).toBeTruthy(); expect(password.new_password).toBe('new-user-password')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '删除', exact: true }).click()
  await expect.poll(() => removed).toBe(true)
  expect(errors).toEqual([])
})

test('wide task filters keep a normal button height', async ({ page }) => {
  await page.setViewportSize({ width: 1884, height: 960 })
  await backend(page, (_, path) => path === '/api/alert-tasks' ? { items: [], total: 0, counts: [{ delivery_status: 'SUCCESS', total: 11 }] } : undefined)
  await page.goto('/#/alerts/tasks')
  const button = page.getByRole('button', { name: '查询 / 刷新', exact: true })
  await expect(button).toBeVisible()
  const size = await button.boundingBox()
  expect(size.height).toBeLessThanOrEqual(44)
  expect(size.width).toBeLessThanOrEqual(180)
  await page.screenshot({ path: 'test-results/task-filter-layout.png', fullPage: true })
})

test('alert rules have separated groups and aligned fields at desktop and narrow widths', async ({ page }) => {
  await backend(page)
  await page.goto('/#/services/new')
  const card = page.locator('.alert-policy-card')
  await card.scrollIntoViewIfNeeded()
  await expect(card.getByLabel('邮件组', { exact: true })).toBeVisible()
  await card.getByLabel('邮件组', { exact: true }).check()
  const down = await card.getByLabel('DOWN 连续失败次数').boundingBox()
  const recover = await card.getByLabel('恢复 UP 连续成功次数').boundingBox()
  expect(Math.abs(down.y - recover.y)).toBeLessThan(2)
  const unknown = await card.locator('.unknown-policy').boundingBox()
  expect(unknown.y - down.y - down.height).toBeGreaterThanOrEqual(18)
  await card.screenshot({ path: 'test-results/alert-rule-layout.png' })
  await page.setViewportSize({ width: 640, height: 900 })
  const overflow = await card.evaluate(node => node.scrollWidth > node.clientWidth)
  expect(overflow).toBe(false)
})

test('recent alerts has an independent page with safe HTML and expandable content', async ({ page }) => {
  const errors = await backend(page, (_, path) => {
    if (path === '/api/alerts') return [{ id: 1, service_name: '应用主机', alert_type: 'email', alert_status: 'success', created_at: '2026-11-13 14:15:01', alert_content: '<div style="position:fixed">主机资源告警</div><script>window.alertExecuted=true</script><img src="https://invalid.example/tracker" onerror="window.alertExecuted=true"><div>' + 'CPU：0.9%，内存：82.0%<br>'.repeat(15) + '</div>' }]
  })
  await page.goto('/#/dashboard')
  await page.getByRole('link', { name: '最近告警记录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '最近告警记录', exact: true })).toBeVisible()
  const content = page.locator('.message-html')
  await expect(content).toContainText('主机资源告警')
  await expect(content.locator('script,img,iframe,style,[style],[onerror]')).toHaveCount(0)
  expect(await page.evaluate(() => window.alertExecuted)).toBeUndefined()
  expect(await content.innerText()).not.toContain('<div')
  await page.getByRole('button', { name: '展开消息' }).click()
  await expect(page.getByRole('button', { name: '收起消息' })).toBeVisible()
  await page.screenshot({ path: 'test-results/recent-alerts-html.png', fullPage: true })
  expect(errors).toEqual([])
})

test('service table paginates filtered results and keeps a valid page across refreshes', async ({ page }) => {
  let services = Array.from({ length: 29 }, (_, index) => ({ id: index + 1, service_name: `service_${String(index + 1).padStart(2, '0')}`, service_type: 'web', last_status: index === 28 ? 'DOWN' : 'UP', enabled: true }))
  let fetches = 0
  await page.clock.install()
  const errors = await backend(page, (_, path) => {
    if (path === '/api/services') { fetches++; return services }
  })
  await page.goto('/#/dashboard')
  const rows = page.locator('.cm-table tbody .cm-table-row')
  const pagination = page.getByRole('navigation', { name: '服务表格分页' })
  await expect(rows).toHaveCount(10)
  await expect(rows.first()).toContainText('service_01')
  await pagination.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(rows.first()).toContainText('service_11')
  await pagination.getByRole('button', { name: '末页', exact: true }).click()
  await expect(rows).toHaveCount(9)
  await expect(pagination).toContainText('第 3 / 3 页')
  await expect(pagination.getByRole('button', { name: '下一页', exact: true })).toBeDisabled()
  await pagination.getByLabel('每页条数').selectOption('20')
  await expect(rows).toHaveCount(20)
  await expect(rows.first()).toContainText('service_01')
  const search = page.getByPlaceholder('按服务名、类型、集群过滤...')
  await search.fill('service_29')
  await expect(rows).toHaveCount(1)
  await expect(pagination).toContainText('共 1 条')
  await search.fill('no-matching-service')
  await expect(rows).toHaveCount(0)
  await expect(pagination).toContainText('显示 0–0 条')
  await search.fill('')
  await pagination.getByLabel('每页条数').selectOption('10')
  await pagination.getByRole('button', { name: '下一页', exact: true }).click()
  await page.clock.fastForward(30000)
  await expect.poll(() => fetches).toBeGreaterThan(1)
  await expect(rows.first()).toContainText('service_11')
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 CSV', exact: true }).click()
  const stream = await (await downloadPromise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString()
  expect(csv).toContain('service_01'); expect(csv).toContain('service_29')
  services = services.slice(0, 6)
  await page.getByTitle('手动刷新', { exact: true }).click()
  await expect(rows).toHaveCount(6)
  await expect(pagination).toContainText('第 1 / 1 页')
  await page.screenshot({ path: 'test-results/service-table-pagination.png', fullPage: true })
  expect(errors).toEqual([])
})
