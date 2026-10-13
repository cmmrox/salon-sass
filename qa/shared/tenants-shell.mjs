/**
 * The synthetic salons, for a shell check: prints shell assignments read from shared/tenants.ts,
 * so that no check writes a salon's id, name or hostname of its own.
 *
 *   eval "$(node "$QA_ROOT/shared/tenants-shell.mjs")"
 *
 * gives SALON_SLUGS ("alpha beta gamma"), and for each salon ALPHA_ID, ALPHA_SLUG, ALPHA_NAME,
 * ALPHA_COLOUR and ALPHA_HOST; and NIL_TENANT_ID, PLATFORM_DOMAIN and RESERVED_SLUGS.
 */
import { NIL_TENANT_ID, PLATFORM_DOMAIN, RESERVED_SLUGS, salons } from './tenants.ts';

/** A value quoted for a POSIX shell, whatever it contains. */
const quoted = (value) => `'${String(value).replaceAll("'", `'\\''`)}'`;

const lines = [
  `SALON_SLUGS=${quoted(salons.map((salon) => salon.slug).join(' '))}`,
  `NIL_TENANT_ID=${quoted(NIL_TENANT_ID)}`,
  `PLATFORM_DOMAIN=${quoted(PLATFORM_DOMAIN)}`,
  `RESERVED_SLUGS=${quoted(RESERVED_SLUGS.join(' '))}`,
];
for (const salon of salons) {
  const prefix = salon.slug.toUpperCase();
  lines.push(
    `${prefix}_ID=${quoted(salon.id)}`,
    `${prefix}_SLUG=${quoted(salon.slug)}`,
    `${prefix}_NAME=${quoted(salon.name)}`,
    `${prefix}_COLOUR=${quoted(salon.brandColour)}`,
    `${prefix}_HOST=${quoted(salon.hostname)}`,
    `${prefix}_ZONE=${quoted(salon.timeZone)}`,
  );
}
if (salons.length < 2) {
  throw new Error('shared/tenants.ts lists fewer than two salons; an isolation check needs two');
}
console.log(lines.join('\n'));
