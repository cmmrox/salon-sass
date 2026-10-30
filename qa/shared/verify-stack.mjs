import crypto from 'node:crypto';
import { ecsRecord } from './ecs-log.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { salons } from './tenants.ts';
import {root,report,settings,base,url,run,script,curl,db,versions,ENTRY} from './stack-settings.mjs';
import { chromium } from '@playwright/test';
let failures=0;
async function one(id,title,fn){console.log(`--- ${id} ${title}`);try{await fn();console.log(`PASS ${id} ${title}`);}catch(e){failures++;console.error(`FAIL ${id} ${title}: ${e.message}`);}}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,limit=15000){const end=Date.now()+limit;while(Date.now()<end){if(await fn())return;await sleep(100);}throw Error('expected observable outcome did not arrive within limit');}
assert.equal(settings.SALON_STACK_ENV||'local','local','ENVIRONMENT: QA stack must use local HTTP');
const sha=run('git',['rev-parse','HEAD']).trim();
await one('TC-F01-S05-001','images are unprivileged, report the tested commit and ship reference fonts',async()=>{
  for(const kind of ['api','web']){const version=versions()[`SALON_${kind.toUpperCase()}_1_VERSION`];const image=`${settings.SALON_IMAGE_PREFIX||''}salon-${kind}:${version}`;const info=JSON.parse(run('docker',['image','inspect',image]))[0];assert(info.Config.User&&!['0','root'].includes(info.Config.User.split(':')[0]));assert.equal(info.Config.Labels['org.opencontainers.image.revision'],sha);assert.equal(info.Config.Labels['org.opencontainers.image.version'],version);assert.notEqual(run('docker',['run','--rm','--network','none','--entrypoint','id',image,'-u']).trim(),'0');assert.equal(run('docker',['run','--rm','--network','none','--user','0','--entrypoint','find',image,'/','-xdev','(','-name','.env','-o','-name','.env.*',')','-print'],{stdio:['pipe','pipe','ignore']}).trim(),'');}
  const meta=JSON.parse(curl('alpha','/api/v1/meta').body);assert.equal(meta.commit,sha);assert.equal(meta.version,versions().SALON_API_1_VERSION);
  const browser=await chromium.launch();
  try {
    // CR-010/CR-012: the salon entry is the staff sign-in (no customer 430px shell); full width at every size.
    const page=await browser.newPage({viewport:{width:390,height:844}}), resources=[];
    page.on('response',response=>{if(new URL(response.url()).pathname.startsWith('/fonts/poppins/'))resources.push({path:new URL(response.url()).pathname,status:response.status(),type:response.headers()['content-type']});});
    const response=await page.goto(url('alpha'));assert.equal(response.status(),200);
    await page.getByTestId('staff-sign-in-name').waitFor();assert.equal((await page.getByTestId('staff-sign-in-name').textContent()).trim(),salons[0].name);
    const rendered=await page.evaluate(async()=>{
      for(const weight of [400,500,600])await document.fonts.load(`${weight} 15px Poppins`);
      await document.fonts.ready;
      const screen=document.querySelector('[data-testid="staff-sign-in-screen"]');
      return {family:getComputedStyle(document.body).fontFamily,width:screen.getBoundingClientRect().width,canvas:getComputedStyle(document.documentElement).getPropertyValue('--background').trim().toUpperCase(),
        loaded:Array.from(document.fonts).filter(face=>face.family.replaceAll(/['"]/g,'')==='Poppins'&&face.status==='loaded').map(face=>face.weight),
        checked:[400,500,600].map(weight=>document.fonts.check(`${weight} 15px Poppins`))};
    });
    assert.equal(rendered.family.split(',')[0].replaceAll(/['"]/g,'').trim(),'Poppins');assert.equal(rendered.width,390);assert.equal(rendered.canvas,'#F3F0EB');
    assert.deepEqual(rendered.checked,[true,true,true]);for(const weight of ['400','500','600'])assert(rendered.loaded.includes(weight));
    for(const name of ['Regular','Medium','SemiBold']){const found=resources.find(resource=>resource.path.endsWith(`Poppins-${name}.ttf`));assert(found,`packaged ${name} font was actually requested`);assert.equal(found.status,200);assert(!found.type?.includes('html'));}
    await page.setViewportSize({width:1280,height:844});assert.equal(await page.getByTestId('staff-sign-in-screen').evaluate(el=>el.getBoundingClientRect().width),1280);
    fs.mkdirSync(path.join(report,'design'),{recursive:true});await page.screenshot({path:path.join(report,'design/F01-production-like-fonts.png')});
    fs.writeFileSync(path.join(report,'design/F01-production-like-fonts.json'),JSON.stringify({resources,rendered,desktopWidth:1280},null,2));
  } finally {await browser.close();}
});
await one('TC-F01-S05-002','image scanner passes clean images and refuses a fixable vulnerable image',()=>{
  const version=versions().SALON_API_1_VERSION;const output=script('images-scan',[`${settings.SALON_IMAGE_PREFIX||''}salon-api:${version}`,`${settings.SALON_IMAGE_PREFIX||''}salon-web:${version}`]);fs.writeFileSync(path.join(report,'images-scan.log'),output);
  run('docker',['pull','alpine:3.18.0']);let failed=false;try{script('images-scan',['alpine:3.18.0']);}catch(e){const out=String(e.stdout)+String(e.stderr);fs.writeFileSync(path.join(report,'images-scan-vulnerable.log'),out);failed=e.status!==0&&/CVE-\d{4}-\d+/.test(out);}assert(failed,'scanner must reject and name a vulnerability');
});
await one('TC-F01-S05-003','HTTP hostname routing preserves salon and neutral hosts',()=>{
  for(const s of salons){const entry=curl(s.slug,'/');assert.equal(entry.status,307);const location=(entry.headers.match(/^location: (\S+)\r?$/im)||[])[1];assert([url(s.slug,'/workspace'),'/workspace'].includes(location),`${s.slug}: same-host workspace entry, got ${location}`);assert.match(entry.headers,/^cache-control: .*no-store/im);const r=curl(s.slug,ENTRY);assert.equal(r.status,200);assert(r.body.includes(s.name.replaceAll('&','&amp;')));const api=curl(s.slug,'/api/v1/salon');assert.equal(api.status,200);assert.deepEqual(JSON.parse(api.body),{slug:s.slug,name:s.name,brandColour:s.brandColour,timeZone:s.timeZone});}
  // Since F03, console.<domain> is the operator console (its routing is F03's cases), not a neutral host.
  for(const slug of ['','unknown']){const r=curl(slug);assert.equal(r.status,slug?404:200);}
});
await one('TC-F01-S05-004','a new salon is served over HTTP immediately without a restart',()=>{
  const slug=`qa-${Date.now().toString(36)}`;const before=run('docker',['inspect','--format','{{.Id}} {{.State.StartedAt}}','salon-stack-edge']);script('stack-seed',['add',slug,'QA New Salon','#2563EB']);const r=curl(slug,ENTRY);assert.equal(r.status,200);assert(r.body.includes('QA New Salon'));assert.equal(JSON.parse(curl(slug,'/api/v1/salon').body).slug,slug);assert.equal(run('docker',['inspect','--format','{{.Id}} {{.State.StartedAt}}','salon-stack-edge']),before);
});
await one('TC-F01-S05-005','only edge ports are published and forged tenant selectors are ignored',()=>{
  const info=JSON.parse(run('docker',['inspect',...['edge','api-1','api-2','web-1','web-2','postgres','collector'].map(n=>'salon-stack-'+n)]));for(const container of info){const ports=container.NetworkSettings.Ports;const published=Object.values(ports||{}).filter(Boolean).flat();if(container.Name!=='/salon-stack-edge')assert.equal(published.length,0,container.Name);else assert.equal(published.length,1);}
  for(const p of [ENTRY,'/api/v1/salon']){const r=curl('alpha',p+'?tenant=beta',['-H','X-Forwarded-Host: beta.'+settings.SALON_PLATFORM_DOMAIN,'-H','X-Tenant-Id: '+salons[1].id]);assert.equal(r.status,200);assert(r.body.includes(salons[0].name));assert(!r.body.includes(salons[1].name.replaceAll('&','&amp;')));}
});
await one('TC-F01-S05-006','database roles cannot bypass tenant isolation and reprovisioning is idempotent',()=>{
  for(const role of [settings.SALON_DB_USER,settings.SALON_DB_APP_USER])assert.equal(db(`SELECT rolsuper||'|'||rolbypassrls FROM pg_roles WHERE rolname='${role}';`),'false|false');assert.equal(db(`SELECT count(*) FROM pg_class WHERE relowner='${settings.SALON_DB_APP_USER}'::regrole;`),'0');let refused=false;try{db('SELECT * FROM tenant;',settings.SALON_DB_APP_USER);}catch(e){refused=String(e.stderr).includes('permission denied');}assert(refused);
  const snapshot=()=>db("SELECT rolname||'|'||rolsuper||'|'||rolbypassrls FROM pg_roles WHERE rolname NOT LIKE 'pg_%' ORDER BY rolname;");const before=snapshot();
  // F03-S01 added the platform runtime role to the provisioning, so its two settings are passed too.
  const input=fs.readFileSync(path.join(root,'infra/postgres/provision-roles.sql'),'utf8');
  run('docker',['exec','-i','-e','SALON_DB_NAME','-e','SALON_DB_USER','-e','SALON_DB_PASSWORD','-e','SALON_DB_APP_USER','-e','SALON_DB_APP_PASSWORD','-e','SALON_DB_PLATFORM_USER','-e','SALON_DB_PLATFORM_PASSWORD','salon-stack-postgres','psql','-U',settings.SALON_DB_ADMIN_USER,'-d',settings.SALON_DB_NAME,'-X','-q','-v','ON_ERROR_STOP=1'],{input,env:{...process.env,...settings}});assert.equal(snapshot(),before);
});
await one('TC-F01-S05-007','staging hides local hooks and refuses missing secrets',()=>{
  for(const p of ['/local/','/local/clock','/local/probes','/local/probes/missing','/actuator/metrics','/actuator/metrics/salon.outbox.dead'])assert.equal(curl('alpha',p).status,404);
  const copy=fs.mkdtempSync('/tmp/salon-qa-settings-');try{run('git',['archive','HEAD'],{encoding:null,stdio:['ignore',fs.openSync(path.join(copy,'tree.tar'),'w'),'ignore']});run('tar',['-xf',path.join(copy,'tree.tar'),'-C',copy]);fs.copyFileSync(path.join(root,'.env.stack.example'),path.join(copy,'.env.stack'));let failed=false;try{run(path.join(copy,'infra/scripts/stack-up.sh'),[],{cwd:copy});}catch(e){const out=String(e.stdout)+String(e.stderr);assert.match(out,/SALON_DB_(ADMIN_PASSWORD|PASSWORD|APP_PASSWORD)/);failed=e.status!==0;}assert(failed);}
  finally{fs.rmSync(copy,{recursive:true,force:true});}
  const committed=run('git',['ls-files','-z']).split('\0').filter(Boolean);const secrets=['SALON_DB_ADMIN_PASSWORD','SALON_DB_PASSWORD','SALON_DB_APP_PASSWORD'].map(k=>settings[k]).filter(Boolean);for(const file of committed){const name=path.join(root,file);const data=fs.lstatSync(name).isSymbolicLink()?Buffer.from(fs.readlinkSync(name)):fs.readFileSync(name);for(const secret of secrets)assert(!data.includes(Buffer.from(secret)),`secret is committed in ${file}`);}
});
await one('TC-F01-S05-010','an absent deployment version changes no running instance',()=>{
  const before=run('docker',['inspect','--format','{{.Id}} {{.State.StartedAt}}',...['api-1','api-2','web-1','web-2'].map(n=>'salon-stack-'+n)]);const old=versions();let failed=false;try{script('stack-deploy',['qa-missing-'+Date.now()]);}catch(e){failed=e.status!==0;assert.match(String(e.stderr),/do not exist/);}assert(failed);assert.equal(run('docker',['inspect','--format','{{.Id}} {{.State.StartedAt}}',...['api-1','api-2','web-1','web-2'].map(n=>'salon-stack-'+n)]),before);assert.deepEqual(versions(),old);assert.equal(curl('alpha',ENTRY).status,200);
});
await one('TC-F01-S05-011','web API and collector share tenant-tagged traces with no sensitive log fields',async()=>{
  const logSince=new Date(Date.now()-1000).toISOString();const trace=crypto.randomUUID().replaceAll('-','');const marker='qa-secret-'+crypto.randomUUID();const r=curl('alpha',ENTRY+'?private='+marker,['-H',`traceparent: 00-${trace}-1234567890abcdef-01`,'-H','Cookie: private='+marker,'-H','Authorization: Bearer '+marker]);assert.equal(r.status,200);
  const lines=kind=>['1','2'].flatMap(n=>run('sh',['-c','docker logs --since "$2" "$1" 2>&1','qa-log-reader','salon-stack-'+kind+'-'+n,logSince],{maxBuffer:32*1024*1024}).split('\n').filter(l=>l.startsWith('{')).map(l=>ecsRecord(JSON.parse(l)))).filter(l=>l['trace.id']===trace);
  const web=lines('web');assert.equal(web.length,1);assert.equal(web[0]['tenant.slug'],'alpha');assert(!JSON.stringify(web).includes(marker));const api=lines('api');assert(api.length>0);for(const line of api)assert.equal(line['tenant.id'],salons[0].id);
  const traceFile=path.join(root,'.stack/telemetry/traces.jsonl');await until(()=>fs.existsSync(traceFile)&&fs.readFileSync(traceFile,'utf8').includes(trace));const batches=fs.readFileSync(traceFile,'utf8').split('\n').filter(Boolean).map(l=>JSON.parse(l));let found=false;for(const b of batches)for(const resource of b.resourceSpans||[])for(const scope of resource.scopeSpans||[])for(const span of scope.spans||[])if(span.traceId===trace){assert(span.attributes.some(a=>a.key==='tenant.id'&&a.value.stringValue===salons[0].id));found=true;}assert(found);
  const metrics=path.join(root,'.stack/telemetry/metrics.jsonl');await until(()=>fs.existsSync(metrics)&&fs.readFileSync(metrics,'utf8').includes('salon.outbox'),65000);const text=fs.readFileSync(metrics,'utf8');for(const name of ['salon.outbox.dead','salon.outbox.waiting','salon.jobs.start.delay'])assert(text.includes(name));
});
await one('TC-F01-S05-012','workflow is valid and publication and deployment gates are enforced',()=>{
  script('lint-workflows');const file=fs.readFileSync(path.join(root,'.github/workflows/delivery.yml'),'utf8');for(const text of ['images-build.sh','images-scan.sh',"github.event.workflow_run.conclusion == 'success'","github.event.workflow_run.head_branch == 'main'","if: github.event_name == 'workflow_dispatch'",'environment: staging','stack-deploy.sh'])assert(file.includes(text),text);
  const block=file.match(/for name in STAGING_SSH_HOST[\s\S]*?exit "\$missing"/)[0];let rejected=false;try{run('bash',['-c',block],{env:{PATH:process.env.PATH}});}catch(e){const out=String(e.stdout);for(const name of ['STAGING_SSH_HOST','STAGING_SSH_USER','STAGING_SSH_KEY','STAGING_SSH_KNOWN_HOSTS'])assert(out.includes(name));rejected=e.status!==0;}assert(rejected);
});
process.exitCode=failures?1:0;
