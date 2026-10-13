import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { test,expect,alpha,beta,ROLES,json,problem,assertTokens,syntheticAccount,type Tokens } from '../../shared/identity';
import { Customers,syntheticMobile,type CustomerChallenge } from '../../shared/customer-identity';
import { identitySql } from '../../shared/identity-storage';
import { apiLog } from '../../shared/identity-runtime';
import { committedContract } from '../../shared/openapi';

const count=(table:string,tenant=alpha.id)=>Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id='${tenant}'`));
const norm=(mobile:string)=>`+94${mobile.slice(1)}`;
const proof=(id:string,code:string)=>({challengeId:id,code});
const c=(n:number)=>`TC-F02-S02-${String(n).padStart(3,'0')}`;

test(`${c(1)} code requests reveal only a challenge and protected issuing device`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();
 for(const channel of ['sms','whatsapp']as const){const before=count('identity_customer_challenge');const request=await customers.request(alpha,{mobile:syntheticMobile(),channel,deviceLabel:'QA device'});
  const body=await json<CustomerChallenge>(request,'Challenge');expect(Object.keys(body).sort()).toEqual(['challengeId','expiresAt']);const cookie=request.headers()['set-cookie'];expect(cookie).toBeTruthy();
  for(const flag of [/HttpOnly/i,/SameSite=Lax/i,/Path=\//i,/Max-Age=31536000/i])expect(flag.test(cookie)).toBe(true);expect(/Domain=|Secure/i.test(cookie)).toBe(false);
  const capture=await customers.capture(alpha,body.challengeId);expect(capture.channel).toBe(channel);expect(count('identity_customer_challenge')-before).toBe(1);const delivery=await customers.delivery(alpha,body.challengeId);expect(delivery.id).toBeTruthy();expect(delivery.channel).toBe(channel);expect(Object.keys(body).some(k=>/number|mobile|code|account/i.test(k))).toBe(false);
 }
});
test(`${c(2)} malformed code requests create no challenge or delivery`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const before=count('identity_customer_challenge'),outbox=count('outbox_message');
 const data={mobile:syntheticMobile(),channel:'sms',deviceLabel:'QA'};
 for(const [field,value]of [['mobile',''],['mobile','0111234567'],['mobile',123456],['channel','email'],['channel',true],['deviceLabel',''],['deviceLabel','a'.repeat(81)]]as const){const p=await problem(await customers.request(alpha,{...data,[field]:value}as never),400,'bad_request');expect(p.errors?.some(e=>e.field===field)).toBe(true);}
 expect(count('identity_customer_challenge')).toBe(before);expect(count('outbox_message')).toBe(outbox);expect(count('identity_customer')).toBe(0);
 const boundary=await customers.request(alpha,{...data,deviceLabel:'a'.repeat(80)});expect(boundary.status()).toBe(200);
});
test(`${c(3)} correct codes create and reuse a customer with five-minute thirty-day credentials`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const before=Date.parse(await identity.now());const actor=await customers.login(alpha);assertTokens(actor.tokens);
 expect(Date.parse(actor.tokens.accessExpiresAt)-before).toBeGreaterThanOrEqual(299000);expect(Date.parse(actor.tokens.accessExpiresAt)-before).toBeLessThan(305000);
 expect(Date.parse(actor.tokens.refreshExpiresAt)-before).toBeGreaterThanOrEqual(30*86400000-1000);expect(Date.parse(actor.tokens.refreshExpiresAt)-before).toBeLessThan(30*86400000+5000);
 expect((await identity.principal(null,actor.tokens)).id).toBe(actor.principal.id);
 const again=await customers.login(alpha,actor.mobile,'whatsapp',actor.device);expect(again.principal.id).toBe(actor.principal.id);expect(count('identity_customer')).toBe(1);
});
test(`${c(4)} identical mobiles at two salons remain separate identities`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const mobile=syntheticMobile();const a=await customers.login(alpha,mobile),b=await customers.login(beta,mobile);
 expect(a.principal.id===b.principal.id).toBe(false);expect((await identity.principal(alpha,a.tokens)).id).toBe(a.principal.id);expect((await identity.principal(beta,b.tokens)).id).toBe(b.principal.id);
 await problem(await identity.call(beta,'GET','/me',undefined,a.tokens),404,'not_found');await problem(await identity.call(alpha,'GET','/me',undefined,b.tokens),404,'not_found');
 expect(count('identity_customer',alpha.id)).toBe(1);expect(count('identity_customer',beta.id)).toBe(1);expect((await identity.sessions(a)).items.some(s=>s.id===b.tokens.sessionId)).toBe(false);
});
test(`${c(5)} customer credentials never authorize staff-only actions`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const customer=await customers.login(alpha);const owner=await identity.staff(alpha,'OWNER');const target=await identity.staff(alpha);const before=await identity.list(owner);
 const routes:[string,string,unknown?][]=[['GET','/staff'],['POST','/staff',syntheticAccount()],['PATCH',`/staff/${target.principal.id}`,{version:0,role:'OWNER'}],['DELETE',`/staff/${target.principal.id}`],['GET','/audit'],['PUT','/staff/password',{oldPassword:target.account.password,newPassword:syntheticAccount().password}],['POST','/staff/mfa/challenges']];
 for(const [method,path,data]of routes)await problem(await identity.call(alpha,method,path,data,customer.tokens),403,'permission_denied');expect(await identity.list(owner)).toEqual(before);
 for(const role of ROLES){const staff=await identity.staff(alpha,role);expect((await identity.principal(alpha,staff.tokens)).kind).toBe('staff');expect((await identity.sessions(staff)).items.some(s=>s.id===customer.tokens.sessionId)).toBe(false);await problem(await identity.call(alpha,'DELETE',`/sessions/${customer.tokens.sessionId}`,undefined,staff.tokens),404,'not_found');}
 await problem(await customers.verify(alpha,proof(randomUUID(),'123456'),'',{Authorization:`Bearer ${owner.tokens.accessToken}`}),401,'invalid_code');expect((await identity.principal(alpha,customer.tokens)).id).toBe(customer.principal.id);
});
test(`${c(6)} five wrong guesses exhaust the challenge without creating identity`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const issued=await customers.challenge(alpha);const captured=await customers.capture(alpha,issued.challenge.challengeId);const wrong=captured.code==='000000'?'111111':'000000';
 for(let n=0;n<5;n++)await problem(await customers.verify(alpha,proof(issued.challenge.challengeId,wrong),issued.device),401,'invalid_code');
 await problem(await customers.verify(alpha,proof(issued.challenge.challengeId,captured.code),issued.device),401,'invalid_code');expect(count('identity_customer')).toBe(0);expect(count('identity_session')).toBe(0);
 expect(identitySql(`SELECT attempts FROM identity_customer_challenge WHERE tenant_id='${alpha.id}' AND id='${issued.challenge.challengeId}'`)).toBe('5');
});
test(`${c(7)} expiry resend and wrong-device proof cannot authorize`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const old=await customers.challenge(alpha),capture=await customers.capture(alpha,old.challenge.challengeId);
 for(const device of ['',randomUUID(),old.device+'tampered'])await problem(await customers.verify(alpha,proof(old.challenge.challengeId,capture.code),device),401,'invalid_code');
 const replacement=await customers.challenge(alpha,norm(old.mobile),'whatsapp',old.device);await problem(await customers.verify(alpha,proof(old.challenge.challengeId,capture.code),old.device),401,'invalid_code');
 const current=await customers.capture(alpha,replacement.challenge.challengeId);await identity.clock(new Date(Date.parse(replacement.challenge.expiresAt)+1000).toISOString());await problem(await customers.verify(alpha,proof(replacement.challenge.challengeId,current.code),old.device),401,'code_expired');expect(count('identity_customer')).toBe(0);
});
test(`${c(8)} a valid code raced eight times creates exactly one customer and session`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const issued=await customers.challenge(alpha);const capture=await customers.capture(alpha,issued.challenge.challengeId);
 const outcomes=await Promise.all(Array.from({length:8},()=>customers.verify(alpha,proof(issued.challenge.challengeId,capture.code),issued.device)));
 expect(outcomes.filter(r=>r.status()===200).length).toBe(1);expect(outcomes.filter(r=>r.status()===401).length).toBe(7);for(const denied of outcomes.filter(r=>r.status()===401))await problem(denied,401,'invalid_code');
 const tokens=await json<Tokens>(outcomes.find(r=>r.status()===200)!,'Tokens');assertTokens(tokens);const principal=await identity.principal(alpha,tokens);expect(principal.kind).toBe('customer');const sessions=await identity.sessions({salon:alpha,tokens,principal});expect(sessions.total).toBe(1);expect(count('identity_customer')).toBe(1);expect(count('identity_session')).toBe(1);
 await problem(await customers.verify(alpha,proof(issued.challenge.challengeId,capture.code),issued.device),401,'invalid_code');
});
test(`${c(9)} challenges captures deliveries and host routes expose no foreign proof`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const foreign=await customers.challenge(beta);const capture=await customers.capture(beta,foreign.challenge.challengeId);expect((await customers.delivery(beta,foreign.challenge.challengeId)).id).toBeTruthy();
 await problem(await customers.verify(alpha,proof(foreign.challenge.challengeId,capture.code),foreign.device),401,'invalid_code');
 for(const id of [foreign.challenge.challengeId,randomUUID()])for(const endpoint of ['captures','deliveries'])await problem(await identity.call(alpha,'GET',`/local/identity/${endpoint}/${id}`),404,'not_found');
 for(const hostname of ['admin.localhost','unknown.localhost','localhost']){
  await problem(await customers.request(alpha,{mobile:syntheticMobile(),channel:'sms',deviceLabel:'QA'},'',{Host:hostname}),404,'not_found');
  await problem(await customers.verify(alpha,proof(foreign.challenge.challengeId,capture.code),foreign.device,{Host:hostname}),404,'not_found');
 }
 expect((await customers.capture(beta,foreign.challenge.challengeId)).challengeId).toBe(foreign.challenge.challengeId);
});
test(`${c(10)} number device and trusted-peer limits prevent excess enqueue`,async({identity})=>{
 const customers=new Customers(identity);
 for(const dimension of ['number','device','address']){
  await customers.reset();const initial=syntheticMobile();let device='';const limit=dimension==='number'?3:dimension==='device'?10:20;
  for(let n=0;n<limit;n++){const issued=await customers.challenge(alpha,dimension==='number'?initial:syntheticMobile(),'sms',dimension==='device'?device:'');device=issued.device;}
  const before=count('identity_customer_challenge'),outbox=count('outbox_message');const r=await customers.request(alpha,{mobile:dimension==='number'?initial:syntheticMobile(),channel:'sms',deviceLabel:'QA'},dimension==='device'?device:randomUUID(),{'X-Forwarded-For':'198.51.100.200'});
  await problem(r,429,'rate_limited');expect(Number(r.headers()['retry-after'])).toBeGreaterThan(0);expect(count('identity_customer_challenge')).toBe(before);expect(count('outbox_message')).toBe(outbox);
  // Extra body fields cannot become a server device or address; budget still holds.
  const forged=await customers.request(alpha,{mobile:dimension==='number'?initial:syntheticMobile(),channel:'sms',deviceLabel:'QA',deviceId:randomUUID(),address:'198.51.100.201'}as never,dimension==='device'?device:randomUUID());
  expect([400,429]).toContain(forged.status());expect(count('outbox_message')).toBe(outbox);
 }
});
test(`${c(14)} customer sessions isolate accounts and expire revoke and replay immediately`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const a=await customers.login(alpha),b=await customers.login(alpha);const second=await customers.login(alpha,a.mobile,'sms',a.device);const staff=await identity.staff();
 const all=await identity.sessions(a),paged=await identity.sessions(a,0,1);expect(all.total).toBe(2);expect(paged.items.length).toBe(1);expect(all.items.filter(s=>s.current).map(s=>s.id)).toEqual([a.tokens.sessionId]);expect(all.items.some(s=>[b.tokens.sessionId,staff.tokens.sessionId].includes(s.id!))).toBe(false);
 for(const id of [b.tokens.sessionId,staff.tokens.sessionId])await problem(await identity.call(alpha,'DELETE',`/sessions/${id}`,undefined,a.tokens),404,'not_found');
 expect((await identity.call(alpha,'DELETE',`/sessions/${second.tokens.sessionId}`,undefined,a.tokens)).status()).toBe(204);await problem(await identity.call(alpha,'GET','/me',undefined,second.tokens),401,'invalid_session');
 await identity.clock(new Date(Date.parse(a.tokens.accessExpiresAt)+1000).toISOString());await problem(await identity.call(alpha,'GET','/me',undefined,a.tokens),401,'invalid_session');
 await problem(await identity.call(null,'POST','/sessions/refresh',{refreshToken:a.tokens.refreshToken}),404,'not_found');const renewed=await json<Tokens>(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:a.tokens.refreshToken}),'Tokens');assertTokens(renewed);expect(renewed.refreshExpiresAt).toBe(a.tokens.refreshExpiresAt);
 const races=await Promise.all(Array.from({length:8},()=>identity.call(alpha,'POST','/sessions/refresh',{refreshToken:renewed.refreshToken})));expect(races.filter(r=>r.status()===200).length).toBe(1);expect(races.filter(r=>r.status()===401).length).toBe(7);const win=await json<Tokens>(races.find(r=>r.status()===200)!,'Tokens');await problem(await identity.call(alpha,'GET','/me',undefined,win),401,'invalid_session');
 const fresh=await customers.login(alpha,a.mobile,'sms',a.device);expect((await identity.call(alpha,'DELETE','/sessions',undefined,fresh.tokens)).status()).toBe(204);await problem(await identity.call(alpha,'GET','/me',undefined,fresh.tokens),401,'invalid_session');
 const long=await customers.login(alpha);await identity.clock(new Date(Date.parse(long.tokens.refreshExpiresAt)+1000).toISOString());await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:long.tokens.refreshToken}),401,'invalid_session');
});
test(`${c(15)} customer records and code material remain isolated and secret at rest`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const a=await customers.login(alpha),b=await customers.login(beta);const unused=await customers.challenge(alpha);const capture=await customers.capture(alpha,unused.challenge.challengeId);
 for(const table of ['identity_customer','identity_customer_challenge']){expect(count(table,beta.id)).toBeGreaterThan(0);expect(identitySql(`SELECT count(*) FROM ${table}`,null)).toBe('0');expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id='${beta.id}'`,alpha.id)).toBe('0');expect(identitySql(`SELECT relrowsecurity::text||'|'||relforcerowsecurity::text FROM pg_class WHERE oid='${table}'::regclass`)).toBe('true|true');}
 const persisted=identitySql(`SELECT row_to_json(t)::text FROM identity_customer_challenge t WHERE tenant_id='${alpha.id}' AND id='${unused.challenge.challengeId}'`);for(const secret of [unused.mobile,norm(unused.mobile),capture.code,unused.device])expect(persisted.includes(secret),'challenge envelope excludes raw proof').toBe(false);
 const delivery=await customers.delivery(alpha,unused.challenge.challengeId);const envelope=identitySql(`SELECT payload::text FROM outbox_message WHERE tenant_id='${alpha.id}' AND id='${delivery.id}'`);for(const secret of [unused.mobile,norm(unused.mobile),capture.code])expect(envelope.includes(secret),'outbox excludes raw code/recipient').toBe(false);
 const session=identitySql(`SELECT refresh_hash FROM identity_session WHERE tenant_id='${alpha.id}' AND id='${a.tokens.sessionId}'`);expect(session.length).toBeGreaterThanOrEqual(32);expect(session.includes(a.tokens.refreshToken)).toBe(false);
 const cross=identitySql(`BEGIN;SET LOCAL app.tenant_id='${alpha.id}';INSERT INTO identity_session(id,tenant_id,created_at,updated_at,account_id,kind,refresh_hash,device_label,expires_at,revoked_at,generation,mfa_verified,customer_account_id) SELECT gen_random_uuid(),tenant_id,created_at,updated_at,account_id,kind,refresh_hash,device_label,expires_at,revoked_at,generation,mfa_verified,'${b.principal.id}'::uuid FROM identity_session WHERE kind='customer' LIMIT 1;ROLLBACK;`,null,false);expect(cross.includes('foreign key')).toBe(true);
 const owner=await identity.staff(alpha,'OWNER');const audit=JSON.stringify(await identity.audit(owner));const logs=fs.readFileSync(apiLog,'utf8');for(const value of [norm(unused.mobile),capture.code,a.tokens.accessToken,a.tokens.refreshToken,unused.device]){expect(audit.includes(value),'audit secret-free').toBe(false);expect(logs.includes(value),'log secret-free').toBe(false);}
 const contract=committedContract();for(const route of ['/api/v1/identity/customers/challenges','/api/v1/identity/customers/sign-in'])expect(contract.paths[route]).toBeTruthy();
 const operation=contract.paths['/api/v1/identity/customers/sign-in'].post as {requestBody:{content:{'application/json':{schema:{$ref:string}}}}};const schema=contract.components.schemas[operation.requestBody.content['application/json'].schema.$ref.split('/').pop()!];expect(schema.required?.includes('challengeToken')).toBe(false);expect(schema.required?.includes('code')).toBe(true);
});
