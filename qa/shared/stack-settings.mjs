import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
/** The QA checkout: its automation and load scripts. */
export const qaRoot=path.resolve(import.meta.dirname,'../..');
function qaLocal(name){try{const line=fs.readFileSync(path.join(qaRoot,'qa/.env.local'),'utf8').split('\n').find(l=>l.startsWith(name+'='));return line?line.slice(name.length+1).trim():undefined;}catch{return undefined;}}
/** The checkout that owns the stack (.env.stack, .stack/, infra scripts, the commit its images are built from): QA_PRODUCT_ROOT, else the QA checkout. */
export const root=path.resolve(process.env.QA_PRODUCT_ROOT||qaLocal('QA_PRODUCT_ROOT')||qaRoot);
export const report=process.env.QA_REPORT_DIR||path.join(root,'qa/reports/adhoc');
export function readEnv(file){return Object.fromEntries(fs.readFileSync(file,'utf8').split('\n').filter(l=>/^[A-Z_0-9]+=/.test(l)).map(l=>{const i=l.indexOf('=');return[l.slice(0,i),l.slice(i+1).replace(/^"|"$/g,'')];}));}
export const settings=readEnv(path.join(root,'.env.stack'));
export const versions=()=>readEnv(path.join(root,'.stack/deployed.env'));
export const base=`http://${settings.SALON_PLATFORM_DOMAIN}:${settings.SALON_EDGE_HTTP_PORT}`;
export const url=(slug='',p='/')=>base.replace('://',`://${slug?slug+'.':''}`)+p;
export function run(command,args=[],options={}){return execFileSync(command,args,{cwd:root,encoding:'utf8',timeout:600000,...options});}
export function script(name,args=[],options={}){return run(path.join(root,'infra/scripts',name+'.sh'),args,options);}
export function curl(slug,p='/',extra=[]){const output=run('curl',['--silent','--show-error','--max-time','10','--resolve',`${new URL(url(slug,p)).hostname}:${settings.SALON_EDGE_HTTP_PORT}:127.0.0.1`,'--dump-header','-',...extra,url(slug,p)]);const parts=output.split(/\r?\n\r?\n/);const header=parts.shift();return{status:Number(header.match(/^HTTP\/\S+ (\d+)/m)[1]),headers:header,body:parts.join('\n\n')};}
export function db(query,role=settings.SALON_DB_ADMIN_USER){return run('docker',['exec','-i','salon-stack-postgres','psql','-U',settings.SALON_DB_ADMIN_USER,'-d',settings.SALON_DB_NAME,'-X','-qAt','-v','ON_ERROR_STOP=1'],{input:role===settings.SALON_DB_ADMIN_USER?query:`SET ROLE ${role}; ${query}`}).trim();}
