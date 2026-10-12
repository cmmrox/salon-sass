import { describe, expect, it } from 'vitest';

import { classifyHostname, hasSlugForm, platformDomainOf, salonHostname, slugOf } from './hostname';

// The same table of cases as the API's PlatformHostnameTest
// (apps/api/src/test/java/com/salonplatform/shared/tenancy/PlatformHostnameTest.java), so that the
// web app and the API agree on which hostname can name a salon.
describe('slugOf: which hostnames can name a salon', () => {
  const localhost = platformDomainOf('localhost');

  it.each([
    ['alpha.localhost', 'alpha'],
    ['alpha.localhost:8090', 'alpha'],
    ['ALPHA.LOCALHOST:8090', 'alpha'],
    ['Alpha.LocalHost', 'alpha'],
    [' alpha.localhost:3100 ', 'alpha'],
    ['x.localhost', 'x'],
    ['console.localhost', 'console'],
  ])('exactly one label in front of the platform domain is a candidate slug: %j -> %s', (host, slug) => {
    expect(slugOf(host, localhost)).toBe(slug);
  });

  it.each([
    'localhost',
    'localhost:8090',
    'LOCALHOST',
    'alpha.beta.localhost',
    'alpha.beta.localhost:8090',
    'alpha.localhost.example.com',
    'alpha.localhost.example.com:8090',
    '.localhost',
    '.localhost:8090',
    'alpha.localhost.',
    'alphalocalhost',
    '127.0.0.1',
    '127.0.0.1:8090',
    '[::1]',
    '[::1]:8090',
    'example.com',
    'alpha.example.com',
  ])('any other hostname names no salon: %j', (host) => {
    expect(slugOf(host, localhost)).toBeNull();
  });

  it.each([null, undefined, ''])('no host names no salon: %j', (host) => {
    expect(slugOf(host, localhost)).toBeNull();
  });

  it('matches another platform domain the same way', () => {
    const staging = platformDomainOf('Salons.Example.COM');

    expect(slugOf('alpha.salons.example.com:443', staging)).toBe('alpha');
    expect(slugOf('salons.example.com', staging)).toBeNull();
    expect(slugOf('alpha.localhost', staging)).toBeNull();
    expect(slugOf('a.b.salons.example.com', staging)).toBeNull();
    expect(slugOf('evilsalons.example.com', staging)).toBeNull();
  });

  it.each(['', ' ', '.localhost', 'localhost.', 'localhost:8090', undefined])(
    'refuses a platform domain that is not a hostname: %j',
    (domain) => {
      expect(() => platformDomainOf(domain)).toThrow(/platform domain/);
    },
  );
});

describe('classifyHostname', () => {
  const localhost = platformDomainOf('localhost');

  it.each(['localhost', 'localhost:3100', 'LocalHost:3100', ' localhost '])(
    'the platform domain itself, with or without a port, is the platform: %j',
    (host) => {
      expect(classifyHostname(host, localhost)).toEqual({ kind: 'platform' });
    },
  );

  it.each(['alpha.beta.localhost', 'alpha.localhost.example.com', '127.0.0.1:3100', '[::1]:3100', 'example.com'])(
    'a hostname that is neither the platform domain nor one label in front of it is none: %j',
    (host) => {
      expect(classifyHostname(host, localhost)).toEqual({ kind: 'none' });
    },
  );

  it('one label in front of the platform domain is a candidate, in lower case', () => {
    expect(classifyHostname('Gamma.LOCALHOST:3100', localhost)).toEqual({ kind: 'candidate', slug: 'gamma' });
  });
});

describe('hasSlugForm', () => {
  it.each(['alpha', 'abc', 'a-b', 'salon-2', '123', 'a'.repeat(30)])('a slug: %j', (label) => {
    expect(hasSlugForm(label)).toBe(true);
  });

  it.each(['x', 'ab', 'a'.repeat(31), '-alpha', 'alpha-', 'al_pha', 'al pha', 'ALPHA', 'alpha%2e', 'al/pha', ''])(
    'not a slug: %j',
    (label) => {
      expect(hasSlugForm(label)).toBe(false);
    },
  );
});

describe('salonHostname', () => {
  it('is the slug in front of the platform domain, with no port', () => {
    expect(salonHostname('alpha', 'localhost')).toBe('alpha.localhost');
  });
});
