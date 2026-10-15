import fs from 'node:fs';
import path from 'node:path';
import {createHmac,randomBytes} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {test,expect,handle,routes,bff} from '../../shared/identity-browser';
import {alpha,beta,syntheticAccount} from '../../shared/identity';
import {syntheticMobile} from '../../shared/customer-identity';
import {identitySql} from '../../shared/identity-storage';
import {apiProcess,localService,localKeys,productRoot,mark} from '../../shared/identity-runtime';
import {standInForApi} from '../../shared/services';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;
const state=()=>identitySql(`SELECT jsonb_build_object('challenges',(SELECT count(*) FROM identity_customer_challenge),'rates',(SELECT coalesce(sum(attempts),0) FROM identity_rate_bucket),'outbox',(SELECT count(*) FROM outbox_message),'staff',(SELECT count(*) FROM identity_staff),'sessions',(SELECT count(*) FROM identity_session))`);

test(`${c(20)} API outage and slow BFF work preserve form values and present safe retry within budget`,async({identity,accounts})=>{
 test.setTimeout(180000);const context=await accounts.context(alpha),signIn=await accounts.open(context,routes.staffSignIn);const staff=syntheticAccount();const beforeKeys=localKeys();await handle(signIn,'identifier').fill(staff.email);
 for(const mode of ['down','silent'] as const){let paused:string|undefined;
  try{
   if(mode==='down'){localService('stop','api');let listening=true;try{apiProcess();}catch{listening=false;}expect(listening,'API is down').toBe(false);}
   else{paused=apiProcess();execFileSync('kill',['-STOP',paused]);expect(execFileSync('ps',['-p',paused,'-o','stat='],{encoding:'utf8'}).includes('T'),'API is suspended').toBe(true);}
   await handle(signIn,'password').fill(staff.password);const answer=signIn.waitForResponse(r=>new URL(r.url()).pathname===bff('staff','sign-in'));await handle(signIn,'sign-in-submit').click();const response=await answer;await response.finished();expect(response.status()).toBe(503);expect(response.request().timing().responseEnd).toBeLessThan(5000);await expect(handle(signIn,'error')).toBeVisible();await expect(handle(signIn,'identifier')).toHaveValue(staff.email);await expect(handle(signIn,'password')).toHaveValue('');
  }finally{if(paused)execFileSync('kill',['-CONT',paused]);else{localService('start','api');await identity.api.reconnect();}}
  await expect.poll(async()=> (await identity.api.health()).status(),{timeout:5000}).toBe(200);expect(localKeys()).toEqual(beforeKeys);await identity.seed(alpha,[staff]);await accounts.staffSignIn(signIn,alpha,staff);await signIn.goto(routes.staffSignIn);await handle(signIn,'identifier').fill(staff.email);
 }
 // Outside fault seam: spend most of the one 3s budget on metadata, then hang the
 // mutation. A new 3s mutation timer would wait longer than the observed metadata
 // leg; the shared deadline cancels it in the shorter remaining leg. Record actual
 // disconnect separately from response serialization, never a 3000ms finish claim.
 const metaTime=await identity.now();const salonResponse=await identity.api.send('GET','/api/v1/salon',{headers:{Host:alpha.hostname}});expect(salonResponse.status()).toBe(200);const publishedSalon=await salonResponse.json();expect(publishedSalon.slug).toBe(alpha.slug);let fake:Awaited<ReturnType<typeof standInForApi>>|undefined;
 let metadataStarted=0,metadataEnded=0,mutationStarted=0,mutationClosed=0;
 localService('stop','api');
 try{
  fake=await standInForApi((request,response)=>{
   if(request.url==='/api/v1/salon'&&request.headers.host===alpha.hostname){response.writeHead(200,{'Content-Type':'application/json'});response.end(JSON.stringify(publishedSalon));}
   else if(request.url==='/api/v1/meta'){metadataStarted=performance.now();setTimeout(()=>{metadataEnded=performance.now();response.writeHead(200,{'Content-Type':'application/json'});response.end(JSON.stringify({service:'salon-api',version:'qa-fault-seam',commit:'synthetic',time:metaTime}));},2200);}
   else if(request.url==='/api/v1/identity/staff/sign-in'){mutationStarted=performance.now();response.once('close',()=>{mutationClosed=performance.now();});}
   else{response.writeHead(503);response.end();}
  });
  await handle(signIn,'password').fill(staff.password);const answer=signIn.waitForResponse(r=>new URL(r.url()).pathname===bff('staff','sign-in'));const start=performance.now();await handle(signIn,'sign-in-submit').click();const response=await answer;await response.finished();expect(response.status()).toBe(503);
  await expect.poll(()=>mutationClosed,{timeout:5000}).toBeGreaterThan(0);expect(metadataStarted).toBeGreaterThan(0);expect(metadataEnded).toBeGreaterThan(metadataStarted);expect(mutationStarted).toBeGreaterThan(metadataEnded);
  const metadataMs=metadataEnded-metadataStarted,remainingWaitMs=mutationClosed-mutationStarted;mark('bff-shared-budget-upstream-disconnect',{metadataMs,remainingWaitMs,combinedUpstreamMs:mutationClosed-metadataStarted,responseCompletionMs:performance.now()-start});
  expect(remainingWaitMs,'mutation uses remaining deadline, not a fresh full API budget').toBeLessThan(metadataMs);expect(response.request().timing().responseEnd,'HTTP completion still honors five seconds').toBeLessThan(5000);await expect(handle(signIn,'identifier')).toHaveValue(staff.email);
 }finally{await fake?.close();localService('start','api');await identity.api.reconnect();}
});
test(`${c(22)} trusted client address assertions cannot alter tenant authority or evade abuse limits`,async({identity,accounts})=>{
 test.setTimeout(120000);const file=process.env.SALON_IDENTITY_LOCAL_KEY_FILE??path.join(productRoot,'.local/identity-keys.properties');const proxy=/^proxy=([0-9a-f]{64})$/m.exec(fs.readFileSync(file,'utf8'))?.[1];if(!proxy)throw new Error('ENVIRONMENT: final fourth local assertion key is absent');
 const now=await identity.now();await identity.clock(now);const target='/api/v1/identity/customers/challenges',ip='192.0.2.20';
 const sign=(host=alpha.hostname,method='POST',pathname=target,address=ip,instant=now)=>`v1;${address};${instant};${createHmac('sha256',Buffer.from(proxy,'hex')).update(['v1',host,method,pathname,address,instant].join('\n')).digest('hex')}`;
 const invalid=['forged',sign().replace(/.$/,'z'),sign(beta.hostname),sign(alpha.hostname,'GET'),sign(alpha.hostname,'POST','/api/v1/identity/staff/sign-in'),
  sign(alpha.hostname,'POST',target,'192.0.2.20:80'),sign(alpha.hostname,'POST',target,'[::1]'),sign(alpha.hostname,'POST',target,'fe80::1%lo0'),sign(alpha.hostname,'POST',target,'192.0.2.20,192.0.2.21'),
  sign(alpha.hostname,'POST',target,ip,new Date(Date.parse(now)-31000).toISOString()),sign(alpha.hostname,'POST',target,ip,new Date(Date.parse(now)+31000).toISOString()),sign()+','+sign(),'x'.repeat(193)];
 for(const [index,proof] of invalid.entries()){await identity.clock(now);const before=state();const response=await accounts.customers.request(alpha,{mobile:syntheticMobile(),channel:'sms',deviceLabel:'QA assertion'},'',{'x-salon-client-assertion':proof});expect(response.status(),`invalid assertion variant ${index}`).toBe(400);expect((await response.json()).code).toBe('invalid_client_assertion');expect(state(),'invalid present assertion has no identity/rate/enqueue side effect').toBe(before);}
 const valid=await accounts.customers.request(alpha,{mobile:syntheticMobile(),channel:'sms',deviceLabel:'QA assertion'},'',{'x-salon-client-assertion':sign()});expect(valid.status()).toBe(200);expect((await valid.json()).challengeId).toBeTruthy();expect((await identity.call(alpha,'GET','/me',undefined,undefined,{'x-salon-client-assertion':sign()})).status()).toBe(401);
 const forged=await identity.call(beta,'POST','/customers/challenges',{mobile:syntheticMobile(),channel:'sms',deviceLabel:'QA'},undefined,{'x-salon-client-assertion':sign()});expect(forged.status()).toBe(400);
});
