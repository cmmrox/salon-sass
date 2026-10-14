/** Lead-approved fixture cleanup, never a product scheduling repair or a timing assertion. */
import { expect } from '@playwright/test';
import { identitySql } from './identity-storage';
import { mark } from './identity-runtime';
import { salons } from './tenants';

const jobs = ['identity-security-cleanup','outbox-cleanup'] as const;
const intervals = {'identity-security-cleanup':900000,'outbox-cleanup':3600000};
type JobRow = {name:typeof jobs[number];next_run_at:string;locked_by:string|null;locked_until:string|null;
  last_started_at:string|null;last_finished_at:string|null;created_at:string;updated_at:string};
const literal=(value:string)=>`'${value.replace(/'/g,"''")}'`;

function rows():JobRow[]{
  const text=identitySql(`SELECT COALESCE(jsonb_agg(to_jsonb(j) ORDER BY name),'[]'::jsonb) FROM scheduled_job j WHERE name IN ('identity-security-cleanup','outbox-cleanup')`);
  return JSON.parse(text) as JobRow[];
}
function unlocked(value:JobRow[]){return value.length===2&&value.every(r=>r.locked_by===null&&r.locked_until===null);}

export class SchedulerClockGuard {
  private readonly changes:{instant:number;askedAt:number}[]=[];
  private constructor(private readonly before:JobRow[],private readonly capturedAt:number){}
  static async snapshot(){
    const tenants=JSON.parse(identitySql(`SELECT jsonb_agg(jsonb_build_object('id',id,'slug',slug) ORDER BY slug) FROM tenant`)) as {id:string;slug:string}[];
    const expected=salons.map(s=>({id:s.id,slug:s.slug})).sort((a,b)=>a.slug.localeCompare(b.slug));
    if(JSON.stringify(tenants)!==JSON.stringify(expected))throw new Error('ENVIRONMENT: clock cleanup requires exactly the three fixed synthetic local salons');
    if(identitySql('SELECT current_database()')!==(process.env.SALON_DB_NAME??'salon'))throw new Error('ENVIRONMENT: local clock cleanup database differs');
    let snapshot:JobRow[]=[];
    await expect.poll(()=>{snapshot=rows();return unlocked(snapshot);},{timeout:5000,intervals:[25,50,100]}).toBe(true);
    if(snapshot.map(r=>r.name).join('|')!==jobs.join('|'))throw new Error('ENVIRONMENT: scheduler fixture targets differ');
    const now=Date.now();
    for(const row of snapshot)if(!Number.isFinite(Date.parse(row.next_run_at))||Date.parse(row.next_run_at)>now+intervals[row.name])throw new Error('ENVIRONMENT: scheduler already has an unrelated future occurrence');
    mark('clock-cleanup-snapshot',{jobs:jobs.join(','),capturedAt:now});
    return new SchedulerClockGuard(snapshot,now);
  }
  advanced(instant:string){
    const value=Date.parse(instant);if(!Number.isFinite(value))throw new Error('ENVIRONMENT: invalid test-clock instant');
    this.changes.push({instant:value,askedAt:Date.now()});mark('clock-test-set',{instant});
  }
  async restoreAfterRealClock(){
    let observed:JobRow[]=[];
    await expect.poll(()=>{observed=rows();return unlocked(observed);},{timeout:5000,intervals:[25,50,100]}).toBe(true);
    const now=Date.now(),targets:JobRow[]=[];
    for(const current of observed){
      const original=this.before.find(r=>r.name===current.name);if(!original)throw new Error('ENVIRONMENT: scheduler target changed during test');
      const next=Date.parse(current.next_run_at);
      if(current.next_run_at===original.next_run_at||next<=now+intervals[current.name])continue;
      // A future handler occurrence must be attributable to this case's recorded clock changes.
      const started=Date.parse(current.last_started_at??''),finished=Date.parse(current.last_finished_at??'');
      const finishedInFutureOrAfterClockReset=finished>=started||(finished>=this.capturedAt&&finished<=now);
      const attributable=this.changes.some(change=>change.instant>this.capturedAt&&started>=change.instant&&finishedInFutureOrAfterClockReset&&
        started<=change.instant+(now-change.askedAt)&&next>started&&next<=started+intervals[current.name]);
      if(!attributable)throw new Error('ENVIRONMENT: future scheduler occurrence was not demonstrably advanced by this case');
      targets.push(current);
    }
    if(targets.length){
      // One transaction, exact whole-row guard, and only next_run_at changes. On any conflict the
      // DO exception rolls back every earlier update in the same transaction.
      const commands=targets.map(current=>{
        const baseline=this.before.find(r=>r.name===current.name)!;
        return `SELECT to_jsonb(j) INTO actual FROM scheduled_job j WHERE name=${literal(current.name)} FOR UPDATE;
          IF actual IS DISTINCT FROM ${literal(JSON.stringify(current))}::jsonb THEN RAISE EXCEPTION 'QA clock cleanup state changed'; END IF;
          UPDATE scheduled_job SET next_run_at=${literal(baseline.next_run_at)}::timestamptz WHERE name=${literal(current.name)} AND next_run_at=${literal(current.next_run_at)}::timestamptz AND locked_by IS NULL AND locked_until IS NULL;
          GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>1 THEN RAISE EXCEPTION 'QA clock cleanup affected count differs'; END IF;`;
      }).join('\n');
      identitySql(`BEGIN;DO $qa_clock$ DECLARE actual jsonb;changed integer;BEGIN ${commands} END $qa_clock$;COMMIT;`);
      mark('clock-cleanup-restored',{count:targets.length,names:targets.map(r=>r.name).join(',')});
    }
    // A restored due occurrence must resume on the normal scheduler. An occurrence still in the
    // ordinary future keeps its original due time; the fixture never forces it to run early.
    for(const target of targets){const baseline=this.before.find(r=>r.name===target.name)!;
      if(Date.parse(baseline.next_run_at)<=now){
        await expect.poll(()=>{const row=rows().find(r=>r.name===target.name)!;return row.locked_by===null&&row.locked_until===null&&Date.parse(row.last_finished_at??'')>=now&&Date.parse(row.next_run_at)<=Date.now()+intervals[row.name];},{timeout:5000,intervals:[25,50,100]}).toBe(true);
        mark('clock-cleanup-normal-handler-resumed',{name:target.name});
      }else{
        const after=rows().find(r=>r.name===target.name)!;
        const ranNormally=after.locked_by===null&&after.locked_until===null&&Date.parse(after.last_finished_at??'')>=now&&Date.parse(after.next_run_at)<=Date.now()+intervals[after.name];
        if(after.next_run_at!==baseline.next_run_at&&!ranNormally)throw new Error('ENVIRONMENT: restored not-yet-due scheduler occurrence changed');
        mark('clock-cleanup-ordinary-schedule-restored',{name:target.name});
      }
    }
  }
}
