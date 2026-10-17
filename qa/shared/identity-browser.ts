/** Centralized S04 draft map. Confirm against the final handoff before product execution. */
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { test as apiTest, expect, totp, json, type Principal, type Identity, type Actor, type SeedAccount, type Tokens } from './identity';
import { Customers } from './customer-identity';
import { Profiles } from './customer-profile';
import { webUrl } from './env';
import { salonUrl, type Salon } from './tenants';

export type Kind='staff'|'customer';
export const routes={staffSignIn:'/workspace/sign-in',staff:'/workspace/account',staffList:'/workspace/staff',audit:'/workspace/audit'};
export const bff=(kind:Kind,action:string)=>`/identity-web/${kind}/${action}`;
export const cookie=(kind:Kind,name:string)=>`salon_${kind}_${name}`;
const finalHandle=(name:string)=>({oldPassword:'old-password',newPassword:'new-password',marketingConsent:'marketing-consent'} as Record<string,string>)[name]??name;
export const handle=(page:Page,name:string)=>page.getByTestId(`account-${finalHandle(name)}`);
export const mutations={staff:['sign-in','mfa-start','mfa-enroll','mfa-verify','password','logout','revoke','revoke-all','staff-create','staff-change','staff-remove','refresh','clear'],
  customer:['code','sign-in','logout','revoke','revoke-all','profile','export','delete','refresh','clear']} as const;
export const screenMap=[routes.staffSignIn,routes.staff,routes.staffList,routes.audit];

export class AccountBrowser {
  private readonly contexts:BrowserContext[]=[];
  readonly customers:Customers;readonly profiles:Profiles;
  constructor(readonly browser:Browser,readonly identity:Identity){this.customers=new Customers(identity);this.profiles=new Profiles(identity);}
  async context(salon:Salon,actors:Actor[]=[],options:{width?:number;unsupported?:boolean;locale?:string}={}){
    const context=await this.browser.newContext({baseURL:salonUrl(salon,webUrl),viewport:{width:options.width??1280,height:1000},locale:options.locale??'en'});
    this.contexts.push(context);
    if(options.unsupported)await context.addInitScript(()=>Object.defineProperty(navigator,'locks',{value:undefined,configurable:true}));
    for(const actor of actors)await this.arrange(context,salon,actor);
    return context;
  }
  async arrange(context:BrowserContext,salon:Salon,actor:Actor){
    // Authority is real API-issued, never manufactured; only arrangement uses the cookie bridge.
    const values={access:actor.tokens.accessToken,refresh:actor.tokens.refreshToken,expires:actor.tokens.accessExpiresAt,session:actor.tokens.sessionId};
    await context.addCookies(Object.entries(values).map(([name,value])=>({name:cookie(actor.principal.kind as Kind,name),value,url:salonUrl(salon,webUrl)+'/',httpOnly:true,sameSite:'Lax' as const,secure:false})));
  }
  async action(context:BrowserContext,salon:Salon,kind:Kind,action:string,body?:unknown,headers:Record<string,string>={}){
    return context.request.fetch(salonUrl(salon,webUrl)+bff(kind,action),{method:body===undefined?'GET':'POST',
      headers:{...(body===undefined?{}:{Origin:salonUrl(salon,webUrl),'Content-Type':'application/json'}),...headers},
      data:body===undefined?undefined:JSON.stringify(body),maxRedirects:0});
  }
  async principal(context:BrowserContext,salon:Salon,kind:Kind):Promise<Principal>{return json<Principal>(await this.action(context,salon,kind,'principal'),'Principal');}
  async tokens(context:BrowserContext,salon:Salon,kind:Kind):Promise<Tokens>{
    const cookies=await context.cookies(salonUrl(salon,webUrl));const value=(name:string)=>cookies.find(c=>c.name===cookie(kind,name))?.value??'';
    expect(Boolean(value('access')&&value('refresh')&&value('session')),'browser has issued authority').toBe(true);
    return {state:'authenticated',accessToken:value('access'),refreshToken:value('refresh'),sessionId:value('session'),accessExpiresAt:value('expires'),refreshExpiresAt:value('expires')};
  }
  async actor(context:BrowserContext,salon:Salon,kind:Kind):Promise<Actor>{return {salon,tokens:await this.tokens(context,salon,kind),principal:await this.identity.principal(salon,await this.tokens(context,salon,kind))};}
  async open(context:BrowserContext,path:string){const page=await context.newPage();await page.goto(path);return page;}
  /** Signed-in controls shown and nothing pending: the page frame (`loading`) and, since CR-013, the in-section lists (`list-loading`). */
  async ready(page:Page){await expect(handle(page,'logout')).toBeVisible();await expect(handle(page,'loading')).toHaveCount(0);await expect(handle(page,'list-loading')).toHaveCount(0);}
  async staffSignIn(page:Page,salon:Salon,account:SeedAccount,identifier=account.email){
    await page.goto(routes.staffSignIn);await handle(page,'identifier').fill(identifier);await handle(page,'password').fill(account.password);
    let enrollmentStatus:number|undefined;const enrollmentResponse=(response:{url:()=>string;status:()=>number})=>{if(new URL(response.url()).pathname===bff('staff','mfa-enroll'))enrollmentStatus=response.status();};page.on('response',enrollmentResponse);
    try{
      await expect(handle(page,'sign-in-submit')).toBeEnabled();await handle(page,'sign-in-submit').click();
      await expect.poll(async()=>new URL(page.url()).pathname===routes.staff||await handle(page,'code').isVisible()).toBe(true);
      const outcome=new URL(page.url()).pathname===routes.staff?'authenticated':'mfa';
      if(outcome==='mfa'){
        if(!account.totpSecret){await expect.poll(()=>enrollmentStatus,'real enrollment response succeeds before setup is read').toBe(200);await expect(handle(page,'setup-key'),'completed authenticator setup value').toHaveText(/^[A-Z2-7]{32}$/);}
        const secret=account.totpSecret??await handle(page,'setup-key').textContent();if(!secret)throw new Error('ENVIRONMENT: authenticator setup is absent');
        await handle(page,'code').fill(totp(secret,await this.identity.now()));await handle(page,'sign-in-submit').click();await page.waitForURL('**'+routes.staff);
      }
      await this.ready(page);return this.actor(page.context(),salon,'staff');
    }finally{page.off('response',enrollmentResponse);}
  }
  async confirm(page:Page,action:string){await handle(page,action).click();await expect(handle(page,'confirm-action')).toBeVisible();await handle(page,'confirm-action').click();}
  async close(){for(const context of this.contexts)await context.close();}
}
export const test=apiTest.extend<{accounts:AccountBrowser}>({accounts:async({browser,identity},use)=>{
  const accounts=new AccountBrowser(browser,identity);try{await use(accounts);}finally{await accounts.close();}
}});
export {expect};
