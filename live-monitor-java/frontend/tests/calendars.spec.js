const {test,expect}=require('@playwright/test')
const sample="delete from t_ld_working_calendar where ND=2026;\ninsert into t_ld_working_calendar (ZRR,GZR,ND) values (20260101,20251231,2026);"
test('calendar SQL updates require preview and confirmation; date mappings can be queried',async({page})=>{
 let applied,submitted;const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{const r=route.request(),path=new URL(r.url()).pathname;let json=[];
 if(path==='/api/auth/me')json={authenticated:true,user_id:'admin',admin:true};
 if(path==='/api/calendars/trading'||path==='/api/calendars/working')json={items:[{zrr:20260101,gzr:20251231,jyr:20251231,nd:2026}],total:1,page:1,page_size:20};
 if(path==='/api/calendars/import/preview'){submitted=r.postDataJSON();json={preview_hash:'test-hash',calendars:{working:{row_count:1,delete_count:2,delete_years:[2026],sample:[{ZRR:20260101,GZR:20251231}],mode:'先删除年度再插入'}}};}
 if(path==='/api/calendars/import/apply'){applied=r.postDataJSON();json={working:1};}
 if(path.endsWith('/resolve'))json={natural_date:20260101,values:[20251231],etl_date:'2025-12-31',status:'RESOLVED'};
 await route.fulfill({json});});
 await page.goto('/#/system/calendars');await expect(page.getByRole('heading',{name:'业务日历管理',exact:true})).toBeVisible();
 await expect(page.getByRole('tabpanel',{name:'日历查询维护'})).toBeVisible();await expect(page.getByRole('heading',{name:'上传 SQL 更新日历',exact:true})).not.toBeVisible();
 await page.getByRole('tab',{name:'SQL 导入更新',exact:true}).click();
 await page.getByLabel('选择 .sql 文件').setInputFiles({name:'working-2026.sql',mimeType:'application/sql',buffer:Buffer.from(sample)});
 await page.getByRole('button',{name:'预览更新',exact:true}).click();await expect(page.getByText(/预计删除 2 条/)).toBeVisible();
 await page.getByRole('tab',{name:'日历查询维护',exact:true}).click();await page.getByLabel('自然日起').fill('2026-01-01');await page.getByRole('tab',{name:'SQL 导入更新',exact:true}).click();await expect(page.getByText('文件：working-2026.sql',{exact:true})).toBeVisible();await expect(page.getByRole('heading',{name:'更新预览',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'确认导入更新',exact:true})).toBeDisabled();expect(applied).toBeUndefined();expect(submitted.sql).toBe(sample);
 await page.getByLabel('我已核对日历类型、删除年度和写入数据').check();await page.getByRole('button',{name:'确认导入更新',exact:true}).click();await expect.poll(()=>applied).toBeTruthy();expect(applied.preview_hash).toBe('test-hash');expect(applied.sql).toBe(sample);
 await page.getByRole('tab',{name:'ETL_Date 映射查询',exact:true}).click();
 await page.getByLabel('自然日',{exact:true}).fill('2026-01-01');await page.getByRole('button',{name:'查询对应业务日期'}).click();await expect(page.getByText('ETL_Date：2025-12-31',{exact:true})).toBeVisible();
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(errors).toEqual([]);
})
test('read-only accounts can query calendars but do not see maintenance actions',async({page})=>{
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;return route.fulfill({json:path==='/api/auth/me'?{authenticated:true,user_id:'viewer',admin:false}:path.startsWith('/api/calendars/')?{items:[],total:0,page:1,page_size:20}:[]})});
 await page.goto('/#/system/calendars');await expect(page.getByRole('heading',{name:'业务日历管理',exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(2);await expect(page.getByRole('tab',{name:'SQL 导入更新',exact:true})).toHaveCount(0);const first=page.getByRole('tab',{name:'日历查询维护',exact:true});await first.focus();await page.keyboard.press('ArrowRight');await expect(page.getByRole('tab',{name:'ETL_Date 映射查询',exact:true})).toBeFocused();await page.keyboard.press('Home');await expect(first).toBeFocused();await expect(page.getByRole('button',{name:'新增记录',exact:true})).toHaveCount(0);await expect(page.getByRole('heading',{name:'上传 SQL 更新日历',exact:true})).toHaveCount(0);
})
