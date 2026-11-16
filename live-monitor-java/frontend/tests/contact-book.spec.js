const {test,expect}=require('@playwright/test')
async function backend(page){
 const errors=[],writes=[];page.on('pageerror',e=>errors.push(e.message))
 const contacts=[{id:1,name:'Alice',email:'alice@example.com',mobile:'13800000000',enabled:true,binding_count:0},{id:2,name:'Bob',email:'bob@example.com',enabled:true,binding_count:0}]
 const group={id:1,group_name:'生产告警组',enabled:true,channel_ids:[3],policy_ids:[9],contact_ids:[]}
 const project={id:5,project_name:'生产项目',match_keyword:'production',alert_group_ids:[1],alert_group_names:['生产告警组'],contact_ids:[],enabled:true}
 await page.route('**/api/**',async route=>{
  const r=route.request(),p=new URL(r.url()).pathname;let json=[];if(r.method()!=='GET')writes.push({path:p,method:r.method(),body:r.postDataJSON()})
  if(p==='/api/auth/me')json={authenticated:true,user_id:'admin',admin:true}
  else if(p==='/api/alert-contacts'){
   if(r.method()==='POST'){const item={...r.postDataJSON(),id:contacts.length+1,binding_count:0};contacts.push(item);json=item}else json=contacts
  }else if(/^\/api\/alert-contacts\/\d+$/.test(p)){const id=Number(p.split('/').at(-1)),i=contacts.findIndex(c=>c.id===id);if(r.method()==='PUT')Object.assign(contacts[i],r.postDataJSON());if(r.method()==='DELETE')contacts.splice(i,1);json=contacts[i]||{}}
  else if(p==='/api/alert-channels')json=[{id:3,channel_name:'生产邮件',channel_type:'email',smtp_host:'smtp.example.com',enabled:true}]
  else if(p==='/api/alert-groups')json=[group]
  else if(p==='/api/alert-groups/1'){if(r.method()==='PUT'){Object.assign(group,r.postDataJSON());for(const c of contacts)c.binding_count=group.contact_ids.includes(c.id)?1:0}json=group}
  else if(p==='/api/alert-policies')json=[{id:9,policy_name:'连续失败',enabled:true,trigger_type:'consecutive_down',trigger_value:3}]
  else if(p==='/api/external-projects')json=[project]
  else if(p==='/api/external-projects/5'){if(r.method()==='PUT')Object.assign(project,r.postDataJSON());json=project}
  else if(p==='/api/services')json=[]
  await route.fulfill({json})
 })
 return {errors,writes,contacts}
}

test('independent contacts can be created, selected by group and overridden by project',async({page})=>{
 const {errors,writes}=await backend(page);await page.goto('/#/alerts/settings');await page.getByRole('button',{name:'告警联系人',exact:true}).click()
 const book=page.locator('.contacts-book');await expect(book.locator('tbody tr')).toHaveCount(2);await book.getByRole('button',{name:'新增联系人',exact:true}).click()
 const editor=page.getByRole('dialog',{name:'新增联系人',exact:true});await editor.getByLabel('姓名',{exact:true}).fill('Charlie');await editor.getByRole('button',{name:'保存联系人',exact:true}).click();await expect(editor.getByRole('alert')).toContainText('至少填写')
 await editor.getByLabel('邮箱',{exact:true}).fill('charlie@example.com');await editor.getByLabel('手机号',{exact:true}).fill('13700000000');await editor.getByRole('button',{name:'保存联系人',exact:true}).click();await expect(book.locator('tbody tr')).toHaveCount(3)
 await page.getByRole('button',{name:'告警渠道与告警组',exact:true}).click();await page.getByRole('row').filter({hasText:'生产告警组'}).getByRole('button',{name:'编辑',exact:true}).click()
 const group=page.getByRole('dialog',{name:'编辑告警组',exact:true});await group.getByLabel('选择联系人 Alice',{exact:true}).check();await group.getByLabel('选择联系人 Charlie',{exact:true}).check();await group.getByRole('button',{name:'保存告警组',exact:true}).click()
 await expect.poll(()=>writes.find(w=>w.path==='/api/alert-groups/1')?.body.contact_ids).toEqual([1,3])
 await page.getByRole('button',{name:'项目告警配置',exact:true}).click();let row=page.locator('.embedded-projects table').first().locator('tbody tr').first();await expect(row).toContainText('alice@example.com');await expect(row).toContainText('charlie@example.com');await row.getByRole('button',{name:'编辑',exact:true}).click()
 const project=page.locator('.project-editor');await project.getByLabel('选择联系人 Bob',{exact:true}).check();await project.getByRole('button',{name:'保存项目',exact:true}).click();await expect.poll(()=>writes.find(w=>w.path==='/api/external-projects/5')?.body.contact_ids).toEqual([2]);await expect(row).toContainText('bob@example.com');await expect(row).not.toContainText('alice@example.com')
 await page.getByRole('button',{name:'告警联系人',exact:true}).click();await page.screenshot({path:'../../tmp/contact-book-desktop.png',fullPage:true});expect(errors).toEqual([])
})

test('contact edits update address book, bound contacts can be disabled and unbound contacts deleted',async({page})=>{
 const {errors,writes,contacts}=await backend(page);contacts[0].binding_count=1;await page.goto('/#/alerts/settings');await page.getByRole('button',{name:'告警联系人',exact:true}).click()
 const book=page.locator('.contacts-book'),alice=book.getByRole('row').filter({hasText:'Alice'});await expect(alice.getByRole('button',{name:'删除',exact:true})).toBeDisabled();await alice.getByRole('button',{name:'编辑联系人',exact:true}).click()
 const editor=page.getByRole('dialog',{name:'编辑联系人',exact:true});await editor.getByLabel('邮箱',{exact:true}).fill('updated-alice@example.com');await editor.getByLabel('启用联系人',{exact:true}).uncheck();await editor.getByRole('button',{name:'保存联系人',exact:true}).click();await expect(alice).toContainText('停用');expect(writes.find(w=>w.path==='/api/alert-contacts/1')?.body).toMatchObject({email:'updated-alice@example.com',enabled:false})
 page.on('dialog',d=>d.accept());await book.getByRole('row').filter({hasText:'Bob'}).getByRole('button',{name:'删除',exact:true}).click();await expect(book.locator('tbody tr')).toHaveCount(1)
 await page.setViewportSize({width:390,height:844});await book.getByRole('button',{name:'新增联系人',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:'../../tmp/contact-book-mobile.png',fullPage:true});expect(errors).toEqual([])
})
