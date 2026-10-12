/**
 * The web app's translation file, read from the build. A browser test names an element by the
 * words a person sees, and takes those words from here, so that it never carries a copy of a
 * string a translation will change.
 */
import fs from 'node:fs';
import path from 'node:path';

import { REPO_ROOT } from './env';

export const MESSAGES_PATH = path.join(REPO_ROOT, 'apps', 'web', 'messages', 'en.json');

type Tree = { [key: string]: string | Tree };

const messages = JSON.parse(fs.readFileSync(MESSAGES_PATH, 'utf8')) as Tree;

/** The string at a dotted key, for example `status.unavailable.retry`. Fails on a missing key. */
export function message(key: string): string {
  let node: string | Tree | undefined = messages;
  for (const part of key.split('.')) {
    node = typeof node === 'object' ? node[part] : undefined;
  }
  if (typeof node !== 'string' || node.length === 0) {
    throw new Error(`apps/web/messages/en.json has no string at "${key}"`);
  }
  return node;
}

/** Every string in the translation file. */
export function allMessages(): string[] {
  const values: string[] = [];
  const walk = (node: string | Tree): void => {
    if (typeof node === 'string') {
      values.push(node);
    } else {
      Object.values(node).forEach(walk);
    }
  };
  walk(messages);
  if (values.length === 0) {
    throw new Error('apps/web/messages/en.json holds no strings');
  }
  return values;
}
