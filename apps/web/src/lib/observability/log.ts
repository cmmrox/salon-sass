import { format } from 'node:util';

import { currentRequest } from './request-context';

/** The name every line of the web app carries, beside the API's `salon-api`. */
export const SERVICE_NAME = 'salon-web';

export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export type LogFields = Record<string, string | number | boolean | undefined>;

/** Where lines go: standard output by default, a capture in tests. */
export type LogSink = (line: string) => void;

const defaultSink: LogSink = (line) => {
  process.stdout.write(line);
};

type JsonObject = { [name: string]: string | number | boolean | JsonObject };

/**
 * Writes one log line: one JSON object in the Elastic Common Schema, shaped as the API's lines are
 * (`@timestamp`, `log.level`, `message`, `service.name`, each dotted name a nested object), with the
 * request's `trace.id` and `span.id` when it is written while a request is served (NFR-16). Fields
 * that are undefined are left out.
 */
export function writeLog(level: LogLevel, message: string, fields: LogFields = {}, sink: LogSink = defaultSink): void {
  const request = currentRequest();
  const flat: LogFields = {
    'log.level': level,
    'service.name': SERVICE_NAME,
    message,
    'trace.id': request?.traceId,
    'span.id': request?.spanId,
    ...fields,
  };
  const line: JsonObject = { '@timestamp': new Date().toISOString() };
  for (const [name, value] of Object.entries(flat)) {
    if (value !== undefined) {
      put(line, name.split('.'), value);
    }
  }
  sink(`${JSON.stringify(line)}\n`);
}

function put(target: JsonObject, path: string[], value: string | number | boolean): void {
  const [head, ...rest] = path as [string, ...string[]];
  if (rest.length === 0) {
    target[head] = value;
    return;
  }
  const next = target[head];
  const child: JsonObject = typeof next === 'object' ? next : {};
  target[head] = child;
  put(child, rest, value);
}

const CONSOLE_LEVELS = { debug: 'DEBUG', log: 'INFO', info: 'INFO', warn: 'WARN', error: 'ERROR' } as const;

/**
 * Turns everything the server writes through `console` (the framework's own messages included)
 * into JSON lines, so that the web app's log is one JSON object per line. Arguments are formatted
 * as `console` formats them; a multi-line message stays on one line.
 */
export function writeConsoleAsJson(target: Console = console, sink: LogSink = defaultSink): void {
  for (const [method, level] of Object.entries(CONSOLE_LEVELS)) {
    (target as unknown as Record<string, (...args: unknown[]) => void>)[method] = (...args: unknown[]) =>
      writeLog(level, format(...args), {}, sink);
  }
}
