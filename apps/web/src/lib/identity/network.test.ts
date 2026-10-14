// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clientAddress, strictInstant } from './network';
afterEach(() => vi.unstubAllEnvs());
describe('trusted browser address', () => {
  it('ignores browser forwarded headers when direct local sockets are not trusted', () => { vi.stubEnv('SALON_WEB_TRUSTED_PROXIES', ''); expect(clientAddress('127.0.0.2', '203.0.113.5')).toBe('127.0.0.2'); });
  it('accepts one edge address only from an explicitly trusted peer range', () => { vi.stubEnv('SALON_WEB_TRUSTED_PROXIES', '10.89.43.0/24'); expect(clientAddress('10.89.43.2', '203.0.113.5')).toBe('203.0.113.5'); expect(clientAddress('10.89.44.2', '203.0.113.5')).toBe('10.89.44.2'); });
  it('rejects a malformed/list-valued trusted header instead of using a different rate dimension', () => { vi.stubEnv('SALON_WEB_TRUSTED_PROXIES', '10.89.43.0/24'); expect(() => clientAddress('10.89.43.2', '1.2.3.4,5.6.7.8')).toThrow(); });
  it('rejects normalized-invalid calendar dates and leap seconds', () => { expect(strictInstant('2026-02-31T00:00:00Z')).toBe(false); expect(strictInstant('2026-01-01T00:00:60Z')).toBe(false); });
});
