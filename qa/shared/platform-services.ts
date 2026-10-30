import { ecsRecord } from './ecs-log.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { apiUrl, PRODUCT_ROOT } from './env';
import { type Salon } from './tenants';
import { ApiClient } from './api-client';
import { expect } from './fixtures';

export type Probe = {id:string;status:string;attempts:number;providerCalls:number;nextAttemptAt:string|null;lastError:string|null;createdAt:string;createdAtLocal:string;sentAt:string|null;audit:{action:string;occurredAt:string;actorKind:string;after:Record<string,unknown>;traceId:string}[]};
export const logs = () => fs.readFileSync(path.join(PRODUCT_ROOT,'.local/logs/api.log'),'utf8').split('\n').filter(Boolean).map(line=>ecsRecord(JSON.parse(line)));
export function sql(query:string, role='salon', strict=true):string {
  return execFileSync('docker',['exec','-i','salon-postgres','psql','-U',role,'-d',process.env.SALON_DB_NAME??'salon','-X','-qAt',...(strict?['-v','ON_ERROR_STOP=1']:[])],{input:query,encoding:'utf8'}).trim();
}
export const service=(action:string,name:string, env?:Record<string,string>) => execFileSync(path.join(PRODUCT_ROOT,'infra/scripts/local-service.sh'),[action,name],{cwd:PRODUCT_ROOT,env:{...process.env,...env},timeout:180000,stdio:'pipe'});
export async function clock(api:ApiClient,instant?:string){const r=await api.send(instant?'PUT':'DELETE','/local/clock',{headers:{'Content-Type':'application/json'},data:instant?JSON.stringify({instant}):undefined});expect(r.status()).toBe(200);return r.json();}
export async function create(api:ApiClient,salon:Salon,data:Record<string,unknown>={},headers:Record<string,string>={}):Promise<Probe>{const r=await api.send('POST','/local/probes',{headers:{Host:salon.hostname,'Content-Type':'application/json',...headers},data:JSON.stringify(data)});expect(r.status()).toBe(201);const p=await r.json();expect(p.id).toMatch(/^[0-9a-f-]{36}$/);return p;}
export async function read(api:ApiClient,salon:Salon,id:string):Promise<Probe>{const r=await api.getOn(salon.hostname,`/local/probes/${id}`);expect(r.status()).toBe(200);return r.json();}
export async function state(api:ApiClient,salon:Salon,id:string,target:string,timeout=5000):Promise<Probe>{let p:Probe;await expect.poll(async()=>{p=await read(api,salon,id);return p.status;},{timeout,intervals:[50,100,250]}).toBe(target);return p!;}
export async function isolatedFixture(api:ApiClient){service('stop','api');sql('DELETE FROM outbox_message; DELETE FROM audit_entry; DELETE FROM scheduled_job;');service('start','api');await clock(api);}
export async function meter(api:ApiClient,name:string){const r=await api.get(`/actuator/metrics/${name}`);expect(r.status()).toBe(200);return (await r.json()).measurements[0].value as number;}
export async function jumpDue(api:ApiClient,p:Probe){expect(p.nextAttemptAt).not.toBeNull();await clock(api,new Date(Date.parse(p.nextAttemptAt!)+2000).toISOString());}
export { apiUrl };
