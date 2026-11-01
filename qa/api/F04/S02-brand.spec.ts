/**
 * F04-S02 the brand from outside: draft, publish, discard and reset; image uploads checked by
 * content and re-encoded; size, pixel and body limits; who may see an image on which hostname;
 * roles, a support session, suspended and read-only salons; isolation; versions and races; the
 * cleanup job on the test clock; and the audit trail.
 *
 * Expected results are the F04-S02 spec's (acceptance criteria, "API and data changes", "Design
 * notes", "Corrections at close"), docs/qa/test-types.md and the generated contract. Brand changes
 * are published on salons the operator creates; where a fixed salon must change, the test puts its
 * seeded brand back (qa/shared/salon-brand.ts `restoreSeededBrand`).
 */
import { randomUUID } from 'node:crypto';

import { readOnlySalon } from '../../shared/billing';
import { apiUrl as identityApiUrl, webUrl } from '../../shared/env';
import type { Identity, Role, Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import {
  test, expect, alpha, beta, comparable, operatorJson, ownerSession, schedulerSettled, sqlLiteral, supportJson,
  type Operator, type SupportStart,
} from '../../shared/operator';
import { logOffsets, logsSince } from '../../shared/product-logs';
import {
  BRAND_ENDPOINTS, BRAND_TABLES, BRAND_WRITES, Brands, DEFAULT_COLOUR, brandAudit, brandFieldErrors, brandInput, brandSnapshot,
  chunkedUpload, fixture, grayPng, imageExists, imageRows, pngUpload, readJpeg, readPng,
  type BrandDraft, type BrandEndpoint, type FileUpload,
} from '../../shared/salon-brand';
import { refused } from '../../shared/salon-profile';
import { onHostname, type Salon } from '../../shared/tenants';

async function newSalon(operator: Operator, identity: Identity) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id);
  const owner = await ownerSession(identity, salon, input);
  return { admin, created, salon, owner, input };
}

/** The body each endpoint takes for a version. */
function bodyFor(e: BrandEndpoint, version: number, salon: Salon): unknown {
  if (e.name === 'read') return undefined;
  if (e.name === 'save') return { version, brand: brandInput({ name: `${salon.name || 'QA'} edited` }) };
  return { version };
}

const LOGO = () => fixture('logo-700x600-banded.png');
const COVER = () => fixture('cover-2000x800-exif.jpg');
const noStore = (headers: Record<string, string>, label: string) => {
  expect(headers['cache-control'] ?? '', `${label}: no-store`).toContain('no-store');
  expect(headers['x-content-type-options'], `${label}: nosniff`).toBe('nosniff');
};

test('TC-F04-S02-001 AC-3: a saved brand draft survives a re-read while the public salon keeps the published brand', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const publishedBefore = await brands.published(salon);
  const start = await brands.draft(salon, owner.tokens);
  expect(start.hasUnpublishedChanges, 'a new salon\'s draft equals its published brand').toBe(false);
  expect(start.brand).toEqual({ name: publishedBefore.name, colour: publishedBefore.brandColour, tagline: null, logo: null, cover: null });
  const logo = await brands.upload(salon, owner.tokens, 'logo', LOGO());
  const cover = await brands.upload(salon, owner.tokens, 'cover', COVER());
  const input = brandInput({ logo: logo.id, cover: cover.id });
  const saved = await brands.save(salon, owner.tokens, start.version, input);
  expect(saved.hasUnpublishedChanges).toBe(true);
  const reread = await brands.draft(salon, owner.tokens);
  expect(reread.brand).toEqual({ name: input.name, tagline: input.tagline, colour: input.colour,
    logo: { id: logo.id, width: logo.width, height: logo.height }, cover: { id: cover.id, width: cover.width, height: cover.height } });
  expect(await brands.published(salon), 'the public salon is unchanged').toEqual(publishedBefore);
  await brands.dispose();
});

test('TC-F04-S02-002 AC-3: publishing makes the draft the published brand in one step and the API serves exactly it', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const logo = await brands.upload(salon, owner.tokens, 'logo', LOGO());
  const cover = await brands.upload(salon, owner.tokens, 'cover', COVER());
  const input = brandInput({ colour: '#8a4b6b', logo: logo.id, cover: cover.id });
  const saved = await brands.save(salon, owner.tokens, (await brands.draft(salon, owner.tokens)).version, input);
  const published = await brands.publish(salon, owner.tokens, saved.version);
  expect(published.hasUnpublishedChanges).toBe(false);
  expect(published.publishedBy, 'the publishing staff account').toBe(owner.principal.id);
  const served = await brands.published(salon);
  expect({ name: served.name, tagline: served.tagline, colour: served.brandColour, logo: served.logo, cover: served.cover }).toEqual({
    name: input.name, tagline: input.tagline, colour: '#8A4B6B',
    logo: { id: logo.id, width: logo.width, height: logo.height }, cover: { id: cover.id, width: cover.width, height: cover.height },
  });
  expect(published.brand, 'the draft and the published brand are the same').toEqual({ name: served.name, tagline: served.tagline, colour: served.brandColour, logo: served.logo, cover: served.cover });
  await brands.dispose();
});

test('TC-F04-S02-003 AC-3: publishing a brand with nothing changed is 409 invalid_state and changes nothing', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  for (const label of ['a new salon', 'straight after a publication']) {
    await test.step(label, async () => {
      if (label !== 'a new salon') {
        const saved = await brands.edit(salon, owner.tokens, { tagline: 'Published tagline' });
        await brands.publish(salon, owner.tokens, saved.version);
      }
      const draft = await brands.draft(salon, owner.tokens);
      const before = brandSnapshot(salon.id);
      await refused(await brands.versionRaw('publish', salon, owner.tokens, draft.version), 409, 'invalid_state', label);
      expect(brandSnapshot(salon.id), `${label}: nothing changed`).toBe(before);
    });
  }
  await brands.dispose();
});

test('TC-F04-S02-004 AC-3: discarding returns the brand draft to the published brand and leaves the published brand as it was', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const logo = await brands.upload(salon, owner.tokens, 'logo', LOGO());
  const first = await brands.edit(salon, owner.tokens, { tagline: 'First tagline', logo: logo.id });
  const published = await brands.publish(salon, owner.tokens, first.version);
  const servedBefore = await brands.published(salon);
  const other = await brands.upload(salon, owner.tokens, 'logo', fixture('logo-300.webp'));
  await brands.edit(salon, owner.tokens, { name: 'Another name', colour: '#141413', tagline: null, logo: other.id });
  const discarded = await brands.discard(salon, owner.tokens, (await brands.draft(salon, owner.tokens)).version);
  expect(discarded.hasUnpublishedChanges).toBe(false);
  expect(discarded.brand, 'the draft is the published brand again').toEqual(published.brand);
  expect(await brands.published(salon), 'the public salon is unchanged').toEqual(servedBefore);
  await brands.dispose();
});

test('TC-F04-S02-005 AC-5: removing the logo or cover and resetting to default branding are draft changes until published, and reset keeps the name', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const logo = await brands.upload(salon, owner.tokens, 'logo', LOGO());
  const cover = await brands.upload(salon, owner.tokens, 'cover', COVER());
  const full = await brands.edit(salon, owner.tokens, { name: 'Kept Name', tagline: 'Will go', colour: '#2F4B8A', logo: logo.id, cover: cover.id });
  await brands.publish(salon, owner.tokens, full.version);
  const servedFull = await brands.published(salon);
  await test.step('"Use default logo" and "Use default cover" clear only that image in the draft', async () => {
    const noLogo = await brands.edit(salon, owner.tokens, { logo: null });
    expect(noLogo.brand.logo).toBeNull();
    expect(noLogo.brand.cover?.id).toBe(cover.id);
    const noCover = await brands.edit(salon, owner.tokens, { cover: null });
    expect(noCover.brand.cover).toBeNull();
    expect(await brands.published(salon), 'still a draft').toEqual(servedFull);
  });
  await test.step('Reset to default branding', async () => {
    await brands.edit(salon, owner.tokens, { logo: logo.id, cover: cover.id });
    const reset = await brands.reset(salon, owner.tokens, (await brands.draft(salon, owner.tokens)).version);
    expect(reset.brand).toEqual({ name: 'Kept Name', tagline: null, colour: DEFAULT_COLOUR, logo: null, cover: null });
    expect(reset.hasUnpublishedChanges).toBe(true);
    expect(await brands.published(salon), 'reset is a draft change').toEqual(servedFull);
    await brands.publish(salon, owner.tokens, reset.version);
    const served = await brands.published(salon);
    expect({ name: served.name, tagline: served.tagline, colour: served.brandColour, logo: served.logo, cover: served.cover })
      .toEqual({ name: 'Kept Name', tagline: null, colour: DEFAULT_COLOUR, logo: null, cover: null });
  });
  await brands.dispose();
});

test('TC-F04-S02-006 AC-6: a missing or long name, a long tagline, a colour that is not #RRGGBB or an unknown image is a 400 on that field and saves nothing', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const logo = await brands.upload(salon, owner.tokens, 'logo', LOGO());
  const rows: { label: string; brand: Record<string, unknown>; field: RegExp }[] = [
    { label: 'empty name', brand: brandInput({ name: '' }), field: /(^|\.)name$/ },
    { label: 'blank name', brand: brandInput({ name: '    ' }), field: /(^|\.)name$/ },
    { label: '61-character name after trimming', brand: brandInput({ name: `  ${'N'.repeat(61)}  ` }), field: /(^|\.)name$/ },
    { label: 'no name at all', brand: { colour: '#D6086A', tagline: null, logo: null, cover: null }, field: /(^|\.)name$/ },
    { label: '81-character tagline', brand: brandInput({ tagline: 'T'.repeat(81) }), field: /(^|\.)tagline$/ },
    { label: 'colour as a word', brand: brandInput({ colour: 'red' }), field: /(^|\.)colour$/ },
    { label: 'colour without #', brand: brandInput({ colour: 'D6086A' }), field: /(^|\.)colour$/ },
    { label: 'colour of five digits', brand: brandInput({ colour: '#D6086' }), field: /(^|\.)colour$/ },
    { label: 'colour of seven digits', brand: brandInput({ colour: '#D6086A1' }), field: /(^|\.)colour$/ },
    { label: 'colour with non-hex digits', brand: brandInput({ colour: '#GG086A' }), field: /(^|\.)colour$/ },
    { label: 'colour with alpha', brand: brandInput({ colour: '#D6086AFF' }), field: /(^|\.)colour$/ },
    { label: 'unknown logo', brand: brandInput({ logo: randomUUID() }), field: /(^|\.)logo$/ },
    { label: 'unknown cover', brand: brandInput({ cover: randomUUID() }), field: /(^|\.)cover$/ },
    { label: 'a logo used as the cover', brand: brandInput({ cover: logo.id }), field: /(^|\.)cover$/ },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const draft = await brands.draft(salon, owner.tokens);
      const before = brandSnapshot(salon.id);
      const { fields } = await brandFieldErrors(await brands.saveRaw(salon, owner.tokens, draft.version, row.brand), row.label);
      expect(fields.length, `${row.label}: names the field`).toBeGreaterThan(0);
      for (const field of fields) expect(field, `${row.label}: on that field`).toMatch(row.field);
      expect(brandSnapshot(salon.id), `${row.label}: nothing saved`).toBe(before);
    });
  }
  await brands.dispose();
});

test('TC-F04-S02-007 AC-6: a name and tagline at their limits are kept trimmed, an empty tagline means none and the colour is stored in upper case', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const name = 'N'.repeat(60); const tagline = 'T'.repeat(80);
  const saved = await brands.edit(salon, owner.tokens, { name: `  ${name}  `, tagline: ` ${tagline} `, colour: '#cc5500' });
  expect(saved.brand).toMatchObject({ name, tagline, colour: '#CC5500' });
  const empty = await brands.edit(salon, owner.tokens, { name: 'A', tagline: '' });
  expect(empty.brand).toMatchObject({ name: 'A', tagline: null });
  await brands.dispose();
});

test('TC-F04-S02-008 AC-4: PNG, JPEG and WebP uploads are re-encoded: the logo cropped to its centre square as a PNG of at most 512, the cover a JPEG at most 1600 wide, with no metadata kept', async ({ identity, operator }) => {
  test.setTimeout(120_000);
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const read = async (id: string) => {
    const r = await brands.image(salon.hostname, id, owner.tokens);
    expect(r.status(), 'the settings user reads the draft image').toBe(200);
    return { type: (r.headers()['content-type'] ?? '').split(';')[0], body: await r.body() };
  };
  await test.step('a 700 x 600 PNG logo becomes its 512 x 512 centre square, without its text chunks', async () => {
    const image = await brands.upload(salon, owner.tokens, 'logo', LOGO());
    expect([image.width, image.height]).toEqual([512, 512]);
    const { type, body } = await read(image.id);
    expect(type).toBe('image/png');
    const png = readPng(body);
    expect([png.width, png.height]).toEqual([512, 512]);
    // The fixture has a red band 50 pixels wide at each side; its centre square has none.
    for (const [x, y] of [[0, 0], [0, 511], [511, 0], [511, 511], [2, 256], [509, 256]]) {
      const [r, , b] = png.pixel(x, y);
      expect(r < 60 && b > 180, `pixel ${x},${y} is from the centre (blue), not the cut-off red band: ${png.pixel(x, y)}`).toBe(true);
    }
    expect(png.chunks.filter(c => ['tEXt', 'zTXt', 'iTXt', 'eXIf', 'tIME'].includes(c)), 'no metadata chunks').toEqual([]);
    expect(body.toString('latin1')).not.toContain('QA-PNG-');
  });
  await test.step('a 2000 x 800 JPEG cover with camera and GPS data becomes a 1600 x 640 JPEG without them', async () => {
    const image = await brands.upload(salon, owner.tokens, 'cover', COVER());
    expect([image.width, image.height]).toEqual([1600, 640]);
    const { type, body } = await read(image.id);
    expect(type).toBe('image/jpeg');
    const jpeg = readJpeg(body);
    expect([jpeg.width, jpeg.height]).toEqual([1600, 640]);
    expect(jpeg.markers.filter(m => m === 0xe1 || m === 0xfe), 'no Exif (APP1) or comment segment').toEqual([]);
    for (const text of ['QA-CAMERA', 'QA-SOFTWARE', 'QA-JPEG-COMMENT', 'Exif', 'GPS']) expect(body.toString('latin1'), text).not.toContain(text);
  });
  const rows: { label: string; kind: 'logo' | 'cover'; file: () => FileUpload; size: [number, number]; type: string }[] = [
    { label: 'a WebP logo is kept as a PNG', kind: 'logo', file: () => fixture('logo-300.webp'), size: [300, 300], type: 'image/png' },
    { label: 'a WebP cover is kept as a JPEG', kind: 'cover', file: () => fixture('cover-1000x400.webp'), size: [1000, 400], type: 'image/jpeg' },
    { label: 'the smallest logo, 128 x 128', kind: 'logo', file: () => fixture('logo-128-min.png'), size: [128, 128], type: 'image/png' },
    { label: 'the smallest cover, 800 x 300', kind: 'cover', file: () => fixture('cover-800x300-min.jpg'), size: [800, 300], type: 'image/jpeg' },
    { label: 'a cover taller than wide but kept within 1600 high (900 x 1200)', kind: 'cover', file: () => fixture('cover-900x1200-tall.jpg'), size: [900, 1200], type: 'image/jpeg' },
    { label: 'a cover kept exactly 1600 high (1000 x 1600)', kind: 'cover', file: () => pngUpload(grayPng(1000, 1600)), size: [1000, 1600], type: 'image/jpeg' },
    { label: 'a declared type that is wrong does not matter when the content is a PNG', kind: 'logo', file: () => fixture('logo-128-min.png', 'application/pdf', 'logo.pdf'), size: [128, 128], type: 'image/png' },
    { label: 'a 7000 x 7000 logo, under the pixel limit, is decoded within budget', kind: 'logo', file: () => pngUpload(grayPng(7000, 7000)), size: [512, 512], type: 'image/png' },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const image = await brands.upload(salon, owner.tokens, row.kind, row.file());
      expect([image.width, image.height]).toEqual(row.size);
      expect((await read(image.id)).type).toBe(row.type);
    });
  }
  await brands.dispose();
});

test('TC-F04-S02-009 AC-4: a file that is not PNG, JPEG or WebP by content, is over 5 MB, is too small, would be kept over 1600 high or is undecodable, or declares too many pixels is a 400 on file and stores nothing', async ({ identity, operator }) => {
  test.setTimeout(120_000);
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const big = grayPng(400, 400, { padding: 6 * 1024 * 1024 });
  expect(big.length, 'a valid PNG over 5 MB and under the 10 MB request limit').toBeGreaterThan(5 * 1024 * 1024);
  const rows: { label: string; kind: string; file: FileUpload; field?: RegExp }[] = [
    { label: 'text named .png and declared image/png', kind: 'logo', file: fixture('not-an-image.txt', 'image/png', 'logo.png') },
    { label: 'an SVG declared image/png', kind: 'logo', file: fixture('logo-300.svg', 'image/png', 'logo.png') },
    { label: 'a GIF', kind: 'logo', file: fixture('logo-300.gif') },
    { label: 'a BMP renamed .jpg', kind: 'cover', file: fixture('logo-300.bmp', 'image/jpeg', 'cover.jpg') },
    { label: 'a truncated PNG', kind: 'logo', file: fixture('logo-truncated.png') },
    { label: 'a 127 x 127 logo', kind: 'logo', file: fixture('logo-127-small.png') },
    { label: 'a 900 x 299 cover', kind: 'cover', file: fixture('cover-900x299-short.jpg') },
    { label: 'a 128 x 128 image as a cover', kind: 'cover', file: fixture('logo-128-min.png') },
    // The height bound (lead's ruling at the dry run, replacing the corrections' wording): a cover
    // is refused when its kept height, after scaling to at most 1600 wide, would exceed 1600.
    { label: 'a cover that would be kept 1601 high (1000 x 1601)', kind: 'cover', file: pngUpload(grayPng(1000, 1601)) },
    { label: 'a cover that would be kept 2000 high (1000 x 2000)', kind: 'cover', file: pngUpload(grayPng(1000, 2000)) },
    { label: 'a tall cover wider than 1600, kept 1600 x 1680 (2000 x 2100)', kind: 'cover', file: pngUpload(grayPng(2000, 2100)) },
    { label: 'a valid PNG of 6 MB', kind: 'logo', file: pngUpload(big) },
    { label: 'a PNG declaring 60000 x 60000 pixels', kind: 'logo', file: pngUpload(grayPng(16, 16, { declared: [60000, 60000] })) },
    { label: 'a decompression bomb, 8000 x 8000 in a small file', kind: 'logo', file: pngUpload(grayPng(8000, 8000)) },
    { label: 'an empty file', kind: 'logo', file: pngUpload(Buffer.alloc(0), 'empty.png') },
    { label: 'an unknown kind', kind: 'banner', file: fixture('logo-128-min.png'), field: /(^|\.)kind$/ },
  ];
  // Every row runs, and every row that is not refused as the spec says is listed: one refusal
  // missing must not hide another.
  const wrong: string[] = [];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const before = brandSnapshot(salon.id);
      const r = await brands.uploadRaw(salon, owner.tokens, row.kind, row.file);
      const body = await r.text();
      let fields: string[] = [];
      try { fields = ((JSON.parse(body) as { errors?: { field: string }[] }).errors ?? []).map(e => e.field); } catch { /* not problem details */ }
      const fieldOk = fields.length > 0 && fields.every(f => (row.field ?? /(^|\.)file$/).test(f));
      if (r.status() !== 400 || !body.includes('"bad_request"') || !fieldOk) wrong.push(`${row.label}: ${r.status()} ${body.slice(0, 160)}`);
      if (brandSnapshot(salon.id) !== before) wrong.push(`${row.label}: something was stored`);
    });
  }
  expect(wrong, 'every hostile or out-of-range file is a 400 on its field and stores nothing').toEqual([]);
  expect((await identity.api.health()).status(), 'the API is healthy after the hostile files').toBe(200);
  await brands.dispose();
});


test('TC-F04-S02-010 AC-4: a request over 10 MB, declared or chunked, is 413 at the API and through the web and stores nothing', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const before = brandSnapshot(salon.id);
  const ELEVEN_MB = 11 * 1024 * 1024;
  const wrong: string[] = [];
  await test.step('declared length over 10 MB, at the API', async () => {
    const r = await brands.uploadRaw(salon, owner.tokens, 'logo', pngUpload(grayPng(400, 400, { padding: ELEVEN_MB })));
    const body = await r.text();
    if (r.status() !== 413 || !body.includes('"request_rejected"')) wrong.push(`declared 11 MB at the API: ${r.status()} ${body.slice(0, 160)}`);
  });
  await test.step('chunked with no declared length, at the API', async () => {
    const answer = await chunkedUpload(`${onHostname(apiBase(), '127.0.0.1')}/api/v1/salon/brand/images`,
      { Host: `${salon.hostname}:${new URL(apiBase()).port}`, Authorization: `Bearer ${owner.tokens.accessToken}` }, ELEVEN_MB);
    if (answer.status !== 413) wrong.push(`chunked 11 MB at the API: ${answer.status} ${answer.error ?? answer.body.slice(0, 160)}`);
  });
  await test.step('chunked with no declared length, through the workspace BFF', async () => {
    const port = new URL(webUrl).port;
    const origin = `http://${salon.hostname}:${port}`;
    const cookies = { access: owner.tokens.accessToken, refresh: owner.tokens.refreshToken, expires: owner.tokens.accessExpiresAt, session: owner.tokens.sessionId };
    const answer = await chunkedUpload(`http://127.0.0.1:${port}/identity-web/staff/brand-image`, {
      Host: `${salon.hostname}:${port}`, Origin: origin,
      Cookie: Object.entries(cookies).map(([k, v]) => `salon_staff_${k}=${v}`).join('; '),
    }, ELEVEN_MB);
    if (answer.status !== 413) wrong.push(`chunked 11 MB through the web: ${answer.status} ${answer.error ?? answer.body.slice(0, 160)}`);
  });
  expect(brandSnapshot(salon.id), 'nothing stored by an oversized request').toBe(before);
  expect((await identity.api.health()).status(), 'the API is healthy').toBe(200);
  expect(wrong, 'every request over 10 MB is 413 request_rejected').toEqual([]);
  await brands.dispose();
});

test('TC-F04-S02-011 AC-7: a published image is served to anyone on its own hostname, a draft image only to settings users, and on another hostname neither, exactly like an unknown id', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const published = await brands.upload(salon, owner.tokens, 'logo', LOGO());
  const saved = await brands.edit(salon, owner.tokens, { logo: published.id });
  await brands.publish(salon, owner.tokens, saved.version);
  const draftOnly = await brands.upload(salon, owner.tokens, 'cover', COVER());
  await brands.edit(salon, owner.tokens, { cover: draftOnly.id });
  const unknown = randomUUID();
  const shape = async (r: Awaited<ReturnType<Brands['image']>>, id: string) => ({ status: r.status(), body: comparable(await r.text()).split(id).join('ID') });
  await test.step('the published logo, to anyone, on its own hostname', async () => {
    const r = await brands.image(salon.hostname, published.id);
    expect(r.status()).toBe(200);
    expect((r.headers()['content-type'] ?? '').split(';')[0]).toBe('image/png');
    noStore(r.headers(), 'published image');
  });
  await test.step('the draft-only cover: to the settings user, and to nobody signed out', async () => {
    const own = await brands.image(salon.hostname, draftOnly.id, owner.tokens);
    expect(own.status()).toBe(200);
    noStore(own.headers(), 'draft image to its settings user');
    const anonymous = await brands.image(salon.hostname, draftOnly.id);
    expect(await shape(anonymous, draftOnly.id), 'signed out: the same as an unknown id').toEqual(await shape(await brands.image(salon.hostname, unknown), unknown));
    noStore(anonymous.headers(), 'refused draft image');
  });
  await test.step('another salon\'s hostname: 404 for both, exactly as for an unknown id', async () => {
    const reference = await shape(await brands.image(alpha.hostname, unknown), unknown);
    expect(reference.status).toBe(404);
    for (const id of [published.id, draftOnly.id]) {
      const r = await brands.image(alpha.hostname, id);
      expect(await shape(r, id), `${id} on alpha`).toEqual(reference);
      noStore(r.headers(), 'image on another hostname');
    }
  });
  await test.step('a draft image of a fixed salon: not to its Receptionist', async () => {
    const manager = await identity.staff(alpha, 'MANAGER');
    const receptionist = await identity.staff(alpha, 'RECEPTIONIST');
    const image = await brands.upload(alpha, manager.tokens, 'logo', fixture('logo-128-min.png'));
    const draft = await brands.edit(alpha, manager.tokens, { logo: image.id });
    try {
      expect((await brands.image(alpha.hostname, image.id, manager.tokens)).status()).toBe(200);
      const r = await brands.image(alpha.hostname, image.id, receptionist.tokens);
      expect([403, 404], 'not served to the Receptionist').toContain(r.status());
      expect((r.headers()['content-type'] ?? '')).not.toContain('image/');
    } finally {
      await brands.discard(alpha, manager.tokens, (await brands.draft(alpha, manager.tokens)).version);
      void draft;
    }
  });
  await test.step('through the web\'s image route on the salon hostname', async () => {
    const web = (host: string, id: string) => identity.api.get(`${onHostname(webUrl, host)}/brand-images/${id}`);
    const pub = await web(salon.hostname, published.id);
    expect(pub.status()).toBe(200);
    noStore(pub.headers(), 'web route, published');
    expect((await web(salon.hostname, draftOnly.id)).status(), 'draft image signed out through the web').toBe(404);
    expect((await web(alpha.hostname, published.id)).status(), 'published image on another hostname through the web').toBe(404);
  });
  await brands.dispose();
});

test('TC-F04-S02-012 AC-8: another salon\'s settings user reaches nothing of this salon\'s brand, and another salon\'s image id is the same field error as an unknown one', async ({ identity }) => {
  const brands = new Brands(identity);
  const alphaManager = await identity.staff(alpha, 'MANAGER');
  const betaManager = await identity.staff(beta, 'MANAGER');
  const alphaImage = await brands.upload(alpha, alphaManager.tokens, 'logo', fixture('logo-128-min.png'));
  await brands.edit(alpha, alphaManager.tokens, { logo: alphaImage.id, tagline: 'Alpha draft tagline' });
  try {
    expect((await brands.draft(alpha, alphaManager.tokens)).hasUnpublishedChanges, 'alpha has a draft to protect').toBe(true);
    const alphaBefore = brandSnapshot(alpha.id);
    const betaVersion = (await brands.draft(beta, betaManager.tokens)).version;
    for (const endpoint of BRAND_ENDPOINTS) {
      await refused(await brands.call(alpha, endpoint, betaManager.tokens, bodyFor(endpoint, betaVersion, beta)), 404, 'not_found', `beta on alpha ${endpoint.name}`);
    }
    await refused(await brands.uploadRaw(alpha, betaManager.tokens, 'logo', fixture('logo-128-min.png')), 404, 'not_found', 'beta uploads on alpha');
    await refused(await brands.image(alpha.hostname, alphaImage.id, betaManager.tokens), 404, 'not_found', 'beta reads alpha\'s draft image');
    expect(brandSnapshot(alpha.id), 'alpha unchanged').toBe(alphaBefore);
    const betaBefore = brandSnapshot(beta.id);
    const foreign = await brandFieldErrors(await brands.saveRaw(beta, betaManager.tokens, betaVersion, brandInput({ name: beta.name, colour: beta.brandColour, logo: alphaImage.id })), 'alpha\'s image in beta');
    const unknownId = randomUUID();
    const unknown = await brandFieldErrors(await brands.saveRaw(beta, betaManager.tokens, betaVersion, brandInput({ name: beta.name, colour: beta.brandColour, logo: unknownId })), 'unknown image in beta');
    expect(comparable(foreign.body).split(alphaImage.id).join('ID'), 'indistinguishable from an unknown id').toBe(comparable(unknown.body).split(unknownId).join('ID'));
    expect(brandSnapshot(beta.id), 'beta unchanged').toBe(betaBefore);
  } finally {
    await brands.discard(alpha, alphaManager.tokens, (await brands.draft(alpha, alphaManager.tokens)).version);
    await brands.dispose();
  }
});

test('TC-F04-S02-013 AC-8: with no tenant context the brand draft and image tables return no rows to the application role', async ({ identity }) => {
  const brands = new Brands(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  await brands.upload(alpha, manager.tokens, 'logo', fixture('logo-128-min.png'));
  for (const table of BRAND_TABLES) {
    expect(Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id = ${sqlLiteral(alpha.id)}`)), `${table}: alpha has rows`).toBeGreaterThan(0);
    expect(identitySql(`SELECT count(*) FROM ${table}`, null), `${table}: no tenant, no rows`).toBe('0');
    expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id <> ${sqlLiteral(alpha.id)}`, alpha.id), `${table}: alpha sees only its own`).toBe('0');
  }
  await brands.dispose();
});

test('TC-F04-S02-014 AC-9: a save, reset, publish or discard sent with an older brand version is 409 conflict and changes nothing', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const seen = await brands.draft(salon, owner.tokens);
  const moved = await brands.edit(salon, owner.tokens, { tagline: 'Moved on' });
  expect(moved.hasUnpublishedChanges, 'there is something to publish, so only the version can refuse it').toBe(true);
  const before = brandSnapshot(salon.id);
  await refused(await brands.saveRaw(salon, owner.tokens, seen.version, brandInput()), 409, 'conflict', 'stale save');
  for (const name of ['reset', 'publish', 'discard'] as const) {
    await refused(await brands.versionRaw(name, salon, owner.tokens, seen.version), 409, 'conflict', `stale ${name}`);
  }
  expect(brandSnapshot(salon.id), 'nothing changed').toBe(before);
  await brands.dispose();
});

test('TC-F04-S02-015 AC-9: of simultaneous brand publishes carrying one version exactly one succeeds and the rest are 409 conflict', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const saved = await brands.edit(salon, owner.tokens, { name: 'Raced Name', colour: '#2F4B8A' });
  const answers = await Promise.all(Array.from({ length: 10 }, () => brands.versionRaw('publish', salon, owner.tokens, saved.version)));
  const ok = answers.filter(a => a.status() === 200);
  expect(ok.length, 'exactly one publication').toBe(1);
  for (const a of answers.filter(a => a.status() !== 200)) await refused(a, 409, 'conflict', 'losing publish');
  const winner = await ok[0].json() as BrandDraft;
  const after = await brands.draft(salon, owner.tokens);
  expect(after.version).toBe(winner.version);
  const served = await brands.published(salon);
  expect([served.name, served.brandColour]).toEqual(['Raced Name', '#2F4B8A']);
  await brands.dispose();
});

test('TC-F04-S02-016 AC-1, AC-9: only the Owner and Manager read and change the brand and upload images; every other role and no credential are refused and change nothing', async ({ identity }) => {
  const brands = new Brands(identity);
  const expected: Record<Role, 'allowed' | 'refused'> = { OWNER: 'allowed', MANAGER: 'allowed', RECEPTIONIST: 'refused', PROFESSIONAL: 'refused', ACCOUNTANT: 'refused' };
  const keeper = await identity.staff(alpha, 'MANAGER');
  try {
    for (const role of Object.keys(expected) as Role[]) {
      await test.step(`${role}: ${expected[role]}`, async () => {
        const actor = await identity.staff(alpha, role);
        if (expected[role] === 'allowed') {
          const image = await brands.upload(alpha, actor.tokens, 'logo', fixture('logo-128-min.png'));
          expect((await brands.image(alpha.hostname, image.id, actor.tokens)).status()).toBe(200);
          const saved = await brands.edit(alpha, actor.tokens, { tagline: `${role} tagline`, logo: image.id });
          const published = await brands.publish(alpha, actor.tokens, saved.version);
          const reset = await brands.reset(alpha, actor.tokens, published.version);
          await brands.discard(alpha, actor.tokens, reset.version);
          return;
        }
        for (const endpoint of BRAND_ENDPOINTS) {
          const version = (await brands.draft(alpha, keeper.tokens)).version;
          const before = brandSnapshot(alpha.id);
          await refused(await brands.call(alpha, endpoint, actor.tokens, bodyFor(endpoint, version, alpha)), 403, 'permission_denied', `${role} ${endpoint.name}`);
          expect(brandSnapshot(alpha.id), `${role} ${endpoint.name}: nothing changed`).toBe(before);
        }
        const before = brandSnapshot(alpha.id);
        await refused(await brands.uploadRaw(alpha, actor.tokens, 'logo', fixture('logo-128-min.png')), 403, 'permission_denied', `${role} upload`);
        expect(brandSnapshot(alpha.id), `${role} upload: nothing stored`).toBe(before);
      });
    }
    await test.step('no credential', async () => {
      for (const endpoint of BRAND_ENDPOINTS) {
        const version = (await brands.draft(alpha, keeper.tokens)).version;
        const before = brandSnapshot(alpha.id);
        expect((await brands.call(alpha, endpoint, undefined, bodyFor(endpoint, version, alpha))).status(), `signed out ${endpoint.name}`).toBe(401);
        expect(brandSnapshot(alpha.id)).toBe(before);
      }
      const before = brandSnapshot(alpha.id);
      expect((await brands.uploadRaw(alpha, undefined, 'logo', fixture('logo-128-min.png'))).status(), 'signed out upload').toBe(401);
      expect(brandSnapshot(alpha.id)).toBe(before);
    });
  } finally {
    await brands.restoreSeededBrand(alpha, keeper.tokens);
    await brands.dispose();
  }
});

test('TC-F04-S02-017 AC-9: a support session reads the brand draft and its images and is refused every change and upload with support_read_only', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { created, salon, owner } = await newSalon(operator, identity);
  const image = await brands.upload(salon, owner.tokens, 'logo', LOGO());
  await brands.edit(salon, owner.tokens, { logo: image.id, tagline: 'Support can read this' });
  const ownerView = await brands.draft(salon, owner.tokens);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F04 brand support reason' }), 'supportStart', 201);
  const tokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
  expect(await brands.draft(salon, tokens)).toEqual(ownerView);
  expect((await brands.image(salon.hostname, image.id, tokens)).status(), 'the draft image').toBe(200);
  const before = brandSnapshot(salon.id);
  for (const endpoint of BRAND_WRITES) {
    await refused(await brands.call(salon, endpoint, tokens, bodyFor(endpoint, ownerView.version, salon)), 403, 'support_read_only', `support ${endpoint.name}`);
  }
  await refused(await brands.uploadRaw(salon, tokens, 'logo', fixture('logo-128-min.png')), 403, 'support_read_only', 'support upload');
  expect(brandSnapshot(salon.id), 'nothing changed').toBe(before);
  await brands.dispose();
});

test('TC-F04-S02-018 AC-9: a suspended salon\'s brand endpoints and upload answer 403 salon_suspended and change nothing', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { admin, created, salon, owner } = await newSalon(operator, identity);
  const draft = await brands.edit(salon, owner.tokens, { tagline: 'Before suspension' });
  await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
  const before = brandSnapshot(salon.id);
  for (const endpoint of BRAND_ENDPOINTS) {
    await refused(await brands.call(salon, endpoint, owner.tokens, bodyFor(endpoint, draft.version, salon)), 403, 'salon_suspended', `suspended ${endpoint.name}`);
  }
  await refused(await brands.uploadRaw(salon, owner.tokens, 'logo', fixture('logo-128-min.png')), 403, 'salon_suspended', 'suspended upload');
  expect(brandSnapshot(salon.id), 'nothing changed').toBe(before);
  await brands.dispose();
});

test('TC-F04-S02-019 AC-9: a read-only salon reads its brand draft and refuses every change and upload with salon_read_only', async ({ identity, operator, clock }) => {
  test.setTimeout(180_000);
  await clock.set('2026-11-20T07:00:00Z');
  const brands = new Brands(identity);
  const { admin, created, salon, owner } = await newSalon(operator, identity);
  await brands.edit(salon, owner.tokens, { tagline: 'Before read-only' });
  await readOnlySalon(operator, clock, admin, created.id);
  const ownerNow = await identity.login(salon, owner.account);
  const draft = await brands.draft(salon, ownerNow.tokens);
  expect(draft.brand.tagline).toBe('Before read-only');
  const before = brandSnapshot(salon.id);
  for (const endpoint of BRAND_WRITES) {
    await refused(await brands.call(salon, endpoint, ownerNow.tokens, bodyFor(endpoint, draft.version, salon)), 403, 'salon_read_only', `read-only ${endpoint.name}`);
  }
  await refused(await brands.uploadRaw(salon, ownerNow.tokens, 'logo', fixture('logo-128-min.png')), 403, 'salon_read_only', 'read-only upload');
  expect(brandSnapshot(salon.id), 'nothing changed').toBe(before);
  await brands.dispose();
});

test('TC-F04-S02-020 AC-10: the cleanup job deletes an image 24 hours after it stopped being used, or after its upload when never used, and never one the draft or the published brand uses', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  const T0 = '2026-11-25T08:00:00.000Z';
  const at = (hours: number) => new Date(Date.parse(T0) + hours * 3_600_000).toISOString();
  await clock.set(T0);
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  let tokens = owner.tokens;
  const signIn = async () => { tokens = (await identity.login(salon, owner.account)).tokens; };
  const pass = async (hours: number) => {
    await clock.set(at(hours));
    await schedulerSettled(operator, ['branding-image-cleanup']);
    await signIn();
  };
  const neverUsed = await brands.upload(salon, tokens, 'logo', fixture('logo-128-min.png'));
  const publishedCover = await brands.upload(salon, tokens, 'cover', fixture('cover-800x300-min.jpg'));
  const replaced = await brands.upload(salon, tokens, 'logo', fixture('logo-300.webp'));
  const draftLogo = await brands.upload(salon, tokens, 'logo', LOGO());
  const first = await brands.edit(salon, tokens, { cover: publishedCover.id });
  await brands.publish(salon, tokens, first.version);
  await brands.edit(salon, tokens, { logo: replaced.id });
  const exists = (id: string) => imageExists(id);
  await test.step('10 hours on, the draft logo is replaced: it stops being used', async () => {
    await pass(10);
    await brands.edit(salon, tokens, { logo: draftLogo.id });
  });
  await test.step('23 hours after upload: nothing is old enough', async () => {
    await pass(23);
    expect([neverUsed, publishedCover, replaced, draftLogo].map(i => exists(i.id))).toEqual([true, true, true, true]);
  });
  await test.step('25 hours after upload: the never-used image goes; the replaced one (15 hours unused) and the ones in use stay', async () => {
    await pass(25);
    expect(exists(neverUsed.id), 'never used, uploaded 25 hours ago').toBe(false);
    expect((await brands.image(salon.hostname, neverUsed.id, tokens)).status(), 'and is no longer served').toBe(404);
    expect(exists(replaced.id), 'unused for 15 hours').toBe(true);
    expect(exists(publishedCover.id), 'published').toBe(true);
    expect(exists(draftLogo.id), 'in the draft').toBe(true);
  });
  await test.step('35 hours: the replaced image (25 hours unused) goes; the ones in use stay', async () => {
    await pass(35);
    expect(exists(replaced.id), 'unused for 25 hours').toBe(false);
    expect(exists(publishedCover.id), 'published').toBe(true);
    expect(exists(draftLogo.id), 'in the draft').toBe(true);
    expect((await brands.image(salon.hostname, publishedCover.id)).status(), 'the published cover is still served').toBe(200);
  });
  await brands.dispose();
});

test('TC-F04-S02-021 AC-11: upload, save, publish, reset and discard are audited with actor, time, action and changed field names, and no bytes, file names or values reach the audit, the logs or an error', async ({ identity, operator }) => {
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const t0 = await identity.now();
  const offsets = logOffsets();
  const marker = `QAVAL${randomUUID().slice(0, 8)}`;
  const fileName = `qa-secret-${marker}.png`;
  const file = { ...fixture('logo-128-min.png'), name: fileName };
  const image = await brands.upload(salon, owner.tokens, 'logo', file);
  const saved = await brands.edit(salon, owner.tokens, { tagline: `Tagline ${marker}`, logo: image.id, colour: '#141413' });
  const published = await brands.publish(salon, owner.tokens, saved.version);
  const reset = await brands.reset(salon, owner.tokens, published.version);
  await brands.discard(salon, owner.tokens, reset.version);
  const errors = [
    await brands.uploadRaw(salon, owner.tokens, 'logo', { ...fixture('not-an-image.txt', 'image/png'), name: fileName }),
    await brands.saveRaw(salon, owner.tokens, (await brands.draft(salon, owner.tokens)).version, brandInput({ name: `${marker}`.repeat(10), tagline: `${marker}`.repeat(10) })),
  ];
  const t1 = await identity.now();
  const bytesSample = file.buffer.subarray(16, 48);
  const secrets = [marker, fileName, bytesSample.toString('base64').slice(0, 24), bytesSample.toString('hex').slice(0, 24)];
  const rows = brandAudit(salon.id, t0);
  expect(rows.length, 'one entry each for upload, save, publish, reset and discard').toBe(5);
  expect(new Set(rows.map(r => r.action)).size, 'five different actions').toBe(5);
  for (const row of rows) {
    expect(row.actorId).toBe(owner.principal.id);
    expect(row.actorKind.toLowerCase()).toContain('staff');
    expect(Date.parse(row.at)).toBeGreaterThanOrEqual(Date.parse(t0) - 1000);
    expect(Date.parse(row.at)).toBeLessThanOrEqual(Date.parse(t1) + 1000);
    for (const secret of secrets) expect(row.details, `${row.action}: nothing of the value, file name or bytes`).not.toContain(secret);
  }
  const save = rows[1];
  for (const name of ['tagline', 'logo', 'colour']) expect(save.details, `the save names ${name}`).toContain(name);
  expect(save.details, 'and not the name it did not change').not.toMatch(/"name"|\bname\b(?!s)/);
  for (const r of errors) {
    expect(r.status()).toBe(400);
    const body = await r.text();
    for (const secret of secrets) expect(body, 'no value or file name in an error').not.toContain(secret);
  }
  const logs = logsSince(offsets);
  for (const secret of secrets) expect(logs, 'no value, file name or bytes in the logs').not.toContain(secret);
  await brands.dispose();
});

test('TC-F04-S02-022 AC-4: simultaneous large decodes are each kept or told the service is busy, and the API stays up', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const brands = new Brands(identity);
  const { salon, owner } = await newSalon(operator, identity);
  {
    const file = pngUpload(grayPng(7000, 7000));
    const rowsBefore = imageRows(salon.id);
    const answers = await Promise.all(Array.from({ length: 6 }, () => brands.uploadRaw(salon, owner.tokens, 'logo', file)));
    const statuses = answers.map(a => a.status());
    expect(statuses.every(s => s === 201 || s === 503), `only 201 or 503: ${statuses}`).toBe(true);
    for (const a of answers.filter(a => a.status() === 503)) await refused(a, 503, 'service_unavailable', 'busy decode');
    const kept = statuses.filter(s => s === 201).length;
    expect(kept, 'at least one large image was kept').toBeGreaterThan(0);
    expect(imageRows(salon.id), 'one stored image per 201, none for a 503').toBe(rowsBefore + kept);
  }
  expect((await identity.api.health()).status(), 'the API is healthy').toBe(200);
  await brands.dispose();
});

function apiBase(): string {
  return identityApiUrl;
}
