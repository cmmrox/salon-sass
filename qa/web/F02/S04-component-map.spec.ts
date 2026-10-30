/**
 * F02-S04 AC-14 (CR-017): the sign-in and account screens use the shadcn/ui component the component
 * map of docs/engineering/web.md, "Styling", names for each job, keeping test ids, translations and
 * keyboard and screen-reader behaviour. Expected results come from the spec and web.md, never from
 * product code; shadcn/ui components mark their rendered root with `data-slot`.
 */
import type {Locator,Page} from '@playwright/test';
import {test,expect,handle,routes} from '../../shared/identity-browser';
import {alpha,beta,syntheticAccount} from '../../shared/identity';
import {appearance} from '../../shared/design-reference';
const c=(n:number)=>`TC-F02-S04-${String(n).padStart(3,'0')}`;

const slot=(element:Locator,name:string)=>expect(element,`is the shadcn/ui ${name}`).toHaveAttribute('data-slot',name);
const ancestorSlot=(element:Locator)=>element.evaluate(el=>Array.from({length:6}).reduce<{node:Element|null;slots:string[]}>(acc=>{const p=acc.node?.parentElement??null;return {node:p,slots:p?.getAttribute('data-slot')?[...acc.slots,p.getAttribute('data-slot')!]:acc.slots};},{node:el,slots:[]}).slots);
async function focusOutline(page:Page){return page.evaluate(()=>{const el=document.activeElement as HTMLElement;const s=getComputedStyle(el);return {visible:el.matches(':focus-visible'),outline:`${s.outlineStyle} ${s.outlineWidth} ${s.outlineColor}`};});}
const OUTLINE='solid 3px rgb(29, 95, 208)';

test(`${c(31)} sign-in and account screens use the shadcn/ui component for every job, operable by keyboard and screen reader`,async({identity,accounts})=>{
 test.setTimeout(180000);
 // Sign-in: Field, Input Group with the visibility toggle, the refusal Alert, the busy Spinner.
 const signedOut=await accounts.context(beta);let page=await accounts.open(signedOut,routes.staffSignIn);await expect(handle(page,'sign-in-submit')).toBeEnabled();
 await slot(handle(page,'identifier'),'input');expect(await ancestorSlot(handle(page,'identifier'))).toContain('field');
 await slot(handle(page,'password'),'input-group-control');expect(await ancestorSlot(handle(page,'password'))).toEqual(expect.arrayContaining(['input-group','field']));
 await expect(handle(page,'identifier')).toHaveAccessibleName(/./);await expect(handle(page,'password')).toHaveAccessibleName(/./);
 const toggle=handle(page,'password-toggle');expect(await ancestorSlot(toggle)).toContain('input-group-addon');await expect(toggle).toHaveAttribute('aria-pressed','false');
 await handle(page,'password').fill('Synthetic sample');await handle(page,'password').press('Tab');await expect(toggle).toBeFocused();expect((await focusOutline(page)).outline).toBe(OUTLINE);
 await page.keyboard.press('Enter');await expect(handle(page,'password')).toHaveAttribute('type','text');await expect(toggle).toHaveAttribute('aria-pressed','true');await page.keyboard.press('Enter');await expect(handle(page,'password')).toHaveAttribute('type','password');
 const wrong=syntheticAccount('MANAGER',false);await identity.seed(beta,[wrong]);await handle(page,'identifier').fill(wrong.email);await handle(page,'password').fill('Wrong synthetic password');
 // Hold the sign-in answer briefly so the busy state can be read, then let it through unchanged.
 let release:()=>void=()=>{};const held=new Promise<void>(resolve=>{release=resolve;});
 await page.route('**/identity-web/staff/sign-in',async route=>{await held;await route.continue();});
 await handle(page,'sign-in-submit').click();const spinner=handle(page,'sign-in-submit').locator('[data-slot="spinner"]');await expect(spinner,'a busy sign-in shows the Spinner').toBeVisible();
 await expect(handle(page,'sign-in-submit')).toHaveAttribute('aria-busy','true');const answered=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith('/identity-web/staff/sign-in'));release();await answered;await page.unroute('**/identity-web/staff/sign-in');
 await expect(handle(page,'error')).toBeVisible();await slot(handle(page,'error'),'alert');await expect(handle(page,'error')).toHaveAttribute('role','alert');await expect(spinner).toHaveCount(0);
 await page.close();
 // MFA challenge: the code is an Input OTP of six slots, digits only, named for screen readers.
 const mfa=syntheticAccount('OWNER',true);await identity.seed(beta,[mfa]);page=await accounts.open(signedOut,routes.staffSignIn);
 await handle(page,'identifier').fill(mfa.email);await handle(page,'password').fill(mfa.password);await handle(page,'sign-in-submit').click();
 const code=handle(page,'code');await expect(code).toBeVisible();await expect(code).toHaveAccessibleName(/./);await expect(code).toHaveAttribute('maxlength','6');
 const otp=page.locator('[data-slot="input-otp-slot"]');await expect(otp).toHaveCount(6);
 await code.focus();await page.keyboard.type('12ab34');await expect(code,'letters are refused').toHaveValue('1234');await page.keyboard.type('5678');await expect(code,'never more than six digits').toHaveValue('123456');
 for(const box of await otp.all()){const s=await appearance(box);expect(s.height).toBeGreaterThanOrEqual(44);}
 await page.close();
 // Account: Input Group toggles on both password fields, the role Badge, device Items with the current-device Badge, Pagination.
 await identity.staff(beta,'PROFESSIONAL');
 const owner=await identity.staff(beta,'OWNER'),signedIn=await accounts.context(beta,[owner]);page=await accounts.open(signedIn,routes.staff);await accounts.ready(page);
 for(const name of ['oldPassword','newPassword']){await slot(handle(page,name),'input-group-control');await expect(handle(page,name)).toHaveAccessibleName(/./);}
 for(const id of ['account-old-password-toggle','account-new-password-toggle']){const t=page.getByTestId(id);await expect(t).toHaveAccessibleName(/./);await expect(t).toHaveAttribute('aria-pressed','false');await t.click();await expect(t).toHaveAttribute('aria-pressed','true');await t.click();}
 await slot(handle(page,'role'),'badge');await slot(page.getByTestId('account-devices'),'item-group');
 const device=handle(page,'device').first();await slot(device,'item');await expect(device).toHaveAttribute('role','listitem');await slot(page.getByTestId('account-device-current'),'badge');
 const pager=page.getByTestId('account-pagination');await slot(pager,'pagination');await expect(pager).toHaveAccessibleName('Pages');await expect(handle(page,'page')).toHaveAttribute('aria-current',/.+/);
 await slot(handle(page,'mfa-enabled'),'alert');
 await page.close();
 // Staff: Items and Badges, the Professional grant as a horizontal Field, the Separator, and the role Select from the keyboard.
 page=await accounts.open(signedIn,routes.staffList);await accounts.ready(page);
 await slot(page.getByTestId('account-staff-list'),'item-group');const row=handle(page,'staff-row').first();await slot(row,'item');
 await slot(row.getByTestId('account-staff-role-label').or(row.getByTestId('account-staff-state')).first(),'badge');
 await slot(page.getByTestId('account-staff-separator'),'separator');
 const grant=page.getByTestId('account-professional-grant').first();await slot(grant,'checkbox');await expect(grant).toHaveRole('checkbox');await expect(grant).toHaveAccessibleName(/./);
 const role=handle(page,'staff-new-role');await slot(role,'select-trigger');await expect(role).toHaveRole('combobox');await expect(role).toHaveAccessibleName('Role');await expect(role).toHaveAttribute('aria-expanded','false');
 const before=(await role.innerText()).trim();
 // Enter opens the list; Escape closes it without a change and returns focus to the trigger.
 await role.focus();expect((await focusOutline(page)).outline).toBe(OUTLINE);await page.keyboard.press('Enter');
 const list=page.getByRole('listbox');await expect(list).toBeVisible();await expect(role).toHaveAttribute('aria-expanded','true');
 await list.evaluate(el=>Promise.all(el.getAnimations({subtree:true}).map(a=>a.finished.catch(()=>undefined))));// the opening zoom scales the list until it settles
 const options=list.getByRole('option');expect(await options.count()).toBeGreaterThanOrEqual(5);for(const option of await options.all()){await expect(option).toHaveAccessibleName(/./);expect((await appearance(option)).height).toBeGreaterThanOrEqual(44);}
 await page.keyboard.press('Escape');await expect(list).toHaveCount(0);await expect(role).toBeFocused();expect((await role.innerText()).trim()).toBe(before);
 // Enter, ArrowDown, Enter chooses the next option and focus returns to the trigger, which names the choice.
 await page.keyboard.press('Enter');await expect(list).toBeVisible();const highlighted=async()=>(await list.locator('[data-highlighted]').innerText()).trim();
 await expect.poll(highlighted).not.toBe('');const first=await highlighted();await page.keyboard.press('ArrowDown');await expect.poll(highlighted).not.toBe(first);const chosen=await highlighted();
 await page.keyboard.press('Enter');await expect(list).toHaveCount(0);await expect(role).toBeFocused();await expect(role).toHaveText(chosen);await expect(role).toHaveAccessibleName('Role');
 expect(await page.locator('select:not([aria-hidden="true"] select)').evaluateAll(els=>els.filter(el=>!el.closest('[aria-hidden="true"]')).length),'no visible native select').toBe(0);
 await page.close();
 // Denied: a Receptionist on the staff page gets the denied Alert.
 const receptionist=await identity.staff(alpha,'RECEPTIONIST'),limited=await accounts.context(alpha,[receptionist]);page=await accounts.open(limited,routes.staffList);
 await expect(handle(page,'denied')).toBeVisible();await slot(handle(page,'denied'),'alert');await expect(handle(page,'denied')).toHaveAttribute('role','alert');await expect(handle(page,'staff-row')).toHaveCount(0);
 await page.close();
});
