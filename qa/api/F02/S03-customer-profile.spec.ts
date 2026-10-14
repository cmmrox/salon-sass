import { randomUUID } from 'node:crypto';
import { test,expect,alpha,beta,gamma,ROLES,json,problem,type Actor,type Tokens } from '../../shared/identity';
import { Customers,syntheticMobile,type SignedCustomer } from '../../shared/customer-identity';
import { Profiles,type Profile,type ProfileUpdate,type DataCopy } from '../../shared/customer-profile';
import { identitySql } from '../../shared/identity-storage';

const c=(n:number)=>`TC-F02-S03-${String(n).padStart(3,'0')}`;
const normalize=(mobile:string)=>`+94${mobile.slice(1)}`;
const profileKeys=['birthday','email','id','marketingConsent','mobile','name','notificationPreferences','preferredLanguage','version'];

// All customers authenticate through the actual code/capture route, never fabricated tokens.
test(`${c(1)} newly verified customer has an own profile with explicit defaults`,async({identity})=>{
 const profiles=new Profiles(identity);const actor=await profiles.customers.login(alpha);const other=await profiles.customers.login(beta);const p=await profiles.read(actor);
 expect(Object.keys(p).sort()).toEqual(profileKeys.sort());expect(p).toEqual({id:actor.principal.id,version:0,name:'',mobile:normalize(actor.mobile),email:null,birthday:null,preferredLanguage:'en',notificationPreferences:{sms:true,whatsapp:true,email:true},marketingConsent:false});
 expect((await profiles.read(other)).id).toBe(other.principal.id);expect(p.id===other.principal.id).toBe(false);
});
test(`${c(2)} privacy endpoints reject foreign salons staff forged and absent credentials`,async({identity})=>{
 const profiles=new Profiles(identity);const a=await profiles.customers.login(alpha),b=await profiles.customers.login(beta,a.mobile),stranger=await profiles.customers.login(alpha);const saved=await profiles.saved(a),before=await profiles.saved(b,'Beta Synthetic'),strangerBefore=await profiles.saved(stranger,'Other Alpha');
 const requests:[string,string,unknown?][]=[['GET','/customers/me'],['PUT','/customers/me',Profiles.updateBody(saved,{name:'Unauthorized'})],['DELETE','/customers/me',{confirm:true}],['GET','/customers/me/export']];
 for(const [method,path,data]of requests){await problem(await identity.call(beta,method,path,data,a.tokens),404,'not_found');await problem(await identity.call(alpha,method,path,data),401,'invalid_session');await problem(await identity.call(alpha,method,path,data,{...a.tokens,accessToken:a.tokens.accessToken+'.forged'}),401,'invalid_session');}
 for(const role of ROLES){const staff=await identity.staff(alpha,role);for(const [method,path,data]of requests)await problem(await identity.call(alpha,method,path,data,staff.tokens),401,'invalid_session');}
 for(const field of ['id','customerId','tenantId','mobile'])await problem(await identity.call(alpha,'PUT','/customers/me',{...Profiles.updateBody(saved),[field]:field==='tenantId'?beta.id:stranger.principal.id},a.tokens),400,'bad_request');
 for(const path of [`/customers/${b.principal.id}`,`/customers/${stranger.principal.id}/export`])await problem(await identity.call(alpha,'GET',path,undefined,a.tokens),404,'not_found');
 const own=await json<Profile>(await identity.call(alpha,'GET',`/customers/me?customerId=${stranger.principal.id}&tenantId=${beta.id}`,undefined,a.tokens),'Profile');expect(own.id).toBe(a.principal.id);
 expect(await profiles.read(b)).toEqual(before);expect(await profiles.read(stranger)).toEqual(strangerBefore);expect(await profiles.read(a)).toEqual(saved);
});
test(`${c(3)} complete updates normalize and persist then clear optional fields`,async({identity})=>{
 const profiles=new Profiles(identity),actor=await profiles.customers.login(alpha);let current=await profiles.read(actor);
 for(const language of ['en','si','ta']){const before=current.version;const data=Profiles.updateBody(current,{name:'  QA Profile  ',email:'  QA.Profile@Example.Invalid  ',birthday:'2000-02-29',preferredLanguage:language,notificationPreferences:{sms:false,whatsapp:true,email:false},marketingConsent:true});current=await profiles.update(actor,data);
  expect(current.version).toBe(before+1);expect(current.name).toBe('QA Profile');expect(current.email).toBe('qa.profile@example.invalid');expect(current.birthday).toBe('2000-02-29');expect(current.preferredLanguage).toBe(language);expect(current.notificationPreferences).toEqual(data.notificationPreferences);expect(current.marketingConsent).toBe(true);expect(current.mobile).toBe(normalize(actor.mobile));expect(await profiles.read(actor)).toEqual(current);
 }
 for(const clear of ['null','omitted','blank']){const data=Profiles.updateBody(current,{email:clear==='blank'?'   ':null,birthday:null,marketingConsent:false});if(clear==='omitted'){delete data.email;delete data.birthday;}current=await profiles.update(actor,data);expect(current.email).toBeNull();expect(current.birthday).toBeNull();expect(current.marketingConsent).toBe(false);expect(current.notificationPreferences.whatsapp).toBe(true);}
});
test(`${c(4)} invalid or injected updates leave saved profile and version unchanged`,async({identity})=>{
 const profiles=new Profiles(identity),actor=await profiles.customers.login(alpha);let saved=await profiles.saved(actor);const data=Profiles.updateBody(saved);
 const variants:[string,unknown][]=[['name',''],['name','   '],['name','💇'.repeat(121)],['email','invalid-address'],['birthday','2999-01-01'],['birthday','2026-02-30'],['preferredLanguage','fr'],['version','zero'],['marketingConsent','true'],['notificationPreferences',{sms:'true',whatsapp:true,email:true}],['notificationPreferences',{sms:true,whatsapp:true}],['mobile','0771234567'],['id',randomUUID()],['tenantId',beta.id]];
 for(const [field,value]of variants){const p=await problem(await identity.call(alpha,'PUT','/customers/me',{...data,[field]:value},actor.tokens),400,'bad_request');expect(p.errors?.some(e=>e.field===field||e.field.startsWith(field+'.')),'invalid field identified').toBe(true);expect(await profiles.read(actor)).toEqual(saved);}
 for(const omitted of ['marketingConsent','notificationPreferences']){const body:Record<string,unknown>={...data};delete body[omitted];const p=await problem(await identity.call(alpha,'PUT','/customers/me',body,actor.tokens),400,'bad_request');expect(p.errors?.some(e=>e.field===omitted)).toBe(true);expect(await profiles.read(actor)).toEqual(saved);}
 for(const name of ['A','💇'.repeat(120)]){saved=await profiles.update(actor,Profiles.updateBody(saved,{name}));expect(saved.name).toBe(name);}
});
test(`${c(5)} birthday validation follows each salon local date`,async({identity})=>{
 await identity.clock('2030-03-28T20:30:00Z');const profiles=new Profiles(identity);
 for(const [salon,today,tomorrow]of [[alpha,'2030-03-29','2030-03-30'],[gamma,'2030-03-28','2030-03-29']]as const){const actor=await profiles.customers.login(salon);const saved=await profiles.saved(actor);const accepted=await profiles.update(actor,Profiles.updateBody(saved,{birthday:today}));expect(accepted.birthday).toBe(today);
  const p=await problem(await identity.call(salon,'PUT','/customers/me',Profiles.updateBody(accepted,{birthday:tomorrow}),actor.tokens),400,'bad_request');expect(p.errors?.some(e=>e.field==='birthday')).toBe(true);expect(await profiles.read(actor)).toEqual(accepted);
 }
});
test(`${c(6)} concurrent and stale versions preserve the one successful profile`,async({identity})=>{
 const profiles=new Profiles(identity),actor=await profiles.customers.login(alpha),saved=await profiles.saved(actor);const data=[Profiles.updateBody(saved,{name:'Concurrent A',email:'a@example.invalid'}),Profiles.updateBody(saved,{name:'Concurrent B',email:'b@example.invalid'})];
 const results=await Promise.all(data.map(body=>identity.call(alpha,'PUT','/customers/me',body,actor.tokens)));expect(results.filter(r=>r.status()===200).length).toBe(1);expect(results.filter(r=>r.status()===409).length).toBe(1);await problem(results.find(r=>r.status()===409)!,409,'conflict');const winner=await json<Profile>(results.find(r=>r.status()===200)!,'Profile');expect(winner.version).toBe(saved.version+1);expect(await profiles.read(actor)).toEqual(winner);
 await problem(await identity.call(alpha,'PUT','/customers/me',data[0],actor.tokens),409,'conflict');expect(await profiles.read(actor)).toEqual(winner);
});
test(`${c(7)} concurrent profile reads and exports never cross salons`,async({identity})=>{
 const profiles=new Profiles(identity),a=await profiles.customers.login(alpha),b=await profiles.customers.login(beta);const expected=[await profiles.saved(a,'Alpha Profile'),await profiles.saved(b,'Beta Profile')];
 const callers=Array.from({length:16},(_,n)=>({actor:n%2?a:b,profile:expected[n%2?0:1],export:n%4===0}));const results=await Promise.all(callers.map(async(row)=>row.export?(await json<DataCopy>(await profiles.export(row.actor),'Export')).profile:await profiles.read(row.actor)));
 expect(results.length).toBe(callers.length);results.forEach((p,n)=>expect(p).toEqual(callers[n].profile));
 expect(identitySql('SELECT count(*) FROM identity_customer',null)).toBe('0');expect(identitySql(`SELECT count(*) FROM identity_customer WHERE tenant_id='${beta.id}'`,alpha.id)).toBe('0');
});
test(`${c(8)} own JSON export uses an allowlist download headers and rights audit`,async({identity})=>{
 const profiles=new Profiles(identity),actor=await profiles.customers.login(alpha);const second=await profiles.customers.login(alpha,actor.mobile,'whatsapp',actor.device),stranger=await profiles.customers.login(alpha),foreign=await profiles.customers.login(beta);const saved=await profiles.saved(actor);const owner=await identity.staff(alpha,'OWNER');const baseline=(await identity.audit(owner,actor.principal.id)).items.filter(e=>e.action==='identity.data_exported').length;
 const r=await profiles.export(actor);expect(r.headers()['cache-control']).toContain('no-store');expect(r.headers()['content-disposition']).toBe('attachment; filename="customer-data.json"');const body=await json<DataCopy>(r,'Export');expect(Object.keys(body).sort()).toEqual(['devices','generatedAt','profile']);expect(body.profile).toEqual(saved);expect(Number.isFinite(Date.parse(body.generatedAt))).toBe(true);
 expect(body.devices.length).toBe(2);expect(body.devices.map(d=>d.id).sort()).toEqual([actor.tokens.sessionId,second.tokens.sessionId].sort());for(const d of body.devices)expect(Object.keys(d).sort()).toEqual(['createdAt','current','deviceLabel','expiresAt','id']);
 const text=JSON.stringify(body);for(const forbidden of [actor.tokens.accessToken,actor.tokens.refreshToken,second.tokens.accessToken,stranger.principal.id,foreign.principal.id,owner.account.password])expect(text.includes(forbidden),'own export excludes other authority or owner').toBe(false);
 expect(Object.keys(body.profile).sort()).toEqual(profileKeys.sort());expect((await identity.audit(owner,actor.principal.id)).items.filter(e=>e.action==='identity.data_exported').length).toBe(baseline+1);
});
test(`${c(9)} invalid deletion confirmation cannot erase any data or authority`,async({identity})=>{
 const profiles=new Profiles(identity),actor=await profiles.customers.login(alpha),saved=await profiles.saved(actor);const devices=await identity.sessions(actor);const owner=await identity.staff(alpha,'OWNER');const before=(await identity.audit(owner,actor.principal.id)).items.filter(e=>e.action==='identity.account_deleted').length;
 for(const body of [{confirm:false},{},{confirm:'true'},{confirm:null},{confirm:true,id:randomUUID()}]){const p=await problem(await identity.call(alpha,'DELETE','/customers/me',body,actor.tokens),400,body.confirm===false?'confirmation_required':'bad_request');expect(p.errors?.some(e=>['confirm','id'].includes(e.field))).toBe(true);expect(await profiles.read(actor)).toEqual(saved);expect(await identity.sessions(actor)).toEqual(devices);}
 expect((await identity.audit(owner,actor.principal.id)).items.filter(e=>e.action==='identity.account_deleted').length).toBe(before);
});

test(`${c(10)} committed deletion erases all owned material while retaining rate protection`,async({identity})=>{
 const profiles=new Profiles(identity),actor=await profiles.customers.login(alpha),other=await profiles.customers.login(beta,actor.mobile),second=await profiles.customers.login(alpha,actor.mobile,'sms',actor.device);await profiles.saved(actor,'Erase This Synthetic Profile');const otherProfile=await profiles.saved(other,'Keep Beta Profile');
 const rotated=await json<Tokens>(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:actor.tokens.refreshToken}),'Tokens');const old=[actor.tokens,second.tokens,rotated];actor.tokens=rotated;
 const unused=await profiles.customers.challenge(alpha,actor.mobile,'whatsapp',actor.device);await profiles.customers.capture(alpha,unused.challenge.challengeId);
 const rows=identitySql(`SELECT id::text||'|'||number_hash||'|'||message_id::text FROM identity_customer_challenge WHERE tenant_id='${alpha.id}' ORDER BY id`).split('\n');expect(rows.length).toBe(3);const numberHash=rows[0].split('|')[1];expect(numberHash).toBeTruthy();
 const sessionIds=identitySql(`SELECT id FROM identity_session WHERE tenant_id='${alpha.id}' AND customer_account_id='${actor.principal.id}'`).split('\n');expect(sessionIds.length).toBe(2);
 const rates=identitySql(`SELECT id,dimension,key_hash,attempts,window_start FROM identity_rate_bucket WHERE tenant_id='${alpha.id}' AND dimension LIKE 'customer-%' ORDER BY id`);expect(rates.length).toBeGreaterThan(0);
 expect((await profiles.delete(actor)).status()).toBe(204);
 for(const tokens of old){await problem(await identity.call(alpha,'GET','/me',undefined,tokens),401,'invalid_session');await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:tokens.refreshToken}),401,'invalid_session');}
 expect(identitySql(`SELECT count(*) FROM identity_customer WHERE tenant_id='${alpha.id}' AND id='${actor.principal.id}'`)).toBe('0');expect(identitySql(`SELECT count(*) FROM identity_session WHERE tenant_id='${alpha.id}' AND customer_account_id='${actor.principal.id}'`)).toBe('0');
 for(const sessionId of sessionIds)expect(identitySql(`SELECT count(*) FROM identity_refresh_history WHERE tenant_id='${alpha.id}' AND session_id='${sessionId}'`)).toBe('0');
 expect(identitySql(`SELECT count(*) FROM identity_customer_challenge WHERE tenant_id='${alpha.id}' AND number_hash='${numberHash}'`)).toBe('0');
 for(const row of rows){const [challengeId,,messageId]=row.split('|');expect(identitySql(`SELECT count(*) FROM outbox_message WHERE tenant_id='${alpha.id}' AND id='${messageId}'`)).toBe('0');await problem(await identity.call(alpha,'GET',`/local/identity/captures/${challengeId}`),404,'not_found');}
 expect(identitySql(`SELECT id,dimension,key_hash,attempts,window_start FROM identity_rate_bucket WHERE tenant_id='${alpha.id}' AND dimension LIKE 'customer-%' ORDER BY id`)).toBe(rates);
 expect(await profiles.read(other)).toEqual(otherProfile);const before=Number(identitySql(`SELECT count(*) FROM outbox_message WHERE tenant_id='${alpha.id}'`));await problem(await profiles.customers.request(alpha,{mobile:actor.mobile,channel:'sms',deviceLabel:'QA'},actor.device),429,'rate_limited');expect(Number(identitySql(`SELECT count(*) FROM outbox_message WHERE tenant_id='${alpha.id}'`))).toBe(before);
});
test(`${c(11)} fresh verification after erasure creates a new id and default profile`,async({identity})=>{
 const profiles=new Profiles(identity),actor=await profiles.customers.login(alpha),foreign=await profiles.customers.login(beta,actor.mobile);await profiles.saved(actor,'Deleted Profile');const betaProfile=await profiles.saved(foreign,'Still Beta');const oldId=actor.principal.id;expect((await profiles.delete(actor)).status()).toBe(204);
 const fresh=await profiles.customers.login(alpha,actor.mobile,'whatsapp',actor.device);expect(fresh.principal.id===oldId).toBe(false);const p=await profiles.read(fresh);expect(p.name).toBe('');expect(p.email).toBeNull();expect(p.birthday).toBeNull();expect(p.preferredLanguage).toBe('en');expect(p.notificationPreferences).toEqual({sms:true,whatsapp:true,email:true});expect(p.marketingConsent).toBe(false);expect((await identity.sessions(fresh)).total).toBe(1);expect(await profiles.read(foreign)).toEqual(betaProfile);
 await problem(await profiles.customers.verify(alpha,{challengeId:actor.challenge.challengeId,code:'123456'},actor.device),404,'not_found');
});
test(`${c(12)} queued and in-flight delivery cannot restore erased state`,async({identity})=>{
 const profiles=new Profiles(identity);
 for(const state of ['queued','in-flight']){
  await identity.reset();const actor=await profiles.customers.login(alpha),foreign=await profiles.customers.login(beta,actor.mobile);await profiles.saved(actor);const betaProfile=await profiles.saved(foreign);
  await profiles.customers.control(alpha,'whatsapp',state==='queued'?1:0,state==='in-flight'?3000:0);const issued=await profiles.customers.challenge(alpha,actor.mobile,'whatsapp',actor.device);
  await expect.poll(async()=>{const d=await profiles.customers.delivery(alpha,issued.challenge.challengeId);return state==='queued'?d.status==='PENDING'&&d.attempts===1:d.status==='SENDING';},{timeout:5000,intervals:[25,50,100]}).toBe(true);
  const delivery=await profiles.customers.delivery(alpha,issued.challenge.challengeId);
  const cancelled=async()=>{const r=await identity.api.get('/actuator/metrics/salon.outbox.deliveries?tag=channel:whatsapp&tag=outcome:cancelled');if(r.status()===404)return 0;expect(r.status()).toBe(200);const data=await r.json();expect(data.measurements.length).toBeGreaterThan(0);return data.measurements[0].value as number;};
  const cancelledBefore=await cancelled();expect((await profiles.delete(actor)).status()).toBe(204);await profiles.customers.control(alpha,'whatsapp');
  if(state==='queued'&&delivery.nextAttemptAt){await identity.clock(new Date(Date.parse(delivery.nextAttemptAt)+1000).toISOString());}
  // A new public request and capture prove the dispatcher can make progress after old delivery.
  const fresh=await profiles.customers.login(alpha);expect(fresh.principal.id===actor.principal.id).toBe(false);
  if(state==='in-flight')await expect.poll(cancelled,{timeout:5000,intervals:[25,50,100]}).toBeGreaterThan(cancelledBefore);
  await problem(await identity.call(alpha,'GET',`/local/identity/captures/${issued.challenge.challengeId}`),404,'not_found');await problem(await identity.call(alpha,'GET',`/local/identity/deliveries/${issued.challenge.challengeId}`),404,'not_found');
  expect(identitySql(`SELECT count(*) FROM identity_customer WHERE tenant_id='${alpha.id}' AND id='${actor.principal.id}'`)).toBe('0');expect(identitySql(`SELECT count(*) FROM outbox_message WHERE tenant_id='${alpha.id}' AND id='${delivery.id}'`)).toBe('0');await problem(await identity.call(alpha,'GET','/me',undefined,actor.tokens),401,'invalid_session');expect(await profiles.read(foreign)).toEqual(betaProfile);await identity.clock();
 }
});
test(`${c(13)} deletion versus refresh or proof leaves no surviving deleted authority`,async({identity})=>{
 const profiles=new Profiles(identity);
 for(const operation of ['refresh','verification']){
  await identity.reset();const actor=await profiles.customers.login(alpha);await profiles.saved(actor);const oldId=actor.principal.id;let issuing:Awaited<ReturnType<Customers['challenge']>>|undefined,code='';
  if(operation==='verification'){issuing=await profiles.customers.challenge(alpha,actor.mobile,'whatsapp',actor.device);code=(await profiles.customers.capture(alpha,issuing.challenge.challengeId)).code;}
  const responses=await Promise.all([profiles.delete(actor),operation==='refresh'?identity.call(alpha,'POST','/sessions/refresh',{refreshToken:actor.tokens.refreshToken}):profiles.customers.verify(alpha,{challengeId:issuing!.challenge.challengeId,code},issuing!.device)]);
  const [deletion,competing]=responses;const credentials=[actor.tokens];
  if(competing.status()===200)credentials.push(await json<Tokens>(competing,'Tokens'));else if(operation==='refresh')await problem(competing,401,'invalid_session');else await problem(competing,competing.status()===404?404:401,competing.status()===404?'not_found':'invalid_code');
  if(deletion.status()===401){expect(operation).toBe('refresh');expect(competing.status()).toBe(200);await problem(deletion,401,'invalid_session');await problem(await identity.call(alpha,'GET','/me',undefined,actor.tokens),401,'invalid_session');const current={...actor,tokens:credentials[1]};expect((await profiles.read(current)).id).toBe(oldId);expect((await profiles.delete(current)).status()).toBe(204);}
  else expect(deletion.status()).toBe(204);
  // Statuses alone do not establish erasure: both overlapping successes may have serialized safely.
  for(const tokens of credentials){await problem(await identity.call(alpha,'GET','/me',undefined,tokens),401,'invalid_session');await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:tokens.refreshToken}),401,'invalid_session');}
  expect(identitySql(`SELECT count(*) FROM identity_customer WHERE tenant_id='${alpha.id}' AND id='${oldId}'`)).toBe('0');expect(identitySql(`SELECT count(*) FROM identity_session WHERE tenant_id='${alpha.id}' AND customer_account_id='${oldId}'`)).toBe('0');
  if(issuing)await problem(await profiles.customers.verify(alpha,{challengeId:issuing.challenge.challengeId,code},issuing.device),404,'not_found');
  const {mark}=await import('../../shared/identity-runtime');mark('privacy-race-order',{operation,deletionStatus:deletion.status(),competingStatus:competing.status()});
 }
});
test(`${c(14)} rights audit keeps opaque safe metadata after erasure and remains immutable`,async({identity})=>{
 const profiles=new Profiles(identity),actor=await profiles.customers.login(alpha),owner=await identity.staff(alpha,'OWNER'),manager=await identity.staff(alpha,'MANAGER');const saved=await profiles.saved(actor,'Specific Synthetic Name');await json<DataCopy>(await profiles.export(actor),'Export');expect((await profiles.delete(actor)).status()).toBe(204);
 const audits=(await identity.audit(owner,actor.principal.id)).items;const events=audits.filter(e=>['identity.profile_updated','identity.data_exported','identity.account_deleted'].includes(e.action!));expect(events.length).toBe(3);for(const e of events){expect(e.actorId).toBe(actor.principal.id);expect(e.actorKind).toBe('customer');expect(e.subjectId).toBe(actor.principal.id);expect(Number.isFinite(Date.parse(e.occurredAt!))).toBe(true);}
 const updated=events.find(e=>e.action==='identity.profile_updated')!;expect(JSON.stringify(updated).includes('version')).toBe(true);expect(JSON.stringify(updated).includes('name')).toBe(true);
 const text=JSON.stringify(audits);for(const personal of [saved.mobile,saved.name,saved.email!,saved.birthday!,actor.tokens.accessToken,actor.tokens.refreshToken])expect(text.includes(personal),'rights audit excludes personal values and authority').toBe(false);
 expect((await identity.audit(manager,actor.principal.id)).items).toEqual(audits);for(const role of ['RECEPTIONIST','PROFESSIONAL','ACCOUNTANT']as const){const staff=await identity.staff(alpha,role);await problem(await identity.call(alpha,'GET','/audit',undefined,staff.tokens),403,'permission_denied');}
 const refusal=identitySql(`BEGIN;UPDATE audit_entry SET action='modified' WHERE id='${events[0].id}';ROLLBACK;`,undefined,false);expect(/immutable|cannot.*update|not allowed/i.test(refusal)).toBe(true);
});
