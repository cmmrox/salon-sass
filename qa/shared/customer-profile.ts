import type { components } from '@contracts/schema';
import { Customers, type SignedCustomer } from './customer-identity';
import { json, type Actor, type Identity } from './identity';

export type Profile = components['schemas']['Profile'];
export type ProfileUpdate = components['schemas']['CustomerProfileUpdateRequest'];
export type DataCopy = components['schemas']['Export'];
export type Deletion = components['schemas']['AccountDeletionRequest'];

export class Profiles {
  readonly customers:Customers;
  constructor(readonly identity:Identity){this.customers=new Customers(identity);}
  read(actor:Actor):Promise<Profile>{return this.identity.call(actor.salon,'GET','/customers/me',undefined,actor.tokens).then(r=>json<Profile>(r,'Profile'));}
  static updateBody(profile:Profile,overrides:Partial<ProfileUpdate>={}):ProfileUpdate{
    return {version:profile.version,name:profile.name,email:profile.email,birthday:profile.birthday,
      preferredLanguage:profile.preferredLanguage,notificationPreferences:profile.notificationPreferences,
      marketingConsent:profile.marketingConsent,...overrides};
  }
  update(actor:Actor,data:ProfileUpdate):Promise<Profile>{return this.identity.call(actor.salon,'PUT','/customers/me',data,actor.tokens).then(r=>json<Profile>(r,'Profile'));}
  async saved(actor:SignedCustomer,name='QA Synthetic Customer'){
    const current=await this.read(actor);
    return this.update(actor,Profiles.updateBody(current,{name,email:'qa-synthetic@example.invalid',birthday:'2000-01-02',preferredLanguage:'en',
      notificationPreferences:{sms:true,whatsapp:false,email:true},marketingConsent:false}));
  }
  export(actor:Actor){return this.identity.call(actor.salon,'GET','/customers/me/export',undefined,actor.tokens);}
  delete(actor:Actor,data:Deletion={confirm:true}){return this.identity.call(actor.salon,'DELETE','/customers/me',data,actor.tokens);}
}
