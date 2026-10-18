import {test,expect,handle,routes,mutations,cookie} from '../../shared/identity-browser';
import {alpha,beta,ROLES,syntheticAccount,totp,problem} from '../../shared/identity';
import {codeSettings} from '../../shared/identity-runtime';
import {randomInt} from 'node:crypto';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;

test(`${c(1)} staff password and authenticator flows reach account only after complete verification`,async({identity,accounts})=>{
 for(const [role,mfa] of [['MANAGER',false],['OWNER',true],['OWNER',false]] as const){
  const account=syntheticAccount(role,mfa);await identity.seed(alpha,[account]);const context=await accounts.context(alpha),page=await context.newPage();
  const actor=await accounts.staffSignIn(page,alpha,account);expect(actor.principal.role).toBe(role);expect(actor.principal.mfaEnabled).toBe(role==='OWNER');
  await handle(page,'logout').click();await page.waitForURL('**'+routes.staffSignIn);await expect(handle(page,'identifier')).toBeVisible();await expect(handle(page,'password')).toBeVisible();await expect(handle(page,'code')).toHaveCount(0);await expect(handle(page,'setup-key')).toHaveCount(0);
 }
 const staff=await identity.staff(alpha,'RECEPTIONIST'),context=await accounts.context(alpha,[staff]),page=await accounts.open(context,routes.staff);await accounts.ready(page);
 await handle(page,'mfa-start').click();await expect(handle(page,'setup-key')).toBeVisible();const secret=await handle(page,'setup-key').textContent();expect(Boolean(secret)).toBe(true);
 await handle(page,'code').fill(totp(secret!,await identity.now()));await handle(page,'mfa-verify').click();await expect(handle(page,'mfa-enabled')).toBeVisible();
 await expect(handle(page,'setup-key')).toHaveCount(0);expect((await accounts.actor(context,alpha,'staff')).principal.mfaEnabled).toBe(true);
});
test(`${c(2)} staff authentication refusals preserve useful input and expose recovery`,async({identity,accounts})=>{
 const account=syntheticAccount('OWNER');await identity.seed(alpha,[account]);const context=await accounts.context(alpha),page=await accounts.open(context,routes.staffSignIn);
 // CR-012 recovery and visibility are local controls, never credential-reset requests.
 await expect(handle(page,'sign-in-submit')).toBeEnabled();
 await handle(page,'password').fill('Synthetic visibility sample');
 await expect(handle(page,'password')).toHaveAttribute('type','password');
 let requests=0;const observed=(request:{url:()=>string})=>{if(new URL(request.url()).pathname.startsWith('/identity-web/'))requests++;};page.on('request',observed);
 await handle(page,'password-toggle').click();await expect(handle(page,'password')).toHaveAttribute('type','text');await expect(handle(page,'password-toggle')).toHaveAttribute('aria-pressed','true');await expect(handle(page,'password-toggle')).toHaveAccessibleName('Hide password');
 await handle(page,'password-toggle').click();await expect(handle(page,'password')).toHaveAttribute('type','password');await expect(handle(page,'password-toggle')).toHaveAttribute('aria-pressed','false');await expect(handle(page,'password-toggle')).toHaveAccessibleName('Show password');
 await handle(page,'forgot-password').click();await expect(handle(page,'forgot-password')).toHaveAttribute('aria-expanded','true');await expect(handle(page,'password-help')).toBeVisible();await expect(handle(page,'password-help')).toHaveAttribute('role','status');await expect(handle(page,'password-help')).toContainText('salon administrator');
 await handle(page,'forgot-password').click();await expect(handle(page,'password-help')).toHaveCount(0);await expect(handle(page,'forgot-password')).toHaveAttribute('aria-expanded','false');expect(requests,'visibility and administrator guidance must not send identity requests').toBe(0);page.off('request',observed);
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
test(`${c(4)} web permissions protect every page and BFF action for all roles and kinds`,async({identity,accounts})=>{
 test.setTimeout(120000);const other=await accounts.customers.login(beta);const before=await accounts.profiles.saved(other,'Other salon customer');
 for(const role of ROLES){const actor=await identity.staff(alpha,role),context=await accounts.context(alpha,[actor]);
  for(const [path,allowed]of [[routes.staff,true],[routes.staffList,role==='OWNER'||role==='MANAGER'],[routes.audit,role==='OWNER'||role==='MANAGER']] as const){const page=await accounts.open(context,path);if(allowed)await accounts.ready(page);else await expect(handle(page,'denied')).toBeVisible();await page.close();}
  for(const [action,allowed] of [['staff',role==='OWNER'||role==='MANAGER'],['audit',role==='OWNER'||role==='MANAGER']] as const){const response=await accounts.action(context,alpha,'staff',action);expect(response.status()).toBe(allowed?200:403);}
  const denied=role!=='OWNER';if(denied){for(const action of ['staff-create','staff-change','staff-remove'])expect((await accounts.action(context,alpha,'staff',action,{...syntheticAccount(),id:other.principal.id,version:0,role:'OWNER'})).status()).toBe(403);}
  expect((await accounts.action(context,alpha,'customer','principal')).status()).toBe(404);
 }
 const customer=await accounts.customers.login(alpha),context=await accounts.context(alpha,[customer]);for(const action of ['staff','audit'])expect((await accounts.action(context,alpha,'customer',action)).status()).toBe(404);
 const owner=await identity.staff(alpha,'OWNER'),foreign=await accounts.context(beta,[owner]);expect((await accounts.action(foreign,beta,'staff','principal')).status()).toBe(401);
 const revoked=await accounts.context(alpha,[owner]);expect((await identity.call(alpha,'DELETE',`/sessions/${owner.tokens.sessionId}`,undefined,owner.tokens)).status()).toBe(204);expect((await accounts.action(revoked,alpha,'staff','principal')).status()).toBe(401);
 const guest=await accounts.context(alpha);for(const kind of ['staff'] as const){for(const action of mutations[kind].filter(a=>!['sign-in','code','mfa-enroll','mfa-verify','clear'].includes(a)))expect((await accounts.action(guest,alpha,kind,action,{})).status()).toBe(401);}
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

test(`${c(11)} recoverable confirmation refusals keep feedback and recovery accessible`,async({identity,accounts},testInfo)=>{
 for(const row of [{kind:'staff',action:'revoke-all',status:503,code:'service_unavailable'},{kind:'staff',action:'staff-remove',status:409,code:'conflict'}] as const){
  const actor=await identity.staff(alpha,'OWNER');
  const context=await accounts.context(alpha,[actor]),page=await accounts.open(context,row.action==='staff-remove'?routes.staffList:routes.staff);await accounts.ready(page);
  const original=await accounts.principal(context,alpha,row.kind);let targetId:string|undefined;
  if(row.action==='staff-remove'){
   const target=await identity.staff(alpha,'RECEPTIONIST');targetId=target.principal.id;await page.reload();await accounts.ready(page);
   await page.locator(`[data-testid="account-staff-row"][data-id="${targetId}"]`).getByTestId('account-staff-remove').click();
  }else await handle(page,row.action).click();
  await expect(page.getByRole('dialog')).toBeVisible();await context.route(`**/identity-web/${row.kind}/${row.action}`,route=>route.fulfill({status:row.status,contentType:'application/json',body:JSON.stringify({code:row.code})}));
  const answer=page.waitForResponse(r=>new URL(r.url()).pathname===`/identity-web/${row.kind}/${row.action}`);await handle(page,'confirm-action').click();expect((await answer).status()).toBe(row.status);await expect.poll(async()=>await page.getByRole('dialog').count()===0||await handle(page,'confirm-action').isEnabled()).toBe(true);
  const dialog=page.getByRole('dialog'),open=await dialog.count()>0;const alert=open?dialog.getByRole('alert'):page.getByRole('alert');
  await testInfo.attach(`${row.action}-accessible-feedback`,{body:JSON.stringify({action:row.action,status:row.status,dialogOpen:open,modalAlertCount:open?await dialog.getByRole('alert').count():0,pageAlertCount:await page.getByRole('alert').count()}),contentType:'application/json'});
  expect.soft(await alert.count(),`${row.action}: failure must be announced inside the modal or after dismissal`).toBeGreaterThan(0);
  if(open){await handle(page,'cancel-action').click();await expect(dialog).toHaveCount(0);}
  expect((await accounts.principal(context,alpha,row.kind)).id).toBe(original.id);
  if(targetId)expect((await identity.list(actor as Awaited<ReturnType<typeof identity.staff>>)).some(item=>item.id===targetId&&item.active)).toBe(true);
 }
});

test(`${c(4)} live self-demotion removes inaccessible workspace navigation without reload`,async({identity,accounts},testInfo)=>{
 const owner=await identity.staff(alpha,'OWNER');await identity.staff(alpha,'OWNER');
 const context=await accounts.context(alpha,[owner]),page=await accounts.open(context,routes.staffList);await accounts.ready(page);
 await expect(page.getByTestId('workspace-staff')).toBeVisible();await expect(page.getByTestId('workspace-audit')).toBeVisible();
 const ownRow=page.locator(`[data-testid="account-staff-row"][data-id="${owner.principal.id}"]`);await expect(ownRow).toBeVisible();
 await accounts.chooseRole(page,ownRow.getByTestId('account-staff-role'),'RECEPTIONIST');await expect(handle(page,'denied')).toBeVisible();
 const principal=await accounts.principal(context,alpha,'staff');expect(principal.role).toBe('RECEPTIONIST');expect(principal.permissions).not.toContain('staff.list');expect(principal.permissions).not.toContain('audit.list');
 expect((await accounts.action(context,alpha,'staff','staff')).status()).toBe(403);expect((await accounts.action(context,alpha,'staff','audit')).status()).toBe(403);
 await testInfo.attach('live-navigation',{body:JSON.stringify({role:principal.role,contentDenied:await handle(page,'denied').isVisible(),staffLinks:await page.getByTestId('workspace-staff').count(),auditLinks:await page.getByTestId('workspace-audit').count()}),contentType:'application/json'});
 await expect.soft(page.getByTestId('workspace-staff')).toHaveCount(0);await expect.soft(page.getByTestId('workspace-audit')).toHaveCount(0);
});
