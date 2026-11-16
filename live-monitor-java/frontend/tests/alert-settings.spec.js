const {test,expect}=require('@playwright/test')

async function backend(page, options={}) {
 const errors=[],writes=[];page.on('pageerror',e=>errors.push(e.message))
 const channels=[{id:3,channel_name:'生产邮件',channel_type:'email',smtp_host:'smtp.example.com',smtp_port:465,smtp_from:'sender@example.com',alert_email:'ops@example.com,backup@example.com',alert_cc:'cc@example.com',smtp_auth:true,smtp_use_ssl:true,enabled:true},{id:4,channel_name:'短信通知',channel_type:'sms',sms_api_url:'http://sms.example.com/send',alert_mobile:'13800000000',enabled:true}]
 const groups=[{id:1,group_name:'项目运维',enabled:true,channel_ids:[3,4],channels:channels.slice(),policy_ids:[9]},{id:2,group_name:'短信值班',enabled:true,channel_ids:[4],channels:[channels[1]],policy_ids:[9]}]
 const projects=[{id:55,project_name:'IDS项目',match_keyword:'IDS',description:'生产业务',alert_group_ids:[1],alert_group_names:['项目运维'],enabled:true}]
 await page.route('**/api/**',async route=>{
  const r=route.request(),p=new URL(r.url()).pathname;let json=[]
  if(r.method()!=='GET')writes.push({path:p,body:r.postDataJSON()})
  if(p==='/api/auth/me')json={authenticated:true,user_id:'admin',admin:true}
  else if(p==='/api/services')json=[{id:90,service_name:'外部消息服务',service_type:'external_message',enabled:true,alert_group_ids:[1]}]
  else if(p==='/api/alert-channels')json=channels
  else if(p==='/api/alert-groups')json=groups
  else if(p==='/api/external-projects')json=projects
  else if(p==='/api/alert-policies')json=Array.from({length:options.manyPolicies?30:3},(_,i)=>({id:i+9,policy_name:'触发策略 '+i,trigger_type:'consecutive_down',trigger_value:'3',enabled:true}))
  else if(p==='/api/alert-channels/3'&&r.method()==='PUT'){Object.assign(channels[0],r.postDataJSON());json=channels[0]}
  else if(p==='/api/alert-groups/1'){if(r.method()==='PUT')Object.assign(groups[0],r.postDataJSON());json=groups[0]}
  else if(p==='/api/external-projects/55'){if(r.method()==='PUT'){Object.assign(projects[0],r.postDataJSON());projects[0].alert_group_names=groups.filter(g=>projects[0].alert_group_ids.includes(g.id)).map(g=>g.group_name)}json=projects[0]}
  await route.fulfill({json})
 })
 return {errors,writes}
}

test('restored project routing selects groups and exposes actual recipients in the contact list',async({page})=>{
 const {errors,writes}=await backend(page);await page.goto('/#/alerts/settings')
 await page.getByRole('button',{name:'项目告警配置',exact:true}).click()
 const row=page.getByRole('row').filter({hasText:'IDS项目'})
 await expect(row).toContainText('ops@example.com');await expect(row).toContainText('13800000000')
 await row.getByRole('button',{name:'编辑',exact:true}).click()
 const editor=page.locator('.project-editor');await editor.getByLabel('项目运维',{exact:true}).uncheck();await editor.getByLabel('短信值班',{exact:true}).check();await editor.getByRole('button',{name:'保存项目',exact:true}).click()
 await expect.poll(()=>writes.find(r=>r.path==='/api/external-projects/55')?.body.alert_group_ids).toEqual([2])
 await expect(row).not.toContainText('ops@example.com');await expect(row).toContainText('13800000000')
 await page.getByRole('button',{name:'告警联系人',exact:true}).click();await page.getByLabel('搜索联系人').fill('IDS项目')
 await expect(page.locator('.contacts-panel tbody tr')).toHaveCount(1);await expect(page.locator('.contacts-panel tbody tr')).toContainText('短信通知')
 await page.screenshot({path:'../../tmp/alert-contacts-list.png',fullPage:true});expect(errors).toEqual([])
})

test('recipient lists edit multiple addresses without overwriting credentials or other delivery settings',async({page})=>{
 const {errors,writes}=await backend(page);await page.goto('/#/alerts/settings');await page.getByRole('button',{name:'告警联系人',exact:true}).click()
 await page.getByLabel('搜索联系人').fill('ops@example.com');await page.getByRole('button',{name:'维护联系人',exact:true}).click()
 const dialog=page.getByRole('dialog',{name:'编辑通知渠道',exact:true});await expect(dialog).toBeVisible()
 await dialog.getByLabel('渠道名称',{exact:true}).fill('邮件值班联系人')
 await dialog.getByLabel('添加邮件接收人',{exact:true}).fill('invalid-address');await dialog.getByRole('button',{name:'保存渠道',exact:true}).click();await expect(dialog.getByRole('alert')).toContainText('格式');expect(writes).toEqual([])
 await dialog.getByRole('button',{name:'移除backup@example.com',exact:true}).click()
 await dialog.getByLabel('添加邮件接收人',{exact:true}).fill('new@example.com;ops@example.com\nnew@example.com');await dialog.getByLabel('添加邮件接收人',{exact:true}).press('Enter')
 await expect(dialog.getByRole('region',{name:'邮件接收人',exact:true}).locator('tbody tr')).toHaveCount(2)
 await dialog.getByLabel('STARTTLS',{exact:true}).check();await dialog.getByLabel('SSL',{exact:true}).uncheck()
 await dialog.locator('.modal-body').evaluate(el=>{el.scrollTop=el.scrollHeight});await page.screenshot({path:'../../tmp/alert-channel-options.png',fullPage:true})
 await dialog.getByRole('button',{name:'保存渠道',exact:true}).click();await expect(dialog).toHaveCount(0)
 const saved=writes.find(r=>r.path==='/api/alert-channels/3')?.body
 expect(saved.channel_name).toBe('邮件值班联系人');expect(saved.alert_email).toBe('ops@example.com,new@example.com');expect(saved.alert_cc).toBe('cc@example.com');expect(saved.smtp_from).toBe('sender@example.com');expect(saved.smtp_use_tls).toBe(true);expect(saved.smtp_use_ssl).toBe(false);expect(saved).not.toHaveProperty('smtp_password')
 await page.getByLabel('搜索联系人').fill('new@example.com');await expect(page.locator('.contacts-panel tbody tr')).toContainText('邮件值班联系人');expect(errors).toEqual([])
})

test('group dialog has one scroll body, keeps selected policies, and returns keyboard focus',async({page})=>{
 const {errors,writes}=await backend(page,{manyPolicies:true});await page.goto('/#/alerts/settings');await page.getByRole('button',{name:'告警渠道与告警组',exact:true}).click()
 const edit=page.getByRole('row').filter({hasText:'项目运维'}).getByRole('button',{name:'编辑',exact:true});await edit.click()
 const dialog=page.getByRole('dialog',{name:'编辑告警组',exact:true});await expect(dialog).toBeVisible();const body=dialog.locator('.modal-body')
 expect(await body.evaluate(el=>el.scrollHeight>el.clientHeight)).toBe(true);await expect(dialog.getByRole('button',{name:'保存告警组',exact:true})).toBeInViewport()
 await page.screenshot({path:'../../tmp/alert-group-layout.png',fullPage:true});await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(edit).toBeFocused()
 await edit.click();await dialog.getByLabel('短信通知 (SMS)',{exact:true}).uncheck();await dialog.getByLabel('启用告警组',{exact:true}).uncheck();await dialog.getByRole('button',{name:'保存告警组',exact:true}).click()
 expect(writes.find(r=>r.path==='/api/alert-groups/1')?.body).toMatchObject({channel_ids:[3],policy_ids:[9],enabled:false})
 await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'新建渠道',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await expect(page.getByRole('button',{name:'保存渠道',exact:true})).toBeInViewport();await page.screenshot({path:'../../tmp/alert-channel-mobile.png',fullPage:true});expect(errors).toEqual([])
})
