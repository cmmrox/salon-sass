import {createServer} from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import ts from 'typescript';
import {test,expect,handle,routes,screenMap} from '../../shared/identity-browser';
import {alpha,beta,syntheticAccount} from '../../shared/identity';
import {salons} from '../../shared/tenants';
import {fit,seriousViolations,testIds,TEST_ID_NAME} from '../../shared/page-checks';
import {productRoot} from '../../shared/identity-runtime';
import {DESIGN_WIDTHS,appearance,designScreenshot,referenceAccountControls,referenceDialog,referenceFit,referenceNavigation,referenceTypeAndPalette} from '../../shared/design-reference';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;
const WIDTHS=[360,390,768,1024,1280,1440].map(width=>({width,height:1000}));

test(`${c(16)} responsive layouts keep every control reachable across six widths`,async({identity,accounts})=>{
 test.setTimeout(180000);const staff=await identity.staff(beta,'OWNER'),context=await accounts.context(beta,[staff]);
 for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();
  for(const viewport of WIDTHS){await page.setViewportSize(viewport);const inspected=await referenceFit(page,beta.name);expect(inspected.counted).toBeGreaterThan(0);expect(inspected.pageScrolls).toBe(false);expect(inspected.outside).toEqual([]);expect(inspected.clipped).toEqual([]);
   const targets=await page.locator('button:visible,input:visible,select:visible,a:visible').evaluateAll(elements=>elements.map(element=>{const target=(element as HTMLInputElement).type==='checkbox'?element.closest('label')!:element;const r=target.getBoundingClientRect();return {height:r.height,width:r.width,id:element.getAttribute('data-testid')};}));expect(targets.length).toBeGreaterThan(0);for(const target of targets){expect(target.height,`${target.id} target height`).toBeGreaterThanOrEqual(44);expect(target.width,`${target.id} target width`).toBeGreaterThanOrEqual(44);}
  }await page.close();
 }
 const account=syntheticAccount('OWNER',false);await identity.seed(alpha,[account]);const enrollment=await accounts.context(alpha),page=await accounts.open(enrollment,routes.staffSignIn);await handle(page,'identifier').fill(account.email);await handle(page,'password').fill(account.password);await handle(page,'sign-in-submit').click();await expect(handle(page,'setup-key')).toBeVisible();for(const viewport of WIDTHS){await page.setViewportSize(viewport);expect((await fit(page)).pageScrolls).toBe(false);}
});

test(`${c(23)} staff identity screens follow the supplied salon layout with accessible confirmation overlays`,async({identity,accounts})=>{
 test.setTimeout(180000);
 const owner=await identity.staff(beta,'OWNER'),context=await accounts.context(beta,[owner]);
 for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();
  for(const viewport of DESIGN_WIDTHS){await page.setViewportSize(viewport);await referenceTypeAndPalette(page);await referenceAccountControls(page);
   const workspace=route.startsWith('/workspace');
   if(workspace){const shell=await appearance(page.getByTestId('workspace-shell'));expect(shell.width).toBe(viewport.width);expect(shell.background).toBe('rgb(243, 240, 235)');
    const header=await appearance(page.getByTestId('workspace-header'));expect(header.position).toBe('sticky');if(viewport.width>=900){expect(header.background).toBe('rgb(255, 255, 255)');expect(header.padding).toBe('8px 28px');expect(header.minHeight).toBe('60px');}
    await referenceNavigation(page,'workspace-navigation',viewport.width);await expect(handle(page,'title')).toHaveCSS('font-size',viewport.width>=900?'26px':'22px');
    if(viewport.width>=900){const main=await appearance(page.getByTestId('workspace-main'));expect(main.left).toBe(viewport.width<1200?200:236);expect(main.padding).toBe(viewport.width<1200?'24px 24px 48px':'28px 36px 56px');}
   }
   const result=await referenceFit(page,beta.name);expect(result.counted).toBeGreaterThan(0);expect(result.pageScrolls).toBe(false);expect(result.outside).toEqual([]);expect(result.clipped).toEqual([]);
   await designScreenshot(page,`F02-${route.replaceAll('/','-')}-${viewport.width}`);
   if(route===routes.staff){const trigger=handle(page,'revoke-all');await trigger.click();await referenceDialog(page,workspace,viewport.width);
    await designScreenshot(page,`F02-confirmation-${workspace?'staff':'customer'}-${viewport.width}`);await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);await expect(trigger).toBeFocused();
    await trigger.click();await handle(page,'cancel-action').click();await expect(page.getByRole('dialog')).toHaveCount(0);await expect(trigger).toBeFocused();
   }
  }await page.close();
 }
 const professional=await identity.staff(alpha,'PROFESSIONAL'),limited=await accounts.context(alpha,[professional]),page=await accounts.open(limited,routes.staff);await accounts.ready(page);
 await expect(page.getByTestId('workspace-staff')).toHaveCount(0);await expect(page.getByTestId('workspace-audit')).toHaveCount(0);await referenceNavigation(page,'workspace-navigation',1280);
});
test(`${c(17)} all screens pass automated accessibility checks and announce errors`,async({identity,accounts})=>{
 test.setTimeout(180000);for(const salon of salons){const owner=await identity.staff(salon,'OWNER'),context=await accounts.context(salon,[owner]);
  for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();expect(await seriousViolations(page)).toEqual([]);
   if(route===routes.staff){await handle(page,'revoke-all').click();await expect(handle(page,'confirm-action')).toBeVisible();expect(await seriousViolations(page)).toEqual([]);await handle(page,'cancel-action').click();}
   if(route===routes.staffSignIn){await handle(page,'identifier').fill(owner.account.email);await handle(page,'password').fill('Wrong synthetic password');await handle(page,'sign-in-submit').click();await expect(handle(page,'error')).toHaveAttribute('role','alert');expect(await seriousViolations(page)).toEqual([]);}
   await page.close();
  }
 }
});
test(`${c(19)} staff workspace identifies only its salon and keeps the neutral palette`,async({identity,accounts})=>{
 const colours:string[]=[];
 for(const salon of salons){const staff=await identity.staff(salon,'MANAGER'),context=await accounts.context(salon,[staff]);
  for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();
   await expect(handle(page,'home')).toHaveText(salon.name);await expect(page.getByTestId('salon-shell')).toHaveCount(0);
   for(const other of salons.filter(s=>s!==salon))expect((await page.locator('body').innerText()).includes(other.name)).toBe(false);
   const surface=await appearance(page.getByTestId('workspace-shell'));colours.push(surface.background);await page.close();
  }
 }
 expect(new Set(colours).size,'all staff workspaces share the neutral canvas').toBe(1);expect(colours[0]).toBe('rgb(243, 240, 235)');
});
test(`${c(21)} translation and test-id inventories cover every new screen state`,async({identity,accounts})=>{
 const source=path.join(productRoot,'apps/web/src/components/account');const files=fs.readdirSync(source).filter(file=>file.endsWith('.tsx')&&!file.endsWith('.test.tsx'));expect(files.length).toBeGreaterThan(0);
 const messages=JSON.parse(fs.readFileSync(path.join(productRoot,'apps/web/messages/en.json'),'utf8')).account as Record<string,string>;expect(Object.keys(messages).length).toBeGreaterThan(50);const leaves=(node:unknown,prefix=''): [string,unknown][]=>node&&typeof node==='object'?Object.entries(node).flatMap(([key,value])=>leaves(value,prefix?prefix+'.'+key:key)):[[prefix,node]];const flattened=Object.fromEntries(leaves(messages));expect(Object.keys(flattened).length).toBeGreaterThan(50);for(const [key,value]of Object.entries(flattened))expect(typeof value==='string'&&value.trim().length>0,`nonempty account translation ${key}`).toBe(true);
 const texts=files.map(file=>fs.readFileSync(path.join(source,file),'utf8'));const keys=texts.flatMap(text=>[...text.matchAll(/\bt\(['"]([^'"]+)['"]/g)].map(match=>match[1]));expect(keys.length).toBeGreaterThan(20);for(const key of keys)expect(Object.hasOwn(flattened,key),`account translation ${key}`).toBe(true);
 let jsx=0;for(const [index,text]of texts.entries()){const parsed=ts.createSourceFile(files[index],text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);const visit=(node:ts.Node)=>{if(ts.isJsxElement(node)||ts.isJsxSelfClosingElement(node))jsx++;if(ts.isJsxText(node))expect(/[\p{L}\p{N}]/u.test(node.text),`${files[index]} has no unlocalized JSX text`).toBe(false);ts.forEachChild(node,visit);};visit(parsed);}expect(jsx).toBeGreaterThan(20);
 const owner=await identity.staff(alpha,'OWNER'),context=await accounts.context(alpha,[owner]);
 for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();await expect(page.locator('html')).toHaveAttribute('lang','en');const ids=await testIds(page);expect(ids.length).toBeGreaterThan(3);for(const id of ids)expect(id).toMatch(TEST_ID_NAME);
  const missing=await page.locator('button,input,select').evaluateAll(elements=>elements.filter(el=>!el.getAttribute('data-testid')).length);expect(missing,'every new form control has a stable handle').toBe(0);expect((await page.locator('body').innerText()).includes('account.')).toBe(false);await page.close();
 }
});

test(`${c(23)} prototype defaults to Salon web and labels the deferred Customer mobile reference`,async({browser},testInfo)=>{
 const html=fs.readFileSync(path.join(productRoot,'docs/design/prototype/index.html'),'utf8');
 const server=createServer((_request,response)=>{response.writeHead(200,{'Content-Type':'text/html'});response.end(html);});
 await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));const address=server.address();if(!address||typeof address==='string')throw Error('ENVIRONMENT: prototype preview listener absent');
 const page=await browser.newPage();try{
  for(const width of [360,768,1440]){await page.setViewportSize({width,height:1000});await page.goto(`http://127.0.0.1:${address.port}/`);
   const salon=page.getByRole('button',{name:'Salon web',exact:true}),customer=page.getByRole('button',{name:'Customer mobile design (later)',exact:true});
   await expect(salon).toHaveAttribute('aria-pressed','true');await expect(customer).toHaveAttribute('aria-pressed','false');await expect(page.locator('body')).toHaveClass(/adm/);
   await expect(page.locator('#platform-note')).toContainText('React Native mobile reference for after the salon web release');
   const bounds=await page.getByRole('group',{name:'Design reference platform'}).locator('button').evaluateAll(buttons=>buttons.map(button=>{const r=button.getBoundingClientRect();return{left:r.left,right:r.right,top:r.top,bottom:r.bottom,client:button.clientHeight,scroll:button.scrollHeight};}));expect(bounds).toHaveLength(2);
   for(const box of bounds){expect(box.left).toBeGreaterThanOrEqual(0);expect(box.right).toBeLessThanOrEqual(width);expect(box.scroll).toBeLessThanOrEqual(box.client);}
   expect(bounds[0].right<=bounds[1].left||bounds[0].bottom<=bounds[1].top,'platform labels do not overlap').toBe(true);
   const identity=await page.locator('#bm').boundingBox(),platform=await page.getByRole('group',{name:'Design reference platform'}).boundingBox();expect(identity).not.toBeNull();expect(platform).not.toBeNull();expect(identity!.x+identity!.width<=platform!.x||platform!.x+platform!.width<=identity!.x||identity!.y+identity!.height<=platform!.y||platform!.y+platform!.height<=identity!.y,'identity does not overlap the platform labels').toBe(true);
   await page.screenshot({path:testInfo.outputPath(`prototype-staff-default-${width}.png`)});
   await customer.click();await expect(customer).toHaveAttribute('aria-pressed','true');await expect(page.locator('body')).not.toHaveClass(/adm/);await expect(page.locator('#platform-note')).toContainText('React Native');
   await salon.click();await expect(page.locator('body')).toHaveClass(/adm/);
  }
 }finally{await page.close();await new Promise<void>((resolve,reject)=>server.close(error=>error?reject(error):resolve()));}
});
