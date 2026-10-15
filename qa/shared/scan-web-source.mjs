#!/usr/bin/env node
// Scans the web app's source for two things AC-9 of F01-S01 forbids, and that a browser test can
// only see on the screens it happens to open:
//
//   1. a string a person would see or hear that is written into a component instead of coming
//      from a translation file;
//   2. an element a person can press or type into that carries no data-testid.
//
// It also checks the translation files themselves: every value is a non-empty string, and every
// language has the same keys.
//
//   node qa/shared/scan-web-source.mjs <web app folder>      for example apps/web
//
// Exits 0 when nothing is found, 1 with one line per finding ("<file>:<line> <what>"), and 2 when
// it was given nothing to scan: a scan that read no component must never look like a pass.
import fs from 'node:fs';
import path from 'node:path';

import ts from 'typescript';

const root = path.resolve(process.argv[2] ?? '');
const sourceDirectory = path.join(root, 'src');
const messagesDirectory = path.join(root, 'messages');
if (!process.argv[2] || !fs.existsSync(sourceDirectory) || !fs.existsSync(messagesDirectory)) {
  console.error(`scan: ${root} has no src/ and messages/ folders; give the web app's folder`);
  process.exit(2);
}

// Attributes whose value is shown or spoken.
const SPOKEN = new Set(['aria-label', 'aria-description', 'aria-placeholder', 'aria-roledescription', 'aria-valuetext', 'alt', 'title', 'placeholder', 'label']);
// Elements a person presses or types into, and the components that render one.
const PRESSABLE_TAGS = new Set(['a', 'button', 'input', 'select', 'textarea', 'summary', 'Link']);
const PRESSABLE_ROLES = new Set(['button', 'link', 'checkbox', 'radio', 'switch', 'tab', 'menuitem', 'option', 'textbox', 'combobox', 'slider']);
// Keys of the page metadata a browser shows.
const METADATA_KEYS = new Set(['title', 'description']);

const findings = [];
const counts = { files: 0, elements: 0, pressable: 0, strings: 0 };

function filesUnder(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      return entry.name === 'test' ? [] : filesUnder(full);
    }
    return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) && !entry.name.endsWith('.d.ts') ? [full] : [];
  });
}

const hasWords = (text) => /[\p{L}\p{N}]/u.test(text);

function scan(file) {
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const shown = path.relative(root, file);
  const report = (node, what) => {
    const { line } = source.getLineAndCharacterOfPosition(node.getStart(source));
    findings.push(`${shown}:${line + 1} ${what}`);
  };
  const literalText = (node) =>
    ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) ? node.text : ts.isTemplateExpression(node) ? node.head.text + node.templateSpans.map((span) => span.literal.text).join('') : null;
  const controls=path.join(root,'src/components/account/Controls.tsx');
  const buttonContract=fs.existsSync(controls)&&/data-testid=\{testId\}/.test(fs.readFileSync(controls,'utf8'));
  const importsButton=source.statements.some(statement=>ts.isImportDeclaration(statement)&&ts.isStringLiteral(statement.moduleSpecifier)&&
    /(?:^@\/components\/account\/Controls$|^\.\/Controls$)/.test(statement.moduleSpecifier.text)&&statement.importClause?.namedBindings&&ts.isNamedImports(statement.importClause.namedBindings)&&
    statement.importClause.namedBindings.elements.some(element=>element.name.text==='Button'&&(element.propertyName?.text??element.name.text)==='Button'));

  /** String literals an expression can evaluate to: `'a'`, `x ? 'a' : 'b'`, `x && 'a'`, `x ?? 'a'`. */
  const literalsOf = (expression) => {
    if (!expression) return [];
    if (literalText(expression) !== null) return [expression];
    if (ts.isParenthesizedExpression(expression)) return literalsOf(expression.expression);
    if (ts.isConditionalExpression(expression)) return [...literalsOf(expression.whenTrue), ...literalsOf(expression.whenFalse)];
    if (ts.isBinaryExpression(expression)) {
      if(expression.operatorToken.kind===ts.SyntaxKind.AmpersandAmpersandToken)return literalsOf(expression.right);
      if([ts.SyntaxKind.BarBarToken,ts.SyntaxKind.QuestionQuestionToken,ts.SyntaxKind.PlusToken].includes(expression.operatorToken.kind))return [...literalsOf(expression.left),...literalsOf(expression.right)];
    }
    return [];
  };

  const attributesOf = (opening) => {
    const attributes = new Map();
    for (const attribute of opening.attributes.properties) {
      if (ts.isJsxAttribute(attribute)) {
        attributes.set(attribute.name.getText(source), attribute);
      }
    }
    return attributes;
  };

  const checkElement = (opening) => {
    counts.elements += 1;
    const tag = opening.tagName.getText(source);
    const attributes = attributesOf(opening);
    const role = attributes.get('role')?.initializer;
    const roleName = role && ts.isStringLiteral(role) ? role.text : null;
    const wrappedButton=tag==='Button'&&importsButton;
    const pressable = wrappedButton || PRESSABLE_TAGS.has(tag) || (roleName && PRESSABLE_ROLES.has(roleName)) || attributes.has('onClick') || attributes.has('href');
    if (pressable) {
      counts.pressable += 1;
      const testId = attributes.get('data-testid')??(wrappedButton&&buttonContract?attributes.get('testId'):undefined);
      const value = testId?.initializer;
      const empty = !testId || !value || (ts.isStringLiteral(value) && value.text.trim() === '');
      if (empty) {
        report(opening, `<${tag}> can be pressed or typed into and has no data-testid`);
      }
    }
    for (const [name, attribute] of attributes) {
      if (!SPOKEN.has(name) || !attribute.initializer) continue;
      const value = ts.isJsxExpression(attribute.initializer) ? attribute.initializer.expression : attribute.initializer;
      for (const literal of literalsOf(value)) {
        if (hasWords(literalText(literal))) {
          report(literal, `${name}="${literalText(literal)}" is written into the component; it must come from a translation file`);
        }
      }
    }
  };

  const visit = (node) => {
    if (ts.isJsxText(node) && hasWords(node.text)) {
      report(node, `the text "${node.text.trim().replace(/\s+/g, ' ')}" is written into the component; it must come from a translation file`);
    }
    if (ts.isJsxExpression(node) && node.parent && (ts.isJsxElement(node.parent) || ts.isJsxFragment(node.parent))) {
      for (const literal of literalsOf(node.expression)) {
        if (hasWords(literalText(literal))) {
          report(literal, `the text "${literalText(literal)}" is written into the component; it must come from a translation file`);
        }
      }
    }
    if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) {
      checkElement(node);
    }
    // Page metadata: `export const metadata = { title: '...' }` or `return { title: '...' }` in generateMetadata.
    if (ts.isPropertyAssignment(node) && METADATA_KEYS.has(node.name.getText(source))) {
      const inMetadata = (() => {
        for (let parent = node.parent; parent; parent = parent.parent) {
          if (ts.isVariableDeclaration(parent) && parent.name.getText(source) === 'metadata') return true;
          if (ts.isFunctionDeclaration(parent) && parent.name?.getText(source) === 'generateMetadata') return true;
        }
        return false;
      })();
      if (inMetadata) {
        for (const literal of literalsOf(node.initializer)) {
          if (hasWords(literalText(literal))) {
            report(literal, `the page's ${node.name.getText(source)} "${literalText(literal)}" is written into the code; it must come from a translation file`);
          }
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
}

function flatten(node, prefix, out, file) {
  for (const [key, value] of Object.entries(node)) {
    const name = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      flatten(value, name, out, file);
    } else if (typeof value === 'string' && value.trim() !== '') {
      out.set(name, value);
      counts.strings += 1;
    } else {
      findings.push(`${file} the key "${name}" is not a non-empty string`);
    }
  }
}

const files = filesUnder(sourceDirectory);
for (const file of files) {
  counts.files += 1;
  scan(file);
}

const languages = new Map();
for (const entry of fs.readdirSync(messagesDirectory).filter((name) => name.endsWith('.json')).sort()) {
  const shown = path.join('messages', entry);
  const keys = new Map();
  try {
    flatten(JSON.parse(fs.readFileSync(path.join(messagesDirectory, entry), 'utf8')), '', keys, shown);
  } catch (error) {
    findings.push(`${shown} is not valid JSON: ${error.message}`);
  }
  languages.set(shown, keys);
}
const [first, ...others] = [...languages.entries()];
for (const [file, keys] of others) {
  for (const key of first[1].keys()) {
    if (!keys.has(key)) findings.push(`${file} has no string for "${key}", which ${first[0]} has`);
  }
  for (const key of keys.keys()) {
    if (!first[1].has(key)) findings.push(`${file} has a string for "${key}", which ${first[0]} does not`);
  }
}

console.log(`scan: ${counts.files} source files, ${counts.elements} elements, ${counts.pressable} that can be pressed, ${languages.size} translation file(s), ${counts.strings} strings`);
if (counts.files === 0 || counts.elements === 0 || languages.size === 0 || counts.strings === 0) {
  console.error('scan: nothing was scanned (no component, no element or no translated string was found)');
  process.exit(2);
}
if (findings.length > 0) {
  for (const finding of findings) {
    console.error(`scan: ${finding}`);
  }
  console.error(`scan: ${findings.length} finding(s)`);
  process.exit(1);
}
console.log('scan: every string comes from a translation file and every pressable element has a data-testid');
