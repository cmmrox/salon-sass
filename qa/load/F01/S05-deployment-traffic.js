// Invoked only by S05-deployment.sh: TC-F01-S05-008 and TC-F01-S05-009.
// 50 VUs continuously read web, salon API and metadata across all three salons.
// SSL_CERT_FILE is the stack's exported CA: TLS validation remains enabled.
import http from 'k6/http';
import {check} from 'k6';
import {Counter} from 'k6/metrics';
import exec from 'k6/execution';
import {salons} from '../../shared/tenants.ts';
const base=__ENV.QA_STACK_URL;
if(!base)throw Error('ENVIRONMENT: QA_STACK_URL missing');
const domain=base.replace(/^https:\/\//,'').split(':')[0];
const hostMap=Object.fromEntries(salons.map(s=>[`${s.slug}.${domain}`,__ENV.QA_STACK_EDGE_IP||'127.0.0.1']));
const own=new Counter('own_tenant'),cross=new Counter('cross_tenant'),versions=new Counter('meta_versions');
export const options={hosts:hostMap,vus:50,duration:'10m',thresholds:{http_req_failed:['rate==0'],checks:['rate==1'],own_tenant:['count>300'],cross_tenant:['count==0'],meta_versions:['count>0']}};
export default function(){const salon=salons[exec.vu.idInTest%salons.length];const origin=base.replace('://',`://${salon.slug}.`);for(const p of ['/','/api/v1/salon']){const r=http.get(origin+p,{timeout:'10s'});let valid=r.status===200;const body=String(r.body);if(p==='/')valid=valid&&body.includes(salon.name.replaceAll('&','&amp;'))&&body.includes(salon.brandColour);else{try{const b=r.json();valid=valid&&b.slug===salon.slug&&b.name===salon.name&&b.brandColour===salon.brandColour&&b.timeZone===salon.timeZone;}catch{valid=false;}}const other=salons.some(o=>o!==salon&&(body.includes(o.name.replaceAll('&','&amp;'))||body.includes(o.brandColour)||body.includes(o.id)));own.add(valid?1:0);cross.add(other?1:0);check(r,{'own tenant only':()=>valid&&!other});}
const meta=http.get(origin+'/api/v1/meta');let value;try{value=meta.json();}catch{}check(meta,{'valid tested metadata':()=>meta.status===200&&value?.commit===__ENV.QA_STACK_SHA&&[__ENV.QA_VERSION_N,__ENV.QA_VERSION_NEXT].includes(value?.version)});if(value){versions.add(1,{version:value.version});console.log('QA_VERSION '+JSON.stringify({version:value.version,at:Date.now()}));}}
export function handleSummary(data){return{[__ENV.QA_K6_SUMMARY]:JSON.stringify(data,null,2)};}
