const {test,expect}=require('@playwright/test')
async function backend(page,options={}){
 const requests=[],errors=[];page.on('pageerror',e=>errors.push(e.message));const now=new Date().toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'});
 const project={id:'123',name:'业务项目',owner:'scheduler-owner',online_count:2,workflow_count:3,task_definition_count:12,etl_dates:['2026-11-13'],not_executed_workflows:0,unclassified_workflows:0,workflow_statistics:{total:3,running:1,success:1,waiting:0,failed:1},config:{primary_owner:'alice',group_ids:'[2]',alert_enabled:1}};
 await page.route('**/api/**',async route=>{const r=route.request(),u=new URL(r.url()),p=u.pathname;requests.push({path:p,query:Object.fromEntries(u.searchParams),method:r.method(),body:r.postDataJSON?.()});let body=[];
  if(p==='/api/auth/me')body={authenticated:true,user_id:'admin',admin:true,name:'admin'};
  else if(p==='/api/dolphinscheduler/connections')body=[{id:7,service_name:'测试集群',config_json:'{}',check_interval:60}];
  else if(p==='/api/alert-groups')body=[{id:2,group_name:'业务运维'}];
  else if(p.endsWith('/monitor/summary'))body={project_count:1,online_workflows:2,task_definitions:12,not_executed_workflows:0,pending_deliveries:0,enterprise_enabled:true,projects:[project],workflow_statistics:{total:3,running:1,success:1,failed:1},collection:['inventory','runtime','users','resources'].map(lane=>({lane,last_success:now,failures:0})),nodes:[{id:'masters:node:5678',role:'masters',host:'node',cpu_percent:20,host_memory_percent:60,jvm_available:false,online:true,heartbeat:now,waiting_tasks:0,execution_threads:0}],databases:[]};
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
  await route.fulfill({json:body});
 });return {requests,errors};
}
test('single DolphinScheduler monitor project drilldown preserves owner mapping and separate schedule state',async({page})=>{
 const {requests,errors}=await backend(page);await page.goto('/#/dolphinscheduler');await expect(page.getByRole('heading',{name:'DolphinScheduler 监控',exact:true})).toBeVisible();await expect(page.locator('aside').getByRole('link',{name:'DolphinScheduler 监控',exact:true})).toHaveCount(1);await expect(page.locator('aside').getByRole('link',{name:/驾驶舱/})).toHaveCount(0);await expect(page.getByRole('columnheader',{name:'已成功',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'业务项目',exact:true}).click();await expect(page.getByText('业务工作流',{exact:true})).toBeVisible();await expect(page.getByRole('cell',{name:'OFFLINE',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'配置责任人',exact:true}).click();const dialog=page.getByRole('dialog',{name:'项目责任人与告警组'});await dialog.getByLabel('主要责任人').fill('business-owner');await dialog.getByRole('button',{name:'保存',exact:true}).click();
 await expect.poll(()=>requests.some(r=>r.path.endsWith('/projects/123/config')&&r.method==='PUT')).toBe(true);const saved=requests.find(r=>r.path.endsWith('/projects/123/config')&&r.method==='PUT');expect(saved.body.primary_owner).toBe('business-owner');expect(saved.body.group_ids).toEqual([2]);expect(errors).toEqual([]);
})

test('historical cockpit link redirects to one monitor and incomplete counts remain unknown',async({page})=>{
 const {errors}=await backend(page,{incomplete:true});await page.goto('/#/dolphinscheduler/enterprise');
 await expect(page).toHaveURL(/#\/dolphinscheduler$/);
 await expect(page.getByRole('heading',{name:'DolphinScheduler 监控',exact:true})).toBeVisible();
 await expect(page.getByRole('tab',{name:'企业驾驶舱'})).toHaveCount(0);
 const card=page.locator('.metric').filter({hasText:'未执行工作流'});await expect(card.locator('strong')).toHaveText('未知');
 await expect(page.getByText(/当前查询范围尚未完整采集/)).toBeVisible();
 await expect(page.getByRole('heading',{name:'Master / Worker',exact:true})).toBeVisible();
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
