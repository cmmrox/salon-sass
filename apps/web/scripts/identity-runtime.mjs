import { readFileSync } from 'node:fs';

/** Local launcher only: this module is never imported by a Next.js server bundle. */
export function loadIdentityRuntime(environment, defaultKeyFile) {
  const local = environment.SALON_WEB_LOCAL_HTTP;
  if (local !== undefined && local !== 'true' && local !== 'false') throw new Error('SALON_WEB_LOCAL_HTTP must be true or false');
  let key = environment.SALON_IDENTITY_PROXY_KEY;
  if (!key && local === 'true') {
    let saved;
    try { saved = readFileSync(environment.SALON_IDENTITY_LOCAL_KEY_FILE ?? defaultKeyFile, 'utf8'); }
    catch { throw new Error('Cannot read retained local proxy key'); }
    const matches = [...saved.matchAll(/^proxy=([a-fA-F0-9]{64})$/gm)];
    if (matches.length === 1) key = matches[0][1];
  }
  if (!key || !/^[a-fA-F0-9]{64}$/.test(key)) throw new Error('Configure SALON_IDENTITY_PROXY_KEY with an independent 64-hex secret');
  environment.SALON_IDENTITY_PROXY_KEY = key;
}

/**
 * Local launcher only: the operator console's BFF proxy key (F03-S01). Outside explicit local HTTP it
 * must be configured; locally it may be read from the API's retained operator key file. Never printed.
 */
export function loadOperatorRuntime(environment, defaultKeyFile) {
  const local = environment.SALON_WEB_LOCAL_HTTP;
  let key = environment.SALON_OPERATOR_PROXY_KEY;
  if (!key && local === 'true') {
    let saved;
    try { saved = readFileSync(environment.SALON_OPERATOR_LOCAL_KEY_FILE ?? defaultKeyFile, 'utf8'); }
    catch { throw new Error('Cannot read retained local operator proxy key; start the API once first'); }
    const matches = [...saved.matchAll(/^proxy=([a-fA-F0-9]{64})$/gm)];
    if (matches.length === 1) key = matches[0][1];
  }
  if (!key || !/^[a-fA-F0-9]{64}$/.test(key)) throw new Error('Configure SALON_OPERATOR_PROXY_KEY with an independent 64-hex secret');
  if (environment.SALON_IDENTITY_PROXY_KEY && environment.SALON_IDENTITY_PROXY_KEY.toLowerCase() === key.toLowerCase()) throw new Error('SALON_OPERATOR_PROXY_KEY must differ from SALON_IDENTITY_PROXY_KEY');
  environment.SALON_OPERATOR_PROXY_KEY = key;
}
