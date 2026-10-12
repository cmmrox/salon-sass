/**
 * What an error response, a header or a page must never show a caller: how the product is built.
 * One list, so that every test that looks for a leak looks for the same things.
 */
const INTERNALS: Array<[string, RegExp]> = [
  ['a stack frame', /\bat\s+[\w$.]+\([\w$.]*(?::\d+)?\)/],
  ['a stack frame in JavaScript', /\bat\s+\S+\s+\((?:file:|node:|\/)[^)]*:\d+:\d+\)/],
  ['a Java package or class name', /\b(?:java|javax|jakarta|jdk|sun)\.[a-z]+\.[A-Za-z]/],
  ['a framework class name', /\b(?:org\.springframework|org\.apache|org\.hibernate|org\.postgresql|org\.flywaydb|com\.zaxxer|tools\.jackson|com\.fasterxml|io\.swagger|org\.springdoc)\b/],
  ['the application\'s package name', /com\.salonplatform/],
  ['an exception or error class name', /\b[A-Z]\w*(?:Exception|Error)\b/],
  ['the word "exception"', /exception/i],
  ['a stack trace', /stack ?trace/i],
  ['a framework or server name', /\b(?:tomcat|catalina|coyote|spring|hibernate|hikari|jackson|flyway|whitelabel|postgres(?:ql)?|jdbc|next\.js|node\.js|undici)\b/i],
  ['a connection error code', /\bE(?:CONNREFUSED|CONNRESET|TIMEDOUT|NOTFOUND|PIPE)\b/],
  ['a version number', /\b\d+\.\d+\.\d+(?:[-.][\w.]+)?\b/],
  ['a file path', /(?:\/Users\/|\/home\/|\/var\/|\/opt\/|[A-Z]:\\)[\w./\\-]+/],
];

/** The internals found in the text, each with the words that matched. Empty when there are none. */
export function internalsIn(text: string, allow: RegExp[] = []): string[] {
  const found: string[] = [];
  for (const [what, pattern] of INTERNALS) {
    const match = pattern.exec(text);
    if (match && !allow.some((allowed) => allowed.test(match[0]))) {
      found.push(`${what}: "${match[0]}"`);
    }
  }
  return found;
}
