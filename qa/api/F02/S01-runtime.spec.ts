import fs from 'node:fs';
import { spawn } from 'node:child_process';
import { test,expect,alpha,beta,problem } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import { apiLog,localKeys,localService,mark,apiProcess } from '../../shared/identity-runtime';

// No request body, cookie, JWT or other credential is written to run artifacts.
test('TC-F02-S01-027 API restart preserves local keys sessions and failed-attempt limits',async({identity})=>{
 const actor=await identity.staff();const keys=localKeys();expect(keys.mode).toBe(0o600);const unknown='qa-restart-unknown@example.invalid';
 for(let n=0;n<10;n++)await problem(await identity.call(alpha,'POST','/staff/sign-in',{identifier:unknown,password:actor.account.password,deviceLabel:'QA'}),401,'invalid_credentials');
 const c=await identity.signIn(alpha,actor.account);expect(c.credentials?.sessionId).toBeTruthy();
 mark('api-restart-asked');localService('stop','api');try{localService('start','api');await identity.api.reconnect();}finally{mark('api-restart-returned');}
 await expect.poll(async()=>{try{return (await identity.api.health()).status();}catch{return 0;}},{timeout:30000,intervals:[50,100,250]}).toBe(200);
 expect(localKeys().fingerprint===keys.fingerprint,'same generated keys after restart').toBe(true);expect((await identity.principal(alpha,actor.tokens)).id).toBe(actor.principal.id);
 await problem(await identity.call(alpha,'POST','/staff/sign-in',{identifier:unknown,password:actor.account.password,deviceLabel:'QA'}),429,'rate_limited');
 const text=fs.readFileSync(apiLog,'utf8');for(const secret of [actor.account.password,actor.account.mobile,actor.tokens.accessToken,actor.tokens.refreshToken])expect(text.includes(secret),'runtime log excludes supplied secret').toBe(false);
});

test('TC-F02-S01-028 stopped database answers identity requests within five seconds and recovers',async({identity})=>{
 const a=await identity.staff(alpha),b=await identity.staff(beta);const pid=apiProcess();mark('postgres-stop-asked');localService('stop','postgres');mark('postgres-stop-returned');
 try{
  for(const actor of [a,b]){const start=mark('identity-down-request-start');const r=await identity.call(actor.salon,'GET','/me',undefined,actor.tokens);const end=mark('identity-down-request-end',{duration:Date.now()-start,status:r.status()});await problem(r,503,'service_unavailable');expect(end-start).toBeLessThan(5000);}
 }finally{mark('postgres-start-asked');localService('start','postgres');mark('postgres-start-returned');}
 expect(identitySql('SELECT 1')).toBe('1');const available=mark('postgres-available');
 for(const actor of [a,b]){const r=await identity.call(actor.salon,'GET','/me',undefined,actor.tokens);expect(r.status()).toBe(200);const p=await r.json();expect(p.id).toBe(actor.principal.id);mark('identity-down-recovered',{elapsed:Date.now()-available});expect(Date.now()-available).toBeLessThan(5000);}
 expect(apiProcess()).toBe(pid);
});

test('TC-F02-S01-029 silent database answers in-flight identity requests and recovers within five seconds',async({identity})=>{
 const a=await identity.staff(alpha),b=await identity.staff(beta);const pid=apiProcess();
 const lock=spawn('docker',['exec','-i','salon-postgres','psql','-U',process.env.SALON_DB_USER??'salon','-d',process.env.SALON_DB_NAME??'salon','-X','-qAt'],{stdio:['pipe','pipe','pipe']});
 let held=false;lock.stdout.on('data',chunk=>{if(String(chunk).includes('QA_LOCK_HELD'))held=true;});lock.stdin.write("BEGIN;LOCK TABLE identity_staff IN ACCESS EXCLUSIVE MODE;SELECT 'QA_LOCK_HELD';\n");
 let outcomes:Promise<{r:Awaited<ReturnType<typeof identity.call>>;start:number;end:number}>[]=[];
 try{
  await expect.poll(()=>held,{timeout:3000,intervals:[25]}).toBe(true);
  outcomes=[a,b].map(actor=>{const start=mark('identity-silent-request-start');return identity.call(actor.salon,'GET','/me',undefined,actor.tokens).then(r=>({r,start,end:mark('identity-silent-request-end',{duration:Date.now()-start,status:r.status()})}));});
  // Observe the requests waiting on the real table lock, not an arbitrary sleep.
  await expect.poll(()=>Number(identitySql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%identity_staff%' AND pid<>pg_backend_pid()")),{timeout:2000,intervals:[25]}).toBeGreaterThanOrEqual(2);
  mark('postgres-pause-asked');localService('pause','postgres');const frozen=mark('postgres-pause-returned');
  const results=await Promise.all(outcomes);expect(results.length).toBe(2);
  for(const result of results){expect(result.start).toBeLessThan(frozen);expect(result.end).toBeGreaterThan(frozen);await problem(result.r,503,'service_unavailable');expect(result.end-result.start).toBeLessThan(5000);}
  const start=mark('identity-during-silence-start');const r=await identity.call(alpha,'GET','/me',undefined,a.tokens);mark('identity-during-silence-end',{duration:Date.now()-start});await problem(r,503,'service_unavailable');expect(Date.now()-start).toBeLessThan(5000);
 }finally{
  mark('postgres-resume-asked');localService('resume','postgres');mark('postgres-resume-returned');lock.stdin.end('ROLLBACK;\n');
  await new Promise<void>(resolve=>{if(lock.exitCode!==null)resolve();else lock.once('exit',()=>resolve());});
 }
 expect(identitySql('SELECT 1')).toBe('1');const available=mark('postgres-available');
 for(const actor of [a,b]){const r=await identity.call(actor.salon,'GET','/me',undefined,actor.tokens);expect(r.status()).toBe(200);expect((await r.json()).id).toBe(actor.principal.id);mark('identity-silent-recovered',{elapsed:Date.now()-available});expect(Date.now()-available).toBeLessThan(5000);}
 expect(apiProcess()).toBe(pid);
});
