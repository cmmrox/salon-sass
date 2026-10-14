/** Controls the existing local scripts only, when registered feature QA is dispatched. */
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { REPO_ROOT, reportDir, apiUrl } from './env';
import type {ApiClient} from './api-client';
export const productRoot = process.env.QA_PRODUCT_ROOT ?? REPO_ROOT;
export const apiLog = path.join(productRoot,'.local/logs/api.log');
export function localService(action:string,service:string,settings:Record<string,string>={}) {
  try { execFileSync(path.join(productRoot,'infra/scripts/local-service.sh'),[action,service],{
    cwd:productRoot,env:{...process.env,...settings},timeout:180000,stdio:'pipe'}); }
  catch { throw new Error(`ENVIRONMENT: local ${action} ${service} control failed`); }
}
export async function codeSettings<T>(settings:Record<string,string>,work:()=>Promise<T>,connection?:ApiClient):Promise<T>{
  localService('stop','api');
  try{localService('start','api',settings);await connection?.reconnect();return await work();}
  finally{localService('stop','api');localService('start','api',{
    SALON_IDENTITY_CODE_NUMBER_LIMIT:'3',SALON_IDENTITY_CODE_DEVICE_LIMIT:'10',
    SALON_IDENTITY_CODE_ADDRESS_LIMIT:'20',SALON_IDENTITY_CODE_DAILY_LIMIT:'1000',
  });await connection?.reconnect();}
}
export function localKeys() {
  const file = process.env.SALON_IDENTITY_LOCAL_KEY_FILE ?? path.join(productRoot,'.local/identity-keys.properties');
  if(!fs.existsSync(file)) throw new Error('ENVIRONMENT: generated local identity key file unavailable');
  return {mode:fs.statSync(file).mode&0o777,fingerprint:createHash('sha256').update(fs.readFileSync(file)).digest('hex')};
}
export function mark(name:string,details:Record<string,string|number|boolean>={}) {
  fs.mkdirSync(reportDir,{recursive:true});
  const at=Date.now();fs.appendFileSync(path.join(reportDir,'F02-control-marks.jsonl'),JSON.stringify({name,at,...details})+'\n');return at;
}
export function apiProcess() {
  try{return execFileSync('lsof',['-nP','-iTCP:'+new URL(apiUrl).port,'-sTCP:LISTEN','-t'],{encoding:'utf8'}).trim();}
  catch{throw new Error('ENVIRONMENT: cannot identify running local API');}
}
