import { afterEach, describe, expect, it, vi } from 'vitest';
import { consoleRequestHost, isCanonicalPath, isConsoleHost, isConsolePath } from './console-host';

afterEach(() => vi.unstubAllEnvs());

describe('the console hostname', () => {
  it.each(['console.localhost', 'CONSOLE.LOCALHOST', 'console.localhost:3100', 'console.localhost:1'])('is exactly console.<platform domain>: %s', (host) => {
    expect(isConsoleHost(host, 'localhost')).toBe(true);
  });
  it.each(['localhost', 'localhost:3100', 'alpha.localhost', 'admin.localhost', 'support.localhost', 'consolex.localhost', 'a.console.localhost',
    'console.localhost.example.test', 'console.localhost:0', 'console.localhost:65536', 'console.localhost:x', '127.0.0.1', '[::1]:3100', '', null])('is nothing else: %s', (host) => {
    expect(isConsoleHost(host, 'localhost')).toBe(false);
  });
  it('takes its origin scheme from explicit configuration, never from the request', () => {
    vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
    vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
    expect(consoleRequestHost('console.localhost:3100')).toEqual({ hostname: 'console.localhost', origin: 'http://console.localhost:3100' });
    vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'false');
    expect(consoleRequestHost('console.localhost:3100')?.origin).toBe('https://console.localhost:3100');
    expect(consoleRequestHost('alpha.localhost:3100')).toBeNull();
    vi.stubEnv('SALON_PLATFORM_DOMAIN', '');
    expect(consoleRequestHost('console.localhost:3100')).toBeNull();
  });
});

describe('console paths', () => {
  it.each(['/console', '/console/', '/console/salons', '/console/plans/abc', '/operator-web/principal'])('%s is the console\'s', (path) => expect(isConsolePath(path)).toBe(true));
  it.each(['/consoles', '/operator-webx', '/workspace', '/', '/console;x/salons', '/console/%73alons', '//console/salons', '/a/../console', '/operator-web\\principal'])('%s is not', (path) => expect(isConsolePath(path)).toBe(false));
  it.each(['/console;x', '/x%2e', '//x', '/x//y', '/./x', '/x/..', '/x\\y', 'x'])('%s is not canonical', (path) => expect(isCanonicalPath(path)).toBe(false));
  it.each(['/', '/x', '/x/', '/workspace/sign-in'])('%s is canonical', (path) => expect(isCanonicalPath(path)).toBe(true));
});
