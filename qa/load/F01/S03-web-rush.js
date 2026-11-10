// TC-F01-S03-007: 60 simultaneous users, 300 requests, evenly across salons.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { salons, salonUrl } from '../../shared/tenants.ts';
// The QA instance (decision 105); the default instance's ports are the owner's and are refused.
const url=__ENV.QA_WEB_URL || `http://localhost:${__ENV.SALON_WEB_PORT || '3200'}`;
if (/:(3100|8090|5440)(\/|$)/.test(url)) throw new Error(`REFUSED: ${url} is the default instance (decision 105). Nothing was run.`);
// Product copy that contains a synthetic salon's value: Settings' colour help (F04-S02) says "like #D6086A", the platform
// default and alpha's colour. Every page carries the message catalogue, so that copy is removed before looking for another
// salon's data (as in qa/web/F01/S03-branded-shell.spec.ts); any other occurrence still counts.
const strings=[];(function walk(n){if(typeof n==='string')strings.push(n);else Object.values(n).forEach(walk);})(JSON.parse(open('../../../apps/web/messages/en.json')));
const COPY_WITH_SALON_VALUES=strings.filter(t=>salons.some(o=>[o.name,o.brandColour,o.id].some(v=>t.includes(v))));
const withoutCopy=body=>COPY_WITH_SALON_VALUES.reduce((html,t)=>html.split(t).join(''),body);
const own=new Counter('own_brand'), mismatches=new Counter('other_brand');
export const options={ scenarios:{rush:{executor:'per-vu-iterations',vus:60,iterations:5,maxDuration:'60s'}},thresholds:{own_brand:['count==300'],other_brand:['count==0'],http_req_failed:['rate==0'],checks:['rate==1']} };
function entry(s){const root=http.get(salonUrl(s,url),{redirects:0});check(root,{'same-host workspace entry':()=>root.status===307&&[salonUrl(s,url)+'/workspace','/workspace'].includes(root.headers.Location)});if(root.status!==307||![salonUrl(s,url)+'/workspace','/workspace'].includes(root.headers.Location))throw Error('salon staff entry failed');return http.get(salonUrl(s,url)+'/workspace/sign-in');}
export function setup(){ for(const s of salons){const r=entry(s); if(r.status!==200||!r.body.includes(s.name.replaceAll('&','&amp;'))) throw Error(`ENVIRONMENT: ${s.slug} shell absent`);} }
export default function(){const s=salons[exec.scenario.iterationInTest%3];const r=entry(s);const body=withoutCopy(String(r.body)); const correct=r.status===200&&body.includes(s.name.replaceAll('&','&amp;'))&&body.includes('account-identifier')&&body.includes('staff-sign-in-name'); const other=salons.some(o=>o!==s&&(body.includes(o.name.replaceAll('&','&amp;'))||body.includes(o.brandColour)||body.includes(o.id)));own.add(correct?1:0);mismatches.add(other?1:0);check(r,{'own brand only':()=>correct&&!other});}
