import {expect,test} from '@qa/fixtures';
import {salons,salonUrl} from '@qa/tenants';
import {webUrl} from '@qa/env';
import {create,read,state,clock,logs} from '@qa/platform-services';

test('TC-F01-001 salon hostname stays bound across the shell API audit delivery and background cleanup',async({page,api})=>{
  test.setTimeout(120000);
  try{
    const values=[];
    for(const salon of salons){await page.goto(salonUrl(salon,webUrl));await expect(page.getByTestId('salon-name')).toHaveText(salon.name);const brand=await(await api.salon(salon)).json();expect(brand.slug).toBe(salon.slug);const probe=await create(api,salon);values.push({salon,probe});}
    for(const {salon,probe}of values){const own=await state(api,salon,probe.id,'sent');expect(own.audit).toHaveLength(1);for(const other of salons.filter(s=>s!==salon)){const r=await api.getOn(other.hostname,`/local/probes/${probe.id}`);expect(r.status()).toBe(404);expect((await r.json()).code).toBe('not_found');}const lines=logs().filter(l=>l.message?.includes(probe.id));expect(lines.length).toBeGreaterThan(0);for(const line of lines)expect(line['tenant.id']).toBe(salon.id);}
    await clock(api,new Date(Date.now()+31*86400000).toISOString());
    for(const {salon,probe}of values)await expect.poll(async()=>{const r=await api.getOn(salon.hostname,`/local/probes/${probe.id}`);return r.status();},{timeout:5000}).toBe(404);
    for(const salon of salons){await page.goto(salonUrl(salon,webUrl));await expect(page.getByTestId('salon-name')).toHaveText(salon.name);}
  }finally{await clock(api);}
});
