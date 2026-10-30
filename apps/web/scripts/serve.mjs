#!/usr/bin/env node
// Serves the web app in the local environment (infra/scripts). `next start` and `next dev` listen
// on one address; this listens on both of this machine's loopback addresses, 127.0.0.1 and ::1,
// and on nothing else, because `localhost` and `alpha.localhost` resolve to ::1 first on some
// machines and to 127.0.0.1 first on others (CR-002). The application itself is Next.js's own
// request handler, unchanged.
//
//   node scripts/serve.mjs --port 3100          the production build (run `next build` first)
//   node scripts/serve.mjs --port 3100 --dev    the development server, with hot reload
//
// SALON_LISTEN_ON_NETWORK=true listens on every network interface instead, for testing from a
// phone on the same network. Anything else, or nothing, keeps it on this machine.
import { createServer } from 'node:http';
import { loadIdentityRuntime, loadOperatorRuntime } from './identity-runtime.mjs';
import { networkInterfaces } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const dev = args.includes('--dev');
const portArgument = args[args.indexOf('--port') + 1];
const port = Number(args.includes('--port') ? portArgument : process.env.PORT);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('serve: a port is required: --port <number>');
  process.exit(2);
}
const onNetwork = process.env.SALON_LISTEN_ON_NETWORK === 'true';

// Next.js reads this when it is loaded, so it is set first and Next.js is imported afterwards.
process.env.NODE_ENV = dev ? 'development' : 'production';
// Local-only key file handling stays outside every deployed Next.js bundle.
loadIdentityRuntime(
  process.env,
  resolve(dirname(fileURLToPath(import.meta.url)), '../../../.local/identity-keys.properties'),
);
loadOperatorRuntime(
  process.env,
  resolve(dirname(fileURLToPath(import.meta.url)), '../../../.local/operator-keys.properties'),
);
const { default: next } = await import('next');

const hasIPv6Loopback = Object.values(networkInterfaces())
  .flat()
  .some((address) => address?.family === 'IPv6' && address.internal && address.address === '::1');
// `undefined` is every interface, IPv4 and IPv6.
const addresses = onNetwork ? [undefined] : ['127.0.0.1', ...(hasIPv6Loopback ? ['::1'] : [])];

const app = next({ dev, dir: resolve(dirname(fileURLToPath(import.meta.url)), '..'), hostname: 'localhost', port });
await app.prepare();
const handleRequest = app.getRequestHandler();
const handleUpgrade = app.getUpgradeHandler();

const servers = addresses.map((address) => {
  const server = createServer((request, response) => {
    handleRequest(request, response).catch((error) => {
      console.error(error);
      if (!response.headersSent) {
        response.statusCode = 500;
      }
      response.end();
    });
  });
  // The development server's hot reload connects over a WebSocket.
  server.on('upgrade', (request, socket, head) => {
    handleUpgrade(request, socket, head).catch(() => socket.destroy());
  });
  server.on('error', (error) => {
    console.error(`serve: cannot listen on ${address ?? 'every interface'}, port ${port}: ${error.message}`);
    process.exit(1);
  });
  server.listen(port, address, () => {
    console.log(`serve: listening on ${address ?? 'every interface'}, port ${port}${dev ? ' (development)' : ''}`);
  });
  return server;
});

let stopping = false;
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    if (stopping) {
      return;
    }
    stopping = true;
    for (const server of servers) {
      server.close();
      server.closeIdleConnections();
    }
    app.close().finally(() => process.exit(0));
    // A connection that never ends must not keep the process from stopping.
    setTimeout(() => process.exit(0), 5000).unref();
  });
}
