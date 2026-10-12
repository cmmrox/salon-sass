/**
 * A steady stream of GET requests, for a check that takes a dependency away while requests are in
 * flight: so many callers, each asking again the moment it is answered, taking the addresses in
 * turn, until the stop file appears. One line of JSON per request is appended to the output file:
 *
 *   { "start": <ms>, "end": <ms>, "ms": <duration>, "url": "...", "status": 200, "body": "..." }
 *
 * `status` is 0 when no answer came within the request's own limit (`error` then says why). A
 * request is never retried and nothing is concluded here: the check that started the stream reads
 * the lines and judges them against the times at which it froze and thawed the dependency.
 *
 *   node request-stream.mjs --out <file> --stop <file> --callers 24 [--pause-ms 0] [--limit-ms 60000] <url>...
 *
 * `--pause-ms` is how long a caller waits between an answer and its next request (none by default).
 */
import fs from 'node:fs';
import http from 'node:http';

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const at = args.indexOf(`--${name}`);
  if (at === -1) {
    return fallback;
  }
  return args.splice(at, 2)[1];
};
const out = option('out');
const stop = option('stop');
const callers = Number(option('callers', '24'));
const limitMs = Number(option('limit-ms', '60000'));
const pauseMs = Number(option('pause-ms', '0'));
const urls = args;
if (!out || !stop || urls.length === 0 || !Number.isFinite(callers) || callers < 1) {
  console.error('usage: request-stream.mjs --out <file> --stop <file> [--callers n] [--pause-ms n] [--limit-ms n] <url>...');
  process.exit(2);
}

const sink = fs.createWriteStream(out, { flags: 'a' });
// One connection per request: a caller that kept a connection alive would not be a new arrival.
const agent = new http.Agent({ keepAlive: false });
let next = 0;

function once(url) {
  return new Promise((resolve) => {
    const start = Date.now();
    const finish = (status, body, error) => {
      const end = Date.now();
      resolve({ start, end, ms: end - start, url, status, body: body.slice(0, 400), ...(error ? { error } : {}) });
    };
    const request = http.get(url, { agent, timeout: limitMs }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => finish(response.statusCode ?? 0, Buffer.concat(chunks).toString('utf8')));
      response.on('error', (error) => finish(0, '', error.code ?? error.message));
    });
    request.on('timeout', () => request.destroy(new Error(`no answer within ${limitMs} ms`)));
    request.on('error', (error) => finish(0, '', error.code ?? error.message));
  });
}

async function caller() {
  while (!fs.existsSync(stop)) {
    const url = urls[next % urls.length];
    next += 1;
    sink.write(`${JSON.stringify(await once(url))}\n`);
    if (pauseMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, pauseMs));
    }
  }
}

await Promise.all(Array.from({ length: callers }, caller));
await new Promise((resolve) => sink.end(resolve));
