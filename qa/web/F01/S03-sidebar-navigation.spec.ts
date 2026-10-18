/**
 * F01-S03 AC-15 (CR-017): the workspace navigation is the shadcn/ui Sidebar, a fixed rail from 900px
 * and, below 900px, its sheet opened from the header's menu button; the status page's reachable
 * summary is an Alert. Expected results come from the spec, docs/engineering/web.md "Styling" and
 * docs/design/ui-ux-standards.md, never from product code.
 */
import type {Page} from '@playwright/test';
import {test,expect,handle,routes} from '../../shared/identity-browser';
import {alpha,beta} from '../../shared/identity';
import {webUrl} from '../../shared/env';
import {appearance} from '../../shared/design-reference';
import {seriousViolations} from '../../shared/page-checks';

const FOCUS={style:'solid',width:'3px',colour:'rgb(29, 95, 208)'};
async function focusOutline(page:Page){return page.evaluate(()=>{const el=document.activeElement as HTMLElement;const s=getComputedStyle(el);return {id:el.getAttribute('data-testid'),visible:el.matches(':focus-visible'),style:s.outlineStyle,width:s.outlineWidth,colour:s.outlineColor};});}
const sheetOf=(page:Page)=>page.getByRole('dialog',{name:'Staff workspace'});
/** Waits for the sheet's open or close animation, so a measurement or a click meets the settled sheet. */
const settled=(page:Page)=>page.evaluate(()=>Promise.all(document.getAnimations().map(a=>a.finished.catch(()=>undefined))));

test('TC-F01-S03-017 the Sidebar is a rail from 900px and a keyboard-operable sheet below it, showing only permitted routes',async({identity,accounts})=>{
 test.setTimeout(180_000);
 const manager=await identity.staff(beta,'MANAGER'),context=await accounts.context(beta,[manager]);
 for(const width of [360,768]){
  const page=await accounts.open(context,routes.staff);await page.setViewportSize({width,height:900});await accounts.ready(page);
  const menu=page.getByTestId('workspace-menu');
  // Screen reader: a named button that says whether the menu is open; no navigation and no tab bar until it is.
  await expect(menu).toHaveAccessibleName('Menu');await expect(menu).toHaveAttribute('aria-expanded','false');
  await expect(page.getByTestId('workspace-navigation')).toHaveCount(0);await expect(page.getByRole('navigation',{name:'Staff workspace'})).toHaveCount(0);
  // Keyboard: the menu button is the first Tab stop, drawn with the prototype's outline, and Enter opens the sheet.
  await page.locator('body').click({position:{x:1,y:1}});await page.keyboard.press('Tab');await expect(menu).toBeFocused();
  expect(await focusOutline(page),`${width}: menu button focus`).toMatchObject({visible:true,...FOCUS});
  await page.keyboard.press('Enter');const sheet=sheetOf(page);await expect(sheet).toBeVisible();await expect(menu).toHaveAttribute('aria-expanded','true');
  await expect(sheet).toHaveAccessibleDescription('Choose a page of the staff workspace.');
  const nav=sheet.getByRole('navigation',{name:'Staff workspace'});await expect(nav).toBeVisible();
  const current=nav.getByRole('link',{name:'Account and security'});await expect(current).toHaveAttribute('aria-current','page');await expect(current).toBeFocused();
  expect(await focusOutline(page),`${width}: current route focus`).toMatchObject({visible:true,...FOCUS});
  expect(await seriousViolations(page),`${width}: open sheet accessibility`).toEqual([]);
  // Focus stays in the sheet however far the keyboard goes, both ways.
  for(const key of ['Tab','Tab','Tab','Tab','Shift+Tab','Shift+Tab','Shift+Tab','Shift+Tab','Shift+Tab'])
   {await page.keyboard.press(key);expect(await sheet.evaluate(el=>el.contains(document.activeElement)),`${width}: ${key} stays in the sheet`).toBe(true);}
  // Escape closes it and returns focus to the menu button.
  await page.keyboard.press('Escape');await expect(sheet).toHaveCount(0);await expect(menu).toBeFocused();await expect(menu).toHaveAttribute('aria-expanded','false');
  // The backdrop closes it and returns focus to the menu button.
  // Radix registers its outside-pointer listener one event-loop turn after the sheet takes focus; a person never clicks sooner.
  await menu.click();await expect(sheet).toBeVisible();await expect(sheet.locator('[aria-current="page"]')).toBeFocused();await page.evaluate(()=>new Promise(resolve=>setTimeout(resolve,0)));await settled(page);const box=await appearance(sheet);await page.mouse.click(Math.min(box.right+20,width-5),Math.round(page.viewportSize()!.height/2));
  await expect(sheet).toHaveCount(0);await expect(menu).toBeFocused();
  // Choosing a route from the keyboard navigates there, closes the sheet and leaves focus on the menu button.
  await page.keyboard.press('Enter');await expect(sheet).toBeVisible();
  const audit=sheet.getByTestId('workspace-audit');await audit.focus();await page.keyboard.press('Enter');
  await page.waitForURL('**'+routes.audit);await expect(sheetOf(page)).toHaveCount(0);
  const menuAfter=page.getByTestId('workspace-menu').filter({visible:true});await expect(menuAfter).toBeFocused();await expect(menuAfter).toHaveAttribute('aria-expanded','false');
  await menuAfter.click();await expect(sheetOf(page).getByTestId('workspace-audit')).toHaveAttribute('aria-current','page');await page.keyboard.press('Escape');
  await page.close();
 }
 // From 900px: no menu button, the rail is on the page and keyboard-reachable, and Ctrl/Cmd+B does not collapse it.
 for(const width of [899,900,1280]){
  const page=await accounts.open(context,routes.staff);await page.setViewportSize({width,height:900});await accounts.ready(page);
  if(width<900){await expect(page.getByTestId('workspace-menu')).toBeVisible();await expect(page.getByTestId('workspace-navigation')).toHaveCount(0);await page.close();continue;}
  await expect(page.getByTestId('workspace-menu')).toBeHidden();
  const nav=page.getByRole('navigation',{name:'Staff workspace'});await expect(nav).toBeVisible();
  await page.locator('body').click({position:{x:1,y:1}});const order:string[]=[];
  for(let i=0;i<5;i++){await page.keyboard.press('Tab');order.push(await page.evaluate(()=>document.activeElement?.getAttribute('data-testid')??''));}
  expect(order.slice(1,4),`${width}: the rail's routes in order after the salon name`).toEqual(['workspace-account','workspace-staff','workspace-audit']);
  for(const combo of ['Control+b','Meta+b']){await page.keyboard.press(combo);await expect(nav,`${width}: ${combo} leaves the rail`).toBeVisible();}
  await settled(page);// the rail's width transition after the resize
  const rail=await nav.evaluate(el=>el.closest('[data-slot="sidebar-container"]')!.getBoundingClientRect().width);expect(rail).toBe(width<1200?200:236);
  await page.close();
 }
 // Only permitted routes, in the rail and in the sheet: a Receptionist has the account page alone.
 const receptionist=await identity.staff(alpha,'RECEPTIONIST'),limited=await accounts.context(alpha,[receptionist]);
 for(const width of [390,1280]){
  const page=await accounts.open(limited,routes.staff);await page.setViewportSize({width,height:900});await accounts.ready(page);
  if(width<900){await page.getByTestId('workspace-menu').click();await expect(sheetOf(page)).toBeVisible();}
  const nav=page.getByTestId('workspace-navigation');await expect(nav).toBeVisible();
  await expect(nav.getByRole('link')).toHaveCount(1);await expect(nav.getByTestId('workspace-account')).toBeVisible();
  await expect(page.getByTestId('workspace-staff')).toHaveCount(0);await expect(page.getByTestId('workspace-audit')).toHaveCount(0);
  await page.close();
 }
});

test('TC-F01-S03-017 the status page reports a reachable service in a positive Alert',async({page})=>{
 await page.goto(`${webUrl}/`);const summary=page.getByTestId('status-summary');await expect(summary).toBeVisible();
 await expect(summary).toHaveAttribute('data-slot','alert');await expect(summary).toHaveAttribute('role','status');
 const s=await appearance(summary);expect(s.background,'the positive surface').toBe('rgb(221, 241, 230)');expect(s.radius).toBe('20px');
 // Good news is not announced as an alert (the framework's own route announcer lies outside main).
 await expect(page.getByRole('main').getByRole('alert')).toHaveCount(0);
});
