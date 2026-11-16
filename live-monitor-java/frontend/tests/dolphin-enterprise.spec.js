const {test,expect}=require('@playwright/test')
async function backend(page,options={}){
 const requests=[],errors=[];page.on('pageerror',e=>errors.push(e.message));const now=new Date().toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'});
 const project={id:'123',name:'业务项目',owner:'scheduler-owner',online_count:2,workflow_count:3,task_definition_count:12,etl_dates:['2026-11-13'],not_executed_workflows:0,unclassified_workflows:0,workflow_statistics:{total:3,running:1,success:1,waiting:0,failed:1},config:{primary_owner:'alice',group_ids:'[2]',alert_enabled:1}};
 await page.route('**/api/**',async route=>{const r=route.request(),u=new URL(r.url()),p=u.pathname;requests.push({path:p,query:Object.fromEntries(u.searchParams),method:r.method(),body:r.postDataJSON?.()});let body=[];
  if(p==='/api/auth/me')body={authenticated:true,user_id:'admin',admin:true,name:'admin'};
  else if(p==='/api/dolphinscheduler/connections')body=[{id:7,service_name:'测试集群',config_json:JSON.stringify(options.config || {}),check_interval:60}];
  else if(p==='/api/dolphinscheduler/connections/7'&&r.method()==='PUT'){options.config=r.postDataJSON().config;body={id:7};}
  else if(p==='/api/alert-groups')body=options.groups || [{id:2,group_name:'业务运维'}];
  else if(p.endsWith('/monitor/summary'))body={project_count:1,online_workflows:2,task_definitions:12,not_executed_workflows:0,pending_deliveries:0,enterprise_enabled:true,projects:[project],workflow_statistics:{total:3,running:1,success:1,failed:1,waiting:0},collection:['inventory','runtime','users','resources'].map(lane=>({lane,last_success:now,failures:0})),nodes:[{id:'masters:node:5678',role:'masters',host:'node',cpu_percent:20,host_memory_percent:60,jvm_available:false,online:true,heartbeat:now,waiting_tasks:0,execution_threads:0}],databases:[]};
  else if(p.endsWith('/monitor/projects'))body=[project];
  else if(p.endsWith('/monitor/users'))body=[{id:1,userName:'alice'}];
  else if(p.endsWith('/monitor/definitions'))body=[{id:'77',definition_code:'77',name:'业务工作流',releaseState:'ONLINE',scheduleReleaseState:'OFFLINE',task_count:12,userName:'scheduler-owner'}];
  else if(p.endsWith('/authorizations'))body=[{id:'123:1',userName:'alice'}];
  else if(p.endsWith('/monitor/instances'))body={items:[{id:'10',name:'业务实例',definition_code:'77',etl_date:'2026-11-13',etl_date_source:'task_parameters',state:'SUCCESS'}],page:1,total:1};
  else if(p.endsWith('/monitor/business-dates'))body=[{etl_date:'2026-11-13',total:1,success:1,running:0,failed:0,waiting:0,unfinished:0}];
  else if(p.endsWith('/config'))body={...project.config,...r.postDataJSON()};
  else if(p.endsWith('/monitor/test'))body={project_count:1,status:'connected'};
  else if(p.endsWith('/monitor/sync'))body={status:'queued'};
  else if(p==='/api/dashboard')body={summary:{}};
  else if(p==='/api/system-metrics')body={};
  if(p.endsWith('/monitor/summary')&&options.incomplete){body.not_executed_workflows=null;body.execution_coverage_reliable=false;body.projects[0].not_executed_workflows=null;}
  if(options.multiple&&(p.endsWith('/monitor/summary')||p.endsWith('/monitor/projects'))){const rows=[project,{...project,id:'456',name:'第二项目',workflow_statistics:{total:4,running:1,success:0,failed:3,waiting:0}}];if(p.endsWith('/monitor/projects'))body=rows;else{body.projects=rows.filter(r=>!u.searchParams.get('project')||u.searchParams.get('project')===r.id);body.project_count=body.projects.length;}}
  if(options.events&&p.endsWith('/monitor/incidents'))body=[{incident_key:'active',project_code:'123',category:'TASK_FAILURE',message:'active-issue',active:1},{incident_key:'suppressed',project_code:'123',category:'AGGREGATED_TIMEOUT',message:'suppressed-issue',active:2},{incident_key:'recovered',project_code:'123',category:'TASK_FAILURE',message:'recovered-issue',active:0}];
  if(options.empty&&p==='/api/dolphinscheduler/connections')body=[];
  if(options.unavailable&&p.endsWith('/monitor/summary'))body.nodes[0]={...body.nodes[0],online:false,cpu_percent:null,host_memory_percent:null};
  await route.fulfill({json:body});
 });return {requests,errors};
}
test('single DolphinScheduler monitor project drilldown preserves owner mapping and separate schedule state',async({page})=>{
 const {requests,errors}=await backend(page);await page.goto('/#/dolphinscheduler');await expect(page.getByRole('heading',{name:'DolphinScheduler 监控',exact:true})).toBeVisible();await expect(page.locator('aside').getByRole('link',{name:'DolphinScheduler 监控',exact:true})).toHaveCount(1);await expect(page.locator('aside').getByRole('link',{name:/驾驶舱/})).toHaveCount(0);await expect(page.getByRole('heading',{name:'项目健康',exact:true})).toBeVisible();await expect(page.locator('.project-table')).toHaveCount(0);
 await page.getByRole('button',{name:'业务项目',exact:true}).click();await expect(page.getByText('业务工作流',{exact:true})).toBeVisible();await expect(page.getByRole('cell',{name:'未启用',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'配置责任人',exact:true}).click();const dialog=page.getByRole('dialog',{name:'项目责任人与告警组'});await dialog.getByLabel('主要责任人').fill('business-owner');await dialog.getByRole('button',{name:'保存',exact:true}).click();
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/projects/123/config')&&r.method==='PUT')).toBe(true);const saved=requests.find(r=>r.path.endsWith('/projects/123/config')&&r.method==='PUT');expect(saved.body.primary_owner).toBe('business-owner');expect(saved.body.group_ids).toEqual([2]);expect(errors).toEqual([]);
})

test('overview prioritizes failures and offers project and resource drilldowns',async({page})=>{
 const {requests,errors}=await backend(page,{multiple:true});await page.goto('/#/dolphinscheduler');
 await expect(page.locator('.project-table')).toHaveCount(0);
 await expect(page.locator('.project-health-card').first()).toContainText('第二项目');
 await page.getByLabel('搜索概览项目').fill('123');await expect(page.locator('.project-health-card')).toHaveCount(1);
 await page.locator('.attention-item').filter({hasText:'业务项目'}).click();
 await expect(page.getByRole('tab',{name:'任务运行明细',exact:true})).toHaveAttribute('aria-selected','true');
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/monitor/instances')&&r.query.project==='123'&&r.query.state==='FAILURE')).toBe(true);
 await page.getByRole('tab',{name:'监控总览',exact:true}).click();await page.getByRole('button',{name:/资源详情/}).click();
 await expect(page.getByRole('tab',{name:'集群资源',exact:true})).toHaveAttribute('aria-selected','true');expect(errors).toEqual([]);
})

test('historical cockpit link redirects to one monitor and incomplete counts remain unknown',async({page})=>{
 const {errors}=await backend(page,{incomplete:true});await page.goto('/#/dolphinscheduler/enterprise');
 await expect(page).toHaveURL(/#\/dolphinscheduler$/);
 await expect(page.getByRole('heading',{name:'DolphinScheduler 监控',exact:true})).toBeVisible();
 await expect(page.getByRole('tab',{name:'企业驾驶舱'})).toHaveCount(0);
 const card=page.locator('.metric').filter({hasText:'未执行工作流'});await expect(card.locator('strong')).toHaveText('未知');
 await expect(page.getByText(/当前查询范围尚未完整采集/)).toBeVisible();
 await expect(page.getByRole('heading',{name:'集群资源概览',exact:true})).toBeVisible();
 expect(errors).toEqual([]);
})

test('project and business date apply to summary and instances together',async({page})=>{
 const {requests,errors}=await backend(page);await page.goto('/#/dolphinscheduler');
 await page.getByLabel('项目',{exact:true}).selectOption('123');
 await page.getByLabel('业务日期',{exact:true}).fill('2026-11-13');
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/monitor/summary')&&r.query.project==='123'&&r.query.etl_date==='2026-11-13')).toBe(true);
 await page.getByRole('tab',{name:'任务运行明细'}).click();
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/monitor/instances')&&r.query.project==='123'&&r.query.etl_date==='2026-11-13')).toBe(true);
 expect(errors).toEqual([]);
})
test('business date filters and missing JVM are explicit, including on mobile',async({page})=>{
 const {requests,errors}=await backend(page);await page.goto('/#/dolphinscheduler');await page.getByRole('tab',{name:'任务运行明细'}).click();await expect(page.getByRole('row').filter({hasText:'业务实例'})).toBeVisible();await page.getByLabel('业务日期',{exact:true}).fill('2026-11-13');await page.getByRole('button',{name:'查询',exact:true}).click();await expect.poll(()=>requests.some(r=>r.query.etl_date==='2026-11-13')).toBe(true);
 await page.getByRole('tab',{name:'集群资源'}).click();await expect(page.getByRole('cell',{name:'60.0%',exact:true})).toBeVisible();await expect(page.getByRole('cell',{name:'不可用',exact:true})).toBeVisible();
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(errors).toEqual([]);
})

test('project list prioritizes failures, searches locally and restores empty filters',async({page})=>{
 const {errors}=await backend(page,{multiple:true});await page.goto('/#/dolphinscheduler');
 await page.getByRole('tab',{name:'项目明细',exact:true}).click();const rows=page.locator('.project-table tbody tr');await expect(rows.first()).toContainText('第二项目');
 await page.getByLabel('搜索项目名称或编码').fill('123');await expect(rows).toHaveCount(1);await expect(rows.first()).toContainText('业务项目');
 await page.getByLabel('清空项目搜索').click();await expect(rows).toHaveCount(2);
 await page.getByRole('button',{name:/有未执行/}).click();await expect(page.locator('.project-table').getByText('没有匹配的项目',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'清除列表筛选',exact:true}).click();await expect(rows).toHaveCount(2);expect(errors).toEqual([]);
})

test('metric cards drill into complete success and waiting status groups',async({page})=>{
 const {requests,errors}=await backend(page);await page.goto('/#/dolphinscheduler');
 await page.locator('.metric').filter({hasText:'已成功实例'}).click();
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/monitor/instances')&&r.query.state==='success')).toBe(true);
 await page.getByRole('tab',{name:'监控总览',exact:true}).click();await page.locator('.metric').filter({hasText:'待执行实例'}).click();
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/monitor/instances')&&r.query.state==='waiting')).toBe(true);expect(errors).toEqual([]);
})

test('unapplied custom dates are marked and do not relabel old results',async({page})=>{
 const {requests,errors}=await backend(page);await page.goto('/#/dolphinscheduler');
 await page.getByLabel('执行时间范围',{exact:true}).selectOption('custom');await expect(page.locator('.metric').first()).toBeEnabled();
 await page.getByLabel('开始时间',{exact:true}).fill('2026-11-12T00:00');
 await expect(page.getByText('筛选已更改，请应用筛选',{exact:true})).toBeVisible();await expect(page.locator('.metric').first()).toBeDisabled();
 await page.getByRole('button',{name:'应用筛选',exact:true}).click();
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/monitor/summary')&&r.query.start==='2026-11-12T00:00:00')).toBe(true);
 await expect(page.getByText('筛选已更改，请应用筛选',{exact:true})).toHaveCount(0);expect(errors).toEqual([]);
})

test('alerts default to unrecovered events and retain aggregated history',async({page})=>{
 const {errors}=await backend(page,{events:true});await page.goto('/#/dolphinscheduler');await page.getByRole('tab',{name:'项目告警',exact:true}).click();
 await expect(page.getByText('active-issue',{exact:true})).toBeVisible();await expect(page.getByText('suppressed-issue',{exact:true})).toHaveCount(0);
 await page.getByRole('button',{name:'查看详情',exact:true}).click();const detail=page.getByRole('dialog',{name:'告警详情',exact:true});await expect(detail.getByText('active-issue',{exact:true})).toBeVisible();await detail.getByRole('button',{name:'关闭详情',exact:true}).last().click();
 await page.getByLabel('事件状态',{exact:true}).selectOption('aggregated');await expect(page.getByText('suppressed-issue',{exact:true})).toBeVisible();await expect(page.getByText('active-issue',{exact:true})).toHaveCount(0);
 await page.getByLabel('事件状态',{exact:true}).selectOption('recovered');await expect(page.getByText('recovered-issue',{exact:true})).toBeVisible();expect(errors).toEqual([]);
})

test('tabs support keyboard navigation and the management popover dismisses',async({page})=>{
 const {errors}=await backend(page);await page.goto('/#/dolphinscheduler');const home=page.getByRole('tab',{name:'监控总览',exact:true});await home.focus();await page.keyboard.press('ArrowRight');await expect(page.getByRole('tab',{name:'项目明细',exact:true})).toBeFocused();await page.keyboard.press('ArrowRight');
 await expect(page.getByRole('tab',{name:'工作流定义',exact:true})).toBeFocused();await expect(page.getByText('选择项目，查看工作流定义',{exact:true})).toBeVisible();
 await page.keyboard.press('End');await expect(page.getByRole('tab',{name:'采集配置',exact:true})).toBeFocused();await page.keyboard.press('Home');await expect(home).toBeFocused();
 await page.getByRole('button',{name:'管理',exact:true}).click();await expect(page.getByRole('button',{name:'连接设置',exact:true})).toBeVisible();await page.getByRole('button',{name:'连接设置',exact:true}).focus();await page.keyboard.press('Escape');await expect(page.getByRole('button',{name:'管理',exact:true})).toBeFocused();
 await expect(page.getByRole('button',{name:'连接设置',exact:true})).toHaveCount(0);expect(errors).toEqual([]);
})

test('automatic refresh can be paused without changing collected counts',async({page})=>{
 await page.clock.install();const {requests,errors}=await backend(page);await page.goto('/#/dolphinscheduler');await expect(page.locator('.metric').first()).toBeEnabled();
 await page.getByLabel('每 30 秒自动刷新',{exact:true}).uncheck();const before=requests.filter(r=>r.path.endsWith('/monitor/summary')).length;
 await page.clock.runFor(31000);expect(requests.filter(r=>r.path.endsWith('/monitor/summary')).length).toBe(before);
 await page.getByLabel('每 30 秒自动刷新',{exact:true}).check();await page.clock.runFor(31000);
 await expect.poll(()=>requests.filter(r=>r.path.endsWith('/monitor/summary')).length).toBeGreaterThan(before);expect(errors).toEqual([]);
})

test('phone project cards expose failure actions without horizontal scrolling',async({page})=>{
 await page.setViewportSize({width:390,height:844});const {requests,errors}=await backend(page);await page.goto('/#/dolphinscheduler');
 await expect(page.locator('.project-health-grid')).toBeVisible();await expect(page.locator('.project-table')).toHaveCount(0);
 await page.getByRole('button',{name:'业务项目的失败实例 1',exact:true}).click();
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/monitor/instances')&&r.query.project==='123'&&r.query.state==='FAILURE')).toBe(true);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(errors).toEqual([]);
})

test('empty connections use onboarding instead of fabricated zero metrics',async({page})=>{
 const {requests,errors}=await backend(page,{empty:true});await page.goto('/#/dolphinscheduler');
 await expect(page.getByRole('heading',{name:'连接你的 DolphinScheduler 集群',exact:true})).toBeVisible();await expect(page.locator('.metric')).toHaveCount(0);
 expect(requests.some(r=>r.path.endsWith('/monitor/summary'))).toBe(false);await page.getByRole('button',{name:'新增连接',exact:true}).click();
 await expect(page.getByRole('dialog',{name:'新增 DolphinScheduler 连接',exact:true})).toBeVisible();expect(errors).toEqual([]);
})

test('unavailable node metrics show unknown instead of zero percent',async({page})=>{
 const {errors}=await backend(page,{unavailable:true});await page.goto('/#/dolphinscheduler');const master=page.locator('.role-summary').filter({hasText:'Master'});
 await expect(master).toContainText('0 / 1 在线');await expect(master.locator('.role-values strong')).toHaveText(['未知','未知']);expect(errors).toEqual([]);
})

test('default operation groups use shared checkbox styling and preserve numeric selections',async({page})=>{
 const {requests,errors}=await backend(page,{config:{default_ops_group_ids:[2]},groups:[{id:2,group_name:'生产邮件告警',enabled:true},{id:3,group_name:'短信告警',enabled:true}]});
 await page.goto('/#/dolphinscheduler');await page.getByRole('tab',{name:'采集配置',exact:true}).click();
 const groups=page.getByRole('group',{name:'默认运维告警组（可多选）',exact:true});
 await expect(groups.getByLabel('生产邮件告警',{exact:true})).toBeChecked();await expect(groups.getByLabel('短信告警',{exact:true})).not.toBeChecked();
 await groups.getByLabel('短信告警',{exact:true}).check();await groups.getByLabel('生产邮件告警',{exact:true}).uncheck();
 await page.getByRole('button',{name:'保存配置',exact:true}).click();
 await expect.poll(()=>requests.some(r=>r.path==='/api/dolphinscheduler/connections/7'&&r.method==='PUT')).toBe(true);
 expect(requests.find(r=>r.path==='/api/dolphinscheduler/connections/7'&&r.method==='PUT').body.config.default_ops_group_ids).toEqual([3]);
 await page.reload();await page.getByRole('tab',{name:'采集配置',exact:true}).click();await expect(groups.getByLabel('短信告警',{exact:true})).toBeChecked();await expect(groups.getByLabel('生产邮件告警',{exact:true})).not.toBeChecked();expect(errors).toEqual([]);
})
