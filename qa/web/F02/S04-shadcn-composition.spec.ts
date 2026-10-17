/**
 * F02-S04 AC-12 (CR-016): the staff screens and the confirmation overlay are built from shadcn/ui
 * components, with no hand-built look-alike, and the overlay keeps its phone bottom sheet and
 * desktop right drawer, initial focus on Cancel, focus containment, Escape and backdrop dismissal
 * and focus return. Expected results come from the spec; shadcn/ui components mark their rendered
 * root with `data-slot` (the component's name), which is how a user-facing build is identified here.
 */
import fs from 'node:fs';
import path from 'node:path';
import type {Locator,Page} from '@playwright/test';
import {test,expect,handle,routes,screenMap} from '../../shared/identity-browser';
import {alpha} from '../../shared/identity';
import {reportDir} from '../../shared/env';
import {appearance} from '../../shared/design-reference';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;

/** The shadcn/ui components each screen is composed of (AC-12's examples, as each screen uses them). */
const COMPOSITION:Record<string,string[]>={
 [routes.staffSignIn]:['card','avatar','label','input','button'],
 [routes.staff]:['card','label','input','button'],
 [routes.staffList]:['card','label','input','native-select','checkbox','button'],
 [routes.audit]:['card','button'],
};
/** Native or role-built controls a user can press or type into, which must each be a shadcn/ui component. */
const CONTROLS='button,input,select,textarea,dialog,table,[role="checkbox"],[role="dialog"],[role="switch"]';

async function composition(page:Page){
 return page.evaluate(controls=>({slots:[...new Set(Array.from(document.querySelectorAll('[data-slot]')).map(el=>el.getAttribute('data-slot')!))],
  controls:Array.from(document.querySelectorAll(controls)).length,
  bare:Array.from(document.querySelectorAll(controls)).filter(el=>!el.closest('[data-slot]')).map(el=>`${el.tagName.toLowerCase()}#${el.getAttribute('data-testid')??el.getAttribute('role')??''}`),
  nativeCheckboxes:document.querySelectorAll('input[type="checkbox"]').length,nativeDialogs:document.querySelectorAll('dialog').length}),CONTROLS);
}
/** When the overlay leaves the document, when focus reaches the opener, and where focus sat in between. */
async function watchFocusReturn(opener:Locator){
 await opener.evaluate(el=>{type Watch={removedAt?:number;returnedAt?:number;between:string[]};const w=window as unknown as {qaFocus:Watch};const watch:Watch={between:[]};w.qaFocus=watch;
  el.addEventListener('focus',()=>{watch.returnedAt??=performance.now();},{once:true});
  const observer=new MutationObserver(()=>{if(watch.removedAt===undefined&&!document.querySelector('[data-slot="sheet-content"]')){watch.removedAt=performance.now();observer.disconnect();}});
  observer.observe(document.body,{childList:true,subtree:true});
  const sample=()=>{if(watch.returnedAt!==undefined)return;if(watch.removedAt!==undefined)watch.between.push(document.activeElement?.tagName.toLowerCase()??'none');requestAnimationFrame(sample);};requestAnimationFrame(sample);});
}
async function focusReturn(page:Page){
 const read=()=>page.evaluate(()=>{const f=(window as unknown as {qaFocus:{removedAt?:number;returnedAt?:number;between:string[]}}).qaFocus;return {removed:f.removedAt!==undefined,ms:f.returnedAt!==undefined&&f.removedAt!==undefined?Math.round(f.returnedAt-f.removedAt):null,between:[...new Set(f.between)]};});
 await expect.poll(async()=>(await read()).ms!==null,'the overlay removal and the focus return were both observed').toBe(true);
 return read();
}

test(`${c(29)} staff screens and the confirmation overlay are shadcn/ui components with the overlay's dismissal and focus behaviour`,async({identity,accounts})=>{
 test.setTimeout(180000);
 // A Professional in the salon gives the Owner's staff list its grant checkbox.
 await identity.staff(alpha,'PROFESSIONAL');
 const owner=await identity.staff(alpha,'OWNER'),signedIn=await accounts.context(alpha,[owner]),signedOut=await accounts.context(alpha);
 for(const route of screenMap){
  const page=await accounts.open(route===routes.staffSignIn?signedOut:signedIn,route);
  if(route===routes.staffSignIn)await expect(handle(page,'sign-in-submit')).toBeVisible();else await accounts.ready(page);
  const found=await composition(page);
  expect(found.controls,`${route}: there are controls to inspect`).toBeGreaterThan(0);
  for(const slot of COMPOSITION[route]!)expect(found.slots,`${route}: built with the shadcn/ui ${slot}`).toContain(slot);
  expect(found.bare,`${route}: every control is a shadcn/ui component`).toEqual([]);
  expect(found.nativeCheckboxes,`${route}: no native checkbox`).toBe(0);expect(found.nativeDialogs,`${route}: no native dialog`).toBe(0);
  if(route===routes.staffSignIn){
   // The refused sign-in's message is the shadcn/ui Alert, announced.
   await handle(page,'identifier').fill(owner.account.email);await handle(page,'password').fill('Wrong synthetic password');await handle(page,'sign-in-submit').click();
   await expect(handle(page,'error')).toHaveAttribute('role','alert');await expect(handle(page,'error')).toHaveAttribute('data-slot','alert');
  }
  if(route===routes.staffList){
   const checkbox=page.getByRole('checkbox').first();await expect(checkbox).toBeVisible();await expect(checkbox).toHaveAttribute('data-slot','checkbox');
   const name=(await checkbox.evaluate(el=>el.closest('label')?.textContent??'')).trim();expect(name.length,'the checkbox is named by its label').toBeGreaterThan(0);await expect(checkbox).toHaveAccessibleName(name);
   const target=await checkbox.evaluate(el=>{const r=el.closest('label')!.getBoundingClientRect();return {height:r.height,width:r.width};});expect(target.height,'the checkbox label is its 44px target').toBeGreaterThanOrEqual(44);expect(target.width).toBeGreaterThanOrEqual(44);
  }
  await page.close();
 }
 // The confirmation overlay: a bottom sheet on a phone, a full-height right drawer on a desktop.
 const timings:unknown[]=[];
 for(const width of [390,1280]){
  const page=await accounts.open(signedIn,routes.staff);await page.setViewportSize({width,height:900});await accounts.ready(page);
  const opener=handle(page,'revoke-all');
  for(const dismissal of ['escape','backdrop','cancel'] as const){
   await opener.click();const sheet=page.locator('[data-slot="sheet-content"]');await expect(sheet).toBeVisible();await expect(sheet).toHaveAttribute('role','dialog');
   await expect(page.locator('[data-slot="sheet-overlay"]')).toBeVisible();
   const box=await appearance(sheet);
   if(width>=900){expect(box.width,'desktop drawer width').toBe(460);expect(box.right).toBe(width);expect(box.height).toBe(900);}
   else{expect(box.bottom,'phone sheet sits on the bottom edge').toBe(900);expect(box.width).toBe(width);expect(box.height).toBeLessThanOrEqual(900*.9);}
   await expect(handle(page,'cancel-action'),`${width} ${dismissal}: initial focus on Cancel`).toBeFocused();
   // Focus stays inside the overlay however far a keyboard user tabs, both ways.
   for(const key of ['Tab','Tab','Tab','Shift+Tab','Shift+Tab','Shift+Tab']){await page.keyboard.press(key);expect(await sheet.evaluate(el=>el.contains(document.activeElement)),`${width} ${dismissal}: ${key} keeps focus in the overlay`).toBe(true);}
   await handle(page,'cancel-action').focus();
   await watchFocusReturn(opener);
   if(dismissal==='escape')await page.keyboard.press('Escape');
   else if(dismissal==='backdrop'){const point=width>=900?{x:20,y:450}:{x:Math.round(width/2),y:12};await page.mouse.click(point.x,point.y);}
   else await handle(page,'cancel-action').click();
   await expect(sheet,`${width} ${dismissal}: the overlay closes`).toHaveCount(0);
   await expect(opener,`${width} ${dismissal}: focus returns to the opener`).toBeFocused();
   timings.push({width,dismissal,...await focusReturn(page)});
   await expect(handle(page,'logout'),`${width} ${dismissal}: nothing was confirmed`).toBeVisible();
  }
  await page.close();
 }
 fs.mkdirSync(reportDir,{recursive:true});fs.writeFileSync(path.join(reportDir,'CR016-focus-return.json'),JSON.stringify(timings,null,2));
});
