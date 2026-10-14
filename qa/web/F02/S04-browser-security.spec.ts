import {createHash} from 'node:crypto';
import {test,expect,handle,routes,bff,cookie,mutations,type Kind} from '../../shared/identity-browser';
import {alpha,beta,syntheticAccount,json,type Tokens,problem,totp} from '../../shared/identity';
import {identitySql} from '../../shared/identity-storage';
import {salonUrl} from '../../shared/tenants';
import {webUrl} from '../../shared/env';
import {assertIdentityArtifactsSafe} from '../../shared/identity-artifact-secrecy';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;
const digest=(value:unknown)=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const totals=()=>identitySql(`SELECT jsonb_build_object('staff',(SELECT count(*) FROM identity_staff),'customers',(SELECT count(*) FROM identity_customer),'sessions',(SELECT count(*) FROM identity_session),'history',(SELECT count(*) FROM identity_refresh_history),'challenges',(SELECT count(*) FROM identity_customer_challenge),'mfa',(SELECT count(*) FROM identity_mfa_challenge),'rates',(SELECT coalesce(sum(attempts),0) FROM identity_rate_bucket),'outbox',(SELECT count(*) FROM outbox_message),'audit',(SELECT count(*) FROM audit_entry))`);

test(`${c(6)} staff and customer cookies coexist and independent signout preserves the other account`,async({identity,accounts})=>{
 const staff=await identity.staff(),customer=await accounts.customers.login(alpha),context=await accounts.context(alpha,[staff,customer]);const s=await accounts.open(context,routes.staff),p=await accounts.open(context,routes.customer);await accounts.ready(s);await accounts.ready(p);
 await handle(s,'logout').click();await s.waitForURL('**'+routes.staffSignIn);expect((await accounts.principal(context,alpha,'customer')).id).toBe(customer.principal.id);await p.reload();await accounts.ready(p);
 await accounts.staffSignIn(s,alpha,staff.account);await handle(p,'logout').click();await p.waitForURL('**'+routes.customerSignIn);expect((await accounts.principal(context,alpha,'staff')).id).toBe(staff.principal.id);
});
test(`${c(7)} simultaneous tabs coordinate renewal once for each principal kind`,async({identity,accounts})=>{
 for(const kind of ['staff','customer'] as const){const actor=kind==='staff'?await identity.staff():await accounts.customers.login(alpha);const context=await accounts.context(alpha,[actor]),a=await accounts.open(context,kind==='staff'?routes.staff:routes.customer),b=await accounts.open(context,kind==='staff'?routes.staff:routes.customer);await accounts.ready(a);await accounts.ready(b);
  const before=Number(identitySql(`SELECT count(*) FROM identity_refresh_history WHERE tenant_id='${alpha.id}' AND session_id='${actor.tokens.sessionId}'`));let renewals=0;context.on('request',request=>{if(new URL(request.url()).pathname===bff(kind,'refresh'))renewals++;});
  await identity.clock(new Date(Date.parse(actor.tokens.accessExpiresAt)+1000).toISOString());await Promise.all([a.reload(),b.reload()]);await Promise.all([accounts.ready(a),accounts.ready(b)]);
  expect(renewals,'one honest cross-tab renewal').toBe(1);expect(Number(identitySql(`SELECT count(*) FROM identity_refresh_history WHERE tenant_id='${alpha.id}' AND session_id='${actor.tokens.sessionId}'`))-before).toBe(1);
  expect((await accounts.principal(context,alpha,kind)).id).toBe(actor.principal.id);expect((await identity.sessions(await accounts.actor(context,alpha,kind))).items.length).toBe(1);await identity.clock();
 }
});
test(`${c(8)} replay revocation and unknown refresh outcome cannot restore signed-out authority`,async({identity,accounts})=>{
 for(const kind of ['staff','customer'] as const){
  const actor=kind==='staff'?await identity.staff():await accounts.customers.login(alpha),context=await accounts.context(alpha,[actor]),path=kind==='staff'?routes.staff:routes.customer,signin=kind==='staff'?routes.staffSignIn:routes.customerSignIn;
  const rotated=await json<Tokens>(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:actor.tokens.refreshToken}),'Tokens');await accounts.arrange(context,alpha,{...actor,tokens:rotated});
  await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:actor.tokens.refreshToken}),401,'invalid_session');let attempts=0;context.on('request',request=>{if(new URL(request.url()).pathname===bff(kind,'refresh'))attempts++;});const page=await accounts.open(context,path);await page.waitForURL('**'+signin);expect(attempts,'future expiry revoked access does not renew').toBe(0);expect((await context.cookies()).some(item=>item.name===cookie(kind,'refresh'))).toBe(false);
  const fresh=kind==='staff'?await identity.staff():await accounts.customers.login(alpha),lost=await accounts.context(alpha,[fresh]);let rotations=0;
  await lost.route('**'+bff(kind,'refresh'),async route=>{rotations++;await route.fetch();await route.abort('connectionreset');});await identity.clock(new Date(Date.parse(fresh.tokens.accessExpiresAt)+1000).toISOString());const lostPage=await accounts.open(lost,path);await lostPage.waitForURL('**'+signin);expect(rotations).toBe(1);await lostPage.goto(path);await lostPage.waitForURL('**'+signin);expect(rotations).toBe(1);expect((await lost.cookies()).some(item=>item.name===cookie(kind,'refresh'))).toBe(false);await identity.clock();
  const delayed=kind==='staff'?await identity.staff():await accounts.customers.login(alpha),shared=await accounts.context(alpha,[delayed]);const a=await accounts.open(shared,path),b=await accounts.open(shared,path);await accounts.ready(a);await accounts.ready(b);
  let release!:()=>void,seen!:()=>void;const arrived=new Promise<void>(resolve=>seen=resolve),gate=new Promise<void>(resolve=>release=resolve);
  await shared.route('**'+bff(kind,'refresh'),async route=>{const response=await route.fetch();seen();await gate;await route.fulfill({response});});await identity.clock(new Date(Date.parse(delayed.tokens.accessExpiresAt)+1000).toISOString());const renewing=a.reload();await arrived;
  const logout=handle(b,'logout').click();release();await Promise.all([renewing,logout]);await b.waitForURL('**'+signin);expect((await accounts.action(shared,alpha,kind,'principal')).status()).toBe(401);expect((await shared.cookies()).some(item=>item.name===cookie(kind,'refresh'))).toBe(false);await identity.clock();
 }
});
test(`${c(9)} unsupported Web Locks disable credential changes with a clear explanation`,async({identity,accounts})=>{
 const staff=await identity.staff(),customer=await accounts.customers.login(alpha);const context=await accounts.context(alpha,[staff,customer],{unsupported:true});
 for(const path of [routes.staffSignIn,routes.customerSignIn]){const page=await accounts.open(context,path);await expect(handle(page,'browser-support')).toBeVisible();await expect(handle(page,'sign-in-submit')).toBeDisabled();}
 for(const [path,controls]of [[routes.staff,['logout','password-save','mfa-start','revoke-all']],[routes.customer,['logout','delete','revoke-all']]] as const){const page=await accounts.open(context,path);await accounts.ready(page);await expect(handle(page,'browser-support')).toBeVisible();for(const control of controls)await expect(handle(page,control)).toBeDisabled();}
 const page=await accounts.open(context,routes.customer);await accounts.ready(page);await handle(page,'name').fill('Supported profile edit');await handle(page,'profile-save').click();await expect(handle(page,'success')).toBeVisible();expect((await accounts.profiles.read(customer)).name).toBe('Supported profile edit');await expect(handle(page,'export')).toBeEnabled();const download=page.waitForEvent('download');await handle(page,'export').click();expect((await download).suggestedFilename()).toBe('customer-data.json');
});
test(`${c(13)} all browser mutation routes reject CSRF before any side effect`,async({identity,accounts})=>{
 test.setTimeout(120000);const owner=await identity.staff(alpha,'OWNER'),customer=await accounts.customers.login(alpha),context=await accounts.context(alpha,[owner,customer]);const origin=salonUrl(alpha,webUrl);
 for(const kind of ['staff','customer'] as const)for(const action of mutations[kind])for(const headers of [
  {},{Origin:salonUrl(beta,webUrl)},{Origin:origin.replace('http:','https:')},{Origin:origin.replace(/:\d+$/,':65534')},{Origin:origin,'Sec-Fetch-Site':'cross-site'},
  {Origin:salonUrl(beta,webUrl),'X-Forwarded-Host':beta.hostname,'X-Forwarded-Proto':'http'},
 ] as Record<string,string>[]){const before=totals(),beforeCookies=digest(await context.cookies());const response=await context.request.post(origin+bff(kind,action),{headers,data:{confirm:true,identifier:owner.account.email,password:owner.account.password,mobile:customer.mobile,code:'000000'}});expect(response.status(),`${kind}/${action}: rejected CSRF`).toBe(403);expect((await response.json()).code).toBe('request_rejected');expect(totals()).toBe(before);expect(digest(await context.cookies()),'no cookie side effect').toBe(beforeCookies);}
});
test(`${c(14)} browser cookies and every client surface exclude session and challenge authority`,async({identity,accounts})=>{
 assertIdentityArtifactsSafe();
 const account=syntheticAccount('OWNER',false);await identity.seed(alpha,[account]);const context=await accounts.context(alpha),page=await context.newPage();const bodies:string[]=[],logs:string[]=[];page.on('console',message=>logs.push(message.text()));context.on('response',response=>{if(response.url().includes('/identity-web/'))void response.text().then(text=>bodies.push(text));});
 await page.goto(routes.staffSignIn);await handle(page,'identifier').fill(account.email);await handle(page,'password').fill(account.password);await handle(page,'sign-in-submit').click();await expect(handle(page,'setup-key')).toBeVisible();const setup=await handle(page,'setup-key').textContent();expect(Boolean(setup)).toBe(true);
 const challenge=(await context.cookies()).filter(item=>item.name.startsWith('salon_staff_challenge'));expect(challenge.length).toBe(2);const values=challenge.map(item=>item.value);
 const storage=()=>page.evaluate(()=>JSON.stringify({local:{...localStorage},session:{...sessionStorage},readable:document.cookie,url:location.href}));for(const value of [...values,setup!])expect((await storage()).includes(value),'setup and challenge are unstored/unlinked').toBe(false);
 for(const value of values)expect((await page.content()).includes(value),'no challenge authority in HTML').toBe(false);
 await handle(page,'code').fill(totp(setup!,await identity.now()));await handle(page,'sign-in-submit').click();await page.waitForURL('**'+routes.staff);await accounts.ready(page);await accounts.customerSignIn(page,alpha);
 const cookies=await context.cookies();const sensitive=cookies.filter(item=>/^salon_(staff|customer)_(access|refresh|challenge)$/.test(item.name));expect(sensitive.length).toBe(4);
 for(const item of cookies.filter(item=>/^salon_(staff|customer)_/.test(item.name))){expect(item.httpOnly).toBe(true);expect(item.sameSite).toBe('Lax');expect(item.path).toBe('/');expect(item.domain).toBe(alpha.hostname);expect(item.secure).toBe(false);}
 await expect.poll(()=>bodies.length).toBeGreaterThan(0);const chunks=await page.locator('script[src]').evaluateAll(scripts=>scripts.map(script=>(script as HTMLScriptElement).src));expect(chunks.length).toBeGreaterThan(0);const chunkContents:string[]=[];for(const url of chunks){const response=await context.request.get(url);expect(response.status()).toBe(200);chunkContents.push(await response.text());}
 const client=[await page.content(),await storage(),...bodies,...logs,...chunkContents];for(const secret of [...values,...sensitive.map(item=>item.value)])expect(client.some(text=>text.includes(secret)),'no credential in client-readable surface').toBe(false);
 expect(client.some(text=>text.includes(account.password)),'submitted password is not rendered or logged').toBe(false);
});
test(`${c(15)} redirect and hostname input cannot choose the bearer destination or another salon`,async({identity,accounts})=>{
 const staff=await identity.staff(),foreign=await accounts.customers.login(beta),saved=await accounts.profiles.saved(foreign,'Foreign preserved'),context=await accounts.context(alpha,[staff]);
 for(const redirect of ['https://example.invalid/steal','//beta.localhost/account','\\\\example.invalid\\steal']){const page=await accounts.open(context,routes.staff+'?returnTo='+encodeURIComponent(redirect));await accounts.ready(page);expect(new URL(page.url()).origin).toBe(salonUrl(alpha,webUrl));await page.close();}
 await context.addCookies([{name:'tenantId',value:beta.id,url:salonUrl(alpha,webUrl)}]);
 for(const input of ['tenantId','salonId','hostname']){const response=await accounts.action(context,alpha,'staff',`principal?${input}=${beta.id}`,undefined,{'X-Forwarded-Host':beta.hostname,'X-Forwarded-Proto':'https'});expect(response.status()).toBe(200);expect((await response.json()).id).toBe(staff.principal.id);}
 const own=await accounts.context(alpha,[staff]);const principal=await accounts.action(own,alpha,'staff','principal?tenantId='+beta.id,undefined,{'X-Forwarded-Host':beta.hostname});expect(principal.status()).toBe(200);expect((await principal.json()).id).toBe(staff.principal.id);
 const owner=await identity.staff(alpha,'OWNER'),ownerContext=await accounts.context(alpha,[owner]);expect((await accounts.action(ownerContext,alpha,'staff','staff-remove',{id:foreign.principal.id,tenantId:beta.id})).status()).toBe(404);expect(await accounts.profiles.read(foreign)).toEqual(saved);
});
