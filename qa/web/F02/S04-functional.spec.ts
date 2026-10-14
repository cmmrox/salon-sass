import {test,expect,handle,routes,mutations,cookie} from '../../shared/identity-browser';
import {alpha,beta,ROLES,syntheticAccount,totp,problem} from '../../shared/identity';
import {codeSettings} from '../../shared/identity-runtime';
import {randomInt} from 'node:crypto';
import {createHash} from 'node:crypto';
import {Profiles} from '../../shared/customer-profile';
import {salonUrl} from '../../shared/tenants';
import {webUrl} from '../../shared/env';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;

test(`${c(1)} staff password and authenticator flows reach account only after complete verification`,async({identity,accounts})=>{
 for(const [role,mfa] of [['MANAGER',false],['OWNER',true],['OWNER',false]] as const){
  const account=syntheticAccount(role,mfa);await identity.seed(alpha,[account]);const context=await accounts.context(alpha),page=await context.newPage();
  const actor=await accounts.staffSignIn(page,alpha,account);expect(actor.principal.role).toBe(role);expect(actor.principal.mfaEnabled).toBe(role==='OWNER');
 }
 const staff=await identity.staff(alpha,'RECEPTIONIST'),context=await accounts.context(alpha,[staff]),page=await accounts.open(context,routes.staff);await accounts.ready(page);
 await handle(page,'mfa-start').click();await expect(handle(page,'setup-key')).toBeVisible();const secret=await handle(page,'setup-key').textContent();expect(Boolean(secret)).toBe(true);
 await handle(page,'code').fill(totp(secret!,await identity.now()));await handle(page,'mfa-verify').click();await expect(handle(page,'mfa-enabled')).toBeVisible();
 await expect(handle(page,'setup-key')).toHaveCount(0);expect((await accounts.actor(context,alpha,'staff')).principal.mfaEnabled).toBe(true);
});
test(`${c(2)} staff authentication refusals preserve useful input and expose recovery`,async({identity,accounts})=>{
 const account=syntheticAccount('OWNER');await identity.seed(alpha,[account]);const context=await accounts.context(alpha),page=await accounts.open(context,routes.staffSignIn);
 await handle(page,'identifier').fill(account.email);await handle(page,'password').fill('A wrong synthetic password');await handle(page,'sign-in-submit').click();
 await expect(handle(page,'error')).toBeVisible();await expect(handle(page,'identifier')).toHaveValue(account.email);await expect(handle(page,'password')).toHaveValue('');
 await handle(page,'password').fill(account.password);await handle(page,'sign-in-submit').click();await expect(handle(page,'code')).toBeVisible();
 const frozen=await identity.now();await identity.clock(frozen);const refusedCode=totp(account.totpSecret!,frozen)==='000000'?'000001':'000000';await handle(page,'code').fill(refusedCode);await handle(page,'sign-in-submit').click();await expect(handle(page,'error')).toBeVisible();await expect(handle(page,'code')).toHaveValue('');
 const expires=(await context.cookies()).find(item=>item.name===cookie('staff','challenge_id'));expect(Boolean(expires)).toBe(true);
 await identity.clock(new Date(Date.parse(await identity.now())+301000).toISOString());await handle(page,'code').fill(totp(account.totpSecret!,await identity.now()));await handle(page,'sign-in-submit').click();await expect(handle(page,'error')).toBeVisible();
 await page.goto(routes.staff);await expect(page).toHaveURL(new RegExp(routes.staffSignIn+'$'));await identity.clock();
 for(let index=0;index<11;index++){const response=await accounts.action(context,alpha,'staff','sign-in',{identifier:account.email,password:'Wrong synthetic password',deviceLabel:'QA'});if(index===10){expect(response.status()).toBe(429);expect((await response.json()).retryAfter).toBeGreaterThan(0);}}
 await page.goto(routes.staffSignIn);await handle(page,'identifier').fill(account.email);await handle(page,'password').fill(account.password);await handle(page,'sign-in-submit').click();await expect(handle(page,'retry-wait')).toBeVisible();await expect(handle(page,'sign-in-submit')).toBeDisabled();
});
test(`${c(3)} customer SMS WhatsApp and resend screens preserve the number and explain proof lifetime`,async({identity,accounts})=>{
 for(const channel of ['sms','whatsapp'] as const){const context=await accounts.context(alpha),page=await accounts.open(context,'/');await expect(page.getByTestId('salon-name')).toBeVisible();
  const proof=await accounts.requestCode(page,alpha,undefined,channel);await expect(handle(page,'code-expiry')).toBeVisible();await handle(page,'code').fill(proof.capture.code==='000000'?'000001':'000000');await handle(page,'sign-in-submit').click();await expect(handle(page,'error')).toBeVisible();
  await handle(page,'resend').click();await expect.poll(async()=> (await context.cookies()).find(item=>item.name===cookie('customer','challenge_id'))?.value).not.toBe(proof.id);
  const id=(await context.cookies()).find(item=>item.name===cookie('customer','challenge_id'))!.value;const capture=await accounts.customers.capture(alpha,id);
  await handle(page,'code').fill(proof.capture.code);await handle(page,'sign-in-submit').click();await expect(handle(page,'error')).toBeVisible();
  await handle(page,'code').fill(capture.code);await handle(page,'sign-in-submit').click();await page.waitForURL('**'+routes.customer);await accounts.ready(page);expect((await accounts.actor(context,alpha,'customer')).principal.kind).toBe('customer');
 }
 const context=await accounts.context(alpha),page=await context.newPage(),proof=await accounts.requestCode(page,alpha);await identity.clock(new Date(Date.parse(await identity.now())+301000).toISOString());
 await handle(page,'code').fill(proof.capture.code);await handle(page,'sign-in-submit').click();await expect(handle(page,'error')).toBeVisible();await handle(page,'restart-sign-in').click();await expect(handle(page,'mobile')).toHaveValue(proof.mobile);
 // The provider contract is proved in S02; this arrangement covers its web presentation alone.
 await context.route('**/identity-web/customer/code',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'provider_unavailable'})}));await handle(page,'channel').selectOption('whatsapp');await handle(page,'sign-in-submit').click();await expect(handle(page,'error')).toBeVisible();await expect(handle(page,'mobile')).toHaveValue(proof.mobile);await expect(handle(page,'channel')).toHaveValue('whatsapp');
});
test(`${c(4)} web permissions protect every page and BFF action for all roles and kinds`,async({identity,accounts})=>{
 test.setTimeout(120000);const other=await accounts.customers.login(beta);const before=await accounts.profiles.saved(other,'Other salon customer');
 for(const role of ROLES){const actor=await identity.staff(alpha,role),context=await accounts.context(alpha,[actor]);
  for(const [path,allowed]of [[routes.staff,true],[routes.staffList,role==='OWNER'||role==='MANAGER'],[routes.audit,role==='OWNER'||role==='MANAGER']] as const){const page=await accounts.open(context,path);if(allowed)await accounts.ready(page);else await expect(handle(page,'denied')).toBeVisible();await page.close();}
  for(const [action,allowed] of [['staff',role==='OWNER'||role==='MANAGER'],['audit',role==='OWNER'||role==='MANAGER'],['profile',false]] as const){const response=await accounts.action(context,alpha,'staff',action);expect(response.status()).toBe(allowed?200:403);}
  const denied=role!=='OWNER';if(denied){for(const action of ['staff-create','staff-change','staff-remove'])expect((await accounts.action(context,alpha,'staff',action,{...syntheticAccount(),id:other.principal.id,version:0,role:'OWNER'})).status()).toBe(403);}
  expect((await accounts.action(context,alpha,'customer','principal')).status()).toBe(401);
 }
 const customer=await accounts.customers.login(alpha),context=await accounts.context(alpha,[customer]);for(const action of ['staff','audit'])expect((await accounts.action(context,alpha,'customer',action)).status()).toBe(403);
 const owner=await identity.staff(alpha,'OWNER'),foreign=await accounts.context(beta,[owner]);expect((await accounts.action(foreign,beta,'staff','principal')).status()).toBe(401);
 const revoked=await accounts.context(alpha,[owner]);expect((await identity.call(alpha,'DELETE',`/sessions/${owner.tokens.sessionId}`,undefined,owner.tokens)).status()).toBe(204);expect((await accounts.action(revoked,alpha,'staff','principal')).status()).toBe(401);
 const guest=await accounts.context(alpha);for(const kind of ['staff','customer'] as const){for(const action of mutations[kind].filter(a=>!['sign-in','code','mfa-enroll','mfa-verify','clear'].includes(a)))expect((await accounts.action(guest,alpha,kind,action,{})).status()).toBe(401);}
 expect(await accounts.profiles.read(other)).toEqual(before);
});
test(`${c(5)} web devices password rotation and logout revoke server authority immediately`,async({identity,accounts})=>{
 test.setTimeout(120000);
 const staff=await identity.staff(),other=await identity.login(alpha,staff.account,'QA other device'),context=await accounts.context(alpha,[staff]),page=await accounts.open(context,routes.staff);await accounts.ready(page);
 const exact=page.locator(`[data-testid="account-device"][data-id="${other.tokens.sessionId}"]`);await expect(exact).toBeVisible();await expect(page.locator(`[data-testid="account-device"][data-id="${staff.tokens.sessionId}"]`).getByTestId('account-device-current')).toBeVisible();await exact.getByTestId('account-device-revoke').click();await handle(page,'confirm-action').click();await expect(exact).toHaveCount(0);
 await problem(await identity.call(alpha,'GET','/me',undefined,other.tokens),401,'invalid_session');
 await identity.clock(new Date(Date.parse(await identity.now())+960000).toISOString());for(let index=0;index<10;index++)await identity.login(alpha,staff.account,`QA paginated device ${index+1}`);await page.reload();await accounts.ready(page);await expect(handle(page,'page-next')).toBeEnabled();await handle(page,'page-next').click();await expect(handle(page,'page')).toContainText('2');await expect(page.getByTestId('account-device-current')).toBeVisible();
 const newPassword=syntheticAccount().password;await handle(page,'oldPassword').fill(staff.account.password);await handle(page,'newPassword').fill(newPassword);await handle(page,'password-save').click();await expect(handle(page,'success')).toBeVisible();await expect(handle(page,'oldPassword')).toHaveValue('');
 const rotated=await accounts.actor(context,alpha,'staff');expect(rotated.tokens.accessToken===staff.tokens.accessToken).toBe(false);await problem(await identity.call(alpha,'GET','/me',undefined,staff.tokens),401,'invalid_session');
 await handle(page,'logout').click();await page.waitForURL('**'+routes.staffSignIn);await problem(await identity.call(alpha,'GET','/me',undefined,rotated.tokens),401,'invalid_session');
 const customer=await accounts.customers.login(alpha);const second=await accounts.customers.login(alpha,customer.mobile);const cc=await accounts.context(alpha,[customer]),cp=await accounts.open(cc,routes.customer);await accounts.ready(cp);await accounts.confirm(cp,'revoke-all');await cp.waitForURL('**'+routes.customerSignIn);await problem(await identity.call(alpha,'GET','/me',undefined,second.tokens),401,'invalid_session');
});
test(`${c(10)} customer profile screens save every mutable field and recover stale edits`,async({accounts})=>{
 const actor=await accounts.customers.login(alpha),context=await accounts.context(alpha,[actor]),a=await accounts.open(context,routes.customer),b=await accounts.open(context,routes.customer);await accounts.ready(a);await accounts.ready(b);
 await handle(a,'name').fill('QA browser profile');await handle(a,'email').fill('qa-browser@example.invalid');await handle(a,'birthday').fill('2000-02-29');await handle(a,'language').selectOption('ta');await handle(a,'notifications-sms').uncheck();await handle(a,'notifications-whatsapp').check();await handle(a,'notifications-email').uncheck();await handle(a,'marketingConsent').check();await handle(a,'profile-save').click();await expect(handle(a,'success')).toBeVisible();
 const saved=await accounts.profiles.read(await accounts.actor(context,alpha,'customer'));expect(saved).toMatchObject({name:'QA browser profile',email:'qa-browser@example.invalid',birthday:'2000-02-29',preferredLanguage:'ta',marketingConsent:true,notificationPreferences:{sms:false,whatsapp:true,email:false}});await expect(handle(a,'mobile')).toHaveAttribute('readonly','');
 await handle(b,'name').fill('Stale draft');await handle(b,'profile-save').click();await expect(handle(b,'profile-conflict')).toBeVisible();await expect(handle(b,'name')).toHaveValue('Stale draft');expect(await accounts.profiles.read(actor)).toEqual(saved);await handle(b,'profile-reload').click();await expect(handle(b,'name')).toHaveValue(saved.name);await b.reload();await expect(handle(b,'name')).toHaveValue(saved.name);
});
test(`${c(11)} data download and deletion confirmation clearly complete or cancel the customer request`,async({identity,accounts})=>{
 const actor=await accounts.customers.login(alpha),other=await accounts.customers.login(beta,actor.mobile),second=await accounts.customers.login(alpha,actor.mobile),staff=await identity.staff();const saved=await accounts.profiles.saved(actor),foreign=await accounts.profiles.saved(other,'Beta preserved');const context=await accounts.context(alpha,[actor,staff]),page=await accounts.open(context,routes.customer);await accounts.ready(page);
 const staffCookies=async()=>createHash('sha256').update(JSON.stringify((await context.cookies()).filter(item=>item.name.startsWith('salon_staff_')))).digest('hex');const staffBefore=await staffCookies();
 const downloaded=page.waitForEvent('download');await handle(page,'export').click();const download=await downloaded;expect(download.suggestedFilename()).toBe('customer-data.json');const stream=await download.createReadStream();expect(Boolean(stream)).toBe(true);let raw='';for await(const chunk of stream!)raw+=chunk;const copy=JSON.parse(raw);expect(copy.profile).toEqual(saved);expect(Object.keys(copy).sort()).toEqual(['devices','generatedAt','profile']);
 await handle(page,'delete').click();await expect(handle(page,'confirm-action')).toBeVisible();await handle(page,'cancel-action').click();expect(await accounts.profiles.read(actor)).toEqual(saved);
 const deletion=page.waitForResponse(response=>new URL(response.url()).pathname==='/identity-web/customer/delete');await accounts.confirm(page,'delete');const answer=await deletion;expect(answer.status(),'successful BFF confirmation of native deletion').toBe(200);expect(await answer.json()).toEqual({ok:true});await page.waitForURL('**'+routes.customerSignIn);
 for(const tokens of [actor.tokens,second.tokens]){await problem(await identity.call(alpha,'GET','/me',undefined,tokens),401,'invalid_session');await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:tokens.refreshToken}),401,'invalid_session');}
 expect((await context.cookies()).some(item=>item.name.startsWith('salon_customer_')),'customer credential cookies are cleared').toBe(false);expect(await staffCookies(),'deletion preserves independent staff cookie family').toBe(staffBefore);expect((await accounts.principal(context,alpha,'staff')).id).toBe(staff.principal.id);expect((await identity.principal(alpha,staff.tokens)).id).toBe(staff.principal.id);expect(await accounts.profiles.read(other)).toEqual(foreign);
});
test(`${c(12)} audit screens expose useful identity alerts without personal or credential values`,async({identity,accounts})=>{
 test.setTimeout(180000);await codeSettings({SALON_IDENTITY_CODE_DAILY_LIMIT:'5'},async()=>{
 await identity.clock(new Date(Date.parse(await identity.now())+randomInt(30,10000)*86400000).toISOString());
 const owner=await identity.staff(alpha,'OWNER'),manager=await identity.staff(alpha,'MANAGER');
 for(let index=0;index<4;index++)await accounts.customers.challenge(alpha);
 for(const actor of [owner,manager]){const context=await accounts.context(alpha,[actor]),page=await accounts.open(context,routes.audit);await accounts.ready(page);const rows=page.getByTestId('account-audit-row');await expect(rows.first()).toBeVisible();const count=await rows.count();expect(count).toBeGreaterThan(0);const text=await rows.allTextContents();expect(text.some(value=>value.includes('identity.'))).toBe(true);for(const secret of [owner.account.password,owner.tokens.accessToken,owner.tokens.refreshToken,owner.account.email,manager.account.email])expect(text.some(value=>value.includes(secret))).toBe(false);
  expect(text.some(value=>value.includes('identity.code_volume_alert'))).toBe(true);
  const response=await accounts.action(context,alpha,'staff','audit');expect(response.status()).toBe(200);const body=await response.json();expect(body.items.length).toBe(count);if(body.total>10){await handle(page,'page-next').click();await expect(handle(page,'page')).toContainText('2');}
  // Empty-list presentation alone is arranged at the browser boundary; no real audit is removed.
  await context.route('**/identity-web/staff/audit*',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({items:[],page:0,size:10,total:0})}));await page.reload();await accounts.ready(page);await expect(handle(page,'audit-empty')).toBeVisible();
 }
 },identity.api);
});
