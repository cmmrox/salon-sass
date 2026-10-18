/**
 * A minimal ZIP reader for the F03 export archive: reads the central directory and inflates each
 * entry (stored or deflated). Standard library only; no product code involved.
 */
import { inflateRawSync } from 'node:zlib';

export function unzip(archive: Buffer): Map<string, Buffer> {
  const entries = new Map<string, Buffer>();
  // End of central directory: signature 0x06054b50 within the last 64 KiB + 22 bytes.
  let end = -1;
  for (let i = archive.length - 22; i >= Math.max(0, archive.length - 65_557); i--) {
    if (archive.readUInt32LE(i) === 0x06054b50) { end = i; break; }
  }
  if (end < 0) throw new Error('not a ZIP archive: no end of central directory');
  const count = archive.readUInt16LE(end + 10);
  let at = archive.readUInt32LE(end + 16);
  for (let n = 0; n < count; n++) {
    if (archive.readUInt32LE(at) !== 0x02014b50) throw new Error('corrupt ZIP central directory');
    const method = archive.readUInt16LE(at + 10);
    const compressed = archive.readUInt32LE(at + 20);
    const nameLength = archive.readUInt16LE(at + 28);
    const extraLength = archive.readUInt16LE(at + 30);
    const commentLength = archive.readUInt16LE(at + 32);
    const local = archive.readUInt32LE(at + 42);
    const name = archive.subarray(at + 46, at + 46 + nameLength).toString('utf8');
    const dataStart = local + 30 + archive.readUInt16LE(local + 26) + archive.readUInt16LE(local + 28);
    const data = archive.subarray(dataStart, dataStart + compressed);
    entries.set(name, method === 0 ? Buffer.from(data) : method === 8 ? inflateRawSync(data) : (() => { throw new Error(`unsupported ZIP method ${method}`); })());
    at += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

/** The rows of one exported table file, whether it is an array or an object holding `rows`. */
export function rowsOf(file: Buffer): Record<string, unknown>[] {
  const parsed = JSON.parse(file.toString('utf8')) as unknown;
  if (Array.isArray(parsed)) return parsed as Record<string, unknown>[];
  const rows = (parsed as { rows?: unknown }).rows;
  if (Array.isArray(rows)) return rows as Record<string, unknown>[];
  throw new Error('an exported table file holds neither an array nor rows');
}
