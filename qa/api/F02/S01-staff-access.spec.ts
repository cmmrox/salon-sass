import { randomUUID } from 'node:crypto';
import type { components } from '@contracts/schema';
import { test, expect, alpha, beta, gamma, ROLES, IDENTITY, Identity, syntheticAccount, json, problem, assertTokens, totp, type Tokens, type Staff, type SignIn, type SignedStaff } from '../../shared/identity';

const id=(n:number)=>`TC-F02-S01-${String(n).padStart(3,'0')}`;
const wrongCode=(secret:string,instant:string)=>{const used=[-30000,0,30000].map(delta=>totp(secret,new Date(Date.parse(instant)+delta).toISOString()));return ['999999','000000','111111','222222'].find(code=>!used.includes(code))!;};
const challengeBody=(c:SignIn)=>({challengeId:c.challengeId,challengeToken:c.challengeToken});
async function missing(identity:Identity,actor:SignedStaff){await problem(await identity.call(actor.salon,'GET','/me',undefined,actor.tokens),401,'invalid_session');}
async function staffRow(identity:Identity,owner:SignedStaff,actor:SignedStaff){const rows=await identity.list(owner);const row=rows.find(r=>r.id===actor.principal.id);expect(Boolean(row),'target exists before mutation').toBe(true);return row!;}
async function refresh(identity:Identity,actor:SignedStaff){const r=await identity.call(actor.salon,'POST','/sessions/refresh',{refreshToken:actor.tokens.refreshToken});const t=await json<Tokens>(r,'Tokens');assertTokens(t);return t;}

// Tables are variations of one harm, not extra copies of the same case.
test(`${id(1)} normalized staff identifiers establish bounded credentials`,async({identity})=>{
 const account=syntheticAccount();await identity.seed(alpha,[account]);
 for(const identifier of [account.email.toUpperCase(),account.mobile,`+94${account.mobile.slice(1)}`]){
  const before=Date.parse(await identity.now());const sign=await identity.signIn(alpha,account,identifier);assertTokens(sign.credentials);
  expect(Date.parse(sign.credentials.accessExpiresAt)-before).toBeGreaterThanOrEqual(299000);expect(Date.parse(sign.credentials.accessExpiresAt)-before).toBeLessThanOrEqual(301000);
  expect(Date.parse(sign.credentials.refreshExpiresAt)-before).toBeGreaterThanOrEqual(7*86400000-1000);expect(Date.parse(sign.credentials.refreshExpiresAt)-before).toBeLessThanOrEqual(7*86400000+1000);
  const p=await identity.principal(alpha,sign.credentials);expect(p.kind).toBe('staff');expect(p.role).toBe('MANAGER');
 }
});
test(`${id(2)} wrong unknown and disabled credentials expose the same denial`,async({identity})=>{
 const owner=await identity.staff(alpha,'OWNER');const member=await identity.staff(alpha);expect((await identity.call(alpha,'DELETE',`/staff/${member.principal.id}`,undefined,owner.tokens)).status()).toBe(204);
 for(const [identifier,password]of [[owner.account.email,'wrong password value'],[`unknown-${randomUUID()}@example.invalid`,owner.account.password],[member.account.email,member.account.password]]){
  const r=await identity.call(alpha,'POST','/staff/sign-in',{identifier,password,deviceLabel:'QA'});const p=await problem(r,401,'invalid_credentials');
  expect(Object.keys(p).some(k=>/token|credential|account|exists/i.test(k))).toBe(false);
 }
});
test(`${id(3)} identifier limits survive failed outcomes and normalization`,async({identity})=>{
 const unknown=`absent-${randomUUID()}@example.invalid`;
 for(let attempt=0;attempt<10;attempt++)await problem(await identity.call(alpha,'POST','/staff/sign-in',{identifier:attempt%2?unknown.toUpperCase():unknown,password:'QA wrong password value',deviceLabel:'QA'}),401,'invalid_credentials');
 const r=await identity.call(alpha,'POST','/staff/sign-in',{identifier:unknown,password:'QA wrong password value',deviceLabel:'QA'});await problem(r,429,'rate_limited');expect(Number(r.headers()['retry-after'])).toBeGreaterThan(0);
 await identity.clock(new Date(Date.parse(await identity.now())+901000).toISOString());await problem(await identity.call(alpha,'POST','/staff/sign-in',{identifier:unknown,password:'QA wrong password value',deviceLabel:'QA'}),401,'invalid_credentials');
});
test(`${id(4)} trusted address limits cannot be bypassed with forwarded headers`,async({identity})=>{
 for(let n=0;n<30;n++)await problem(await identity.call(alpha,'POST','/staff/sign-in',{identifier:`missing-${n}-${randomUUID()}@example.invalid`,password:'QA wrong password value',deviceLabel:'QA'},undefined,{'X-Forwarded-For':`192.0.2.${n+1}`}),401,'invalid_credentials');
 const r=await identity.call(alpha,'POST','/staff/sign-in',{identifier:`missing-${randomUUID()}@example.invalid`,password:'QA wrong password value',deviceLabel:'QA'},undefined,{'X-Forwarded-For':'198.51.100.1'});await problem(r,429,'rate_limited');expect(Number(r.headers()['retry-after'])).toBeGreaterThan(0);
});
test(`${id(5)} an Owner enrollment challenge never authorizes a session before TOTP`,async({identity})=>{
 const account=syntheticAccount('OWNER',false);await identity.seed(alpha,[account]);const sign=await identity.signIn(alpha,account);expect(sign.state).toBe('enroll_mfa');expect(sign.credentials==null).toBe(true);
 const enrollment=await json<components['schemas']['Enrollment']>(await identity.call(alpha,'POST','/staff/mfa/enroll',challengeBody(sign)),'Enrollment');
 expect(Boolean(enrollment.secret?.match(/^[A-Z2-7]{32}$/))).toBe(true);expect(Boolean(enrollment.otpauthUri?.startsWith('otpauth://totp/'))).toBe(true);
 await problem(await identity.call(alpha,'GET','/me',undefined,undefined,{Authorization:`Bearer ${sign.challengeToken}`}),401,'invalid_session');
 const tokens=await identity.verify(alpha,sign,enrollment.secret!);const p=await identity.principal(alpha,tokens);expect(p.mfaEnabled).toBe(true);expect(p.role).toBe('OWNER');
});
test(`${id(6)} invalid expired consumed or exhausted MFA challenges issue no second session`,async({identity})=>{
 const a=syntheticAccount('OWNER');await identity.seed(alpha,[a]);const sign=await identity.signIn(alpha,a);
 await problem(await identity.call(alpha,'POST','/staff/mfa/enroll',{...challengeBody(sign),challengeToken:'untrusted credential'}),401,'invalid_credentials');
 for(let n=0;n<5;n++)await problem(await identity.call(alpha,'POST','/staff/mfa/verify',{...challengeBody(sign),code:wrongCode(a.totpSecret!,await identity.now())}),401,'invalid_credentials');
 await problem(await identity.call(alpha,'POST','/staff/mfa/verify',{...challengeBody(sign),code:totp(a.totpSecret!,await identity.now())}),401,'invalid_credentials');
 const expired=await identity.signIn(alpha,a);await identity.clock(new Date(Date.parse(expired.expiresAt!)+1000).toISOString());await problem(await identity.call(alpha,'POST','/staff/mfa/verify',{...challengeBody(expired),code:totp(a.totpSecret!,await identity.now())}),401,'invalid_credentials');
 const current=await identity.signIn(alpha,a);const tokens=await identity.verify(alpha,current,a.totpSecret!);await problem(await identity.call(alpha,'POST','/staff/mfa/verify',{...challengeBody(current),code:totp(a.totpSecret!,await identity.now())}),401,'invalid_credentials');expect((await identity.principal(alpha,tokens)).role).toBe('OWNER');
});
test(`${id(7)} simultaneous TOTP reuse establishes exactly one session`,async({identity})=>{
 const account=syntheticAccount('OWNER');await identity.seed(alpha,[account]);const c=await identity.signIn(alpha,account);const data={...challengeBody(c),code:totp(account.totpSecret!,await identity.now())};
 const outcomes=await Promise.all(Array.from({length:8},()=>identity.call(alpha,'POST','/staff/mfa/verify',data)));expect(outcomes.filter(r=>r.status()===200).length).toBe(1);expect(outcomes.filter(r=>r.status()===401).length).toBe(7);
 const tokens=await json<Tokens>(outcomes.find(r=>r.status()===200)!,'Tokens');assertTokens(tokens);const actor={salon:alpha,account,tokens,principal:await identity.principal(alpha,tokens)};expect((await identity.sessions(actor)).total).toBe(1);
 // A different challenge for the same account cannot reuse a step already consumed.
 const other=await identity.signIn(alpha,account);await problem(await identity.call(alpha,'POST','/staff/mfa/verify',{...challengeBody(other),code:data.code}),401,'invalid_credentials');
});
test(`${id(8)} voluntary MFA enrollment replaces pre-enrollment authority`,async({identity})=>{
 const actor=await identity.staff(alpha,'PROFESSIONAL');const c=await json<SignIn>(await identity.call(alpha,'POST','/staff/mfa/challenges',undefined,actor.tokens),'SignIn');expect(c.state).toBe('enroll_mfa');
 const e=await json<components['schemas']['Enrollment']>(await identity.call(alpha,'POST','/staff/mfa/enroll',challengeBody(c)),'Enrollment');const tokens=await identity.verify(alpha,c,e.secret!);await missing(identity,actor);expect((await identity.principal(alpha,tokens)).mfaEnabled).toBe(true);
 const next=await identity.signIn(alpha,actor.account);expect(next.state).toBe('verify_mfa');expect(next.credentials==null).toBe(true);
});
test(`${id(9)} hostile access claims and expiry cannot authenticate`,async({identity})=>{
 const actor=await identity.staff();const [header,payload,signature]=actor.tokens.accessToken.split('.');const claims=JSON.parse(Buffer.from(payload,'base64url').toString());
 const encode=(x:unknown)=>Buffer.from(JSON.stringify(x)).toString('base64url');
 const hostile=[`${header}.${payload}.untrusted`,`${encode({alg:'none'})}.${payload}.`,`${encode({alg:'HS512'})}.${payload}.${signature}`,...[{iss:'wrong'},{aud:'wrong'},{kind:'customer'},{tenantId:beta.id},{exp:1},{iat:9999999999}].map(p=>`${header}.${encode({...claims,...p})}.${signature}`)];
 for(const accessToken of hostile)await problem(await identity.call(alpha,'GET','/me',undefined,{...actor.tokens,accessToken}),401,'invalid_session');
 await identity.clock(new Date(Date.parse(actor.tokens.accessExpiresAt)+1000).toISOString());await missing(identity,actor);
});
test(`${id(10)} all identity endpoints honor tenant and hostname boundaries`,async({identity})=>{
 const actor=await identity.staff(alpha,'OWNER');const foreign=await identity.staff(beta,'OWNER');expect(foreign.principal.id===actor.principal.id).toBe(false);
 const c=await identity.signIn(beta,foreign.account);
 const requests:[string,string,unknown?][]=[['GET','/me'],['GET','/sessions'],['DELETE','/sessions'],['DELETE',`/sessions/${foreign.tokens.sessionId}`],['GET','/staff'],['POST','/staff',syntheticAccount()],['PATCH',`/staff/${foreign.principal.id}`,{version:0,role:'MANAGER'}],['DELETE',`/staff/${foreign.principal.id}`],['PUT','/staff/password',{oldPassword:actor.account.password,newPassword:syntheticAccount().password}],['GET','/audit'],['POST','/staff/mfa/challenges'],['POST','/staff/sign-in',{identifier:actor.account.email,password:actor.account.password,deviceLabel:'QA'}],['POST','/staff/mfa/enroll',challengeBody(c)],['POST','/staff/mfa/verify',{...challengeBody(c),code:'000000'}],['POST','/sessions/refresh',{refreshToken:actor.tokens.refreshToken}]];
 for(const [method,path,data]of requests)await problem(await identity.call(beta,method,path,data,actor.tokens),404,'not_found');
 expect((await identity.principal(alpha,actor.tokens)).id).toBe(actor.principal.id);expect((await identity.principal(beta,foreign.tokens)).id).toBe(foreign.principal.id);
 for(const hostname of ['admin.localhost','unknown.localhost'])await problem(await identity.call(alpha,'GET','/me',undefined,actor.tokens,{Host:hostname}),404,'not_found');
 expect((await identity.principal(null,actor.tokens)).id).toBe(actor.principal.id);
 for(const [method,path,data]of requests.filter(r=>['/staff/sign-in','/staff/mfa/enroll','/staff/mfa/verify','/sessions/refresh'].includes(r[1])))await problem(await identity.call(null,method,path,data),404,'not_found');
});
test(`${id(11)} foreign ids lists and MFA credentials reveal no foreign records`,async({identity})=>{
 const owner=await identity.staff(alpha,'OWNER');const foreign=await identity.staff(beta,'OWNER');const record=await staffRow(identity,foreign,foreign);const before=await identity.list(foreign);
 for(const foreignId of [foreign.principal.id,randomUUID()])for(const [method,path,data]of [['PATCH',`/staff/${foreignId}`,{version:record.version,role:'MANAGER'}],['DELETE',`/staff/${foreignId}`],['DELETE',`/sessions/${foreignId}`]]as const)await problem(await identity.call(alpha,method,path,data,owner.tokens),404,'not_found');
 await problem(await identity.call(alpha,'DELETE',`/sessions/${foreign.tokens.sessionId}`,undefined,owner.tokens),404,'not_found');
 expect((await identity.list(owner)).some(r=>r.id===foreign.principal.id)).toBe(false);expect((await identity.sessions(owner)).items.some(r=>r.id===foreign.tokens.sessionId)).toBe(false);expect((await identity.audit(owner,foreign.principal.id)).items.length).toBe(0);
 expect(await identity.list(foreign)).toEqual(before);const c=await identity.signIn(beta,foreign.account);
 for(const path of ['/staff/mfa/enroll','/staff/mfa/verify'])await problem(await identity.call(alpha,'POST',path,{...challengeBody(c),...(path.endsWith('verify')?{code:totp(foreign.account.totpSecret!,await identity.now())}:{})}),401,'invalid_credentials');
 await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:foreign.tokens.refreshToken}),401,'invalid_session');
});
test(`${id(12)} concurrent salon requests never carry another principal`,async({identity})=>{
 const a=await identity.staff(alpha),b=await identity.staff(beta);const calls=Array.from({length:24},(_,n)=>n%2?a:b);
 const principals=await Promise.all(calls.map(actor=>identity.principal(actor.salon,actor.tokens)));expect(principals.length).toBe(calls.length);principals.forEach((p,n)=>expect(p.id).toBe(calls[n].principal.id));
});
test(`${id(13)} device lists paginate and identify only one's current session`,async({identity})=>{
 const actor=await identity.staff();await identity.login(alpha,actor.account,'Second device');const stranger=await identity.staff();
 const first=await identity.sessions(actor,0,1),second=await identity.sessions(actor,1,1);expect(first.total).toBe(2);expect(first.items.length).toBe(1);expect(second.items.length).toBe(1);expect(first.items[0].id===second.items[0].id).toBe(false);
 const all=await identity.sessions(actor);expect(all.items.filter(s=>s.current).map(s=>s.id)).toEqual([actor.tokens.sessionId]);expect(all.items.some(s=>s.id===stranger.tokens.sessionId)).toBe(false);
 for(const query of ['page=-1','size=0','size=101','page=100001'])await problem(await identity.call(alpha,'GET',`/sessions?${query}`,undefined,actor.tokens),400,'bad_request');
 const r=await identity.call(alpha,'POST','/staff/sign-in',{identifier:actor.account.email,password:actor.account.password,deviceLabel:'a'.repeat(81)});const p=await problem(r,400,'bad_request');expect(p.errors?.some(e=>e.field==='deviceLabel')).toBe(true);
});
test(`${id(14)} renewal rotates credentials and cannot extend the absolute session`,async({identity})=>{
 const actor=await identity.staff();await identity.clock(new Date(Date.parse(actor.tokens.accessExpiresAt)+1000).toISOString());await missing(identity,actor);
 const next=await refresh(identity,actor);expect(next.accessToken===actor.tokens.accessToken).toBe(false);expect(next.refreshToken===actor.tokens.refreshToken).toBe(false);expect(next.refreshExpiresAt).toBe(actor.tokens.refreshExpiresAt);expect((await identity.principal(alpha,next)).id).toBe(actor.principal.id);
 await identity.clock(new Date(Date.parse(next.refreshExpiresAt)+1000).toISOString());await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:next.refreshToken}),401,'invalid_session');
});
test(`${id(15)} simultaneous refresh permits one rotation and replay revokes its family`,async({identity})=>{
 const actor=await identity.staff();const responses=await Promise.all(Array.from({length:8},()=>identity.call(alpha,'POST','/sessions/refresh',{refreshToken:actor.tokens.refreshToken})));
 expect(responses.filter(r=>r.status()===200).length).toBe(1);expect(responses.filter(r=>r.status()===401).length).toBe(7);const next=await json<Tokens>(responses.find(r=>r.status()===200)!,'Tokens');assertTokens(next);
 await problem(await identity.call(alpha,'GET','/me',undefined,next),401,'invalid_session');await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:next.refreshToken}),401,'invalid_session');const fresh=await identity.login(alpha,actor.account);expect((await identity.sessions(fresh)).items.some(s=>s.id===next.sessionId)).toBe(false);
});
test(`${id(16)} single and all-device revocation is immediate and owner-scoped`,async({identity})=>{
 const actor=await identity.staff();const other=await identity.login(alpha,actor.account,'Other');const stranger=await identity.staff();
 await problem(await identity.call(alpha,'DELETE',`/sessions/${stranger.tokens.sessionId}`,undefined,actor.tokens),404,'not_found');expect((await identity.principal(alpha,stranger.tokens)).id).toBe(stranger.principal.id);
 expect((await identity.call(alpha,'DELETE',`/sessions/${other.tokens.sessionId}`,undefined,actor.tokens)).status()).toBe(204);await missing(identity,other);await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:other.tokens.refreshToken}),401,'invalid_session');
 expect((await identity.call(alpha,'DELETE','/sessions',undefined,actor.tokens)).status()).toBe(204);await missing(identity,actor);expect((await identity.principal(alpha,stranger.tokens)).id).toBe(stranger.principal.id);
});
test(`${id(17)} each role receives exactly its permitted identity actions`,async({identity})=>{
 for(const role of ROLES){
  const actor=await identity.staff(alpha,role);
  const common=['session.manage','password.change','mfa.enroll'];
  const admin=['staff.list','audit.list','payment.refund','catalogue.price.change','policy.change','customer.export','calendar.read','customer.read','financial.read'];
  const expected=role==='OWNER'?[...common,...admin,'staff.manage']:role==='MANAGER'?[...common,...admin]:role==='RECEPTIONIST'||role==='PROFESSIONAL'?[...common,'calendar.read','customer.read']:[...common,'financial.read'];
  expect([...actor.principal.permissions].sort()).toEqual(expected.sort());
  const victim=await identity.staff(alpha,'PROFESSIONAL');const target=await identity.staff(alpha);const payload=syntheticAccount();
  for(const path of ['/me','/sessions'])expect((await identity.call(alpha,'GET',path,undefined,actor.tokens)).status()).toBe(200);
  for(const path of ['/staff','/audit']){const r=await identity.call(alpha,'GET',path,undefined,actor.tokens);if(role==='OWNER'||role==='MANAGER')expect(r.status()).toBe(200);else await problem(r,403,'permission_denied');}
  const operations:[string,string,unknown?][]=[['POST','/staff',payload],['PATCH',`/staff/${victim.principal.id}`,{version:0,professionalGrant:true}],['DELETE',`/staff/${target.principal.id}`]];
  for(const [method,path,data]of operations){const r=await identity.call(alpha,method,path,data,actor.tokens);if(role==='OWNER')expect(r.status()).toBe(method==='POST'?201:method==='DELETE'?204:200);else await problem(r,403,'permission_denied');}
  if(role!=='OWNER'){expect((await identity.principal(alpha,target.tokens)).role).toBe('MANAGER');expect((await identity.principal(alpha,victim.tokens)).scope).toBe('own');const c=await json<SignIn>(await identity.call(alpha,'POST','/staff/mfa/challenges',undefined,actor.tokens),'SignIn');expect(c.state).toBe('enroll_mfa');}
  const newPassword=syntheticAccount().password;const t=await json<Tokens>(await identity.call(alpha,'PUT','/staff/password',{oldPassword:actor.account.password,newPassword},actor.tokens),'Tokens');assertTokens(t);actor.tokens=t;
  expect((await identity.call(alpha,'DELETE',`/sessions/${t.sessionId}`,undefined,t)).status()).toBe(204);await missing(identity,actor);
 }
 const account=syntheticAccount();for(const [method,path,data]of [['GET','/me'],['GET','/sessions'],['DELETE','/sessions'],['POST','/staff',account],['PATCH',`/staff/${randomUUID()}`,{version:0,role:'MANAGER'}],['DELETE',`/staff/${randomUUID()}`],['GET','/staff'],['GET','/audit'],['POST','/staff/mfa/challenges'],['PUT','/staff/password',{oldPassword:account.password,newPassword:syntheticAccount().password}]]as const)await problem(await identity.call(alpha,method,path,data),401,'invalid_session');
});
test(`${id(18)} live role and Professional grant changes replace cached permissions`,async({identity})=>{
 const owner=await identity.staff(alpha,'OWNER');const p=await identity.staff(alpha,'PROFESSIONAL');expect(p.principal.scope).toBe('own');expect(p.principal.staffId).toBe(p.principal.id);const row=await staffRow(identity,owner,p);
 const granted=await json<Staff>(await identity.call(alpha,'PATCH',`/staff/${row.id}`,{version:row.version,professionalGrant:true},owner.tokens),'StaffView');const grantedPrincipal=await identity.principal(alpha,p.tokens);expect(grantedPrincipal.scope).toBe('salon');expect(grantedPrincipal.permissions.includes('financial.read')).toBe(true);
 await json<Staff>(await identity.call(alpha,'PATCH',`/staff/${row.id}`,{version:granted.version,professionalGrant:false},owner.tokens),'StaffView');const restricted=await identity.principal(alpha,p.tokens);expect(restricted.scope).toBe('own');expect(restricted.permissions.includes('financial.read')).toBe(false);
 const manager=await identity.staff();const m=await staffRow(identity,owner,manager);await json<Staff>(await identity.call(alpha,'PATCH',`/staff/${m.id}`,{version:m.version,role:'RECEPTIONIST'},owner.tokens),'StaffView');expect((await identity.principal(alpha,manager.tokens)).role).toBe('RECEPTIONIST');await problem(await identity.call(alpha,'GET','/audit',undefined,manager.tokens),403,'permission_denied');
});
test(`${id(19)} staff creation accepts either identifier and refused edits preserve accounts`,async({identity})=>{
 const owner=await identity.staff(alpha,'OWNER');const fresh=syntheticAccount();const created=await json<Staff>(await identity.call(alpha,'POST','/staff',fresh,owner.tokens),'StaffView',201);expect(created.active).toBe(true);
 const before=await identity.list(owner);for(const patch of [{email:'invalid'},{email:null,mobile:null},{role:'SUPERUSER'},{password:'short'},{professionalGrant:'yes'}]){const p=await problem(await identity.call(alpha,'POST','/staff',{...syntheticAccount(),...patch},owner.tokens),400,'bad_request');expect((p.errors?.length??0)>0).toBe(true);}
 await problem(await identity.call(alpha,'POST','/staff',{...syntheticAccount(),email:fresh.email.toUpperCase()},owner.tokens),409,'conflict');expect(await identity.list(owner)).toEqual(before);
 const changed=await json<Staff>(await identity.call(alpha,'PATCH',`/staff/${created.id}`,{version:created.version,role:'ACCOUNTANT'},owner.tokens),'StaffView');
 await problem(await identity.call(alpha,'PATCH',`/staff/${created.id}`,{version:created.version,role:'OWNER'},owner.tokens),409,'conflict');expect((await identity.list(owner)).find(s=>s.id===created.id)).toEqual(changed);
 const singleIds:string[]=[];
 for(const field of ['email','mobile'] as const){const account=syntheticAccount();const single=await json<Staff>(await identity.call(alpha,'POST','/staff',{role:account.role,password:account.password,[field]:account[field]},owner.tokens),'StaffView',201);expect(single[field],'supplied staff identifier is normalized').toBe(field==='mobile'?'+94'+account.mobile.slice(1):account.email.toLowerCase());expect(single[field==='email'?'mobile':'email'],'omitted identifier is explicitly nullable').toBeNull();singleIds.push(single.id);const signed=await identity.signIn(alpha,account,account[field]);assertTokens(signed.credentials);expect((await identity.principal(alpha,signed.credentials)).id).toBe(single.id);}
 const inventory=await identity.list(owner);expect(inventory.filter(row=>singleIds.includes(row.id)).length,'both single-identifier accounts satisfy the published list contract').toBe(2);
});
test(`${id(20)} removal and Owner promotion end every existing credential`,async({identity})=>{
 const owner=await identity.staff(alpha,'OWNER');const actor=await identity.staff();const second=await identity.login(alpha,actor.account,'Other');expect((await identity.call(alpha,'DELETE',`/staff/${actor.principal.id}`,undefined,owner.tokens)).status()).toBe(204);await missing(identity,actor);await missing(identity,second);await problem(await identity.call(alpha,'POST','/sessions/refresh',{refreshToken:actor.tokens.refreshToken}),401,'invalid_session');
 const promoted=await identity.staff();const row=await staffRow(identity,owner,promoted);await json<Staff>(await identity.call(alpha,'PATCH',`/staff/${row.id}`,{version:row.version,role:'OWNER'},owner.tokens),'StaffView');await missing(identity,promoted);const next=await identity.signIn(alpha,promoted.account);expect(next.state).toBe('enroll_mfa');expect(next.credentials==null).toBe(true);
});
test(`${id(21)} concurrent demotion and removal retain an active Owner`,async({identity})=>{
 for(const operation of ['demotion','removal']){await identity.reset();const a=await identity.staff(alpha,'OWNER'),b=await identity.staff(alpha,'OWNER');const ar=await staffRow(identity,a,a),br=await staffRow(identity,a,b);
  const results=await Promise.all([[a,br],[b,ar]].map(([caller,row])=>operation==='removal'?identity.call(alpha,'DELETE',`/staff/${(row as Staff).id}`,undefined,(caller as SignedStaff).tokens):identity.call(alpha,'PATCH',`/staff/${(row as Staff).id}`,{version:(row as Staff).version,role:'MANAGER'},(caller as SignedStaff).tokens)));
  expect(results.filter(r=>[200,204].includes(r.status())).length).toBe(1);expect(results.every(r=>[200,204,401,403,409].includes(r.status()))).toBe(true);
  const remaining:SignedStaff[]=[];for(const actor of [a,b]){const r=await identity.call(alpha,'GET','/me',undefined,actor.tokens);if(r.status()===200){const p=await r.json();if(p.role==='OWNER')remaining.push(actor);}}
  expect(remaining.length).toBe(1);const rows=await identity.list(remaining[0]);expect(rows.filter(r=>r.active&&r.role==='OWNER').length).toBe(1);
  await problem(await identity.call(alpha,'DELETE',`/staff/${remaining[0].principal.id}`,undefined,remaining[0].tokens),409,'conflict');const r=rows.find(r=>r.id===remaining[0].principal.id)!;await problem(await identity.call(alpha,'PATCH',`/staff/${r.id}`,{version:r.version,role:'MANAGER'},remaining[0].tokens),409,'conflict');
 }
});
test(`${id(22)} password policy and old-password proof rotate current and revoke other sessions`,async({identity})=>{
 const actor=await identity.staff();const other=await identity.login(alpha,actor.account,'Other');
 await problem(await identity.call(alpha,'PUT','/staff/password',{oldPassword:'incorrect old password',newPassword:syntheticAccount().password},actor.tokens),401,'invalid_credentials');
 for(const weak of ['x'.repeat(14),'x'.repeat(129),'passwordpassword']){const p=await problem(await identity.call(alpha,'PUT','/staff/password',{oldPassword:actor.account.password,newPassword:weak},actor.tokens),400,'bad_request');expect(p.errors?.some(e=>e.field==='newPassword')).toBe(true);}
 const newPassword=' '.repeat(1)+'é'.repeat(13)+'💇';const next=await json<Tokens>(await identity.call(alpha,'PUT','/staff/password',{oldPassword:actor.account.password,newPassword},actor.tokens),'Tokens');assertTokens(next);await missing(identity,actor);await missing(identity,other);expect((await identity.principal(alpha,next)).id).toBe(actor.principal.id);
 await problem(await identity.call(alpha,'POST','/staff/sign-in',{identifier:actor.account.email,password:actor.account.password,deviceLabel:'QA'}),401,'invalid_credentials');const signed=await identity.signIn(alpha,{...actor.account,password:newPassword});assertTokens(signed.credentials);
});
test(`${id(23)} sensitive identity actions write paginated secret-free before-after audit`,async({identity})=>{
 const owner=await identity.staff(alpha,'OWNER');const actor=await identity.staff(alpha,'PROFESSIONAL');const row=await staffRow(identity,owner,actor);await json<Staff>(await identity.call(alpha,'PATCH',`/staff/${row.id}`,{version:row.version,professionalGrant:true},owner.tokens),'StaffView');
 const result=await identity.audit(owner,actor.principal.id);expect(result.items.length).toBeGreaterThan(0);expect(result.items.some(e=>JSON.stringify(e.before).includes('false')&&JSON.stringify(e.after).includes('true'))).toBe(true);
 for(const entry of result.items){expect(entry.id).toMatch(/^[0-9a-f-]{36}$/);expect(entry.actorKind).toBeTruthy();expect(entry.action).toBeTruthy();expect(Number.isFinite(Date.parse(entry.occurredAt??''))).toBe(true);}
 const body=JSON.stringify(result);for(const secret of [owner.account.password,owner.account.mobile,owner.account.totpSecret!,owner.tokens.accessToken,owner.tokens.refreshToken,actor.account.password,actor.account.mobile])expect(body.includes(secret),'audit excludes credential or full phone').toBe(false);
 const paged=await json<components['schemas']['PageAuditView']>(await identity.call(alpha,'GET','/audit?size=1',undefined,owner.tokens),'PageAuditView');expect(paged.items?.length).toBe(1);expect(paged.total).toBeGreaterThan(1);
 for(const q of ['page=-1','size=0','size=101'])await problem(await identity.call(alpha,'GET',`/audit?${q}`,undefined,owner.tokens),400,'bad_request');
});

test(`${id(24)} new identity records fail closed at the database and persist no raw credentials`,async({identity})=>{
 const a=await identity.staff(alpha,'OWNER'),b=await identity.staff(beta,'OWNER');
 const previousTokens=a.tokens;for(const actor of [a,b])actor.tokens=await refresh(identity,actor);
 const {identitySql}=await import('../../shared/identity-storage');
 const tables=['identity_staff','identity_session','identity_refresh_history','identity_mfa_challenge','identity_mfa_step','identity_rate_bucket','identity_guard'];
 for(const table of tables){
  const count=Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id='${beta.id}'`));expect(count,`${table}: beta fixture exists`).toBeGreaterThan(0);
  expect(identitySql(`SELECT count(*) FROM ${table}`,null)).toBe('0');
  expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id='${beta.id}'`,alpha.id)).toBe('0');
  expect(Number(identitySql(`SELECT count(*) FROM ${table}`,beta.id))).toBe(count);
  expect(identitySql(`SELECT relrowsecurity::text||'|'||relforcerowsecurity::text FROM pg_class WHERE oid='${table}'::regclass`)).toBe('true|true');
 }
 // Counts alone cannot prove ciphertext; compare observed persisted material with the fixture.
 const hash=identitySql(`SELECT password_hash FROM identity_staff WHERE tenant_id='${alpha.id}' AND id='${a.principal.id}'`);
 expect(hash.includes(a.account.password),'password not stored raw').toBe(false);expect(hash.length).toBeGreaterThan(64);
 const secret=identitySql(`SELECT mfa_secret FROM identity_staff WHERE tenant_id='${alpha.id}' AND id='${a.principal.id}'`);expect(secret.length).toBeGreaterThan(32);expect(secret.includes(a.account.totpSecret!),'TOTP secret encrypted').toBe(false);
 const refreshMaterial=identitySql(`SELECT refresh_hash FROM identity_session WHERE tenant_id='${alpha.id}' AND id='${a.tokens.sessionId}'`);expect(refreshMaterial.length).toBeGreaterThanOrEqual(32);expect(refreshMaterial.includes(a.tokens.refreshToken),'refresh hashed').toBe(false);
 for(const table of tables){const contents=identitySql(`SELECT row_to_json(t)::text FROM ${table} t WHERE tenant_id='${alpha.id}'`);for(const credential of [a.account.password,a.account.totpSecret!,a.tokens.accessToken,a.tokens.refreshToken,previousTokens.accessToken,previousTokens.refreshToken])expect(contents.includes(credential),`${table}: excludes raw credential`).toBe(false);}
 // Transaction rollback leaves data unchanged; negative writes prove WITH CHECK and composite links.
 const invalid=identitySql(`BEGIN;SET LOCAL app.tenant_id='${alpha.id}';INSERT INTO identity_session(id,tenant_id,created_at,updated_at,account_id,kind,refresh_hash,device_label,expires_at,revoked_at,generation,mfa_verified) SELECT gen_random_uuid(),'${beta.id}'::uuid,created_at,updated_at,account_id,kind,refresh_hash,device_label,expires_at,revoked_at,generation,mfa_verified FROM identity_session LIMIT 1;ROLLBACK;`,null,false);
 expect(invalid.includes('row-level security'),'foreign tenant insert rejected').toBe(true);
 const foreignRef=identitySql(`BEGIN;SET LOCAL app.tenant_id='${alpha.id}';INSERT INTO identity_session(id,tenant_id,created_at,updated_at,account_id,kind,refresh_hash,device_label,expires_at,revoked_at,generation,mfa_verified) SELECT gen_random_uuid(),tenant_id,created_at,updated_at,'${b.principal.id}'::uuid,kind,refresh_hash,device_label,expires_at,revoked_at,generation,mfa_verified FROM identity_session LIMIT 1;ROLLBACK;`,null,false);
 expect(foreignRef.includes('foreign key'),'foreign account reference rejected').toBe(true);
 const auditId=(await identity.audit(a)).items[0]?.id;expect(auditId).toBeTruthy();const originalAction=identitySql(`SELECT action FROM audit_entry WHERE id='${auditId}'`);const immutable=identitySql(`BEGIN;UPDATE audit_entry SET action='tampered' WHERE id='${auditId}';ROLLBACK;`,undefined,false);expect(immutable,'audit trigger rejects the attempted mutation').toContain('an audit entry is never changed');expect(identitySql(`SELECT action FROM audit_entry WHERE id='${auditId}'`),'rejected mutation leaves the record intact').toBe(originalAction);
});

test(`${id(25)} contract and local fixtures expose no accidental auth surface`,async({identity})=>{
 const {committedContract}=await import('../../shared/openapi');const contract=committedContract();expect(Object.keys(contract.paths).some(p=>p.startsWith('/local/identity'))).toBe(false);
 const expectations:[string,string,string][]=[['/staff','post','201'],['/staff/{id}','delete','204'],['/sessions/{id}','delete','204'],['/sessions','delete','204']];
 for(const [path,method,status]of expectations){const operation=contract.paths[`${IDENTITY}${path}`]?.[method] as {responses?:Record<string,unknown>}|undefined;expect(Boolean(operation?.responses?.[status]),`${method} ${path}: documented status${status}`).toBe(true);}
 const errors=contract.components.schemas.Problem.properties?.code as {enum?:string[]};for(const code of ['invalid_credentials','invalid_session','permission_denied','conflict','rate_limited'])expect(errors.enum?.includes(code)).toBe(true);
 const account=syntheticAccount();await identity.seed(alpha,[account]);await identity.seed(alpha,[account]);
 for(const salon of [alpha,beta,gamma]){const accounts=ROLES.map(role=>syntheticAccount(role));await identity.seed(salon,accounts);await identity.seed(salon,accounts);for(const a of accounts){const signed=await identity.login(salon,a);expect(signed.principal.role).toBe(a.role);}}
 const owner=await identity.staff(alpha,'OWNER');expect((await identity.list(owner)).filter(r=>r.email===account.email).length).toBe(1);
 await problem(await identity.call(null,'DELETE','/local/identity'),404,'not_found');
 const before=(await identity.audit(owner)).total;await identity.reset();const fresh=await identity.staff(alpha,'OWNER');expect((await identity.audit(fresh)).total).toBeGreaterThanOrEqual(before);expect((await identity.list(fresh)).some(r=>r.email===account.email)).toBe(false);
});

test(`${id(26)} malformed identity input is refused with field errors and no mutation`,async({identity})=>{
 const owner=await identity.staff(alpha,'OWNER');const before=await identity.list(owner);
 const rows:[string,string,unknown,string][]=[['POST','/staff/sign-in',{identifier:'',password:'',deviceLabel:'QA'},'identifier'],['POST','/staff',{email:syntheticAccount().email,role:'MANAGER',password:'short'},'password'],['PATCH',`/staff/${owner.principal.id}`,{version:'zero',role:'MANAGER'},'version'],['POST','/sessions/refresh',{refreshToken:123},'refreshToken'],['PUT','/staff/password',{oldPassword:owner.account.password,newPassword:true},'newPassword'],['POST','/staff/mfa/enroll',{challengeId:12,challengeToken:false},'challengeId'],['POST','/staff/mfa/verify',{challengeId:randomUUID(),challengeToken:'x',code:12},'code']];
 for(const [method,path,data,field]of rows){const p=await problem(await identity.call(alpha,method,path,data,owner.tokens),400,'bad_request');expect(p.errors?.some(e=>e.field===field),`${path}: field identified`).toBe(true);}
 expect(await identity.list(owner)).toEqual(before);
 for(const path of ['/staff','/audit'])for(const query of ['page=-1','size=101'])await problem(await identity.call(alpha,'GET',`${path}?${query}`,undefined,owner.tokens),400,'bad_request');
});
