/**
 * F02-S04 CR-013: one salon theme with the salon's brand colour as the accent (AC-10), and direct
 * entry with no intermediate generic frame (AC-11). Expected values come from the spec, CR-013 and
 * the prototype's final cascade (`--ac`, `--acs`, `--onac`), never from product CSS.
 */
import fs from 'node:fs';
import path from 'node:path';
import {createServer} from 'node:http';
import {execFileSync} from 'node:child_process';
import type {Locator,Page} from '@playwright/test';
import {test,expect,handle,routes} from '../../shared/identity-browser';
import {alpha,beta,gamma,problem} from '../../shared/identity';
import type {Salon} from '../../shared/tenants';
import {appearance} from '../../shared/design-reference';
import {localService,productRoot} from '../../shared/identity-runtime';
import {standInForApi,metaAnswer} from '../../shared/services';
import {recordPaints,settlePaints,entryViolations} from '../../shared/paint-frames';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;

/** The prototype's default accent, used when a salon has no valid colour. */
const DEFAULT_ACCENT='#D6086A';
/** The prototype's final ink and line tokens (AC-10). */
const INK='#111827',LINE='#DDD6CA';
/** The prototype's keyboard focus outline (`--in`), kept on every brand (spec, CR-013 theme and entry). */
const FOCUS='#1D5FD0';
const WHITE:Rgb=[255,255,255];
/** CR-012's retired taupe action and the retired black workspace accent. */
const RETIRED={taupe:'rgb(138, 112, 94)',black:'rgb(20, 20, 19)'};

type Rgb=[number,number,number];
const hex=(value:string):Rgb=>value.slice(1).match(/../g)!.map(part=>parseInt(part,16)) as Rgb;
const rgb=(value:string)=>`rgb(${hex(value).join(', ')})`;
function channels(css:string){const parts=css.match(/[\d.]+/g)?.map(Number)??[];return {rgb:parts.slice(0,3) as Rgb,alpha:parts.length>3?parts[3]:1};}
function luminance([r,g,b]:Rgb){const l=(v:number)=>{const s=v/255;return s<=0.03928?s/12.92:((s+0.055)/1.055)**2.4;};return 0.2126*l(r)+0.7152*l(g)+0.0722*l(b);}
function contrast(a:Rgb,b:Rgb){const [x,y]=[luminance(a),luminance(b)].sort((p,q)=>q-p);return (x+0.05)/(y+0.05);}

/** A filled accent surface: the brand background with a computed foreground readable on it (AA, 4.5:1). */
async function expectOnBrand(element:Locator,brand:string,label:string){
 const s=await appearance(element);expect(s.background,`${label} background is the salon's brand`).toBe(rgb(brand));
 const fg=channels(s.colour).rgb;expect(contrast(fg,hex(brand)),`${label} foreground is readable on ${brand}`).toBeGreaterThanOrEqual(4.5);
}
/** Where an accent line can be drawn: border, outline, box shadow, on the element or its ::before/::after. */
async function accentLines(element:Locator){
 return element.evaluate(el=>[null,'::before','::after'].flatMap(pseudo=>{const s=getComputedStyle(el,pseudo);return [s.borderTopColor,s.borderBottomColor,s.outlineStyle==='none'?'':s.outlineColor,s.boxShadow];}).join(' | '));
}
/** Text colour is checked only for the taupe: the prototype itself uses #141413 as the readable text on a light brand. */
async function expectNoRetired(scope:Locator,colour:string,label:string,text=true){
 const hits=await scope.evaluate((root,[colour,text])=>[root,...Array.from(root.querySelectorAll('*'))].flatMap(el=>{const s=getComputedStyle(el);return [text?s.color:'',s.backgroundColor,s.borderTopColor,s.outlineStyle==='none'?'':s.outlineColor,s.boxShadow].some(v=>v.includes(colour as string))?[el.getAttribute('data-testid')??el.tagName]:[];}),[colour,text] as const);
 expect(hits,`${label}: no element is drawn in the retired ${colour}`).toEqual([]);
}
const signInName=(page:Page)=>page.getByTestId('staff-sign-in-name');
async function signInAccent(page:Page,brand:string){
 const screen=page.getByTestId('staff-sign-in-screen');await expect(screen).toBeVisible();await expect(handle(page,'sign-in-submit')).toBeEnabled();
 await expectOnBrand(handle(page,'sign-in-submit'),brand,'sign-in action');
 expect(await accentLines(page.getByTestId('staff-sign-in-initial')),'initial ring is the brand').toContain(rgb(brand));
 // CR-012's own palette is retired: the sign-in draws with the shared ink and lines.
 await expect(page.getByTestId('staff-sign-in-card').getByRole('heading',{name:'Welcome back',exact:true}),'sign-in heading uses the shared ink').toHaveCSS('color',rgb(INK));
 expect(await accentLines(handle(page,'identifier')),'resting field line is the shared line colour').toContain(rgb(LINE));
 await handle(page,'identifier').focus();await expect(handle(page,'identifier')).toBeFocused();
 expect(await accentLines(handle(page,'identifier')),'focused field accent is the brand').toContain(rgb(brand));
 // Text in the brand colour is the brand only at 4.5:1 or more on white, otherwise ink.
 const brandText=contrast(hex(brand),WHITE)>=4.5?brand:INK;
 await expect(handle(page,'forgot-password'),'recovery control text').toHaveCSS('color',rgb(brandText));
 // The keyboard focus outline stays the prototype blue on every brand.
 await handle(page,'password').focus();await page.keyboard.press('Tab');await expect(handle(page,'password-toggle')).toBeFocused();
 const outline=await handle(page,'password-toggle').evaluate(el=>{const s=getComputedStyle(el);return {style:s.outlineStyle,colour:s.outlineColor,visible:el.matches(':focus-visible')};});
 expect(outline.visible,'keyboard focus is focus-visible').toBe(true);expect(outline.style,'keyboard focus draws an outline').not.toBe('none');expect(outline.colour,'keyboard focus outline is the prototype blue').toBe(rgb(FOCUS));
 await expectNoRetired(screen,RETIRED.taupe,'sign-in');
}
/** The workspace account page's accents at one width: selected navigation, primary action and field focus. */
async function workspaceAccent(page:Page,salon:Salon,brand:string,width:number,ready:(page:Page)=>Promise<void>){
 await page.setViewportSize({width,height:1000});await ready(page);await expect(handle(page,'home')).toHaveText(salon.name);
 const current=page.getByTestId('workspace-navigation').locator('[aria-current="page"]');await expect(current).toHaveCount(1);
 if(width>=900)await expectOnBrand(current,brand,`${salon.slug} selected navigation at ${width}`);
 else{const tile=channels(await current.locator('svg').first().evaluate(el=>getComputedStyle(el.parentElement!).backgroundColor));
  expect(tile.rgb,`${salon.slug} selected tab tile is the brand's soft tint`).toEqual(hex(brand));expect(tile.alpha).toBeGreaterThan(0);expect(tile.alpha).toBeLessThanOrEqual(0.25);}
 await expectOnBrand(handle(page,'password-save'),brand,`${salon.slug} primary action at ${width}`);
 await handle(page,'oldPassword').focus();await expect(handle(page,'oldPassword')).toBeFocused();expect(await accentLines(handle(page,'oldPassword')),`${salon.slug} focused field accent at ${width}`).toContain(rgb(brand));
 await expectNoRetired(page.getByTestId('workspace-shell'),RETIRED.black,`${salon.slug} workspace at ${width}`,false);
}

test(`${c(24)} sign-in and workspace draw every accent in the salon's own brand colour with a readable foreground`,async({identity,accounts})=>{
 test.setTimeout(180000);
 // Rows: the default-coloured salon, a dark brand and a light brand (docs/qa/test-plan.md).
 for(const salon of [alpha,beta,gamma] as Salon[]){
  const staff=await identity.staff(salon,'MANAGER'),signedOut=await accounts.context(salon),signedIn=await accounts.context(salon,[staff]);
  const login=await accounts.open(signedOut,routes.staffSignIn);await expect(signInName(login)).toHaveText(salon.name);await signInAccent(login,salon.brandColour);await login.close();
  for(const width of [1280,390]){
   const page=await accounts.open(signedIn,routes.staff);await workspaceAccent(page,salon,salon.brandColour,width,p=>accounts.ready(p));await page.close();
  }
 }
});

/** Sets one local salon's stored colour through the lead's local-only hook in the product checkout. */
function setColour(salon:Salon,colour:string){
 try{execFileSync(path.join(productRoot,'infra/scripts/local-salon.sh'),['colour',salon.slug,colour],{cwd:productRoot,stdio:'pipe',timeout:60000});}
 catch{throw new Error(`ENVIRONMENT: local-salon.sh colour ${salon.slug} ${colour} failed`);}
}
async function storedColour(identity:{api:{send:(method:string,path:string,options:{headers:Record<string,string>})=>Promise<import('@playwright/test').APIResponse>}},salon:Salon){
 const answer=await identity.api.send('GET','/api/v1/salon',{headers:{Host:salon.hostname}});expect(answer.status()).toBe(200);return (await answer.json()).brandColour as string;
}

test(`${c(25)} a salon colour change shows on both screens on the next request and an invalid colour falls back to the default accent`,async({identity,accounts})=>{
 test.setTimeout(180000);
 // Part 1, real data: change alpha's stored colour twice with the running web app untouched, then restore it.
 const original=await storedColour(identity,alpha);expect(original,'alpha starts from its seeded colour').toBe(alpha.brandColour);
 const manager=await identity.staff(alpha,'MANAGER'),signedOut=await accounts.context(alpha),signedIn=await accounts.context(alpha,[manager]);
 try{
  for(const colour of ['#0E7490','#A7F3D0']){setColour(alpha,colour);expect(await storedColour(identity,alpha)).toBe(colour);
   const login=await accounts.open(signedOut,routes.staffSignIn);await expect(signInName(login)).toHaveText(alpha.name);await signInAccent(login,colour);await login.close();
   const page=await accounts.open(signedIn,routes.staff);await workspaceAccent(page,alpha,colour,1280,p=>accounts.ready(p));await page.close();
  }
 }finally{setColour(alpha,original);}
 expect(await storedColour(identity,alpha),'alpha colour restored').toBe(original);
 // Part 2, the web's defence: the database cannot hold an invalid colour, so a stand-in on the API's
 // port serves alpha's published brand with each invalid value. The page must still render, in the default.
 const published=await identity.api.send('GET','/api/v1/salon',{headers:{Host:alpha.hostname}});expect(published.status()).toBe(200);const brand=await published.json();expect(brand.slug).toBe(alpha.slug);
 // The first row is the seam's control: a valid colour served the same way must render as itself.
 const rows:[string,unknown,string][]=[['valid control','#0E7490','#0E7490'],['malformed','not-a-colour',DEFAULT_ACCENT],['injection','#fff;}body{display:none',DEFAULT_ACCENT],['missing',undefined,DEFAULT_ACCENT]];
 let served:unknown,calls=0;
 localService('stop','api');let fake:Awaited<ReturnType<typeof standInForApi>>|undefined;
 try{
  const meta=metaAnswer('salon-api','qa-brand-stand-in');
  fake=await standInForApi((request,response)=>{
   if(request.url==='/api/v1/salon'&&request.headers.host?.split(':')[0]===alpha.hostname){calls++;const body={...brand};if(served===undefined)delete body.brandColour;else body.brandColour=served;response.writeHead(200,{'Content-Type':'application/json'});response.end(JSON.stringify(body));}
   else if(request.url==='/api/v1/meta')meta(request,response);
   else{response.writeHead(503);response.end();}
  });
  for(const [label,colour,accent] of rows){served=colour;const before=calls;
   const page=await accounts.open(signedOut,routes.staffSignIn);await expect(signInName(page),label).toHaveText(alpha.name);expect(calls,`${label}: the brand was read for this request`).toBeGreaterThan(before);
   await signInAccent(page,accent);await page.close();
  }
 }finally{await fake?.close();localService('start','api');await identity.api.reconnect();}
 await expect.poll(async()=>(await identity.api.health()).status(),{timeout:5000}).toBe(200);
});

/** The product's own account loading message, so a frame showing it is caught even without its test id. */
function loadingMessage(){const file=path.join(productRoot,'apps/web/messages/en.json');const messages=JSON.parse(fs.readFileSync(file,'utf8')) as {account?:{loading?:string}};return messages.account?.loading??'';}

/** Proves the recorder catches a painted loading frame followed by a client redirect, and passes a server redirect. */
async function proveRecorder(accounts:{browser:import('@playwright/test').Browser}){
 const server=createServer((request,response)=>{
  if(request.url==='/client'){response.writeHead(200,{'Content-Type':'text/html'});response.end('<!doctype html><title>Salon</title><body><header data-testid="workspace-header"><span data-testid="account-home">Salon</span></header><div data-testid="account-loading">Loading your account…</div><script>setTimeout(()=>location.replace("/end"),300)</script>');}
  else if(request.url==='/server'){response.writeHead(307,{Location:'/end'});response.end();}
  else{response.writeHead(200,{'Content-Type':'text/html'});response.end('<!doctype html><title>Alpha Studio</title><body><main data-testid="staff-sign-in-screen"><p data-testid="staff-sign-in-name">Alpha Studio</p></main>');}
 });
 await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));const address=server.address();if(!address||typeof address==='string')throw Error('ENVIRONMENT: recorder proof listener absent');
 const context=await accounts.browser.newContext();
 try{const log=await recordPaints(context,'Loading your account');const page=await context.newPage();
  await page.goto(`http://127.0.0.1:${address.port}/client`);await page.waitForURL('**/end');await expect(page.getByTestId('staff-sign-in-name')).toBeVisible();await settlePaints(page);
  const caught=entryViolations(log,'Alpha Studio',false,'/end');
  for(const reason of ['generic or foreign salon name "Salon"','account loading state','workspace frame before sign-in','not a server redirect'])expect(caught.some(v=>v.includes(reason)),`recorder catches: ${reason}`).toBe(true);
  log.clear();await page.goto(`http://127.0.0.1:${address.port}/server`);await expect(page.getByTestId('staff-sign-in-name')).toBeVisible();await settlePaints(page);
  expect(entryViolations(log,'Alpha Studio',false,'/end'),'a server redirect straight to the screen is clean').toEqual([]);
 }finally{await context.close();await new Promise<void>(resolve=>server.close(()=>resolve()));}
}

test(`${c(26)} entering without a usable session reaches sign-in with no intermediate frame`,async({identity,accounts})=>{
 test.setTimeout(180000);await proveRecorder(accounts);
 const manager=await identity.staff(alpha,'MANAGER');
 // A revoked session still sends its cookies; the server must refuse it before painting anything.
 const revoked=await identity.call(alpha,'DELETE','/sessions',undefined,manager.tokens);expect(revoked.status(),'all sessions revoked').toBeLessThan(300);await problem(await identity.call(alpha,'GET','/me',undefined,manager.tokens),401,'invalid_session');
 const paths=['/','/workspace',routes.staff,routes.staffList,routes.audit,'/account','/sign-in'];
 for(const [state,actors] of [['no session',[]],['revoked session',[manager]]] as const){
  const context=await accounts.context(alpha,[...actors]),log=await recordPaints(context,loadingMessage());
  for(const entry of paths){log.clear();const page=await context.newPage();await page.goto(entry);await expect(page).toHaveURL(new RegExp(`${routes.staffSignIn}$`));await expect(signInName(page)).toHaveText(alpha.name);await expect(handle(page,'identifier')).toBeVisible();await page.waitForLoadState('load');await settlePaints(page);
   expect(log.samples.length,`${state} ${entry}: frames were recorded`).toBeGreaterThan(0);
   expect(entryViolations(log,alpha.name,false,routes.staffSignIn),`${state} ${entry}`).toEqual([]);await page.close();}
 }
});

test(`${c(27)} a signed-in member's first painted frame is their workspace with the salon's resolved name`,async({identity,accounts})=>{
 test.setTimeout(180000);
 const rows:[Salon,string,string][]=[[alpha,'/',routes.staff],[alpha,'/workspace',routes.staff],[alpha,routes.staff,routes.staff],[alpha,routes.staffList,routes.staffList],[alpha,routes.audit,routes.audit],[beta,'/',routes.staff]];
 const contexts=new Map<Salon,{context:import('@playwright/test').BrowserContext;log:Awaited<ReturnType<typeof recordPaints>>}>();
 for(const [salon,entry,landing] of rows){
  if(!contexts.has(salon)){const manager=await identity.staff(salon,'MANAGER'),context=await accounts.context(salon,[manager]);contexts.set(salon,{context,log:await recordPaints(context,loadingMessage())});}
  const {context,log}=contexts.get(salon)!;log.clear();const page=await context.newPage();await page.goto(entry);await expect(page).toHaveURL(new RegExp(`${landing}$`));await accounts.ready(page);await expect(handle(page,'home')).toHaveText(salon.name);await settlePaints(page);
  expect(log.samples.length,`${salon.slug} ${entry}: frames were recorded`).toBeGreaterThan(0);
  expect(entryViolations(log,salon.name,true,landing),`${salon.slug} ${entry}`).toEqual([]);await page.close();
 }
});
