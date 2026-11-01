/**
 * F04-S02 brand fixtures: the brand draft endpoints, image upload and serving, generated image
 * files (committed small fixtures in ./brand-images, and large or hostile ones built here), a
 * small PNG and JPEG reader to check what the product kept, the contrast rule of
 * docs/engineering/web.md ("Branding") computed independently, and read-only database
 * observations of the brand tables.
 *
 * Routes, bodies and codes come from the F04-S02 spec ("API and data changes", "Corrections at
 * close") and the generated contract. Fixed salons keep their seeded brand: a test that changes a
 * fixed salon's published brand puts it back with `restoreSeededBrand`, because F01 to F03 compare
 * the fixed salons with qa/shared/tenants.ts.
 */
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import zlib from 'node:zlib';
import { request, type APIRequestContext, type APIResponse } from '@playwright/test';

import type { components } from '@contracts/schema';

import { answerOf, expectProblem } from './answers';
import { apiUrl } from './env';
import { expect, json, type Identity, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { onSalonHost, sqlLiteral } from './operator';
import type { Salon } from './tenants';

export const BRAND_DRAFT = '/api/v1/salon/brand/draft';
export const BRAND_RESET = '/api/v1/salon/brand/draft/reset';
export const BRAND_PUBLICATION = '/api/v1/salon/brand/publication';
export const BRAND_IMAGES = '/api/v1/salon/brand/images';
export const imagePath = (id: string) => `${BRAND_IMAGES}/${id}`;
/** The web's image route on a salon hostname (spec, "Serving"). */
export const webImagePath = (id: string) => `/brand-images/${id}`;

export type BrandDraft = components['schemas']['SalonBrandDraft'];
export type BrandInput = components['schemas']['SalonBrandInput'];
export type UploadedImage = components['schemas']['UploadedSalonBrandImage'];
export type PublicSalon = components['schemas']['Salon'];
export type Kind = 'logo' | 'cover';

/** The prototype's default colour and its six swatches (prototype `setBrand`). */
export const DEFAULT_COLOUR = '#D6086A';
export const SWATCHES = ['#D6086A', '#141413', '#2F6B5E', '#2F4B8A', '#8A4B6B', '#CC5500'];
/** The tables this stage adds or changes. */
export const BRAND_TABLES = ['branding_brand_draft', 'branding_image'] as const;

export type BrandEndpoint = { name: 'read' | 'save' | 'reset' | 'publish' | 'discard'; method: string; path: string };
export const BRAND_ENDPOINTS: BrandEndpoint[] = [
  { name: 'read', method: 'GET', path: BRAND_DRAFT },
  { name: 'save', method: 'PUT', path: BRAND_DRAFT },
  { name: 'reset', method: 'POST', path: BRAND_RESET },
  { name: 'publish', method: 'POST', path: BRAND_PUBLICATION },
  { name: 'discard', method: 'DELETE', path: BRAND_DRAFT },
];
export const BRAND_WRITES = BRAND_ENDPOINTS.filter(e => e.name !== 'read');

// ---- image files ----

const FIXTURES = path.join(__dirname, 'brand-images');
export type FileUpload = { name: string; mimeType: string; buffer: Buffer };

/** A committed fixture, failing loudly when it is missing (regenerate with brand-images/generate.py). */
export function fixture(name: string, mimeType?: string, as = name): FileUpload {
  const file = path.join(FIXTURES, name);
  if (!fs.existsSync(file)) throw new Error(`ENVIRONMENT: image fixture ${name} is missing; run qa/shared/brand-images/generate.py`);
  const types: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif', bmp: 'image/bmp', svg: 'image/svg+xml', txt: 'text/plain' };
  return { name: as, mimeType: mimeType ?? types[name.split('.').pop()!] ?? 'application/octet-stream', buffer: fs.readFileSync(file) };
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(buffer: Buffer): number {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/**
 * A grayscale PNG of the given size, every pixel `value`. Rows compress to almost nothing, so a
 * huge image is a small file: what a decompression bomb is. `padding` adds a private ancillary
 * chunk of that many bytes, which a decoder skips, to make a valid image of any file size.
 * `declared` writes a different size into the header than the data holds.
 */
export function grayPng(width: number, height: number, options: { value?: number; padding?: number; declared?: [number, number] } = {}): Buffer {
  const header = Buffer.alloc(13);
  const [w, h] = options.declared ?? [width, height];
  header.writeUInt32BE(w, 0); header.writeUInt32BE(h, 4);
  header[8] = 8; header[9] = 0; header[10] = 0; header[11] = 0; header[12] = 0;
  const row = Buffer.alloc(width + 1, options.value ?? 128); row[0] = 0;
  // Uniform rows compress about a thousand to one: 8000 x 8000 is 64 MB raw and a small file.
  const idat = zlib.deflateSync(Buffer.concat(Array.from({ length: height }, () => row)), { level: 9 });
  return Buffer.concat([
    PNG_SIGNATURE, chunk('IHDR', header),
    ...(options.padding ? [chunk('quAl', Buffer.alloc(options.padding, 0x61))] : []),
    chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** A PNG fixture as an upload. */
export const pngUpload = (buffer: Buffer, name = 'generated.png'): FileUpload => ({ name, mimeType: 'image/png', buffer });

export type PngImage = { width: number; height: number; channels: number; pixel: (x: number, y: number) => number[]; chunks: string[]; colourType: number };

/** Reads an 8-bit, non-interlaced PNG (what an encoder writes for this product): size, chunk types and pixels. */
export function readPng(buffer: Buffer): PngImage {
  expect(buffer.subarray(0, 8).equals(PNG_SIGNATURE), 'a PNG signature').toBe(true);
  let offset = 8; const chunks: string[] = []; const idat: Buffer[] = []; let width = 0, height = 0, depth = 0, colourType = 0, interlace = 0; let palette: Buffer | null = null;
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset); const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length); chunks.push(type);
    if (type === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); depth = data[8]; colourType = data[9]; interlace = data[12]; }
    if (type === 'PLTE') palette = data;
    if (type === 'IDAT') idat.push(data);
    offset += 12 + length;
  }
  expect(depth, 'an 8-bit PNG').toBe(8);
  expect(interlace, 'not interlaced').toBe(0);
  const channels = ({ 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 } as Record<number, number>)[colourType];
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels; const out = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)]; const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? out[y * stride + x - channels] : 0; const b = y > 0 ? out[(y - 1) * stride + x] : 0;
      const c = x >= channels && y > 0 ? out[(y - 1) * stride + x - channels] : 0;
      const p = a + b - c; const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      const predictor = [0, a, b, (a + b) >> 1, pa <= pb && pa <= pc ? a : pb <= pc ? b : c][filter];
      out[y * stride + x] = (line[x] + predictor) & 0xff;
    }
  }
  const pixel = (x: number, y: number) => {
    const start = y * stride + x * channels;
    if (colourType === 3 && palette) { const i = out[start] * 3; return [palette[i], palette[i + 1], palette[i + 2]]; }
    return Array.from(out.subarray(start, start + channels));
  };
  return { width, height, channels, pixel, chunks, colourType };
}

export type JpegInfo = { width: number; height: number; markers: number[] };
/** Reads a JPEG's frame size and every marker before the image data (APPn, COM, ...). */
export function readJpeg(buffer: Buffer): JpegInfo {
  expect(buffer[0] === 0xff && buffer[1] === 0xd8, 'a JPEG start-of-image').toBe(true);
  let offset = 2; const markers: number[] = []; let width = 0, height = 0;
  while (offset < buffer.length) {
    if (buffer[offset] !== 0xff) break;
    const marker = buffer[offset + 1]; markers.push(marker);
    if (marker === 0xda) break;
    const length = buffer.readUInt16BE(offset + 2);
    if ([0xc0, 0xc1, 0xc2].includes(marker)) { height = buffer.readUInt16BE(offset + 5); width = buffer.readUInt16BE(offset + 7); }
    offset += 2 + length;
  }
  return { width, height, markers };
}

// ---- the contrast rule, from docs/engineering/web.md "Branding" ----

const channel = (v: number) => { const s = v / 255; return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; };
export function luminance(hex: string): number {
  const n = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map(i => parseInt(n.slice(i, i + 2), 16));
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}
export function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}
export const INK = '#111827';
/** White or ink, whichever contrasts more, when that reaches 4.5:1; otherwise pure black. */
export function textOn(hex: string): string {
  const white = contrast(hex, '#FFFFFF'); const ink = contrast(hex, INK);
  return Math.max(white, ink) >= 4.5 ? (white >= ink ? '#FFFFFF' : INK) : '#000000';
}
/** `rgb(r, g, b)` as the browser reports a computed colour, to `#RRGGBB`. */
export function hexOf(css: string): string {
  const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(css);
  if (!m) throw new Error(`not an rgb colour: ${css}`);
  return `#${[m[1], m[2], m[3]].map(v => Number(v).toString(16).padStart(2, '0')).join('').toUpperCase()}`;
}

// ---- the API ----

export class Brands {
  private uploads: APIRequestContext | null = null;
  constructor(readonly identity: Identity) {}

  call(salon: Salon, endpoint: BrandEndpoint | BrandEndpoint['name'], tokens?: Tokens, data?: unknown): Promise<APIResponse> {
    const e = typeof endpoint === 'string' ? BRAND_ENDPOINTS.find(x => x.name === endpoint)! : endpoint;
    return onSalonHost(this.identity, salon, e.method, e.path, tokens, data);
  }
  async draft(salon: Salon, tokens: Tokens): Promise<BrandDraft> {
    return json<BrandDraft>(await this.call(salon, 'read', tokens), 'SalonBrandDraft');
  }
  saveRaw(salon: Salon, tokens: Tokens | undefined, version: number, brand: unknown) { return this.call(salon, 'save', tokens, { version, brand }); }
  async save(salon: Salon, tokens: Tokens, version: number, brand: BrandInput): Promise<BrandDraft> {
    return json<BrandDraft>(await this.saveRaw(salon, tokens, version, brand), 'SalonBrandDraft');
  }
  versionRaw(name: 'reset' | 'publish' | 'discard', salon: Salon, tokens: Tokens | undefined, version: number) { return this.call(salon, name, tokens, { version }); }
  async publish(salon: Salon, tokens: Tokens, version: number) { return json<BrandDraft>(await this.versionRaw('publish', salon, tokens, version), 'SalonBrandDraft'); }
  async discard(salon: Salon, tokens: Tokens, version: number) { return json<BrandDraft>(await this.versionRaw('discard', salon, tokens, version), 'SalonBrandDraft'); }
  async reset(salon: Salon, tokens: Tokens, version: number) { return json<BrandDraft>(await this.versionRaw('reset', salon, tokens, version), 'SalonBrandDraft'); }

  /** The draft as an input, with changes applied: what a client sends back. */
  async edit(salon: Salon, tokens: Tokens, change: Partial<BrandInput>): Promise<BrandDraft> {
    const current = await this.draft(salon, tokens);
    const b = current.brand;
    return this.save(salon, tokens, current.version, { name: b.name, tagline: b.tagline, colour: b.colour, logo: b.logo?.id ?? null, cover: b.cover?.id ?? null, ...change });
  }

  async published(salon: Salon): Promise<PublicSalon> {
    return json<PublicSalon>(await this.identity.api.getOn(salon.hostname, '/api/v1/salon'), 'Salon');
  }

  /** A multipart upload on the salon's hostname. `kind` or `file` may be left out to test the refusal. */
  async uploadRaw(salon: Salon, tokens: Tokens | undefined, kind: string | null, file: FileUpload | null): Promise<APIResponse> {
    this.uploads ??= await request.newContext({ baseURL: apiUrl, timeout: 60_000, maxRedirects: 0 });
    const multipart: Record<string, string | FileUpload> = {};
    if (kind !== null) multipart.kind = kind;
    if (file !== null) multipart.file = file;
    return this.uploads.fetch(BRAND_IMAGES, { method: 'POST', multipart,
      headers: { Host: salon.hostname, ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}) } });
  }
  async upload(salon: Salon, tokens: Tokens, kind: Kind, file: FileUpload): Promise<UploadedImage> {
    const image = await json<UploadedImage>(await this.uploadRaw(salon, tokens, kind, file), 'UploadedSalonBrandImage', 201);
    expect(image.kind).toBe(kind);
    return image;
  }

  /** An image read on a hostname, with or without a credential. */
  image(host: string, id: string, tokens?: Tokens): Promise<APIResponse> {
    return this.identity.api.getOn(host, imagePath(id), tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : undefined);
  }

  /** Puts a fixed salon's draft and published brand back to its seed: name, colour, no tagline or images. */
  async restoreSeededBrand(salon: Salon, tokens: Tokens) {
    const current = await this.draft(salon, tokens);
    const saved = await this.save(salon, tokens, current.version, { name: salon.name, colour: salon.brandColour, tagline: null, logo: null, cover: null });
    if (saved.hasUnpublishedChanges) await this.publish(salon, tokens, saved.version);
    const published = await this.published(salon);
    expect({ name: published.name, colour: published.brandColour, tagline: published.tagline, logo: published.logo, cover: published.cover },
      `${salon.slug}: the seeded brand is published again`).toEqual({ name: salon.name, colour: salon.brandColour, tagline: null, logo: null, cover: null });
  }

  async dispose() { await this.uploads?.dispose(); }
}

/** A brand input with a unique name and tagline. */
export const brandInput = (overrides: Partial<BrandInput> = {}): BrandInput => {
  const marker = Math.random().toString(16).slice(2, 8);
  return { name: `QA Brand ${marker}`, tagline: `Tagline ${marker}`, colour: '#2F6B5E', logo: null, cover: null, ...overrides };
};

export async function brandFieldErrors(response: APIResponse, label: string): Promise<{ fields: string[]; body: string }> {
  const answer = await answerOf(response);
  expect(answer.status, `${label}: HTTP status (body ${answer.body.slice(0, 300)})`).toBe(400);
  const problem = expectProblem(answer, label);
  expect(problem.code).toBe('bad_request');
  return { fields: (problem.errors ?? []).map(e => e.field), body: answer.body };
}

// ---- raw uploads: a chunked body with no declared length ----

export type RawAnswer = { status: number | null; body: string; error: string | null };
/**
 * Sends a multipart upload of `totalBytes` file bytes with `Transfer-Encoding: chunked` and no
 * Content-Length, to the API or the web BFF, and returns the answer (or the connection error).
 */
export function chunkedUpload(url: string, headers: Record<string, string>, totalBytes: number, content?: Buffer): Promise<RawAnswer> {
  return new Promise(resolve => {
    const boundary = `----qa${Date.now()}`;
    const head = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="kind"\r\n\r\nlogo\r\n--${boundary}\r\n` +
      `Content-Disposition: form-data; name="file"; filename="big.png"\r\nContent-Type: image/png\r\n\r\n`);
    const tail = Buffer.from(`\r\n--${boundary}--\r\n`);
    const target = new URL(url);
    const req = http.request({ hostname: '127.0.0.1', port: target.port, path: target.pathname, method: 'POST',
      headers: { ...headers, 'Content-Type': `multipart/form-data; boundary=${boundary}`, 'Transfer-Encoding': 'chunked' } });
    let settled = false; let body = ''; const done = (a: RawAnswer) => { if (!settled) { settled = true; resolve(a); } };
    req.on('response', res => { res.setEncoding('utf8'); res.on('data', d => { body += d; }); res.on('end', () => done({ status: res.statusCode ?? null, body, error: null })); res.on('error', e => done({ status: res.statusCode ?? null, body, error: e.message })); });
    req.on('error', e => setTimeout(() => done({ status: null, body, error: e.message }), 200));
    req.setTimeout(60_000, () => { req.destroy(new Error('timed out')); });
    const block = Buffer.alloc(64 * 1024, 0x61);
    let sent = 0;
    const pump = () => {
      if (settled) return;
      if (sent === 0) req.write(head);
      if (content) { sent = totalBytes; req.write(content); }
      while (sent < totalBytes) {
        const n = Math.min(block.length, totalBytes - sent); sent += n;
        if (!req.write(n === block.length ? block : block.subarray(0, n))) { req.once('drain', pump); return; }
      }
      req.end(tail);
    };
    pump();
  });
}

// ---- database observations (owner role; never writes) ----

export function brandSnapshot(salonId: string): string {
  const id = sqlLiteral(salonId);
  return [
    identitySql(`SELECT coalesce(md5(string_agg(row_to_json(d)::text, '|')), 'none') FROM branding_brand_draft d WHERE tenant_id = ${id}`),
    identitySql(`SELECT coalesce(md5(string_agg(id::text || kind || md5(bytes) || coalesce(unused_since::text, '-'), '|' ORDER BY id)), 'none') FROM branding_image WHERE tenant_id = ${id}`),
    identitySql(`SELECT coalesce(md5(string_agg(row_to_json(b)::text, '|')), 'none') FROM salon_brand b WHERE tenant_id = ${id}`),
  ].join(',');
}
export const imageRows = (salonId: string) => Number(identitySql(`SELECT count(*) FROM branding_image WHERE tenant_id = ${sqlLiteral(salonId)}`));
export const imageExists = (id: string) => identitySql(`SELECT count(*) FROM branding_image WHERE id = ${sqlLiteral(id)}`) === '1';

export type BrandAuditRow = { action: string; actorKind: string; actorId: string; at: string; details: string };
export function brandAudit(salonId: string, after: string): BrandAuditRow[] {
  const out = identitySql(`SELECT json_build_object('action', action, 'actorKind', actor_kind, 'actorId', actor_id, 'at', created_at, 'details', row_to_json(a)::text)
    FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND action LIKE 'branding.brand%' AND created_at >= ${sqlLiteral(after)}::timestamptz ORDER BY created_at, id`);
  return out ? out.split('\n').map(line => JSON.parse(line) as BrandAuditRow) : [];
}
