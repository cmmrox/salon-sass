/**
 * Asks for addresses from Node.js over IPv4, over IPv6, and left to choose, and with the
 * platform's own fetch: for a check that a name such as alpha.localhost works whichever of
 * 127.0.0.1 and ::1 it resolves to first.
 *
 *   node http-by-family.mjs <url>...
 *
 * prints one line per url and way: <url index>|<4, 6, any or fetch>|<status>|<address reached>|<body>
 * with status 0 and the error's code in place of the address when nothing answered.
 */
import http from 'node:http';

const urls = process.argv.slice(2);
if (urls.length === 0) {
  console.error('usage: http-by-family.mjs <url>...');
  process.exit(2);
}

function get(url, family) {
  return new Promise((resolve) => {
    const request = http.get(url, { family, timeout: 10_000, agent: false }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve({ status: response.statusCode, remote: response.socket.remoteAddress, body: Buffer.concat(chunks).toString('utf8') }));
    });
    request.on('timeout', () => request.destroy(new Error('timeout')));
    request.on('error', (error) => resolve({ status: 0, remote: error.code ?? error.message, body: '' }));
  });
}

const oneLine = (text) => text.replace(/\s+/g, ' ').slice(0, 300);

for (const [index, url] of urls.entries()) {
  for (const [name, family] of [['4', 4], ['6', 6], ['any', 0]]) {
    const answer = await get(url, family);
    console.log(`${index}|${name}|${answer.status}|${answer.remote}|${oneLine(answer.body)}`);
  }
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
    console.log(`${index}|fetch|${response.status}||${oneLine(await response.text())}`);
  } catch (error) {
    console.log(`${index}|fetch|0|${error.cause?.code ?? error.message}|`);
  }
}
