import {chromium} from '../live-monitor-java/frontend/node_modules/playwright-core/index.mjs'
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs'
import assert from 'node:assert/strict'
const base=process.env.LIVE_MONITOR_VALIDATION_TUNNEL_URL || 'http://127.0.0.1:18588', auth=JSON.parse(readFileSync(process.env.LIVE_MONITOR_VALIDATION_AUTH_FILE || 'target/server-validation/browser-auth-private.json'))
assert.equal(new URL(base).hostname,'127.0.0.1','Use an SSH tunnel for server validation')
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']})
try{
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[]
 page.on('pageerror',e=>errors.push(e.message))
 const login=await context.request.post(base+'/api/auth/login',{data:{username:auth.username,password:auth.password}});assert.equal(login.status(),200)
 const failed=await context.request.post(base+'/api/auth/login',{data:{username:'admin',password:'000000'}});assert.equal(failed.status(),401)
 const checks={defaultPasswordRejected:true}
 mkdirSync('target/server-validation/screenshots',{recursive:true})
 for(const section of ['overview','servers','images','deployments','containers','backups','alerts','tasks','permissions']){
  await page.goto(base+'/#/ops/'+section);await page.locator('.ops-workspace h1').waitFor()
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false)
  await page.screenshot({path:`target/server-validation/screenshots/${section}.png`});checks[section]=true
 }
 await page.setViewportSize({width:390,height:844});await page.goto(base+'/#/ops/deployments');await page.locator('.ops-workspace h1').waitFor();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false)
 await page.screenshot({path:'target/server-validation/screenshots/mobile.png'});checks.mobile=true
 assert.deepEqual(errors,[]);writeFileSync('target/server-validation/browser.json',JSON.stringify({checks,errors,transport:'SSH encrypted tunnel',server:process.env.LIVE_MONITOR_VALIDATION_SERVER || 'authorized-server'},null,2));console.log(JSON.stringify({checks,errors}))
}finally{await browser.close()}
