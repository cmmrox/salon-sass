import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { expect, test } from '@qa/fixtures';
import { REPO_ROOT, webUrl } from '@qa/env';
import { alpha, beta, gamma, salons, salonUrl, onHostname, PLATFORM_DOMAIN } from '@qa/tenants';
import { WIDTHS, fit, seriousViolations, stringsShown, testIds, TEST_ID_NAME } from '@qa/page-checks';
import { allMessages } from '@qa/strings';
import { startApi, stopApi, standInForApi } from '@qa/services';
import { DESIGN_WIDTHS, designScreenshot, referenceCustomerShell, referenceFit, referenceTypeAndPalette } from '@qa/design-reference';

const suspend = (action: string) => execFileSync(path.join(REPO_ROOT, 'infra/scripts/local-salon.sh'), [action, gamma.slug]);
function noOtherBrand(body: string, own?: typeof alpha) {
  for (const s of salons.filter(s => s !== own)) for (const value of [s.name, s.brandColour, s.id]) expect(body).not.toContain(value);
}

test('TC-F01-S03-001 each hostname renders only its salon in heading and document title', async ({ page, request }) => {
  for (const s of salons) {
    const response = await page.goto(`${salonUrl(s, webUrl)}/`);
    expect(response!.status()).toBe(200);
    await expect(page.getByRole('heading', {level: 1, name: s.name, exact: true})).toBeVisible();
    await expect(page).toHaveTitle(s.name);
    noOtherBrand(await response!.text(), s);
    expect(response!.headers()['cache-control']).toContain('no-store');
    await page.reload();
    await expect(page.getByTestId('salon-name')).toHaveText(s.name);
  }
  const uppercase = await request.get(`${webUrl}/`, {headers: {Host: alpha.hostname.toUpperCase()}});
  expect(uppercase.status()).toBe(200);
  expect(await uppercase.text()).toContain(alpha.name);
});

test('TC-F01-S03-002 every salon colour is applied with readable foreground contrast', async ({ page }) => {
  for (const s of salons) {
    await page.goto(`${salonUrl(s, webUrl)}/`);
    await expect(page.getByTestId('salon-name')).toHaveText(s.name);
    const colours = await page.getByTestId('salon-identity').evaluate(el => {
      const style = getComputedStyle(el);
      return {brand: getComputedStyle(document.documentElement).getPropertyValue('--color-brand').trim(), foreground: style.color, background: style.backgroundColor};
    });
    expect(colours.brand.toUpperCase()).toBe(s.brandColour);
    expect(colours.background).toBe(`rgb(${s.brandColour.slice(1).match(/../g)!.map(v => parseInt(v,16)).join(', ')})`);
    await expect(page.getByTestId('salon-name')).toHaveCSS('color', 'rgb(17, 24, 39)');
    const channel = (v: number) => v / 255 <= .04045 ? v / 255 / 12.92 : ((v / 255 + .055) / 1.055) ** 2.4;
    const luminance = (rgb: number[]) => .2126*channel(rgb[0])+.7152*channel(rgb[1])+.0722*channel(rgb[2]);
    const fg = colours.foreground.match(/[\d.]+/g)!.slice(0,3).map(Number);
    const bg = s.brandColour.slice(1).match(/../g)!.map(v => parseInt(v,16));
    const values = [luminance(fg), luminance(bg)].sort((a,b) => a-b);
    expect((values[1]+.05)/(values[0]+.05)).toBeGreaterThanOrEqual(4.5);
  }
});

test('TC-F01-S03-003 every unknown and lookalike hostname shares the neutral 404 page', async ({ request }) => {
  const hosts = [`not-a-salon.${PLATFORM_DOMAIN}`, `www.${PLATFORM_DOMAIN}`, `console.${PLATFORM_DOMAIN}`, `${alpha.slug}.${beta.slug}.${PLATFORM_DOMAIN}`, `${alpha.hostname}.example.com`];
  let first: string | undefined;
  for (const host of hosts) {
    const r = await request.get(`${webUrl}/`, {headers:{Host:host}});
    expect(r.status()).toBe(404);
    const body = await r.text();
    expect(body).toContain('salon-not-found'); noOtherBrand(body);
    first ??= body; expect(body).toBe(first);
  }
});

test('TC-F01-S03-004 the platform domain retains the service status page', async ({ page, api }) => {
  const meta = await (await api.meta()).json();
  await page.goto(`${webUrl}/`);
  await expect(page.getByTestId('status-api-service')).toHaveText(meta.service);
  await expect(page.getByTestId('status-api-version')).toHaveText(meta.version);
});

test('TC-F01-S03-005 a suspended salon shows a neutral 503 and returns immediately after activation', async ({ request, api }) => {
  try {
    suspend('suspend');
    const r = await request.get(`${salonUrl(gamma, webUrl)}/`);
    expect(r.status()).toBe(503); expect(await r.text()).toContain('salon-unavailable'); noOtherBrand(await r.text());
    const ar = await api.salon(gamma); expect(ar.status()).toBe(403);
    expect((await ar.json()).code).toBe('salon_suspended');
    for (const s of [alpha, beta]) expect((await request.get(`${salonUrl(s, webUrl)}/`)).status()).toBe(200);
  } finally { suspend('activate'); }
  const r = await request.get(`${salonUrl(gamma, webUrl)}/`); expect(r.status()).toBe(200); expect(await r.text()).toContain(gamma.name);
});

test('TC-F01-S03-006 another salon in client-controlled fields never changes the rendered brand', async ({ page }) => {
  await page.goto(`${salonUrl(beta, webUrl)}/`);
  await expect(page.getByTestId('salon-name')).toHaveText(beta.name);
  await page.setExtraHTTPHeaders({'X-Forwarded-Host':beta.hostname, 'X-Tenant-Id':beta.id, 'Forwarded':`host=${beta.hostname}`, 'Cookie':`tenant=${beta.slug}; tenant_id=${beta.id}`, 'x-salon-resolved':JSON.stringify(beta)});
  const response = await page.goto(`${salonUrl(alpha, webUrl)}/?tenant=${beta.slug}&tenantId=${beta.id}`);
  expect(response!.status()).toBe(200);
  await expect(page.getByTestId('salon-name')).toHaveText(alpha.name);
  await expect(page).toHaveTitle(alpha.name);
  noOtherBrand(await page.locator('body').innerText(),alpha);
  for (const other of salons.filter(s => s !== alpha)) expect(await page.content()).not.toContain(other.brandColour);
});

for (const kind of ['stopped','silent'] as const) test(`TC-F01-S03-${kind === 'stopped' ? '008':'009'} the ${kind} API produces a bounded service unavailable page and recovers`, async ({ request }) => {
  test.setTimeout(180_000);
  await stopApi();
  const fake = kind === 'silent' ? await standInForApi(() => {}) : null;
  try {
    const start = performance.now(); const r = await request.get(`${salonUrl(alpha, webUrl)}/`); const body = await r.text();
    expect(performance.now()-start).toBeLessThan(5000); expect(r.status()).toBe(503);
    expect(body).toContain('status-unavailable'); expect(body).not.toContain('salon-not-found'); noOtherBrand(body);
    expect(body).not.toMatch(/Exception|stack trace|error-overlay/);
    if (fake) expect(fake.requests()).toBeGreaterThan(0);
  } finally { await fake?.close(); await startApi(); }
  const r = await request.get(`${salonUrl(alpha, webUrl)}/`); expect(r.status()).toBe(200); expect(await r.text()).toContain(alpha.name);
});

test('TC-F01-S03-010 long-name and neutral pages fit both widths with no serious accessibility issue', async ({ page }) => {
  try {
    suspend('suspend');
    for (const width of WIDTHS) for (const [url,id] of [[salonUrl(beta,webUrl),'salon-name'],[onHostname(webUrl,`missing.${PLATFORM_DOMAIN}`),'salon-not-found-title'],[salonUrl(gamma,webUrl),'salon-unavailable-title']]) {
      await page.setViewportSize(width); await page.goto(url); await expect(page.getByTestId(id)).toBeVisible();
      const overflow=await referenceFit(page,id === 'salon-name' ? beta.name : undefined); expect(overflow.counted).toBeGreaterThan(0); expect(overflow.pageScrolls).toBe(false); expect(overflow.outside).toEqual([]); expect(overflow.clipped).toEqual([]);
      expect(await seriousViolations(page)).toEqual([]);
    }
  } finally { suspend('activate'); }
});

test('TC-F01-S03-011 strings and readable result handles on all pages follow the shared contract', async ({ page }) => {
  try {
    suspend('suspend');
    for (const [url,id] of [[salonUrl(beta,webUrl),'salon-name'],[onHostname(webUrl,`missing.${PLATFORM_DOMAIN}`),'salon-not-found-title'],[salonUrl(gamma,webUrl),'salon-unavailable-title']]) {
      await page.goto(url); await expect(page.getByTestId(id)).toBeVisible();
      const values=await stringsShown(page); expect(values.length).toBeGreaterThan(0);
      const identity=page.getByTestId('salon-identity');const initials=await identity.count()?await identity.innerText():null;
      if(initials)expect(initials).toMatch(/^[\p{L}]{1,2}$/u);
      for(const value of values) expect([...allMessages(),beta.name,...(initials?[initials]:[])]).toContain(value);
      const ids=await testIds(page); expect(ids.length).toBeGreaterThan(2); for(const id of ids) expect(id).toMatch(TEST_ID_NAME);
    }
  } finally { suspend('activate'); }
});


test('TC-F01-S03-012 the salon contract and client describe a suspended salon', async () => {
  const { committedContract }=await import('@qa/openapi');
  const contract=committedContract(); const operation=contract.paths['/api/v1/salon'].get as {responses:Record<string,unknown>};
  expect(operation.responses['403']).toBeTruthy();
  const code=contract.components.schemas.Problem.properties!.code as {enum:string[]}; expect(code.enum).toContain('salon_suspended');
});

test('TC-F01-S03-013 public and foundation pages match the supplied reference grammar at all six widths', async ({ page }) => {
  test.setTimeout(180_000);
  for (const s of salons) for (const viewport of DESIGN_WIDTHS) {
    await page.setViewportSize(viewport); await page.goto(salonUrl(s, webUrl)); await expect(page.getByTestId('salon-name')).toHaveText(s.name);
    await referenceTypeAndPalette(page); await referenceCustomerShell(page, viewport.width);
    const result = await referenceFit(page, s.name); expect(result.counted).toBeGreaterThan(0); expect(result.pageScrolls).toBe(false); expect(result.outside).toEqual([]); expect(result.clipped).toEqual([]);
    await designScreenshot(page, `F01-public-${s.slug}-${viewport.width}`);
  }
  try {
    suspend('suspend');
    for (const viewport of DESIGN_WIDTHS) for (const [url,id] of [[webUrl,'status-api-service'],[onHostname(webUrl,`missing.${PLATFORM_DOMAIN}`),'salon-not-found-title'],[salonUrl(gamma,webUrl),'salon-unavailable-title']]) {
      await page.setViewportSize(viewport); await page.goto(url); await expect(page.getByTestId(id)).toBeVisible(); await referenceTypeAndPalette(page);
      await expect(page.locator('body')).toHaveCSS('background-color','rgb(243, 240, 235)');
      const result = await fit(page); expect(result.counted).toBeGreaterThan(0); expect(result.pageScrolls).toBe(false); expect(result.outside).toEqual([]); expect(result.clipped).toEqual([]);
      await designScreenshot(page, `F01-${id}-${viewport.width}`);
    }
  } finally { suspend('activate'); }
});
