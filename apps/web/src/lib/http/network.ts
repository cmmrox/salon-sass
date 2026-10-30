import { BlockList, isIP } from 'node:net';

/** Canonical address shared by BFF and API; never accepts a forwarded list or port. */
export function normalizeIp(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 45 || value.includes('%') || !isIP(value)) return null;
  if (isIP(value) === 4) return value;
  const compact = new URL(`http://[${value}]/`).hostname.slice(1, -1);
  const [left, right] = compact.split('::');
  const a = left ? left.split(':') : [];
  const b = right ? right.split(':') : [];
  const groups = right === undefined ? a : [...a, ...Array<string>(8 - a.length - b.length).fill('0'), ...b];
  const numbers = groups.map((part) => Number.parseInt(part, 16));
  if (numbers.slice(0, 5).every((n) => n === 0) && numbers[5] === 65535) {
    return [numbers[6]! >> 8, numbers[6]! & 255, numbers[7]! >> 8, numbers[7]! & 255].join('.');
  }
  return numbers.map((n) => n.toString(16).padStart(4, '0')).join(':');
}

export function trustedPeer(peer: string, setting = process.env.SALON_WEB_TRUSTED_PROXIES ?? ''): boolean {
  const list = new BlockList();
  for (const cidr of setting.split(',').filter(Boolean)) {
    const [ip, bits, extra] = cidr.trim().split('/');
    const normalized = normalizeIp(ip);
    if (!normalized || bits === undefined || extra || !/^\d+$/.test(bits))
      throw new Error('Invalid SALON_WEB_TRUSTED_PROXIES CIDR');
    const family = isIP(normalized) === 4 ? 'ipv4' : 'ipv6';
    const prefix = Number(bits);
    if (prefix > (family === 'ipv4' ? 32 : 128)) throw new Error('Invalid proxy prefix');
    list.addSubnet(normalized, prefix, family);
  }
  return list.check(peer, isIP(peer) === 4 ? 'ipv4' : 'ipv6');
}

export function clientAddress(peer: string | undefined, forwarded: string | string[] | undefined): string | undefined {
  const ip = normalizeIp(peer);
  if (!ip) return undefined;
  if (!trustedPeer(ip)) return ip;
  // A malformed trusted edge assertion must not silently become another rate identity.
  const address = normalizeIp(forwarded);
  if (!address) throw new Error('Invalid trusted client address');
  return address;
}

export function strictInstant(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 19) === value.slice(0, 19);
}
