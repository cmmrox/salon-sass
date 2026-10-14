import fs from 'node:fs';
import path from 'node:path';
import ts from 'typescript';
import {test,expect,handle,routes,screenMap,brandName} from '../../shared/identity-browser';
import {alpha,beta,syntheticAccount} from '../../shared/identity';
import {salons} from '../../shared/tenants';
import {fit,seriousViolations,testIds,TEST_ID_NAME} from '../../shared/page-checks';
import {productRoot} from '../../shared/identity-runtime';
import {DESIGN_WIDTHS,appearance,designScreenshot,referenceAccountControls,referenceCustomerShell,referenceDialog,referenceFit,referenceNavigation,referenceTypeAndPalette} from '../../shared/design-reference';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;
const WIDTHS=[360,390,768,1024,1280,1440].map(width=>({width,height:1000}));

test(`${c(16)} responsive layouts keep every control reachable across six widths`,async({identity,accounts})=>{
 test.setTimeout(180000);const staff=await identity.staff(beta,'OWNER'),customer=await accounts.customers.login(beta),context=await accounts.context(beta,[staff,customer]);
 for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();
  for(const viewport of WIDTHS){await page.setViewportSize(viewport);const inspected=await referenceFit(page,beta.name);expect(inspected.counted).toBeGreaterThan(0);expect(inspected.pageScrolls).toBe(false);expect(inspected.outside).toEqual([]);expect(inspected.clipped).toEqual([]);
   const targets=await page.locator('button:visible,input:visible,select:visible,a:visible').evaluateAll(elements=>elements.map(element=>{const target=(element as HTMLInputElement).type==='checkbox'?element.closest('label')!:element;const r=target.getBoundingClientRect();return {height:r.height,width:r.width,id:element.getAttribute('data-testid')};}));expect(targets.length).toBeGreaterThan(0);for(const target of targets){expect(target.height,`${target.id} target height`).toBeGreaterThanOrEqual(44);expect(target.width,`${target.id} target width`).toBeGreaterThanOrEqual(44);}
  }await page.close();
 }
 const account=syntheticAccount('OWNER',false);await identity.seed(alpha,[account]);const enrollment=await accounts.context(alpha),page=await accounts.open(enrollment,routes.staffSignIn);await handle(page,'identifier').fill(account.email);await handle(page,'password').fill(account.password);await handle(page,'sign-in-submit').click();await expect(handle(page,'setup-key')).toBeVisible();for(const viewport of WIDTHS){await page.setViewportSize(viewport);expect((await fit(page)).pageScrolls).toBe(false);}
});

test(`${c(23)} identity screens follow the supplied customer and salon layouts with accessible confirmation overlays`,async({identity,accounts})=>{
 test.setTimeout(180000);
 const owner=await identity.staff(beta,'OWNER'),customer=await accounts.customers.login(beta),context=await accounts.context(beta,[owner,customer]);
 for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();
  for(const viewport of DESIGN_WIDTHS){await page.setViewportSize(viewport);await referenceTypeAndPalette(page);await referenceAccountControls(page);
   const workspace=route.startsWith('/workspace');
   if(workspace){const shell=await appearance(page.getByTestId('workspace-shell'));expect(shell.width).toBe(viewport.width);expect(shell.background).toBe('rgb(243, 240, 235)');
    const header=await appearance(page.getByTestId('workspace-header'));expect(header.position).toBe('sticky');if(viewport.width>=900){expect(header.background).toBe('rgb(255, 255, 255)');expect(header.padding).toBe('8px 28px');}
    await referenceNavigation(page,'workspace-navigation',viewport.width);await expect(handle(page,'title')).toHaveCSS('font-size',viewport.width>=900?'26px':'22px');
    if(viewport.width>=900){const main=await appearance(page.getByTestId('workspace-main'));expect(main.left).toBe(viewport.width<1200?200:236);expect(main.padding).toBe(viewport.width<1200?'24px 24px 48px':'28px 36px 56px');}
   }else{await referenceCustomerShell(page,viewport.width,beta.brandColour);await expect(handle(page,'title')).toHaveCSS('font-size','22px');}
   const result=await referenceFit(page,beta.name);expect(result.counted).toBeGreaterThan(0);expect(result.pageScrolls).toBe(false);expect(result.outside).toEqual([]);expect(result.clipped).toEqual([]);
   await designScreenshot(page,`F02-${route.replaceAll('/','-')}-${viewport.width}`);
   if(route===routes.customer||route===routes.staff){const trigger=handle(page,route===routes.customer?'delete':'revoke-all');await trigger.click();await referenceDialog(page,workspace,viewport.width);
    await designScreenshot(page,`F02-confirmation-${workspace?'staff':'customer'}-${viewport.width}`);await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);await expect(trigger).toBeFocused();
    await trigger.click();await handle(page,'cancel-action').click();await expect(page.getByRole('dialog')).toHaveCount(0);await expect(trigger).toBeFocused();
   }
  }await page.close();
 }
 const professional=await identity.staff(alpha,'PROFESSIONAL'),limited=await accounts.context(alpha,[professional]),page=await accounts.open(limited,routes.staff);await accounts.ready(page);
 await expect(page.getByTestId('workspace-staff')).toHaveCount(0);await expect(page.getByTestId('workspace-audit')).toHaveCount(0);await referenceNavigation(page,'workspace-navigation',1280);
});
test(`${c(17)} all screens pass automated accessibility checks and announce errors`,async({identity,accounts})=>{
 test.setTimeout(180000);for(const salon of salons){const customer=await accounts.customers.login(salon),owner=await identity.staff(salon,'OWNER'),context=await accounts.context(salon,[customer,owner]);
  for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();expect(await seriousViolations(page)).toEqual([]);
   if(route===routes.customer){await handle(page,'delete').click();await expect(handle(page,'confirm-action')).toBeVisible();expect(await seriousViolations(page)).toEqual([]);await handle(page,'cancel-action').click();}
   if(route===routes.staffSignIn){await handle(page,'identifier').fill(owner.account.email);await handle(page,'password').fill('Wrong synthetic password');await handle(page,'sign-in-submit').click();await expect(handle(page,'error')).toHaveAttribute('role','alert');expect(await seriousViolations(page)).toEqual([]);}
   await page.close();
  }
 }
});
test(`${c(19)} customer screens use the correct salon brand while workspace stays neutral`,async({identity,accounts})=>{
 const workspaceColours:string[]=[];
 for(const salon of salons){const customer=await accounts.customers.login(salon),staff=await identity.staff(salon,'MANAGER'),context=await accounts.context(salon,[customer,staff]);
  for(const route of [routes.customerSignIn,routes.customer]){const page=await accounts.open(context,route);await expect(brandName(page)).toHaveText(salon.name);const theme=await brandName(page).evaluate(el=>getComputedStyle(el).getPropertyValue('--color-brand').trim().toUpperCase());expect(theme).toBe(salon.brandColour.toUpperCase());for(const other of salons.filter(s=>s!==salon))expect((await page.locator('body').innerText()).includes(other.name)).toBe(false);await page.close();}
  const page=await accounts.open(context,routes.staff);await accounts.ready(page);await expect(handle(page,'title')).not.toHaveText(salon.name);const button=await handle(page,'logout').evaluate(el=>({background:getComputedStyle(el).backgroundColor,text:getComputedStyle(el).getPropertyValue('--color-text').trim()}));expect(button.background.length).toBeGreaterThan(0);workspaceColours.push(button.background);
 }
 expect(new Set(workspaceColours).size,'all workspaces use the same neutral palette').toBe(1);
});
test(`${c(21)} translation and test-id inventories cover every new screen state`,async({identity,accounts})=>{
 const source=path.join(productRoot,'apps/web/src/components/account');const files=fs.readdirSync(source).filter(file=>file.endsWith('.tsx')&&!file.endsWith('.test.tsx'));expect(files.length).toBeGreaterThanOrEqual(5);
 const messages=JSON.parse(fs.readFileSync(path.join(productRoot,'apps/web/messages/en.json'),'utf8')).account as Record<string,string>;expect(Object.keys(messages).length).toBeGreaterThan(50);const leaves=(node:unknown,prefix=''): [string,unknown][]=>node&&typeof node==='object'?Object.entries(node).flatMap(([key,value])=>leaves(value,prefix?prefix+'.'+key:key)):[[prefix,node]];const flattened=Object.fromEntries(leaves(messages));expect(Object.keys(flattened).length).toBeGreaterThan(50);for(const [key,value]of Object.entries(flattened))expect(typeof value==='string'&&value.trim().length>0,`nonempty account translation ${key}`).toBe(true);
 const texts=files.map(file=>fs.readFileSync(path.join(source,file),'utf8'));const keys=texts.flatMap(text=>[...text.matchAll(/\bt\(['"]([^'"]+)['"]/g)].map(match=>match[1]));expect(keys.length).toBeGreaterThan(20);for(const key of keys)expect(Object.hasOwn(flattened,key),`account translation ${key}`).toBe(true);
 let jsx=0;for(const [index,text]of texts.entries()){const parsed=ts.createSourceFile(files[index],text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);const visit=(node:ts.Node)=>{if(ts.isJsxElement(node)||ts.isJsxSelfClosingElement(node))jsx++;if(ts.isJsxText(node))expect(/[\p{L}\p{N}]/u.test(node.text),`${files[index]} has no unlocalized JSX text`).toBe(false);ts.forEachChild(node,visit);};visit(parsed);}expect(jsx).toBeGreaterThan(20);
 const owner=await identity.staff(alpha,'OWNER'),customer=await accounts.customers.login(alpha),context=await accounts.context(alpha,[owner,customer]);
 for(const route of screenMap){const page=await accounts.open(context,route);if(!route.endsWith('sign-in'))await accounts.ready(page);else await expect(handle(page,'sign-in-submit')).toBeVisible();await expect(page.locator('html')).toHaveAttribute('lang','en');const ids=await testIds(page);expect(ids.length).toBeGreaterThan(3);for(const id of ids)expect(id).toMatch(TEST_ID_NAME);
  const missing=await page.locator('button,input,select').evaluateAll(elements=>elements.filter(el=>!el.getAttribute('data-testid')).length);expect(missing,'every new form control has a stable handle').toBe(0);expect((await page.locator('body').innerText()).includes('account.')).toBe(false);await page.close();
 }
});
