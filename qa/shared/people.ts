/**
 * Realistic people for the accounts a test adds beside the fixed roster (decision 099,
 * docs/qa/test-plan.md, "Accounts"): a real-sounding name, an email made from it, a valid Sri Lankan
 * mobile, never "QA", "test" or a random string. The names are disjoint from the roster's
 * (infra/local/accounts.json), so an extra account can never be mistaken for a roster account.
 *
 * Within one process (one Playwright invocation, where a test runs from start to end) no name, email
 * or mobile repeats. The sequence starts at a random point so two processes rarely repeat each other.
 */
import { randomInt } from 'node:crypto';

const FIRST = ['Sanduni', 'Nimal', 'Amaya', 'Chathura', 'Dinithi', 'Harsha', 'Iresha', 'Janaka', 'Kavindi', 'Lasantha',
  'Madhavi', 'Nuwan', 'Oshadi', 'Pradeep', 'Rashmi', 'Sampath', 'Thilini', 'Upul', 'Vindya', 'Yasith', 'Hiruni', 'Gayan',
  'Erandi', 'Buddhika', 'Achini', 'Ravindu', 'Nethmi', 'Supun', 'Dulani', 'Kalana', 'Sewwandi', 'Isuru'];
const LAST = ['Rajapaksa', 'Bandara', 'Karunaratne', 'Wijesinghe', 'Gamage', 'Liyanage', 'Samarasinghe', 'Kumara', 'Mendis',
  'Peiris', 'Ranasinghe', 'Weerasinghe', 'Ekanayake', 'Hettiarachchi', 'Kodikara', 'Amarasekara', 'Gunawardena',
  'Jayasuriya', 'Munasinghe', 'Pathirana', 'Seneviratne', 'Tennakoon', 'Vithanage', 'Wanigasekara', 'Alwis', 'Basnayake',
  'Dharmasena', 'Fonseka', 'Ilangakoon', 'Madugalle', 'Nanayakkara'];
const COMBINATIONS = FIRST.length * LAST.length;

export type Person = { first: string; last: string; name: string; local: string };

let nextPerson = randomInt(COMBINATIONS);
let issuedPeople = 0;
/** The next person of this process; after every combination is used, a number keeps emails apart. */
export function person(): Person {
  const index = nextPerson++ % COMBINATIONS;
  const round = Math.floor(issuedPeople++ / COMBINATIONS);
  // Consecutive people differ in first name; the surname moves on after every first name was used.
  const first = FIRST[index % FIRST.length];
  const last = LAST[Math.floor(index / FIRST.length) % LAST.length];
  const local = `${first}.${last}`.toLowerCase() + (round ? String(round + 1) : '');
  return { first, last, name: `${first} ${last}`, local };
}

/** People whose email at a domain is not in `taken`: for accounts that outlive a test (operators). */
export function personNotIn(taken: Set<string>, domain: string): Person {
  for (let tries = 0; tries < COMBINATIONS * 50; tries++) {
    const candidate = person();
    if (!taken.has(`${candidate.local}@${domain}`)) return candidate;
  }
  throw new Error('ENVIRONMENT: no unused realistic name is left for an extra account');
}

/** Sri Lankan mobile prefixes (Dialog, Mobitel, Airtel, Hutch) the product accepts: `07X` and seven digits. */
const MOBILE_PREFIXES = ['070', '071', '072', '074', '075', '076', '077', '078'];
/** The roster's numbers (`0771234501`...), which an extra account never takes. */
const ROSTER_MOBILE = /^07(71234|72345|73456)\d{3}$/;
let nextMobile = randomInt(1_000_000, 9_000_000);
/** A valid Sri Lankan mobile in local form (`07XXXXXXXX`), unique within this process. */
export function mobile(): string {
  for (;;) {
    const n = nextMobile++;
    const number = `${MOBILE_PREFIXES[n % MOBILE_PREFIXES.length]}${String(n).padStart(7, '0').slice(-7)}`;
    if (!ROSTER_MOBILE.test(number)) return number;
  }
}

/** Email domains of the synthetic salons (the roster's), and one for a salon a test provisions. */
const SALON_DOMAINS: Record<string, string> = { alpha: 'alphasalon.lk', beta: 'betasalon.lk', gamma: 'gammasalon.co.uk' };
export const PROVISIONED_SALON_DOMAIN = 'salonstaff.lk';
export const OPERATOR_DOMAIN = 'salonops.lk';
export function staffDomain(slug?: string): string {
  return (slug && SALON_DOMAINS[slug]) || (slug ? PROVISIONED_SALON_DOMAIN : SALON_DOMAINS.alpha);
}

const WORDS = ['cinnamon', 'harbour', 'lantern', 'monsoon', 'jasmine', 'coconut', 'lagoon', 'saffron', 'temple', 'pepper',
  'veranda', 'banyan', 'marigold', 'tamarind', 'pavilion', 'sapphire', 'rampart', 'orchid', 'paddy', 'kingfisher',
  'ceylon', 'emerald', 'cardamom', 'terrace', 'mango', 'peacock', 'waterfall', 'sandalwood', 'tea', 'estate'];
/**
 * A password for a path that goes through the product (adding staff, changing or resetting a password,
 * provisioning an Owner, operator bootstrap): those keep the identity policy (15 to 128 characters,
 * not a common password), which refuses the roster's development password. A passphrase a person
 * might choose: four words and a number.
 */
export function passphrase(): string {
  const words = Array.from({ length: 4 }, () => WORDS[randomInt(WORDS.length)]);
  words[0] = words[0][0].toUpperCase() + words[0].slice(1);
  return `${words.join(' ')} ${randomInt(10, 100)}`;
}
