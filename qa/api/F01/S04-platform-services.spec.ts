import { randomUUID } from 'node:crypto';
import { expect, test } from '@qa/fixtures';
import { alpha,beta,gamma,salons } from '@qa/tenants';
import { create,read,state,clock,meter,sql,service,isolatedFixture,logs,jumpDue } from '@qa/platform-services';
import { committedContract } from '@qa/openapi';
const trace='1234567890abcdef1234567890abcdef';
const traceHeaders={traceparent:`00-${trace}-1234567890abcdef-01`};
test.beforeEach(async({api})=>{test.setTimeout(300000);await isolatedFixture(api);});
test.afterEach(async({api})=>{await clock(api);});

test('TC-F01-S04-001 problem answers carry caller trace and leak no refused input',async({api})=>{
  const cases:[string,string,Record<string,string>,string|undefined,number,string][]=[
    ['GET','/api/v1/missing',{},undefined,404,'not_found'],
    ['POST','/local/probes',{'Content-Type':'application/json'},'{broken-secret-marker',400,'bad_request'],
    ['DELETE','/api/v1/salon',{},undefined,405,'method_not_allowed'],
    ['GET','/api/v1/salon',{Accept:'application/xml'},undefined,406,'not_acceptable'],
    ['POST','/local/probes',{'Content-Type':'text/plain'},'private-marker',415,'unsupported_media_type'],
    ['POST','/local/probes',{'Content-Type':'application/json'},JSON.stringify({abort:true}),500,'internal_error']
  ];
  for(const [method,url,headers,data,status,code] of cases){const r=await api.send(method,url,{headers:{Host:alpha.hostname,...traceHeaders,...headers},data});expect(r.status()).toBe(status);expect(r.headers()['content-type']).toContain('application/problem+json');const p=await r.json();expect(p.code).toBe(code);expect(p.traceId).toBe(trace);expect(await r.text()).not.toMatch(/private-marker|broken-secret-marker|Exception|com\.salonplatform|stackTrace/);}
  service('stop','postgres');try{const r=await api.send('GET','/api/v1/salon',{headers:{Host:alpha.hostname,...traceHeaders}});expect(r.status()).toBe(503);const p=await r.json();expect(p.code).toBe('service_unavailable');expect(p.traceId).toBe(trace);}finally{service('start','postgres');}
  const {execFileSync}=await import('node:child_process'); const path=await import('node:path'); const {PRODUCT_ROOT}=await import('@qa/env');
  execFileSync(path.join(PRODUCT_ROOT,'infra/scripts/local-salon.sh'),['suspend',gamma.slug],{cwd:PRODUCT_ROOT});
  try{const r=await api.send('GET','/api/v1/salon',{headers:{Host:gamma.hostname,...traceHeaders}});expect(r.status()).toBe(403);expect((await r.json()).traceId).toBe(trace);}finally{execFileSync(path.join(PRODUCT_ROOT,'infra/scripts/local-salon.sh'),['activate',gamma.slug],{cwd:PRODUCT_ROOT});}
});

test('TC-F01-S04-002 invalid probe bodies name their fields and write no data',async({api})=>{
  const before=sql('SELECT (SELECT count(*) FROM outbox_message)||\'|\'||(SELECT count(*) FROM audit_entry);');
  for(const [data,field] of [[{failures:-1},'failures'],[{failures:51},'failures'],[{failures:'secret'},'failures'],[{delayMs:-1},'delayMs'],[{delayMs:60001},'delayMs'],[{failures:-1,delayMs:-1},'both']]){const r=await api.send('POST','/local/probes',{headers:{Host:alpha.hostname,'Content-Type':'application/json'},data:JSON.stringify(data)});expect(r.status()).toBe(400);const p=await r.json();expect(p.code).toBe('bad_request');const fields=p.errors.map((e:{field:string})=>e.field);if(field==='both')expect(fields.sort()).toEqual(['delayMs','failures']);else expect(fields).toContain(field);}
  expect(sql('SELECT (SELECT count(*) FROM outbox_message)||\'|\'||(SELECT count(*) FROM audit_entry);')).toBe(before);
});

test('TC-F01-S04-003 the contract lists exactly all documented stable error codes',async()=>{
  const prop=committedContract().components.schemas.Problem.properties!.code as {enum:string[]};
  const expected=['bad_request','internal_error','method_not_allowed','not_acceptable','not_found','request_rejected','salon_suspended','service_unavailable','unsupported_media_type',
    'invalid_client_assertion','confirmation_required','invalid_code','code_expired','provider_unavailable','invalid_credentials','invalid_session','permission_denied','conflict','rate_limited'];
  expect(new Set(prop.enum).size).toBe(19);
  expect([...prop.enum].sort()).toEqual(expected.sort());
});

test('TC-F01-S04-004 instants stay UTC under a different machine timezone',async({api})=>{
  service('stop','api');service('start','api',{TZ:'Pacific/Chatham'});
  try{for(const s of salons)expect((await(await api.salon(s)).json()).timeZone).toBe(s.timeZone);
  await clock(api,'2026-10-12T20:00:00Z');const p=await create(api,alpha);expect(p.createdAt).toBe('2026-10-12T20:00:00Z');expect(sql(`SELECT extract(epoch FROM created_at)::bigint FROM outbox_message WHERE id='${p.id}';`)).toBe(String(Date.parse(p.createdAt)/1000));
  expect((await(await api.meta()).json()).time).toMatch(/Z$/);
  }finally{service('stop','api');service('start','api');}
});

test('TC-F01-S04-005 local times cross daylight saving and midnight without changing the instant',async({api})=>{
  for(const [s,instant,local]of [[gamma,'2026-03-29T00:30:00Z','2026-03-29T00:30:00+00:00'],[gamma,'2026-03-29T01:30:00Z','2026-03-29T02:30:00+01:00'],[alpha,'2026-10-12T20:00:00Z','2026-10-13T01:30:00+05:30']]as const){await clock(api,instant);const p=await create(api,s);expect(p.createdAt).toBe(instant);expect(p.createdAtLocal).toBe(local);}
  const real=await clock(api);expect(real.fixed).toBe(false);expect(Math.abs(Date.parse(real.now)-Date.now())).toBeLessThan(2000);
});

test('TC-F01-S04-006 a committed probe is sent once and audited in its request trace',async({api})=>{
  const p=await create(api,alpha,{},traceHeaders);const sent=await state(api,alpha,p.id,'sent');expect(sent.attempts).toBe(1);expect(sent.providerCalls).toBe(1);expect(sent.audit).toHaveLength(1);expect(sent.audit[0]).toMatchObject({action:'probe.created',occurredAt:p.createdAt,actorKind:'system',traceId:trace,after:{failures:0,delayMs:0,abort:false}});
});

test('TC-F01-S04-007 failed messages back off then succeed or become dead at the attempt limit',async({api})=>{
  const p=await create(api,alpha,{failures:2});let last=Date.parse(p.createdAt);let previous=0;
  for(let attempt=1;attempt<=2;attempt++){await expect.poll(async()=>{const v=await read(api,alpha,p.id);return v.attempts===attempt&&v.status==='pending';},{timeout:5000}).toBe(true);const v=await read(api,alpha,p.id);const due=Date.parse(v.nextAttemptAt!);const gap=due-last;expect(gap).toBeGreaterThanOrEqual(attempt===1?29000:59000);if(previous)expect(gap).toBeGreaterThan(previous);previous=gap;last=due;await jumpDue(api,v);}
  expect((await state(api,alpha,p.id,'sent')).attempts).toBe(3);
  await isolatedFixture(api);const before=await meter(api,'salon.outbox.dead');const dead=await create(api,beta,{failures:50});
  for(let i=1;i<=10;i++){await expect.poll(async()=>{const p=await read(api,beta,dead.id);return p.attempts===i&&p.status!=='sending';},{timeout:5000}).toBe(true);const v=await read(api,beta,dead.id);if(i<10){await clock(api,new Date(Math.max(Date.parse(v.nextAttemptAt!)+2000,Date.now()+120000)).toISOString());}else{expect(v.status).toBe('dead');expect(v.lastError).toBeTruthy();}}
  expect(await meter(api,'salon.outbox.dead')).toBe(before+1);await clock(api,'2030-01-01T00:00:00Z');expect((await read(api,beta,dead.id)).attempts).toBe(10);
});

test('TC-F01-S04-008 an aborted change leaves no outbox or audit row',async({api})=>{
  const before=sql('SELECT (SELECT count(*) FROM outbox_message)||\'|\'||(SELECT count(*) FROM audit_entry);');
  const r=await api.send('POST','/local/probes',{headers:{Host:alpha.hostname,'Content-Type':'application/json'},data:'{"abort":true}'});expect(r.status()).toBe(500);expect((await r.json()).code).toBe('internal_error');expect(sql('SELECT (SELECT count(*) FROM outbox_message)||\'|\'||(SELECT count(*) FROM audit_entry);')).toBe(before);
});

test('TC-F01-S04-009 slow providers time out without blocking tenant reads',async({api})=>{
  const began=performance.now();const probes=await Promise.all(Array.from({length:20},()=>create(api,alpha,{delayMs:60000})));
  await expect.poll(async()=>sql("SELECT count(*) FROM outbox_message WHERE status='sending';"),{timeout:5000}).not.toBe('0');
  for(const s of salons){const start=performance.now();expect((await api.salon(s)).status()).toBe(200);expect(performance.now()-start).toBeLessThan(1000);}
  await expect.poll(async()=>sql("SELECT count(*) FROM outbox_message WHERE attempts>0 AND last_error IS NOT NULL;"),{timeout:12000}).not.toBe('0');expect(performance.now()-began).toBeLessThan(15000);expect(probes).toHaveLength(20);
});

test('TC-F01-S04-010 an open breaker holds new messages until its configured wait',async({api})=>{
  const failures=await Promise.all(Array.from({length:5},()=>create(api,alpha,{failures:50})));
  await expect.poll(async()=>sql("SELECT count(*) FROM outbox_message WHERE last_error IS NOT NULL;"),{timeout:5000}).toBe('5');
  const p=await create(api,beta);await expect.poll(async()=>(await read(api,beta,p.id)).status,{timeout:1000}).toBe('pending');const held=await read(api,beta,p.id);expect(held.attempts).toBe(0);expect(held.providerCalls).toBe(0);
  expect((await api.salon(beta)).status()).toBe(200);expect((await api.health()).status()).toBe(200);
  // Remove the five failed fixtures so they cannot consume half-open probes when the clock jumps.
  sql(`DELETE FROM outbox_message WHERE id IN (${failures.map(p=>`'${p.id}'`).join(',')});`);
  await clock(api,new Date(Date.now()+62000).toISOString());expect((await state(api,beta,p.id,'sent')).providerCalls).toBe(1);
});

for(const dependency of ['api','postgres']as const)test(`TC-F01-S04-${dependency==='api'?'011':'017'} an interrupted delivery recovers after ${dependency} restart with the same message id`,async({api})=>{
  const p=await create(api,alpha,{delayMs:8000});await state(api,alpha,p.id,'sending');
  service('stop',dependency);
  if(dependency==='postgres')await expect.poll(()=>logs().some(l=>l.message?.includes(p.id)&&l.message?.includes('could not be recorded')),{timeout:15000}).toBe(true);
  service('start',dependency);
  const recovered=await read(api,alpha,p.id);expect(recovered.id).toBe(p.id);expect(recovered.attempts).toBe(1);expect(recovered.status).toBe('sending');
  await clock(api,new Date(Date.now()+65000).toISOString());const sent=await state(api,alpha,p.id,'sent',15000);expect(sent.id).toBe(p.id);expect(sent.attempts).toBe(2);expect(sent.audit).toHaveLength(1);
  const lines=logs().filter(l=>l['message']?.includes(p.id));expect(lines.length).toBeGreaterThan(0);
});

test('TC-F01-S04-012 simultaneous probes deliver once with the correct salon logs',async({api})=>{
  const values=await Promise.all(Array.from({length:100},async(_,i)=>({salon:salons[i%3],probe:await create(api,salons[i%3])})));
  for(const {salon,probe}of values){const p=await state(api,salon,probe.id,'sent');expect(p.attempts).toBe(1);expect(p.providerCalls).toBe(1);const delivery=logs().filter(l=>l.message?.includes(probe.id)&&l['tenant.id']);expect(delivery.length).toBeGreaterThan(0);for(const line of delivery)expect(line['tenant.id']).toBe(salon.id);}
});

test('TC-F01-S04-013 another salon cannot read a probe and application transactions expose no other tenant records',async({api})=>{
  const a=await create(api,alpha),b=await create(api,beta);expect((await read(api,beta,b.id)).id).toBe(b.id);
  const foreign=await api.getOn(beta.hostname,`/local/probes/${a.id}`),missing=await api.getOn(beta.hostname,`/local/probes/${randomUUID()}`);expect(foreign.status()).toBe(404);expect(missing.status()).toBe(404);const fp=await foreign.json(),mp=await missing.json();expect({...fp,instance:'',traceId:''}).toEqual({...mp,instance:'',traceId:''});
  for(const table of ['outbox_message','audit_entry']){expect(sql(`BEGIN; SET LOCAL ROLE salon_app; SELECT count(*) FROM ${table}; ROLLBACK;`)).toContain('0');const result=sql(`BEGIN; SET LOCAL ROLE salon_app; SELECT set_config('app.tenant_id','${alpha.id}',true); SELECT count(*) FROM ${table} WHERE tenant_id='${beta.id}'; ROLLBACK;`);expect(result.split('\n')).toContain('0');}
  for(const table of ['outbox_message','audit_entry'])expect(sql(`BEGIN; SET LOCAL ROLE salon_app; DO $$ BEGIN PERFORM set_config('app.tenant_id','${alpha.id}',true); END $$; SELECT count(*) FROM ${table}; ROLLBACK;`)).toBe('1');
  for(const verb of ['UPDATE audit_entry SET action=\'changed\'','DELETE FROM audit_entry']){let error='';try{sql(`BEGIN; SET LOCAL ROLE salon_app; SELECT set_config('app.tenant_id','${alpha.id}',true); ${verb}; ROLLBACK;`);}catch(e){error=String((e as {stderr:Buffer}).stderr);}expect(error).toMatch(/permission denied/);}
  for(const action of [`UPDATE outbox_message SET payload='{}' WHERE tenant_id='${beta.id}' RETURNING id`,`DELETE FROM outbox_message WHERE tenant_id='${beta.id}' RETURNING id`])expect(sql(`BEGIN; SET LOCAL ROLE salon_app; DO $$ BEGIN PERFORM set_config('app.tenant_id','${alpha.id}',true); END $$; WITH changed AS (${action}) SELECT count(*) FROM changed; ROLLBACK;`)).toBe('0');
  let error='';try{sql(`BEGIN; SET LOCAL ROLE salon_app; DO $$ BEGIN PERFORM set_config('app.tenant_id','${alpha.id}',true); END $$; INSERT INTO outbox_message (tenant_id,id,channel,payload,status,next_attempt_at,created_at,updated_at) VALUES ('${beta.id}','${randomUUID()}','probe','{}','pending',now(),now(),now());ROLLBACK;`);}catch(e){error=String((e as {stderr:Buffer}).stderr);}expect(error).toMatch(/row-level security/);expect((await read(api,beta,b.id)).id).toBe(b.id);
});

test('TC-F01-S04-014 request JSON logs retain tenant and trace while dropping sensitive inputs',async({api})=>{
  const marker=`sensitive-${randomUUID()}`;const r=await api.send('GET',`/api/v1/salon?private=${marker}`,{headers:{Host:gamma.hostname,...traceHeaders,Cookie:`private=${marker}`}});expect(r.status()).toBe(200);
  await expect.poll(()=>logs().filter(l=>l['trace.id']===trace&&l['http.request.method']==='GET').length).toBe(1);
  const lines=logs().filter(l=>l['trace.id']===trace);expect(lines.length).toBeGreaterThan(0);for(const l of lines){expect(l['tenant.id']).toBe(gamma.id);expect(JSON.stringify(l)).not.toContain(marker);}
  const requests=lines.filter(l=>l['http.request.method']==='GET');expect(requests).toHaveLength(1);expect(requests[0]['url.path']).toBe('/api/v1/salon');expect(requests[0]['http.response.status_code']).toBe(200);expect(requests[0]['event.duration']).toBeGreaterThan(0);
  const deniedTrace=randomUUID().replaceAll('-','');
  const denied=await api.send('GET',`/api/v1/salon?private=${marker}`,{headers:{Host:gamma.hostname,traceparent:`00-${deniedTrace}-1234567890abcdef-01`,Cookie:`private=${marker}`,Authorization:`Bearer ${marker}`}});
  expect(denied.status()).toBe(401);expect(denied.headers()['content-type']).toContain('application/problem+json');const problem=await denied.json();expect(problem.code).toBe('invalid_session');expect(problem.traceId).toBe(deniedTrace);
  await expect.poll(()=>logs().filter(l=>l['trace.id']===deniedTrace&&l['http.request.method']==='GET').length).toBe(1);
  const deniedLines=logs().filter(l=>l['trace.id']===deniedTrace);expect(deniedLines.length).toBeGreaterThan(0);for(const line of deniedLines){expect(line['tenant.id']).toBeUndefined();expect(JSON.stringify(line)).not.toContain(marker);}
  const deniedRequests=deniedLines.filter(l=>l['http.request.method']==='GET');expect(deniedRequests).toHaveLength(1);expect(deniedRequests[0]['url.path']).toBe('/api/v1/salon');expect(deniedRequests[0]['http.response.status_code']).toBe(401);expect(deniedRequests[0]['event.duration']).toBeGreaterThan(0);
});

test('TC-F01-S04-015 tenant cleanup removes old sent work while keeping newer work',async({api})=>{
  const start=Date.now()+86400000;await clock(api,new Date(start).toISOString());const old=await create(api,alpha);await state(api,alpha,old.id,'sent');
  await clock(api,new Date(start+31*86400000).toISOString());const recent=await create(api,beta);await state(api,beta,recent.id,'sent');
  await expect.poll(async()=>sql(`SELECT count(*) FROM outbox_message WHERE id='${old.id}';`),{timeout:5000}).toBe('0');expect((await read(api,beta,recent.id)).id).toBe(recent.id);
  const cleanup=logs().filter(l=>l.message?.includes('outbox-cleanup')&&l['tenant.id']);expect(cleanup.length).toBeGreaterThan(0);for(const s of salons)expect(cleanup.some(l=>l['tenant.id']===s.id)).toBe(true);
});


test('TC-F01-S04-018 client tenant selectors cannot write a probe into another salon',async({api})=>{
  const owner=await create(api,beta);expect((await read(api,beta,owner.id)).id).toBe(owner.id);
  const r=await api.send('POST',`/local/probes?tenantId=${beta.id}&slug=${beta.slug}`,{headers:{Host:alpha.hostname,'X-Tenant-Id':beta.id,'X-Forwarded-Host':beta.hostname,Cookie:`tenant=${beta.slug}`,'Content-Type':'application/json'},data:'{}'});expect(r.status()).toBe(201);const probe=await r.json();expect((await read(api,alpha,probe.id)).id).toBe(probe.id);const foreign=await api.getOn(beta.hostname,`/local/probes/${probe.id}`);expect(foreign.status()).toBe(404);expect((await foreign.json()).code).toBe('not_found');
});
