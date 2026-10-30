// @vitest-environment node
import { describe, expect, it } from 'vitest';

import { writeConsoleAsJson, writeLog } from './log';
import { runInRequest } from './request-context';

function capture() {
  const lines: string[] = [];
  return { lines, sink: (line: string) => lines.push(line), parsed: () => lines.map((line) => JSON.parse(line)) };
}

describe('writeLog', () => {
  it('writes one JSON object on one line, shaped as the API writes its lines', () => {
    const out = capture();

    writeLog(
      'WARN',
      'first\nsecond',
      { 'http.response.status_code': 503, 'tenant.slug': 'alpha', skipped: undefined },
      out.sink,
    );

    expect(out.lines).toHaveLength(1);
    expect(out.lines[0]).toMatch(/^\{.*\}\n$/);
    expect(out.lines[0]?.slice(0, -1)).not.toContain('\n');
    const [line] = out.parsed();
    expect(line).toMatchObject({
      log: { level: 'WARN' },
      service: { name: 'salon-web' },
      message: 'first\nsecond',
      http: { response: { status_code: 503 } },
      tenant: { slug: 'alpha' },
    });
    expect(Date.parse(line['@timestamp'])).not.toBeNaN();
    expect(line).not.toHaveProperty('skipped');
    expect(line).not.toHaveProperty('trace');
  });

  it('carries the trace of the request it is written for', () => {
    const out = capture();
    const context = { traceId: '4bf92f3577b34da6a3ce929d0e0e4736', spanId: '00f067aa0ba902b7', sampled: true };

    runInRequest(context, () => writeLog('INFO', 'inside', {}, out.sink));

    expect(out.parsed()[0]).toMatchObject({ trace: { id: context.traceId }, span: { id: context.spanId } });
  });
});

describe('writeConsoleAsJson', () => {
  it("turns every console method's output into one JSON line with its level", () => {
    const out = capture();
    const target = { ...console };

    writeConsoleAsJson(target as Console, out.sink);
    target.log('ready in %d ms', 12);
    target.warn('careful');
    target.error(new Error('broken'));

    const lines = out.parsed();
    expect(lines.map((line) => line.log.level)).toEqual(['INFO', 'WARN', 'ERROR']);
    expect(lines[0].message).toBe('ready in 12 ms');
    expect(lines[2].message).toContain('Error: broken');
    expect(out.lines.every((line) => line.split('\n').length === 2)).toBe(true);
  });
});
