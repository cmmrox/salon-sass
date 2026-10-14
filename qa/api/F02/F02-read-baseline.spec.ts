/** TC-F02-004: preparation and cleanup surround the actual k6 threshold run. */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { test, expect, ROLES, syntheticAccount, json, assertTokens, type Tokens, type Actor } from '../../shared/identity';
import { Customers, syntheticMobile } from '../../shared/customer-identity';
import { Profiles } from '../../shared/customer-profile';
import { salons } from '../../shared/tenants';
import { codeSettings, localKeys, apiProcess, mark } from '../../shared/identity-runtime';
import { identitySql } from '../../shared/identity-storage';
import { apiUrl, QA_ROOT, REPO_ROOT } from '../../shared/env';

const execute=promisify(execFile);
type ReadActor={id:string;kind:string;role:string|null;accessToken:string;sessionId:string;name?:string;version?:number};

test('TC-F02-004 bounded local reads meet the documented per-read latency baseline',async({identity})=>{
  test.setTimeout(420000);
  // Check the required tool before any fixture preparation; install policy remains unchanged.
  let k6Version:string;
  try{k6Version=(await execute('k6',['version'])).stdout.trim();}
  catch{throw new Error('ENVIRONMENT: installed k6 is required for the F02 local baseline');}
  const customer=new Customers(identity),profiles=new Profiles(identity);
  const sourceKeys=localKeys(),oldProcess=apiProcess();
  const prepared:{salon:typeof salons[number];actor:Actor;name?:string;version?:number}[]=[];
  const credentialDirectory=path.join(QA_ROOT,'.auth');
  const credentialFile=path.join(credentialDirectory,`f02-load-${randomUUID()}.json`);
  try{
    mark('load-prepare-settings',{number:3,device:10,address:200,daily:1000});
    await codeSettings({SALON_IDENTITY_CODE_NUMBER_LIMIT:'3',SALON_IDENTITY_CODE_DEVICE_LIMIT:'10',
      SALON_IDENTITY_CODE_ADDRESS_LIMIT:'200',SALON_IDENTITY_CODE_DAILY_LIMIT:'1000'},async()=>{
      for(const salon of salons){
        const staff=ROLES.map(role=>syntheticAccount(role));await identity.seed(salon,staff);
        for(const account of staff)prepared.push({salon,actor:await identity.login(salon,account)});
        const usedNumbers=new Set<string>();
        for(let index=0;index<50;index++){
          let mobile=syntheticMobile();while(usedNumbers.has(mobile))mobile=syntheticMobile();usedNumbers.add(mobile);
          const actor=await customer.login(salon,mobile);
          const saved=await profiles.saved(actor,`QA ${salon.slug} Customer ${index+1}`);
          prepared.push({salon,actor,name:saved.name,version:saved.version});
        }
      }
    },identity.api);
    // The measured phase runs a freshly started API with the documented default values.
    expect(apiProcess(),'API restarted after preparation override').not.toBe(oldProcess);
    expect(localKeys()).toEqual(sourceKeys);
    const command=execFileSync('ps',['eww','-p',apiProcess(),'-o','command='],{encoding:'utf8'});
    for(const [name,value] of Object.entries({SALON_IDENTITY_CODE_NUMBER_LIMIT:'3',SALON_IDENTITY_CODE_DEVICE_LIMIT:'10',
      SALON_IDENTITY_CODE_ADDRESS_LIMIT:'20',SALON_IDENTITY_CODE_DAILY_LIMIT:'1000'})){
      expect(new RegExp(`(?:^|\\s)${name}=${value}(?:\\s|$)`).test(command),`running API default ${name}`).toBe(true);
    }
    expect((await identity.api.health()).status()).toBe(200);
    mark('load-defaults-restored',{number:3,device:10,address:20,daily:1000,retainedKeys:true,restarted:true});
    // Preparation time is excluded, and credentials with insufficient remaining access lifetime
    // are rotated by the real refresh endpoint, preserving one device per actor.
    for(const item of prepared){
      if(Date.parse(item.actor.tokens.accessExpiresAt)<Date.parse(await identity.now())+60000){
        const renewed=await json<Tokens>(await identity.call(item.salon,'POST','/sessions/refresh',{refreshToken:item.actor.tokens.refreshToken}),'Tokens');
        assertTokens(renewed);item.actor.tokens=renewed;
      }
    }
    const fixtureSalons=[];
    for(const salon of salons){
      const people=prepared.filter(item=>item.salon===salon);
      const toFixture=(item:typeof people[number]):ReadActor=>({id:item.actor.principal.id,kind:item.actor.principal.kind,role:item.actor.principal.role,
        accessToken:item.actor.tokens.accessToken,sessionId:item.actor.tokens.sessionId,...(item.name?{name:item.name,version:item.version}:{})});
      const staff=people.filter(item=>item.actor.principal.kind==='staff').map(toFixture);
      const customers=people.filter(item=>item.actor.principal.kind==='customer').map(toFixture);
      const counts=identitySql(`SELECT (SELECT count(*) FROM identity_staff WHERE tenant_id='${salon.id}'),(SELECT count(*) FROM identity_customer WHERE tenant_id='${salon.id}')`);
      expect(counts,'nonempty exact synthetic fixture volumes').toBe('5|50');
      const owner=people.find(item=>item.actor.principal.role==='OWNER')!;
      const audit=await identity.audit(owner.actor);expect(audit.items.length).toBe(100);expect(audit.total).toBeGreaterThanOrEqual(100);
      fixtureSalons.push({slug:salon.slug,hostname:salon.hostname,staff,customers,auditRows:audit.total});
      mark('load-fixture-volume',{salon:salon.slug,staff:5,customers:50,auditRows:audit.total});
    }
    fs.mkdirSync(credentialDirectory,{recursive:true});
    fs.writeFileSync(credentialFile,JSON.stringify({apiUrl,salons:fixtureSalons}),{mode:0o600,flag:'wx'});
    expect(fs.statSync(credentialFile).mode&0o777).toBe(0o600);
    console.log(JSON.stringify({case:'TC-F02-004',scenario:{users:10,ramp:'none',duration:'20s'},
      machine:{platform:os.platform(),arch:os.arch(),cpus:os.cpus().length,cpu:os.cpus()[0]?.model,memoryBytes:os.totalmem()},
      k6:k6Version,data:fixtureSalons.map(s=>({salon:s.slug,staff:s.staff.length,customers:s.customers.length,auditRows:s.auditRows})),
      target:'per-read p95 <300ms; local baseline'}));
    try{
      const result=await execute('k6',['run',path.join(QA_ROOT,'load/F02/identity-reads.js')],{
        cwd:REPO_ROOT,env:{...process.env,F02_LOAD_FIXTURE:credentialFile},timeout:45000,maxBuffer:4*1024*1024});
      console.log(result.stdout);if(result.stderr)console.log(result.stderr);
    }catch(error){
      const result=error as {stdout?:string;stderr?:string;code?:number|string};
      if(result.stdout)console.log(result.stdout);if(result.stderr)console.log(result.stderr);
      throw new Error(`TC-F02-004: k6 baseline failed (exit ${result.code??'unknown'}); inspect its threshold output`);
    }
  }finally{
    fs.rmSync(credentialFile,{force:true});
    // The Identity fixture separately guarantees clock and mutable identity cleanup even if
    // preparation or k6 fails. Audit remains immutable and retained.
    mark('load-ignored-authority-removed',{removed:!fs.existsSync(credentialFile)});
  }
});
