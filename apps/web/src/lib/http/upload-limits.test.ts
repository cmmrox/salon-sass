import { describe, expect, it } from 'vitest';

import nextConfig from '../../../next.config';

import { PROXY_BODY_LIMIT_BYTES, UPLOAD_LIMIT_BYTES } from './upload-limits';

// F04-S02 fix: Next.js's proxy buffers a body only up to proxyClientMaxBodySize and passes the request on
// truncated. At the 10 MB default a chunked upload over the limit reached the BFF cut to exactly 10 MB, so its
// byte count never passed the limit and the form read as one with no file (400 instead of 413).
describe('the proxy body limit', () => {
  it('is set above the upload limit, so a body over the upload limit still arrives too long', () => {
    expect(PROXY_BODY_LIMIT_BYTES).toBeGreaterThan(UPLOAD_LIMIT_BYTES);
    expect(nextConfig.experimental?.proxyClientMaxBodySize).toBe(PROXY_BODY_LIMIT_BYTES);
  });
});
