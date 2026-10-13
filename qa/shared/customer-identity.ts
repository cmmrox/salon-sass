/** S02's real code flow. Cookie/code material stays in memory and outside run artifacts. */
import { randomInt } from 'node:crypto';
import type { APIResponse } from '@playwright/test';
import type { components } from '@contracts/schema';
import { Identity, json, assertTokens, expect, type Actor, type Tokens, type Principal } from './identity';
import { type Salon } from './tenants';

export type CustomerChallenge = components['schemas']['Challenge'];
export type CodeRequest = components['schemas']['CodeRequest'];
// Expected shape from S02's final spec. The integrated contract currently collides with staff
// VerifyRequest; use its generated subset until the developer publishes a uniquely named DTO.
export type CustomerProof = Pick<components['schemas']['VerifyRequest'],'challengeId'|'code'>;
export type Capture = {challengeId:string;channel:string;code:string;attempt:number;deliveredAt:string};
export type Delivery = {id:string;channel:string;status:string;attempts:number;nextAttemptAt:string|null;leaseUntil:string|null;lastError:string|null;createdAt:string;sentAt:string|null};
export type ChallengeDevice = {challenge:CustomerChallenge;device:string;mobile:string;channel:'sms'|'whatsapp'};
export type SignedCustomer = Actor & ChallengeDevice;
export const syntheticMobile = ()=>`077${randomInt(1000000,10000000)}`;

export class Customers {
  constructor(readonly identity:Identity){}
  request(salon:Salon, data:CodeRequest, device='', headers:Record<string,string>={}) {
    return this.identity.call(salon,'POST','/customers/challenges',data,undefined,{
      Cookie:device?`salon_device=${device}`:'',...headers,
    });
  }
  async challenge(salon:Salon,mobile=syntheticMobile(),channel:'sms'|'whatsapp'='sms',device=''):Promise<ChallengeDevice>{
    const response=await this.request(salon,{mobile,channel,deviceLabel:'QA customer device'},device);
    const challenge=await json<CustomerChallenge>(response,'Challenge');
    expect(Object.keys(challenge).sort()).toEqual(['challengeId','expiresAt']);
    expect(challenge.challengeId).toMatch(/^[0-9a-f-]{36}$/);
    const cookie=response.headers()['set-cookie'];
    if(cookie){
      expect(/HttpOnly/i.test(cookie)).toBe(true);expect(/SameSite=Lax/i.test(cookie)).toBe(true);
      expect(/Path=\//i.test(cookie)).toBe(true);expect(/Domain=/i.test(cookie)).toBe(false);
      expect(/Max-Age=31536000/i.test(cookie)).toBe(true);
      device=cookie.split(';')[0].slice('salon_device='.length);
    }
    if(!device)throw new Error('ENVIRONMENT: accepted code request did not supply an issuing device');
    return {challenge,device,mobile,channel};
  }
  verify(salon:Salon,proof:CustomerProof,device='',headers:Record<string,string>={}):Promise<APIResponse>{
    return this.identity.call(salon,'POST','/customers/sign-in',proof,undefined,{
      Cookie:device?`salon_device=${device}`:'',...headers,
    });
  }
  async capture(salon:Salon,id:string):Promise<Capture>{
    let captured:Capture|undefined;
    await expect.poll(async()=>{
      const response=await this.identity.call(salon,'GET',`/local/identity/captures/${id}`);
      if(response.status()===200)captured=await response.json() as Capture;
      return response.status();
    },{timeout:5000,intervals:[25,50,100,250]}).toBe(200);
    expect(captured?.challengeId).toBe(id);expect(Boolean(captured?.code.match(/^\d{6}$/)),'six-digit captured proof').toBe(true);
    return captured!;
  }
  async delivery(salon:Salon,id:string):Promise<Delivery>{
    const r=await this.identity.call(salon,'GET',`/local/identity/deliveries/${id}`);expect(r.status()).toBe(200);return r.json();
  }
  async control(salon:Salon,channel:'sms'|'whatsapp',failures=0,delayMs=0){
    expect((await this.identity.call(salon,'PUT',`/local/identity/providers/${channel}`,{failures,delayMs})).status()).toBe(204);
  }
  async reset(){for(const salon of (await import('./tenants')).salons)expect((await this.identity.call(salon,'DELETE','/local/identity/customers')).status()).toBe(204);}
  async login(salon:Salon,mobile=syntheticMobile(),channel:'sms'|'whatsapp'='sms',device=''):Promise<SignedCustomer>{
    const issued=await this.challenge(salon,mobile,channel,device);const capture=await this.capture(salon,issued.challenge.challengeId);
    expect(capture.channel).toBe(channel);
    const tokens=await json<Tokens>(await this.verify(salon,{challengeId:issued.challenge.challengeId,code:capture.code},issued.device),'Tokens');assertTokens(tokens);
    const principal=await this.identity.principal(salon,tokens);
    expect(principal.kind).toBe('customer');expect(principal.role).toBeNull();expect(principal.staffId).toBeNull();expect(principal.scope).toBe('own');expect(principal.mfaEnabled).toBe(false);
    return {salon,tokens,principal,...issued};
  }
}
