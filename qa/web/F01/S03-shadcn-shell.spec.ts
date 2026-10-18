/**
 * F01-S03 AC-14 (CR-016): the workspace shell, navigation, status page, application not-found page
 * and the plain proxy pages, rebuilt from shadcn/ui and Tailwind, keep the look they had. Expected
 * values come from the spec, the prototype's final cascade and the approved CR-007/CR-013 mapping
 * (docs/design/ui-ux-standards.md), never from product CSS. The side-by-side comparison with the
 * pre-CR-016 build is the manual case TC-F01-S03-016.
 */
import type {Locator,Page} from '@playwright/test';
import {test,expect,handle,routes} from '../../shared/identity-browser';
import {alpha,beta,gamma} from '../../shared/identity';
import {salons,salonUrl,onHostname,PLATFORM_DOMAIN,type Salon} from '../../shared/tenants';
import {webUrl} from '../../shared/env';
import {DESIGN_WIDTHS,appearance,designScreenshot,referenceFit,referenceNavigation,referenceTypeAndPalette} from '../../shared/design-reference';
import {fit,testIds,TEST_ID_NAME} from '../../shared/page-checks';
import {startApi,stopApi} from '../../shared/services';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {productRoot} from '../../shared/identity-runtime';

/** The prototype's keyboard focus outline (`--in`): 3px solid #1D5FD0, offset 2px, on every brand. */
const FOCUS={style:'solid',width:'3px',colour:'rgb(29, 95, 208)',offset:'2px'};
type Rgb=[number,number,number];
const hex=(value:string)=>value.slice(1).match(/../g)!.map(part=>parseInt(part,16)) as Rgb;
const channels=(css:string)=>{const parts=css.match(/[\d.]+/g)?.map(Number)??[];return {rgb:parts.slice(0,3),alpha:parts.length>3?parts[3]:1};};
function luminance([r,g,b]:number[]){const l=(v:number)=>{const s=v/255;return s<=0.03928?s/12.92:((s+0.055)/1.055)**2.4;};return 0.2126*l(r!)+0.7152*l(g!)+0.0722*l(b!);}
const contrast=(a:number[],b:number[])=>{const [x,y]=[luminance(a),luminance(b)].sort((p,q)=>q-p);return (x!+0.05)/(y!+0.05);};

/** Presses Tab until the target holds a keyboard focus, as a keyboard user reaches it. */
async function tabTo(page:Page,target:Locator,label:string){
 await page.locator('body').click({position:{x:1,y:1}});
 for(let presses=0;presses<40;presses++){await page.keyboard.press('Tab');if(await target.evaluate(el=>el===document.activeElement||el.contains(document.activeElement)))return;}
 throw new Error(`${label}: never reached by Tab`);
}
/** The focused element draws the prototype's keyboard outline. */
async function expectKeyboardFocus(page:Page,label:string){
 const outline=await page.evaluate(()=>{const el=document.activeElement as HTMLElement;const s=getComputedStyle(el);return {visible:el.matches(':focus-visible'),style:s.outlineStyle,width:s.outlineWidth,colour:s.outlineColor,offset:s.outlineOffset,id:el.getAttribute('data-testid')};});
 expect(outline.visible,`${label}: ${outline.id} has keyboard focus`).toBe(true);
 expect({style:outline.style,width:outline.width,colour:outline.colour,offset:outline.offset},`${label}: ${outline.id} focus outline`).toEqual({style:FOCUS.style,width:FOCUS.width,colour:FOCUS.colour,offset:FOCUS.offset});
}
const suspend=(action:string)=>execFileSync(path.join(productRoot,'infra/scripts/local-salon.sh'),[action,gamma.slug],{cwd:productRoot,stdio:'pipe'});

test('TC-F01-S03-015 the rebuilt shell, navigation and foundation pages keep the reference look at six widths and three brands',async({identity,accounts,page,request})=>{
 test.setTimeout(300_000);
 // Rows: alpha the default colour, beta dark with the long name, gamma light (docs/qa/test-plan.md).
 for(const salon of [alpha,beta,gamma] as Salon[]){
  const staff=await identity.staff(salon,'MANAGER'),context=await accounts.context(salon,[staff]);
  const workspace=await accounts.open(context,routes.staff);await accounts.ready(workspace);
  for(const viewport of DESIGN_WIDTHS){
   const label=`${salon.slug} at ${viewport.width}`;
   await workspace.setViewportSize(viewport);await accounts.ready(workspace);await referenceTypeAndPalette(workspace);
   // The Sidebar animates its width when the viewport crosses 1200px; measure the settled layout.
   await workspace.evaluate(()=>Promise.all(document.getAnimations().map(a=>a.finished.catch(()=>undefined))));
   // CR-017: below 900px the navigation lives in the Sidebar's sheet, opened from the header's menu button (TC-F01-S03-017).
   for(const id of ['workspace-shell','workspace-header','workspace-main',viewport.width>=900?'workspace-navigation':'workspace-menu'])await expect(workspace.getByTestId(id),`${label}: ${id}`).toBeVisible();
   await expect(handle(workspace,'home'),`${label}: the header names the salon`).toHaveText(salon.name);
   const shell=await appearance(workspace.getByTestId('workspace-shell'));expect(shell.background,`${label}: warm canvas`).toBe('rgb(243, 240, 235)');expect(shell.width).toBe(viewport.width);
   const header=await appearance(workspace.getByTestId('workspace-header'));expect(header.position,`${label}: sticky header`).toBe('sticky');expect(header.minHeight,`${label}: header height`).toBe('60px');
   if(viewport.width>=900){expect(header.background,`${label}: white header from 900px`).toBe('rgb(255, 255, 255)');const main=await appearance(workspace.getByTestId('workspace-main'));expect(main.left,`${label}: rail width`).toBe(viewport.width<1200?200:236);}
   await referenceNavigation(workspace,'workspace-navigation',viewport.width);
   // The selected Sidebar item (AC-15): a brand fill with a readable foreground, on the rail and in the phone sheet.
   if(viewport.width<900){await workspace.getByTestId('workspace-menu').click();await expect(workspace.getByRole('dialog',{name:'Staff workspace'})).toBeVisible();}
   const current=workspace.getByTestId('workspace-navigation').locator('[aria-current="page"]');await expect(current).toHaveCount(1);
   const item=await appearance(current);expect(channels(item.background).rgb,`${label}: selected item is the brand`).toEqual(hex(salon.brandColour));expect(contrast(channels(item.colour).rgb,hex(salon.brandColour)),`${label}: selected item text readable`).toBeGreaterThanOrEqual(4.5);
   if(viewport.width<900){await designScreenshot(workspace,`CR017-F01-sheet-${salon.slug}-${viewport.width}`);await workspace.keyboard.press('Escape');await expect(workspace.getByRole('dialog')).toHaveCount(0);await expect(workspace.getByTestId('workspace-menu')).toBeFocused();}
   const ids=await testIds(workspace);expect(ids.length).toBeGreaterThan(3);for(const id of ids)expect(id).toMatch(TEST_ID_NAME);
   const result=await referenceFit(workspace,salon.name);expect(result.counted).toBeGreaterThan(0);expect(result.pageScrolls,`${label}: no horizontal scroll`).toBe(false);expect(result.outside).toEqual([]);expect(result.clipped).toEqual([]);
   await tabTo(workspace,viewport.width>=900?workspace.getByTestId('workspace-navigation').getByRole('link').first():workspace.getByTestId('workspace-menu'),label);await expectKeyboardFocus(workspace,`${label} navigation`);
   await designScreenshot(workspace,`CR017-F01-shell-${salon.slug}-${viewport.width}`);
  }
  await workspace.close();
 }
 // The platform status page and the application's not-found page: neutral theme, same handles.
 for(const viewport of DESIGN_WIDTHS){
  await page.setViewportSize(viewport);
  await page.goto(`${webUrl}/`);await expect(page.getByTestId('status-api-service')).toBeVisible();await referenceTypeAndPalette(page);
  for(const id of ['app-name','status-panel','status-heading','status-summary','status-api-version'])await expect(page.getByTestId(id),`status at ${viewport.width}: ${id}`).toBeVisible();
  const panel=await appearance(page.getByTestId('status-panel'));expect(panel.background).toBe('rgb(255, 255, 255)');expect(panel.radius,'status card radius').toBe('24px');expect(panel.shadow).toBe('none');
  await expect(page.getByTestId('status-heading')).toHaveCSS('font-size','22px');
  let inspected=await fit(page);expect(inspected.counted).toBeGreaterThan(0);expect(inspected.pageScrolls).toBe(false);expect(inspected.outside).toEqual([]);expect(inspected.clipped).toEqual([]);
  await designScreenshot(page,`CR016-F01-status-${viewport.width}`);
  const missing=await page.goto(`${salonUrl(alpha,webUrl)}/salon-site`);expect(missing!.status()).toBe(404);
  for(const id of ['not-found','not-found-title','not-found-body','not-found-home'])await expect(page.getByTestId(id),`not-found at ${viewport.width}: ${id}`).toBeVisible();
  await referenceTypeAndPalette(page);await expect(page.locator('body')).toHaveCSS('background-color','rgb(243, 240, 235)');
  for(const other of salons)expect(await page.content(),`not-found shows no salon`).not.toContain(other.brandColour);
  inspected=await fit(page);expect(inspected.counted).toBeGreaterThan(0);expect(inspected.pageScrolls).toBe(false);expect(inspected.outside).toEqual([]);expect(inspected.clipped).toEqual([]);
  await tabTo(page,page.getByTestId('not-found-home'),`not-found at ${viewport.width}`);await expectKeyboardFocus(page,`not-found at ${viewport.width}`);
  await designScreenshot(page,`CR016-F01-app-not-found-${viewport.width}`);
 }
 // The plain proxy pages: their status codes, no script, and the theme inlined from theme.css.
 const plain=async(label:string,url:string,host:string|undefined,status:number,testId:string)=>{
  const answer=await request.get(url,{headers:host?{Host:host}:{},maxRedirects:0});expect(answer.status(),`${label} status`).toBe(status);const body=await answer.text();
  expect(body,`${label} handle`).toContain(`data-testid="${testId}"`);expect(body.toLowerCase(),`${label} has no script`).not.toContain('<script');
  for(const token of ['--background: #F3F0EB','--foreground: #111827','--ring: #1D5FD0','--radius:'])expect(body,`${label} inlines the theme (${token})`).toContain(token);
  for(const other of salons)for(const value of [other.name,other.brandColour])expect(body,`${label} shows no salon`).not.toContain(value);
 };
 await plain('salon not found',`${webUrl}/`,`missing.${PLATFORM_DOMAIN}`,404,'salon-not-found');
 try{suspend('suspend');await plain('temporarily unavailable',`${salonUrl(gamma,webUrl)}/`,undefined,503,'salon-unavailable');}finally{suspend('activate');}
 await stopApi();
 try{await plain('service unavailable',`${salonUrl(beta,webUrl)}/workspace/sign-in`,undefined,503,'status-unavailable');}finally{await startApi();}
 const back=await request.get(`${salonUrl(beta,webUrl)}/workspace/sign-in`);expect(back.status(),'beta answers again after the API is back').toBe(200);
});
