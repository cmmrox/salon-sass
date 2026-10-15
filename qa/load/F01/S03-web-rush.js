// TC-F01-S03-007: 60 simultaneous users, 300 requests, evenly across salons.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { salons, salonUrl } from '../../shared/tenants.ts';
const url=__ENV.QA_WEB_URL || `http://localhost:${__ENV.SALON_WEB_PORT || '3100'}`;
const own=new Counter('own_brand'), mismatches=new Counter('other_brand');
export const options={ scenarios:{rush:{executor:'per-vu-iterations',vus:60,iterations:5,maxDuration:'60s'}},thresholds:{own_brand:['count==300'],other_brand:['count==0'],http_req_failed:['rate==0'],checks:['rate==1']} };
function entry(s){const root=http.get(salonUrl(s,url),{redirects:0});check(root,{'same-host workspace entry':()=>root.status===307&&root.headers.Location==='/workspace'});if(root.status!==307||root.headers.Location!=='/workspace')throw Error('salon staff entry failed');return http.get(salonUrl(s,url)+'/workspace/sign-in');}
export function setup(){ for(const s of salons){const r=entry(s); if(r.status!==200||!r.body.includes(s.name.replaceAll('&','&amp;'))) throw Error(`ENVIRONMENT: ${s.slug} shell absent`);} }
export default function(){const s=salons[exec.scenario.iterationInTest%3];const r=entry(s);const body=String(r.body); const correct=r.status===200&&body.includes(s.name.replaceAll('&','&amp;'))&&body.includes('account-identifier')&&body.includes('workspace-shell'); const other=salons.some(o=>o!==s&&(body.includes(o.name.replaceAll('&','&amp;'))||body.includes(o.brandColour)||body.includes(o.id)));own.add(correct?1:0);mismatches.add(other?1:0);check(r,{'own brand only':()=>correct&&!other});}
