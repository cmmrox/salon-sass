import { randomInt } from 'node:crypto';
import { test,expect,alpha,beta,json,problem,type Tokens } from '../../shared/identity';
import { Customers,syntheticMobile } from '../../shared/customer-identity';
import { identitySql } from '../../shared/identity-storage';
import { localService,codeSettings,mark } from '../../shared/identity-runtime';

const count=(table:string,tenant=alpha.id)=>Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id='${tenant}'`));
const settings={SALON_IDENTITY_CODE_DAILY_LIMIT:'5',SALON_IDENTITY_CODE_DEVICE_LIMIT:'100',SALON_IDENTITY_CODE_ADDRESS_LIMIT:'100'};
const c=(n:number)=>`TC-F02-S02-${String(n).padStart(3,'0')}`;

test(`${c(11)} number and daily admission races enqueue only their allowed budgets`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const mobile=syntheticMobile();const before=count('outbox_message');
 const responses=await Promise.all(Array.from({length:8},()=>customers.request(alpha,{mobile,channel:'sms',deviceLabel:'QA'})));
 expect(responses.filter(r=>r.status()===200).length).toBe(3);expect(responses.filter(r=>r.status()===429).length).toBe(5);for(const r of responses.filter(r=>r.status()===429)){await problem(r,429,'rate_limited');expect(Number(r.headers()['retry-after'])).toBeGreaterThan(0);}
 expect(count('identity_customer_challenge')).toBe(3);expect(count('outbox_message')-before).toBe(3);
 await codeSettings(settings,async()=>{await customers.reset();mark('daily-limit-local-override',{daily:5,device:100,address:100});const initial=count('outbox_message');
  const rows=await Promise.all(Array.from({length:8},()=>customers.request(alpha,{mobile:syntheticMobile(),channel:'sms',deviceLabel:'QA'})));
  expect(rows.filter(r=>r.status()===200).length).toBe(5);expect(rows.filter(r=>r.status()===429).length).toBe(3);expect(count('identity_customer_challenge')).toBe(5);expect(count('outbox_message')-initial).toBe(5);
  for(const r of rows.filter(r=>r.status()===429))await problem(r,429,'rate_limited');
 },identity.api);mark('daily-limit-default-restored',{number:3,device:10,address:20,daily:1000});
});
test(`${c(12)} restart cannot reset accepted counters or invalidate issuing-device signature`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();const mobile=syntheticMobile();for(let n=0;n<3;n++)await customers.challenge(alpha,mobile);const issued=await customers.challenge(alpha);const capture=await customers.capture(alpha,issued.challenge.challengeId);
 mark('customer-api-restart-asked');localService('stop','api');localService('start','api');await identity.api.reconnect();mark('customer-api-restart-returned');
 const before=count('outbox_message');await problem(await customers.request(alpha,{mobile,channel:'sms',deviceLabel:'QA'}),429,'rate_limited');expect(count('outbox_message')).toBe(before);
 const tokens=await json<Tokens>(await customers.verify(alpha,{challengeId:issued.challenge.challengeId,code:capture.code},issued.device),'Tokens');expect((await identity.principal(alpha,tokens)).kind).toBe('customer');
});
test(`${c(13)} an eighty-percent alert occurs once per UTC day and never crosses salons`,async({identity})=>{
 const customers=new Customers(identity);await codeSettings(settings,async()=>{
  await customers.reset();const now=Date.parse(await identity.now());const day=new Date(now).toISOString().slice(0,10);const instant=`${day}T23:58:00Z`;await identity.clock(instant);
  // Fresh synthetic future day avoids immutable alert evidence from previous runs/handoffs.
  const nextDay=new Date(Date.parse(instant)+randomInt(30,10000)*86400000).toISOString().slice(0,10);await identity.clock(`${nextDay}T23:58:00Z`);
  // Previously issued Owner access expires under the clock jump: authenticate fresh synthetic Owner.
  const active=await identity.staff(alpha,'OWNER'),foreign=await identity.staff(beta,'OWNER');
  const baseline=(await identity.audit(active)).items.filter(e=>e.action==='identity.code_volume_alert'&&e.subjectId===nextDay).length;expect(baseline).toBe(0);
  const meterPath=`/actuator/metrics/salon.identity.code.volume.alerts?tag=tenant.id:${alpha.id}`;
  const meter=async()=>{const r=await identity.api.get(meterPath);if(r.status()===404)return 0;expect(r.status()).toBe(200);const m=await r.json();expect(m.measurements.length).toBeGreaterThan(0);return m.measurements[0].value as number;};const initial=await meter();
  const constrained=syntheticMobile();for(let n=0;n<3;n++)await customers.challenge(alpha,constrained);await customers.challenge(alpha);
  const alert=(await identity.audit(active)).items.filter(e=>e.action==='identity.code_volume_alert'&&e.subjectId===nextDay);expect(alert.length).toBe(1);expect(await meter()).toBe(initial+1);expect(JSON.stringify(alert).includes(constrained)).toBe(false);
  await customers.challenge(alpha);const before=count('outbox_message');const denied=await customers.request(alpha,{mobile:syntheticMobile(),channel:'sms',deviceLabel:'QA'});await problem(denied,429,'rate_limited');expect(count('outbox_message')).toBe(before);expect((await identity.audit(active)).items.filter(e=>e.action==='identity.code_volume_alert'&&e.subjectId===nextDay).length).toBe(1);
  await problem(await identity.call(beta,'GET','/audit',undefined,active.tokens),404,'not_found');expect((await identity.audit(foreign)).items.filter(e=>e.action==='identity.code_volume_alert'&&e.subjectId===nextDay).length).toBe(0);
  const midnight=new Date(Date.parse(`${nextDay}T23:58:00Z`)+121000).toISOString();await identity.clock(midnight);await customers.challenge(alpha);await problem(await customers.request(alpha,{mobile:constrained,channel:'sms',deviceLabel:'QA'}),429,'rate_limited');
  for(let n=0;n<3;n++)await customers.challenge(alpha);expect(await meter()).toBe(initial+2);const newDay=midnight.slice(0,10);expect((await identity.audit(active)).items.filter(e=>e.action==='identity.code_volume_alert'&&e.subjectId===newDay).length).toBe(1);
 },identity.api);
});
test(`${c(16)} failed and silent message providers retry the same request and recover`,async({identity})=>{
 const customers=new Customers(identity);
 for(const channel of ['sms','whatsapp']as const)for(const fault of ['failed','silent']){
  await customers.reset();await customers.control(alpha,channel,fault==='failed'?1:0,fault==='silent'?12000:0);const before=count('identity_customer_challenge');const issued=await customers.challenge(alpha,syntheticMobile(),channel);
  await expect.poll(async()=>{const d=await customers.delivery(alpha,issued.challenge.challengeId);return d.status==='PENDING'&&d.attempts===1;},{timeout:15000,intervals:[50,100,250]}).toBe(true);
  const waiting=await customers.delivery(alpha,issued.challenge.challengeId);expect(waiting.nextAttemptAt).toBeTruthy();await customers.control(alpha,channel);await identity.clock(new Date(Date.parse(waiting.nextAttemptAt!)+1000).toISOString());
  const capture=await customers.capture(alpha,issued.challenge.challengeId);expect(capture.attempt).toBe(2);expect(capture.channel).toBe(channel);expect(count('identity_customer_challenge')-before).toBe(1);
  await expect.poll(async()=>(await customers.delivery(alpha,issued.challenge.challengeId)).status,{timeout:5000,intervals:[25,50,100]}).toBe('SENT');await identity.clock();
 }
});
test(`${c(17)} an expired in-flight code is cancelled without capture`,async({identity})=>{
 const customers=new Customers(identity);await customers.reset();await customers.control(alpha,'whatsapp',0,3000);const issued=await customers.challenge(alpha,syntheticMobile(),'whatsapp');
 await expect.poll(async()=>(await customers.delivery(alpha,issued.challenge.challengeId)).status,{timeout:2500,intervals:[25,50]}).toBe('SENDING');await identity.clock(new Date(Date.parse(issued.challenge.expiresAt)+1000).toISOString());
 await expect.poll(async()=>(await customers.delivery(alpha,issued.challenge.challengeId)).status,{timeout:5000,intervals:[25,50,100]}).toBe('CANCELLED');await problem(await identity.call(alpha,'GET',`/local/identity/captures/${issued.challenge.challengeId}`),404,'not_found');
 await problem(await customers.verify(alpha,{challengeId:issued.challenge.challengeId,code:'123456'},issued.device),401,'code_expired');expect(count('identity_customer')).toBe(0);
});
