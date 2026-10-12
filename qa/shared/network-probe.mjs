/**
 * Which of this machine's addresses a port can be reached on, for a check of what the product
 * listens on. For every address of every network interface it tries to connect to each port
 * given, and to a control: a listener of its own, bound to every interface, on a free port.
 *
 * The control is what makes a refusal mean something. On some machines an address refuses every
 * connection whatever is listening (a VPN route in front of it, a firewall), and "the product's
 * port was refused there" would then be true of any product. Only an address on which the control
 * was reached can tell a port that is open from one that is not.
 *
 *   node network-probe.mjs <port>...
 *
 * prints one JSON array: { interface, address, family, loopback, control, ports: { "<port>": outcome } }
 * where an outcome is "connected", "refused", "timeout", or the error's code.
 */
import net from 'node:net';
import os from 'node:os';

const ports = process.argv.slice(2).map(Number);
if (ports.length === 0 || ports.some((port) => !Number.isInteger(port) || port < 1)) {
  console.error('usage: network-probe.mjs <port>...');
  process.exit(2);
}

function attempt(host, port) {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port });
    const settle = (outcome) => {
      socket.destroy();
      resolve(outcome);
    };
    socket.setTimeout(3000, () => settle('timeout'));
    socket.once('connect', () => settle('connected'));
    socket.once('error', (error) => settle(error.code === 'ECONNREFUSED' ? 'refused' : error.code ?? 'error'));
  });
}

const control = net.createServer((socket) => socket.end());
// "::" with both families: every interface, IPv4 and IPv6, as a product listening on the network would.
await new Promise((resolve, reject) => {
  control.once('error', reject);
  control.listen({ port: 0, host: '::', ipv6Only: false }, resolve);
});
const controlPort = control.address().port;

// Every address at once: an address that answers nothing takes its whole three seconds.
const entries = Object.entries(os.networkInterfaces()).flatMap(([name, addresses]) =>
  (addresses ?? []).map((entry) => ({
    interface: name,
    // A link-local IPv6 address is only an address together with its interface.
    address: entry.family === 'IPv6' && entry.address.toLowerCase().startsWith('fe80') ? `${entry.address}%${name}` : entry.address,
    family: entry.family,
    loopback: entry.internal,
  })),
);
const results = await Promise.all(
  entries.map(async (entry) => {
    const outcomes = await Promise.all([attempt(entry.address, controlPort), ...ports.map((port) => attempt(entry.address, port))]);
    return { ...entry, control: outcomes[0], ports: Object.fromEntries(ports.map((port, index) => [String(port), outcomes[index + 1]])) };
  }),
);
await new Promise((resolve) => control.close(resolve));
console.log(JSON.stringify(results, null, 2));
