// @vitest-environment node
import { describe, expect, it } from 'vitest';

import { contextFromTraceparent, currentRequest, markTenant, runInRequest, traceparentOf } from './request-context';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';
const PARENT = '00f067aa0ba902b7';

describe('contextFromTraceparent', () => {
  it("continues a caller's W3C trace with a span of its own", () => {
    const context = contextFromTraceparent(`00-${TRACE}-${PARENT}-01`);

    expect(context.traceId).toBe(TRACE);
    expect(context.spanId).toMatch(/^[0-9a-f]{16}$/);
    expect(context.spanId).not.toBe(PARENT);
    expect(context.sampled).toBe(true);
  });

  it('keeps the sampled flag the caller sent', () => {
    expect(contextFromTraceparent(`00-${TRACE}-${PARENT}-00`).sampled).toBe(false);
  });

  it.each([
    ['no header', undefined],
    ['empty', ''],
    ['not hex', `00-${'z'.repeat(32)}-${PARENT}-01`],
    ['upper case', `00-${TRACE.toUpperCase()}-${PARENT}-01`],
    ['a trace id of zeros', `00-${'0'.repeat(32)}-${PARENT}-01`],
    ['a parent id of zeros', `00-${TRACE}-${'0'.repeat(16)}-01`],
    ['the forbidden version', `ff-${TRACE}-${PARENT}-01`],
    ['version 00 with more fields', `00-${TRACE}-${PARENT}-01-extra`],
    ['too short', `00-${TRACE.slice(1)}-${PARENT}-01`],
  ])('starts a new trace when the header is not valid: %s', (_, header) => {
    const context = contextFromTraceparent(header);

    expect(context.traceId).toMatch(/^[0-9a-f]{32}$/);
    expect(context.traceId).not.toBe(TRACE);
    expect(context.traceId).not.toMatch(/^0+$/);
    expect(context.spanId).toMatch(/^[0-9a-f]{16}$/);
    expect(context.sampled).toBe(true);
  });

  it('accepts a later version with more fields, as the W3C specification asks', () => {
    expect(contextFromTraceparent(`01-${TRACE}-${PARENT}-01-extra`).traceId).toBe(TRACE);
  });

  it('gives every request a different new trace', () => {
    expect(contextFromTraceparent(undefined).traceId).not.toBe(contextFromTraceparent(undefined).traceId);
  });
});

describe('traceparentOf', () => {
  it("names the trace and this request's span as the parent of the call", () => {
    const context = { traceId: TRACE, spanId: 'a1b2c3d4e5f60718', sampled: true };

    expect(traceparentOf(context)).toBe(`00-${TRACE}-a1b2c3d4e5f60718-01`);
    expect(traceparentOf({ ...context, sampled: false })).toBe(`00-${TRACE}-a1b2c3d4e5f60718-00`);
  });
});

describe('the request being served', () => {
  it('is what everything run for the request reads, across awaits', async () => {
    const context = contextFromTraceparent(undefined);

    const seen = await runInRequest(context, async () => {
      await new Promise((resolve) => setTimeout(resolve, 1));
      markTenant('alpha');
      return currentRequest();
    });

    expect(seen).toBe(context);
    expect(context.tenantSlug).toBe('alpha');
    expect(currentRequest()).toBeUndefined();
  });

  it('naming a salon outside a request does nothing', () => {
    expect(() => markTenant('alpha')).not.toThrow();
    expect(currentRequest()).toBeUndefined();
  });
});
