const { test, expect } = require('@playwright/test')
const groups = [{ id: 1, group_name: '邮件组', enabled: true }, { id: 2, group_name: '短信组', enabled: true }]
test('DolphinScheduler single monitor keeps project filters, instance/task paging and connection modals', async ({ page }) => {
  let saved,fail=false,summaryParams
  const now=new Date().toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'})
  const config={base_url:'http://scheduler:12345/dolphinscheduler',enterprise_enabled:true,project_codes:'',window_hours:24,alert_states:'FAILURE,STOP,KILL',running_timeout_minutes:180}
  const connection={id:50,service_name:'DS production',service_type:'dolphinscheduler',enabled:true,check_interval:60,service_alert_enabled:true,service_consecutive_failures:1,service_recover_successes:1,unknown_alert_enabled:true,unknown_consecutive_failures:1,alert_repeat_interval_seconds:300,alert_group_ids:[1],config_json:JSON.stringify(config)}
  const projects=Array.from({length:23},(_,i)=>({id:String(i+1),name:'project_'+i,online_count:2,workflow_count:2,task_definition_count:3,not_executed_workflows:0,unclassified_workflows:0,workflow_statistics:{total:1,running:0,success:1,failed:0,waiting:0},config:{}}))
  const instances=Array.from({length:25},(_,i)=>({id:String(i+1),project_code:i<20?'1':'2',project_name:'project_0',name:'workflow_'+i,state:i===0?'FAILURE':'SUCCESS'}))
  const errors=await backend(page,(request,path,url)=>{
    if(path==='/api/dolphinscheduler/connections')return[connection]
    if(path==='/api/dolphinscheduler/connections/50'){saved=request.postDataJSON();return connection}
    if(path.endsWith('/monitor/projects'))return projects
    if(path.endsWith('/monitor/summary')){summaryParams=url.searchParams;const selected=projects.filter(r=>!url.searchParams.get('project')||r.id===url.searchParams.get('project'));return fail?{status:502,body:{detail:'DolphinScheduler HTTP 401'}}:{project_count:selected.length,projects:selected,online_workflows:selected.length*2,not_executed_workflows:0,workflow_statistics:{total:25,running:0,success:24,failed:1},collection:['inventory','runtime','users','resources'].map(lane=>({lane,last_success:now,fresh:true,failures:0})),nodes:[],databases:[],enterprise_enabled:true}}
    if(path.endsWith('/monitor/instances')){const rows=instances.filter(r=>(!url.searchParams.get('project')||r.project_code===url.searchParams.get('project'))&&(!url.searchParams.get('state')||r.state===url.searchParams.get('state'))),p=Number(url.searchParams.get('page')||1);return {items:rows.slice((p-1)*20,p*20),page:p,total:rows.length}}
    if(path.endsWith('/tasks'))return Array.from({length:21},(_,i)=>({id:String(i+1),name:'task_'+i,state:'SUCCESS'}))
  })
  await page.goto('/#/dolphinscheduler')
  await expect(page.getByRole('heading',{name:'DolphinScheduler 监控',exact:true})).toBeVisible()
  await expect(page.locator('aside').getByRole('link',{name:/驾驶舱/})).toHaveCount(0)
  await page.getByLabel('项目',{exact:true}).selectOption('2')
  await expect.poll(()=>summaryParams.get('project')).toBe('2');await expect(page.getByRole('button',{name:'执行明细',exact:true})).toHaveCount(1)
  await page.getByRole('button',{name:'执行明细',exact:true}).click()
  const rows=page.locator('section').filter({has:page.getByLabel('记录类型')}).locator('tbody tr')
  await expect(rows).toHaveCount(5)
  await page.getByLabel('项目',{exact:true}).selectOption('')
  await expect(rows).toHaveCount(20)
  await page.getByRole('button',{name:'下一页',exact:true}).click();await expect(rows).toHaveCount(5)
  await page.getByRole('button',{name:'刷新',exact:true}).click();await expect(page.getByText('第 2 页 · 共 25 条',{exact:true})).toBeVisible()
  await page.getByLabel('执行时间范围',{exact:true}).selectOption('all');await expect.poll(()=>summaryParams.get('start')).toBeNull()
  await page.getByLabel('状态',{exact:true}).selectOption('FAILURE');await expect(rows).toHaveCount(1)
  await page.getByRole('button',{name:'任务节点',exact:true}).click()
  const taskNav=page.getByRole('navigation',{name:'任务节点分页'});await expect(taskNav.locator('..').locator('tbody tr')).toHaveCount(10)
  await page.getByRole('dialog').getByRole('button',{name:'关闭详情',exact:true}).last().click()
  await page.getByRole('button',{name:'管理',exact:true}).click();await page.getByRole('button',{name:'告警配置',exact:true}).click()
  await expect(page.getByRole('dialog',{name:'告警配置',exact:true})).toHaveClass(/modal-panel/)
  await page.getByRole('button',{name:'保存告警配置',exact:true}).click();await expect.poll(()=>saved).toBeTruthy()
  expect(saved.secret_config).toBeUndefined();expect(saved.config.base_url).toBe(config.base_url);expect(saved.alert_group_ids).toEqual([1])
  await page.getByRole('button',{name:'管理',exact:true}).click();await page.getByRole('button',{name:'新增连接',exact:true}).click()
  const modal=page.getByRole('dialog',{name:'新增 DolphinScheduler 连接',exact:true})
  await expect(modal).toHaveClass(/modal-panel/)
  await modal.getByRole('button',{name:'取消',exact:true}).focus();await page.keyboard.press('Tab');await expect(modal.getByRole('button',{name:'关闭配置',exact:true})).toBeFocused()
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
  await page.keyboard.press('Escape');await expect(page.getByRole('button',{name:'管理',exact:true})).toBeFocused()
  fail=true;await page.getByRole('button',{name:'应用筛选',exact:true}).click();await expect(page.getByRole('alert')).toContainText('401')
  expect(errors).toEqual([])
})

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

test('account section starts collapsed and navigation scrolls without a visible scrollbar', async ({page}) => {
  await backend(page);await page.setViewportSize({width:1280,height:500});await page.goto('/#/data-send');
  const group=page.getByRole('button',{name:'账号与安全',exact:true});await expect(group).toHaveAttribute('aria-expanded','false');
  await expect(page.locator('aside').getByRole('link',{name:'用户安全管理',exact:true})).toHaveCount(0);
  await group.click();await expect(group).toHaveAttribute('aria-expanded','true');await expect(page.locator('aside').getByRole('link',{name:'用户安全管理',exact:true})).toBeVisible();
  await expect(page.locator('aside a[href="#/account"]')).toHaveCount(0);
  await page.getByRole('button',{name:'告警中心',exact:true}).click();await page.getByRole('button',{name:'资产基础设施',exact:true}).click();
  const area=page.locator('.sidebar-menu-wrapper');const result=await area.evaluate(el=>{el.scrollTop=el.scrollHeight;return {scrollable:el.scrollHeight>el.clientHeight,moved:el.scrollTop>0,overflow:getComputedStyle(el).overflowY,bar:getComputedStyle(el,'::-webkit-scrollbar').display}});
  expect(result).toEqual({scrollable:true,moved:true,overflow:'auto',bar:'none'});
})

test('unified security tabs show current user, created users and preserve password validation and audit', async ({ page }) => {
  const users = [{ id: 2, user_id: 'john', name: 'John', status: 1 }, { id: 1, user_id: 'admin', name: '管理员', status: 1, grade: 0 }]
  let password, created
  const errors = await backend(page, (request, path) => {
    if (path === '/api/admin/users' && request.method() === 'POST') { created = request.postDataJSON(); users.push({ id: 3, ...created, status: 1 }); return { status: 201, body: users[2] } }
    if (path === '/api/admin/users') return users
    if (path === '/api/admin/password') { password = request.postDataJSON(); return {} }
    if (path === '/api/admin/audit-logs') return { records: [{ id: 1, user_id: 'john', action: 'LOGIN', event_time: '2026-11-14 10:00:00', ip_address: '127.0.0.1' }], total: 1 }
  })
  await page.goto('/#/admin')
  await expect(page.getByRole('tab', { name: '用户管理', exact: true })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('region', { name: '当前登录用户' })).toContainText('admin')
  await expect(page.locator('tbody tr').first()).toContainText('当前用户')
  await expect(page.locator('tbody tr').first()).toContainText('超级管理员')
  await expect(page.locator('tbody tr').nth(1)).toContainText('john')
  await page.getByRole('button', { name: '新建用户', exact: true }).click()
  await page.getByPlaceholder('英文小写或数字').fill('alice')
  await page.getByPlaceholder('例如：张三').fill('Alice')
  await page.getByPlaceholder('至少 6 位密码').fill('temporary-password')
  await page.getByRole('button', { name: '立即创建' }).click()
  await expect(page.locator('tbody tr')).toHaveCount(3)
  await expect(page.locator('tbody')).toContainText('alice')
  expect(created.user_id).toBe('alice')
  await page.getByRole('tab', { name: '个人密码', exact: true }).click()
  await page.getByLabel('当前密码', { exact: true }).fill('current')
  await page.getByLabel('新密码', { exact: true }).fill('new-password')
  await page.getByLabel('确认新密码', { exact: true }).fill('mismatch')
  await page.getByRole('button', { name: '修改密码', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('两次新密码不一致')
  expect(password).toBeUndefined()
  await page.getByLabel('确认新密码', { exact: true }).fill('new-password')
  await page.getByRole('button', { name: '修改密码', exact: true }).click()
  await expect.poll(() => password).toEqual({ current_password: 'current', new_password: 'new-password' })
  await expect(page.getByLabel('当前密码', { exact: true })).toHaveValue('')
  await page.getByRole('tab', { name: '个人密码', exact: true }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(page.getByRole('tab', { name: '登录审计', exact: true })).toBeFocused()
  await expect(page.locator('tbody')).toContainText('127.0.0.1')
  await page.reload()
  await expect(page.getByRole('tab', { name: '登录审计', exact: true })).toHaveAttribute('aria-selected', 'true')
  await page.goto('/#/account')
  await expect(page).toHaveURL(/#\/admin\?tab=password/)
  await expect(page.getByRole('tab', { name: '个人密码', exact: true })).toHaveAttribute('aria-selected', 'true')
  expect(errors).toEqual([])
})

test('security users failure is visible and retry restores list; read-only users cannot access admin controls', async ({ page }) => {
  let fail = true, admin = true, protectedRequests = 0
  await backend(page, (_, path) => {
    if (path === '/api/auth/me') return { authenticated: true, user_id: admin ? 'admin' : 'viewer', admin }
    if (path.startsWith('/api/admin/')) { protectedRequests++; return fail ? { status: 503, body: { detail: '用户服务不可用' } } : [{ id: 1, user_id: 'admin', status: 1 }] }
  })
  await page.goto('/#/admin')
  await expect(page.getByRole('alert')).toContainText('加载用户列表失败')
  fail = false
  await page.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await expect(page.getByRole('alert')).toHaveCount(0)
  admin = false; protectedRequests = 0
  await page.reload()
  await expect(page.getByRole('region', { name: '当前登录用户' })).toContainText('viewer')
  await expect(page.getByRole('button', { name: '新建用户', exact: true })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '登录审计', exact: true })).toHaveCount(0)
  await page.getByRole('tab', { name: '个人密码', exact: true }).click()
  await expect(page.getByText('当前账号为只读用户，请联系管理员修改密码。')).toBeVisible()
  expect(protectedRequests).toBe(0)
})

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
  await page.getByRole('dialog').getByRole('button', { name: '关闭详情', exact: true }).last().click()
  await page.getByRole('button', { name: '手动重试' }).click()
  await expect(page.getByRole('cell', { name: '排队中', exact: true })).toBeVisible()
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

test('web advanced parameters start collapsed and preserve edits when saved collapsed', async ({ page }) => {
  let saved
  const errors = await backend(page, (request,path) => {
    if (path === '/api/services' && request.method() === 'POST') { saved=request.postDataJSON(); return {id:99,...saved} }
  })
  await page.goto('/#/services/new')
  const toggle=page.getByRole('button',{name:/高级参数/})
  await expect(toggle).toHaveAttribute('aria-expanded','false')
  await expect(page.getByLabel(/^认证方式/)).toBeHidden()
  await toggle.focus();await page.keyboard.press('Enter')
  await expect(toggle).toHaveAttribute('aria-expanded','true')
  await page.getByLabel(/^认证方式/).selectOption('basic')
  await page.getByLabel('Basic 用户名',{exact:true}).fill('probe-user')
  await page.getByLabel('Basic 密码',{exact:true}).fill('test-only-password')
  await page.getByLabel('文本断言内容',{exact:true}).fill('healthy')
  await page.getByLabel('断言表达式',{exact:true}).fill('status_code == 200')
  await toggle.click();await toggle.click()
  await expect(page.getByLabel('Basic 用户名',{exact:true})).toHaveValue('probe-user')
  await toggle.click()
  await page.locator('input[maxlength="120"]').fill('web advanced test')
  await page.locator('input[type="url"]').first().fill('https://example.com/health')
  await page.getByLabel('邮件组',{exact:true}).check()
  await page.getByRole('button',{name:'保存监控服务',exact:true}).click()
  await expect.poll(()=>saved).toBeTruthy()
  expect(saved.api_auth_type).toBe('basic');expect(saved.api_basic_username).toBe('probe-user')
  expect(saved.api_basic_password).toBe('test-only-password');expect(saved.api_text_assertion_value).toBe('healthy')
  expect(saved.api_assertion_expression).toBe('status_code == 200');expect(errors).toEqual([])
})

test('database advanced parameters are uniformly collapsed and preserve preview and saved assertions', async ({ page }) => {
  let saved, preview
  const errors = await backend(page, (request, path) => {
    if (path === '/api/database/preview') { preview = request.postDataJSON(); return { columns: ['total'], rows: [{ total: 42 }] } }
    if (path === '/api/services' && request.method() === 'POST') { saved = request.postDataJSON(); return { id: 99, ...saved } }
    if (path === '/api/services/99') return { ...saved, id: 99 }
  })
  await page.goto('/#/services/new')
  const toggle = page.getByRole('button', { name: /高级参数/ })
  for (const type of ['mysql', 'oracle', 'postgresql', 'jdbc']) {
    await page.getByLabel('服务类型', { exact: true }).selectOption(type)
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(page.getByText('断言、认证与查询预览', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '查询预览', exact: true })).toBeHidden()
    await toggle.click()
    await expect(page.getByRole('button', { name: '查询预览', exact: true })).toBeVisible()
  }
  await page.getByLabel('参与断言的字段（逗号分隔）', { exact: true }).fill('total')
  await page.getByLabel('断言表达式', { exact: true }).fill('total > 0')
  await page.getByRole('button', { name: '查询预览', exact: true }).click()
  await expect(page.getByRole('cell', { name: '42', exact: true })).toBeVisible()
  expect(preview.database_assertion_fields).toEqual(['total'])
  await toggle.click()
  await page.locator('input[maxlength="120"]').fill('Database advanced parameters')
  await page.getByLabel('邮件组', { exact: true }).check()
  await page.getByRole('button', { name: '保存监控服务', exact: true }).click()
  await expect.poll(() => saved?.database_assertion_fields).toEqual(['total'])
  expect(saved.api_assertion_expression).toBe('total > 0')
  await expect(page).toHaveURL(/#\/dashboard/)
  await page.goto('/#/services/99/edit')
  await expect(page.getByLabel('服务类型', { exact: true })).toHaveValue('jdbc')
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await toggle.click()
  await expect(page.getByLabel('断言表达式', { exact: true })).toHaveValue('total > 0')
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
  await expect(page.getByRole('cell', { name: '渠道已受理', exact: true })).toBeVisible()
  await page.getByText('查看完整消息与处理说明', { exact: true }).click()
  await expect(page.locator('.audit-details pre')).toHaveText('已投递到邮件网关')
  await page.getByLabel('开始', { exact: true }).fill('2026-11-12T00:00')
  await page.getByLabel('结束', { exact: true }).fill('2026-11-14T00:00')
  await page.getByLabel('结束', { exact: true }).press('Tab')
  await expect.poll(() => parameters.get('end_time')).toBe('2026-11-14 00:00')
  expect(parameters.get('start_time')).toBe('2026-11-12 00:00'); expect(parameters.get('page_size')).toBe('10')
  expect(errors).toEqual([])
})

test('audit retries a transient network failure and never renders failed requests as zero statistics or a zero trend', async ({ page }) => {
  let attempts = 0, failList = false, failTrend = true, trendRequests = 0
  const errors = await backend(page, (_, path) => {
    if (path === '/api/alert-notifications') return failList ? { status: 503, body: { detail: '审计服务暂不可用' } } : { items: [{ id: 1, notify_status: 'success', service_name: '真实通知' }], total: 7, today_total: 7, today_success: 7, today_failed: 0 }
    if (path === '/api/alert-notifications/trend') { trendRequests++; return failTrend ? { status: 503, body: { detail: '趋势查询暂不可用' } } : [{ date: '2026-11-14', total: 7 }] }
  })
  await page.route('**/api/alert-notifications?*', async route => { attempts++; if (attempts === 1) await route.abort('failed'); else await route.fallback() })
  await page.goto('/#/alerts/audit')
  await expect(page.getByRole('cell', { name: '真实通知', exact: true })).toBeVisible()
  expect(attempts).toBe(2); expect(trendRequests).toBe(0)
  await expect(page.locator('.grid-4 .card').first()).toContainText('7')
  await page.locator('.audit-trend > summary').click()
  await expect(page.locator('.audit-trend [role="alert"]')).toContainText('趋势查询暂不可用')
  expect(trendRequests).toBe(2)
  await expect(page.locator('.audit-trend canvas')).toHaveCount(0)
  await expect(page.getByRole('cell', { name: '真实通知', exact: true })).toBeVisible()
  failList = true
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '加载审计日志失败' })).toContainText('审计服务暂不可用')
  for (const card of await page.locator('.grid-4 .card').all()) await expect(card).toContainText('—')
  await expect(page.getByText('暂无符合筛选条件的通知记录')).toHaveCount(0)
  failList = false
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.getByRole('cell', { name: '真实通知', exact: true })).toBeVisible()
  failTrend = false
  await page.getByRole('button', { name: '重试趋势', exact: true }).click()
  await expect(page.locator('.audit-trend canvas')).toBeVisible()
  expect(await page.locator('.audit-trend canvas').evaluate(el => el.width)).toBeGreaterThan(100)
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(errors).toEqual([])
})

test('audit lists load independently of a slow trend and rapid filters retain the latest results', async ({ page }) => {
  let releaseTrend, releaseOld, oldStarted = false
  const trend = new Promise(resolve => { releaseTrend = resolve })
  const old = new Promise(resolve => { releaseOld = resolve })
  const errors = await backend(page, async (_, path, url) => {
    if (path === '/api/alert-notifications/trend') { await trend; return [{ date: '2026-11-14', total: 2 }] }
    if (path === '/api/alert-notifications') {
      const status = url.searchParams.get('status')
      if (status === 'pending') { oldStarted = true; await old }
      return { items: [{ id: 1, service_name: status || 'initial', notify_status: status || 'success' }], total: 1, today_total: 2 }
    }
  })
  await page.goto('/#/alerts/audit')
  await expect(page.getByRole('cell', { name: 'initial', exact: true })).toBeVisible()
  await page.locator('.audit-trend > summary').click()
  await expect(page.getByText('正在加载通知趋势…')).toBeVisible()
  await page.getByLabel('通知状态', { exact: true }).selectOption('pending')
  await expect.poll(() => oldStarted).toBe(true)
  await page.getByLabel('通知状态', { exact: true }).selectOption('failed')
  await expect(page.getByRole('cell', { name: 'failed', exact: true })).toBeVisible()
  releaseOld(); releaseTrend()
  await expect(page.locator('.audit-trend canvas')).toBeVisible()
  await expect(page.getByRole('cell', { name: 'failed', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'pending', exact: true })).toHaveCount(0)
  expect(errors).toEqual([])
})

test('all page routes render without runtime errors and direct links keep the page', async ({ page }) => {
  const errors = await backend(page, (_, path) => {
    if (path === '/api/services/1') return { id: 1, service_name: '应用监控', service_type: 'web', endpoint: 'http://localhost', config_json: '{}', last_status: 'DOWN', alert_group_ids: [1] }
    if (path === '/api/hosts/1') return { id: 1, host_name: '应用主机', ip: '127.0.0.1' }
    if (path === '/api/hosts/1/metrics') return { cpu_usage_percent: 10 }
    if (path === '/api/alert-tasks') return { items: [], total: 0, counts: [] }
  })
  for (const [path, heading] of [['/dashboard','监控服务总览'],['/services/1','应用监控'],['/hosts/1','应用主机'],['/data-send','数据发送功能'],['/admin','用户安全管理'],['/alerts/audit','告警通知审计流水'],['/external-projects','外部项目与消息转发'],['/external-requests','外部消息请求'],['/alerts/tasks','告警任务与重试管理']]) {
    await page.goto('/#' + path)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
  }
  await page.goto('/external-projects')
  await expect(page.getByRole('heading', { name: '外部项目与消息转发', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '告警中心', exact: true }).click()
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
  await expect(page.getByRole('button', { name: '告警中心', exact: true })).toHaveAttribute('aria-expanded','false')
  await expect(page.getByRole('button', { name: '资产基础设施', exact: true })).toHaveAttribute('aria-expanded','false')
  await expect(page.locator('#menu-group-assets')).toBeHidden()
  await expect(page.locator('#menu-group-alerts')).toBeHidden()
  await page.getByRole('button', { name: '告警中心', exact: true }).click()
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
  await page.getByRole('button', { name: '告警中心', exact: true }).click()
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

test('audit separates configuration blocks from retries and exposes complete failure reasons', async ({ page }) => {
  let taskParameters
  const errors = await backend(page, (_, path, url) => {
    if (path === '/api/alert-notifications') return { items: [
      { id: 21, service_id: 11, service_name: 'unbound-host', notify_status: 'blocked', notify_message: 'Service UNKNOWN Delivery skipped: no alert group bound.' },
      { task_id: 'task-smtp', service_id: 12, channel_id: 2, channel_name: 'production-email', current_recipients: 'ops@example.com', notify_status: 'retrying', last_error: 'SMTP gateway timeout: full diagnostic detail', attempt_count: 2, next_retry_at: Date.now() + 30000, notify_message: 'host memory high' }
    ], total: 2, today_total: 2, today_success: 0, today_failed: 0, today_pending: 1, today_blocked: 1 }
    if (path === '/api/alert-tasks') { taskParameters = url.searchParams; return { items: [], total: 0 } }
  })
  await page.goto('/#/alerts/audit')
  await expect(page.getByRole('cell', { name: '未发送（配置阻塞）', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '重试中', exact: true })).toBeVisible()
  await expect(page.getByText('SMTP gateway timeout: full diagnostic detail', { exact: true })).toBeVisible()
  await expect(page.getByText('未绑定告警组：没有接收渠道，消息未发送。', { exact: true })).toBeVisible()
  await expect(page.getByText('ops@example.com', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '检查服务告警配置' })).toHaveAttribute('href', '#/services/11/edit')
  await page.getByRole('link', { name: '查看发送任务与重试' }).click()
  await expect.poll(() => taskParameters?.get('service_id')).toBe('12')
  expect(taskParameters.get('channel_id')).toBe('2')
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
  await page.getByRole('button', { name: '服务卡片', exact: true }).click()
  await expect(page.locator('.cm-service-card')).toHaveCount(10)
  const viewPagination = page.getByRole('navigation', { name: '服务视图分页' })
  await viewPagination.getByRole('button', { name: '末页', exact: true }).click()
  await expect(page.locator('.cm-service-card')).toHaveCount(9)
  await page.getByRole('button', { name: '集群分组', exact: true }).click()
  await expect(page.locator('.cm-table tbody .cm-table-row')).toHaveCount(9)
  await page.getByRole('button', { name: '服务表格', exact: true }).click()
  services = services.slice(0, 6)
  await page.getByTitle('手动刷新', { exact: true }).click()
  await expect(rows).toHaveCount(6)
  await expect(pagination).toContainText('第 1 / 1 页')
  await page.screenshot({ path: 'test-results/service-table-pagination.png', fullPage: true })
  expect(errors).toEqual([])
})

test('all workspace pages retain usable content width on a phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const errors = await backend(page, (_, path) => {
    if (path === '/api/services/1') return { id: 1, service_name: '应用监控', service_type: 'web', config_json: '{}', alert_group_ids: [1] }
    if (path === '/api/hosts/1') return { id: 1, host_name: '应用主机', ip: '127.0.0.1' }
    if (path === '/api/hosts/1/metrics') return {}
    if (path === '/api/alert-tasks') return { items: [], total: 0, counts: [] }
  })
  for (const [path, heading] of [['/dashboard','监控服务总览'],['/databases','数据库监控维护'],['/hosts','服务器主机维护'],['/services/new','添加监控服务'],['/data-send','数据发送功能'],['/alerts/settings','告警配置与通知管理'],['/alerts/tasks','告警任务与重试管理'],['/alerts/recent','最近告警记录'],['/alerts/audit','告警通知审计流水'],['/external-projects','外部项目与消息转发'],['/external-requests','外部消息请求'],['/account','用户安全管理'],['/admin','用户安全管理'],['/services/1','应用监控'],['/hosts/1','应用主机']]) {
    await page.goto('/#' + path)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    const main = await page.locator('main').boundingBox()
    expect(main.width, path).toBeGreaterThan(370)
    const overflowing = await page.locator('main').evaluate(node => [...node.querySelectorAll('.card,input,select')].filter(element => {
      if (element.closest('.table-wrap') || !element.getClientRects().length) return false
      return element.getBoundingClientRect().right > innerWidth + 2
    }).map(element => element.className))
    expect(overflowing, path).toEqual([])
  }
  await page.screenshot({ path: 'test-results/product-mobile-host.png', fullPage: true })
  expect(errors).toEqual([])
})

test('mobile navigation traps focus, dismisses with Escape and closes after navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await backend(page)
  await page.goto('/#/dashboard')
  await expect(page.getByRole('heading', { name: '监控服务总览', exact: true })).toBeVisible()
  const toggle = page.getByRole('button', { name: '打开导航菜单' })
  await toggle.click()
  const navigation = page.getByRole('dialog', { name: '主导航' })
  await expect(navigation).toBeVisible()
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('Escape')
  await expect(toggle).toBeFocused()
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await toggle.click()
  await page.getByRole('button', { name: '告警中心', exact: true }).click()
  await navigation.getByRole('link', { name: '最近告警记录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '最近告警记录', exact: true })).toBeVisible()
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  expect(await page.evaluate(() => document.body.style.overflow)).not.toBe('hidden')
})

test('notification previews remove HTML and menus dismiss without losing focus', async ({ page }) => {
  await backend(page, (_, path) => path === '/api/alerts' ? [{ id: 7, service_id: 11, service_name: '应用主机', alert_status: 'success', alert_content: '<div>主机资源告警</div><script>window.productXss=true</script><img src="https://invalid.example/image" onerror="window.productXss=true">' }] : undefined)
  await page.goto('/#/dashboard')
  await expect(page.getByRole('heading', { name: '监控服务总览', exact: true })).toBeVisible()
  const bell = page.getByRole('button', { name: '告警通知', exact: true })
  await bell.click()
  await expect(page.locator('.notify-item-msg')).toHaveText('主机资源告警')
  await expect(page.locator('.notify-item-msg img, .notify-item-msg script')).toHaveCount(0)
  await expect(page.locator('.notify-item')).toHaveAttribute('href', '#/alerts/recent?service_id=11')
  await page.keyboard.press('Escape')
  await expect(bell).toBeFocused()
  await expect(bell).toHaveAttribute('aria-expanded', 'false')
  await page.getByRole('button', { name: '账号菜单' }).click()
  await expect(page.locator('#account-menu')).toBeVisible()
  await page.getByRole('heading', { name: '监控服务总览' }).click()
  await expect(page.locator('#account-menu')).toHaveCount(0)
  expect(await page.evaluate(() => window.productXss)).toBeUndefined()
  await page.goto('/#/account')
  await page.getByRole('link', { name: '跳到主要内容' }).focus()
  await page.keyboard.press('Enter')
  await expect(page.locator('main')).toBeFocused()
  await expect(page).toHaveURL(/#\/admin\?tab=password/)
  await page.goto('/#/alerts/recent?service_id=11')
  await expect(page.getByRole('heading', { name: '最近告警记录', exact: true })).toBeVisible()
  const nextService = page.waitForRequest(request => request.url().includes('/api/services/12/alerts'))
  await page.goto('/#/alerts/recent?service_id=12')
  await nextService
})

test('task filters use service and channel names and reset to all results', async ({ page }) => {
  let parameters
  await backend(page, (_, path, url) => {
    if (path === '/api/services') return [{ id: 12, service_name: '生产主机' }]
    if (path === '/api/alert-channels') return [{ id: 2, channel_name: '生产邮件', channel_type: 'email' }]
    if (path === '/api/alert-tasks') { parameters = url.searchParams; return { items: [], total: 0, counts: [] } }
  })
  await page.goto('/#/alerts/tasks')
  await page.getByLabel('监控服务', { exact: true }).selectOption({ label: '生产主机' })
  await page.getByLabel('告警渠道', { exact: true }).selectOption({ label: '生产邮件' })
  await page.getByLabel('发送状态', { exact: true }).selectOption({ label: '等待重试' })
  await page.getByRole('button', { name: '查询 / 刷新', exact: true }).click()
  await expect.poll(() => parameters.get('service_id')).toBe('12')
  expect(parameters.get('channel_id')).toBe('2'); expect(parameters.get('status')).toBe('FAILED')
  await page.getByRole('button', { name: '重置筛选' }).click()
  await expect.poll(() => parameters.get('service_id')).toBeNull()
  expect(parameters.get('channel_id')).toBeNull(); expect(parameters.get('status')).toBeNull()
})

test('external integration examples use the selected project and preserve JSON and shell quoting', async ({ page }) => {
  const errors = await backend(page, (_, path) => {
    if (path === '/api/external-projects') return [{ id: 9, project_name: "O'Reilly 项目", match_keyword: "ORDER'KEY", enabled: true, alert_group_ids: [1] }]
  })
  await page.goto('/#/external-projects')
  await page.getByRole('button', { name: '接入示例', exact: true }).click()
  const guide = page.getByRole('region', { name: '外部消息接入说明' })
  await expect(guide.getByRole('heading')).toBeFocused()
  await expect(guide.getByLabel('外部消息接口地址')).toHaveValue('http://127.0.0.1:18407/api/external/messages')
  const fs = require('fs')
  for (const [label, extension] of [['JSON 请求体','json'],['cURL','curl'],['Python','py'],['PowerShell','ps1']]) {
    await guide.getByRole('tab', { name: label, exact: true }).click()
    const example = await guide.locator('.example-code pre').innerText()
    fs.writeFileSync(`test-results/external-guide.${extension}`, example)
    if (extension === 'json') expect(JSON.parse(example).content).toContain("ORDER'KEY")
    if (extension === 'curl') expect(example).toContain("'\\''")
  }
  await guide.getByText('如何判断发送成功与排查错误', { exact: true }).click()
  await expect(guide.getByRole('cell', { name: '200 / success: false', exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  const size = await guide.boundingBox(); expect(size.x + size.width).toBeLessThanOrEqual(390)
  expect(errors).toEqual([])
})

test('external guide copies on HTTP without Clipboard API and never sends a message', async ({ page }) => {
  const writes = []
  await backend(page, (request, path) => { if (request.method() !== 'GET') writes.push(path) })
  await page.addInitScript(() => {
    window.guideCopies = []
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('Not a secure context') } } })
    document.execCommand = command => { if (command !== 'copy') return false; window.guideCopies.push(document.querySelector('textarea[readonly]').value); return true }
  })
  await page.goto('/#/external-projects')
  const guide = page.getByRole('region', { name: '外部消息接入说明' })
  await expect(guide.getByRole('button', { name: '展开说明', exact: true })).toHaveAttribute('aria-expanded','false')
  await expect(guide.getByLabel('外部消息接口地址')).toBeHidden()
  await page.getByRole('button', { name: '接入说明', exact: true }).click()
  await expect(guide.getByRole('button', { name: '收起说明', exact: true })).toHaveAttribute('aria-expanded','true')
  await guide.getByRole('button', { name: '复制地址', exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.guideCopies[0])).toBe('http://127.0.0.1:18407/api/external/messages')
  await guide.getByRole('button', { name: '复制示例', exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.guideCopies.length)).toBe(2)
  expect(await page.evaluate(() => window.guideCopies[1])).toContain('curl --request POST')
  await expect(page.getByText('调用示例已复制', { exact: true })).toBeVisible()
  expect(writes).toEqual([])
})

test('host state follows scheduled check results and distinguishes partial, stale and disabled samples', async ({ page }) => {
  const now = Date.parse('2026-11-13T08:00:00Z')
  await page.clock.install({ time: now })
  let fetches = 0
  const common = { enabled: true, monitor_enabled: true, check_interval: 60, last_checked_at_epoch_ms: now, last_checked_at: '2026-11-13 16:00:00', cpu_usage_percent: 20, memory_used_percent: 30, disk_used_percent: 40 }
  await backend(page, (_, path) => path === '/api/hosts' ? (++fetches, [
    { ...common, id: 1, host_name: 'normal-host', last_status: 'UP' },
    { ...common, id: 2, host_name: 'partial-host', last_status: 'UNKNOWN', cpu_usage_percent: null, last_message: 'Unable to parse host CPU usage' },
    { ...common, id: 3, host_name: 'stale-host', last_status: 'UP', last_checked_at_epoch_ms: now - 600000 },
    { ...common, id: 4, host_name: 'disabled-host', enabled: false, last_status: 'UP' },
    { ...common, id: 5, host_name: 'uncollected-host' },
    { ...common, id: 6, host_name: 'threshold-host', last_status: 'UP', disk_used_percent: 96, disk_threshold_percent: 90 }
  ]) : undefined)
  await page.goto('/#/hosts')
  const rows = page.locator('tbody tr')
  await expect(rows).toHaveCount(6)
  for (const [name,status] of [['normal-host','采集正常'],['partial-host','采集异常'],['stale-host','数据过期'],['disabled-host','已停用'],['uncollected-host','等待采集'],['threshold-host','资源超阈']]) await expect(rows.filter({ hasText: name })).toContainText(status)
  await rows.filter({ hasText: 'partial-host' }).getByText('查看原因', { exact: true }).click()
  await expect(page.getByText('Unable to parse host CPU usage', { exact: true })).toBeVisible()
  await page.getByLabel('主机采集状态').selectOption('ERROR')
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('partial-host')
  await page.clock.fastForward(15000)
  await expect.poll(() => fetches).toBeGreaterThan(1)
})

test('database assets use monitor types instead of host names and keep a dedicated creation flow', async ({ page }) => {
  await backend(page, (_, path) => path === '/api/services' ? [
    { id: 1, service_type: 'host', service_name: 'MySQL服务器', last_status: 'UP', enabled: true },
    { id: 2, service_type: 'mysql', service_name: '交易数据库', host: 'db.example', port: 3306, last_status: 'UP', enabled: true },
    { id: 3, service_type: 'web', service_name: '数据库入口页面', enabled: true },
    { id: 4, service_type: 'jdbc', service_name: '业务SQL监控', last_status: 'DOWN', enabled: true, database_connection_service_id: 2 }
  ] : undefined)
  await page.goto('/#/databases')
  await expect(page.getByRole('heading', { name: '数据库监控维护', exact: true })).toBeVisible()
  await expect(page.locator('tbody tr')).toHaveCount(2)
  await expect(page.locator('tbody')).not.toContainText('MySQL服务器')
  await expect(page.locator('tbody')).not.toContainText('数据库入口页面')
  await page.getByLabel('数据库类型').selectOption('mysql')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await page.getByRole('link', { name: '新增数据库监控', exact: true }).click()
  await expect(page.getByRole('heading', { name: '添加数据库监控', exact: true })).toBeVisible()
  await expect(page.getByLabel('服务类型', { exact: true })).toHaveValue('mysql')
  await expect(page.getByLabel('服务类型', { exact: true }).locator('option')).toHaveCount(4)
  await page.getByRole('button', { name: '资产基础设施', exact: true }).click()
  await expect(page.getByRole('link', { name: '数据库监控', exact: true })).toHaveAttribute('aria-current','page')
  await page.getByRole('link', { name: '取消', exact: true }).click()
  await expect(page).toHaveURL(/#\/databases/)
})

test('external request audit lives in its submenu and paginates server results with filters', async ({ page }) => {
  let auditCalls = 0, parameters
  const projects = Array.from({ length: 25 }, (_,i) => ({ id:i+1, project_name:`project_${i+1}`, match_keyword:`key_${i+1}`, enabled:true }))
  const requests = Array.from({ length: 35 }, (_,i) => ({ id:i+1, request_status:i<30?'SUCCESS':'FAILED', message_summary:`request_${i+1}` }))
  await backend(page, (_,path,url) => {
    if(path==='/api/external-projects') return projects
    if(path==='/api/external/messages/audit') {
      auditCalls++; parameters=url.searchParams
      const size=Number(parameters.get('page_size')),pageNumber=Number(parameters.get('page'))
      const filtered=requests.filter(item=>(!parameters.get('status')||item.request_status===parameters.get('status'))&&(!parameters.get('query')||item.message_summary.includes(parameters.get('query'))))
      return {items:filtered.slice((pageNumber-1)*size,pageNumber*size),total:filtered.length,page:Math.max(1,Math.min(pageNumber,Math.ceil(filtered.length/size)||1))}
    }
  })
  await page.goto('/#/external-projects')
  await expect(page.locator('.external-projects-page > section').first().locator('tbody tr')).toHaveCount(10)
  await expect(page.getByRole('heading',{name:'外部消息请求审计',exact:true})).toHaveCount(0)
  expect(auditCalls).toBe(0)
  await page.getByRole('navigation',{name:'外部项目分页'}).getByRole('button',{name:'末页',exact:true}).click()
  await expect(page.locator('.external-projects-page > section').first().locator('tbody tr')).toHaveCount(5)
  await page.getByRole('button', { name: '告警中心', exact: true }).click()
  await page.locator('.submenu-item').filter({hasText:'外部消息请求'}).click()
  await expect(page.getByRole('heading',{name:'外部消息请求',exact:true})).toBeVisible()
  const nav=page.getByRole('navigation',{name:'外部消息请求分页'}),rows=page.locator('tbody tr')
  await expect(rows).toHaveCount(10)
  await nav.getByRole('button',{name:'下一页',exact:true}).click()
  await expect(rows.first()).toContainText('request_11')
  await nav.getByLabel('外部消息请求每页条数').selectOption('20')
  await expect(rows).toHaveCount(20)
  expect(parameters.get('page')).toBe('1');expect(parameters.get('page_size')).toBe('20')
  await page.getByLabel('请求状态',{exact:true}).selectOption('FAILED')
  await page.getByRole('button',{name:'查询请求',exact:true}).click()
  await expect(rows).toHaveCount(5)
  await expect(nav.getByRole('button',{name:'下一页',exact:true})).toBeDisabled()
  await page.getByPlaceholder('项目 / 来源 / 消息 / 错误').fill('no-results')
  await page.getByRole('button',{name:'查询请求',exact:true}).click()
  await expect(nav).toContainText('0–0')
})

test('alert configuration pages preserve all group choices while lists paginate', async ({ page }) => {
  const services=Array.from({length:23},(_,i)=>({id:i+1,service_name:`service_${i+1}`,service_type:'web',alert_group_ids:[],service_alert_enabled:true}))
  await backend(page,(_,path)=>{
    if(path==='/api/services')return services
    if(path==='/api/alert-groups')return Array.from({length:12},(_,i)=>({id:i+1,group_name:`group_${i+1}`,enabled:true}))
    if(path==='/api/alert-channels')return Array.from({length:13},(_,i)=>({id:i+1,channel_name:`channel_${i+1}`,channel_type:'email',enabled:true}))
  })
  await page.goto('/#/alerts/settings')
  const nav=page.getByRole('navigation',{name:'服务告警绑定分页'})
  await expect(page.locator('tbody tr')).toHaveCount(10)
  await page.locator('tbody tr').first().getByLabel('group_12',{exact:true}).check()
  await nav.getByRole('button',{name:'末页',exact:true}).click()
  await expect(page.locator('tbody tr')).toHaveCount(3)
  await nav.getByRole('button',{name:'首页',exact:true}).click()
  await expect(page.locator('tbody tr').first().getByLabel('group_12',{exact:true})).toBeChecked()
  await page.getByRole('button',{name:'告警渠道与告警组',exact:true}).click()
  await page.getByRole('navigation',{name:'告警渠道分页'}).getByRole('button',{name:'末页',exact:true}).click()
  await expect(page.locator('table').first().locator('tbody tr')).toHaveCount(3)
  await page.getByRole('navigation',{name:'告警组分页'}).getByRole('button',{name:'末页',exact:true}).click()
  await expect(page.locator('table').nth(1).locator('tbody tr')).toHaveCount(2)
  await page.getByRole('button',{name:'新建告警组',exact:true}).click()
  await expect(page.getByLabel('channel_13 (EMAIL)',{exact:true})).toBeVisible()
})

test('deleting the only user on the last page clamps to an existing page', async ({ page }) => {
  let users=Array.from({length:21},(_,i)=>({id:i+1,user_id:`test_${i+1}`,status:1,grade:2}))
  await backend(page,(request,path)=>{
    if(request.method()==='DELETE'){users=users.filter(user=>!path.endsWith('/'+user.user_id));return {success:true}}
    if(path==='/api/admin/users')return users
  })
  page.on('dialog',dialog=>dialog.accept())
  await page.goto('/#/admin')
  const nav=page.getByRole('navigation',{name:'用户账号分页'})
  await expect(page.locator('tbody tr')).toHaveCount(10)
  await nav.getByRole('button',{name:'末页',exact:true}).click()
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await page.getByRole('button',{name:'删除',exact:true}).click()
  await expect(page.locator('tbody tr')).toHaveCount(10)
  await expect(nav).toContainText('第 2 / 2 页')
})

test('detail and history lists expose bounded pages rather than expanded records', async ({ page }) => {
  await backend(page,(_,path)=>{
    if(path==='/api/hosts/1')return{id:1,host_name:'host'}
    if(path==='/api/hosts/1/metrics')return{disk_metrics:Array.from({length:25},(_,i)=>({mount:`/disk_${i}`,used_percent:10}))}
    if(path==='/api/hosts/1/processes')return Array.from({length:25},(_,i)=>({id:i+1,process_name:`process_${i+1}`}))
    if(path==='/api/data-send/history')return Array.from({length:25},(_,i)=>({id:i+1,success:true,message:`sent_${i+1}`}))
    if(path==='/api/services/1')return{id:1,service_name:'service',service_type:'web'}
    if(path==='/api/services/1/results')return Array.from({length:25},(_,i)=>({id:i+1,status:'UP',message:`check_${i+1}`}))
    if(path==='/api/alerts')return Array.from({length:25},(_,i)=>({id:i+1,alert_status:'success',alert_content:`alert_${i+1}`}))
    if(path==='/api/alert-tasks')return{items:[],total:0}
  })
  for(const [route,label] of [['/hosts/1','磁盘分区'],['/hosts/1','主机进程'],['/data-send','数据发送历史'],['/services/1','最近探测记录'],['/alerts/recent','告警消息']]){
    await page.goto('/#'+route)
    const nav=page.getByRole('navigation',{name:label+'分页'}),panel=nav.locator('..')
    await expect(panel.locator('tbody tr')).toHaveCount(10)
    await nav.getByRole('button',{name:'末页',exact:true}).click()
    await expect(panel.locator('tbody tr')).toHaveCount(5)
  }
})
