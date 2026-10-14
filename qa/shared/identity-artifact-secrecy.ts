/** Actual ignored build output is examined; no rg ignore rules or secret values in output. */
import fs from 'node:fs';
import path from 'node:path';
import {expect} from '@playwright/test';
import {productRoot,mark} from './identity-runtime';

export function assertIdentityArtifactsSafe(){
 const keyFile=process.env.SALON_IDENTITY_LOCAL_KEY_FILE??path.join(productRoot,'.local/identity-keys.properties');
 if(!fs.existsSync(keyFile))throw new Error('ENVIRONMENT: retained four-key file unavailable for artifact check');
 const content=fs.readFileSync(keyFile,'utf8');
 const needles=['signing','challenge','encryption','proxy'].map(name=>{
  const value=new RegExp(`^${name}=([a-fA-F0-9]{64})$`,'m').exec(content)?.[1];
  if(!value)throw new Error(`ENVIRONMENT: retained ${name} key unavailable for artifact check`);
  return [Buffer.from(value),Buffer.from(value,'hex')];
 }).flat();
 const output=path.join(productRoot,'apps/web/.next/standalone');
 if(!fs.existsSync(output))throw new Error('ENVIRONMENT: final standalone artifact unavailable');
 const visited=new Set<string>();let files=0,bytes=0;const findings:string[]=[];
 const walk=(directory:string)=>{
  const real=fs.realpathSync(directory);if(visited.has(real))return;visited.add(real);
  for(const entry of fs.readdirSync(directory,{withFileTypes:true})){
   const file=path.join(directory,entry.name),relative=path.relative(output,file);const stat=fs.statSync(file);
   if(stat.isDirectory()){walk(file);continue;}if(!stat.isFile())continue;
   files++;const data=fs.readFileSync(file);bytes+=data.length;
   if(entry.name==='identity-keys.properties'||/^\.env(?:$|\.)/.test(entry.name))findings.push(`${relative}: environment/key file copy`);
   if(needles.some(needle=>data.includes(needle)))findings.push(`${relative}: retained secret bytes`);
  }
 };
 walk(output);expect(files,'actual standalone inventory exists').toBeGreaterThan(10);expect(bytes).toBeGreaterThan(0);
 mark('standalone-secret-inventory',{files,bytes,keys:4});expect(findings,'standalone artifact contains no environment/key copies or retained secret bytes').toEqual([]);
}
