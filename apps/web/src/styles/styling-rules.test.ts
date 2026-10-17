import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The styling rules of docs/engineering/web.md, "Styling", rule 10 (CR-016): every screen is
 * Tailwind CSS and shadcn/ui. This fails on any CSS Module, any style sheet other than globals.css,
 * theme.css and plain-page.css, any style sheet import outside the root layout and the plain
 * pages, an arbitrary colour in a class name, or a hex or rgb() colour in a component file.
 * Fix the code, never this test.
 */

/** A file of the application, by its path from apps/web (always with `/`). */
type SourceFile = { path: string; content: string };

/** The only style sheets there are. */
const STYLE_SHEETS = ['src/app/globals.css', 'src/styles/theme.css', 'src/lib/plain-page/plain-page.css'];

/** The only files that import a style sheet, and what each of them imports. */
const STYLE_SHEET_IMPORTS: Record<string, string[]> = {
  'src/app/layout.tsx': ['./globals.css'],
  'src/lib/plain-page/plain-page.ts': ['../../styles/theme.css?raw', './plain-page.css?raw'],
};

/**
 * F02-S04 (CR-016) rebuilds the staff sign-in, account, staff, audit and confirmation screens from
 * shadcn/ui and removes these three CSS Modules, and this allowance with them. Nothing else is let
 * through: not another module, not another importer.
 */
const F02_S04_REMAINING_MODULES = [
  'src/components/account/Account.module.css',
  'src/components/account/Confirmation.module.css',
  'src/components/account/StaffSignIn.module.css',
];

const STYLE_SHEET = /\.(css|scss|sass|less|styl)$/;
const TEST_FILE = /\.test\.[cm]?[jt]sx?$|^src\/test\//;
const CODE_FILE = /\.[cm]?[jt]sx?$/;
/** `import x from '…css'` and `import '…css'` statements, and `require('…css')` or `import('…css')`. */
const STYLE_SHEET_IMPORT = /(?:^\s*import\s+(?:[\w*{}\s,]+\s+from\s+)?|(?<![@\w])(?:require|import)\s*\(\s*)['"]([^'"]+\.(?:css|scss|sass|less|styl)(?:\?[^'"]*)?)['"]/gm;
/** Comments, which may show an import as an example. */
const COMMENT = /\/\*[\s\S]*?\*\/|(?<![:'"\w])\/\/[^\n]*/g;
/** A colour written as a value: `#RGB`, `#RRGGBB` (and alpha forms) or a CSS colour function. */
const COLOUR_VALUE = /(?<![\w&])#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3,4})\b|\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color-mix|color)\(/gi;
/** An arbitrary value holding a colour: `bg-[#D6086A]`, `text-[rgb(…)]`, `border-[color:…]`. */
const ARBITRARY_COLOUR = /[\w-]+-\[(?:color:)?[^\]\s]*?(?:#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color-mix|color)\()[^\]\s]*\]/gi;

/** A file a component lives in: every .tsx, and the code of src/components and src/app. */
function isComponentFile(path: string): boolean {
  return !TEST_FILE.test(path) && (path.endsWith('.tsx') || (/^src\/(components|app)\//.test(path) && CODE_FILE.test(path)));
}

/** Every rule a set of files breaks, one line each. */
export function violations(files: SourceFile[]): string[] {
  const found: string[] = [];
  for (const { path, content } of files) {
    if (STYLE_SHEET.test(path)) {
      if (path.endsWith('.module.css') && !F02_S04_REMAINING_MODULES.includes(path)) {
        found.push(`${path}: a CSS Module; build it from shadcn/ui components and Tailwind utilities`);
      } else if (!STYLE_SHEETS.includes(path) && !F02_S04_REMAINING_MODULES.includes(path)) {
        found.push(`${path}: a style sheet other than globals.css, theme.css and plain-page.css`);
      }
    }
    if (CODE_FILE.test(path) && !path.endsWith('styles/styling-rules.test.ts')) {
      for (const [, target] of content.replace(COMMENT, '').matchAll(STYLE_SHEET_IMPORT)) {
        const allowed = STYLE_SHEET_IMPORTS[path]?.includes(target!) || isRemainingModuleImport(path, target!);
        if (!allowed) {
          found.push(`${path}: imports the style sheet ${target}; only the root layout and the plain pages import one`);
        }
      }
    }
    if (isComponentFile(path)) {
      for (const [match] of content.matchAll(ARBITRARY_COLOUR)) {
        found.push(`${path}: the arbitrary colour ${match}; use a theme colour (bg-primary) or theme variable`);
      }
      for (const [match] of content.replace(ARBITRARY_COLOUR, '').matchAll(COLOUR_VALUE)) {
        found.push(`${path}: the colour ${match}; colours come from the theme`);
      }
    }
  }
  return found;
}

/** An import of one of F02-S04's three remaining modules, by a file beside it. */
function isRemainingModuleImport(importer: string, target: string): boolean {
  if (!target.startsWith('./') || !importer.startsWith('src/components/account/')) {
    return false;
  }
  return F02_S04_REMAINING_MODULES.includes(`src/components/account/${target.slice(2)}`);
}

/** Every file under a directory, as paths from apps/web. */
function filesUnder(root: string, dir: string): SourceFile[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return filesUnder(root, path);
    }
    return [{ path: relative(root, path).split(sep).join('/'), content: readFileSync(path, 'utf8') }];
  });
}

const webRoot = join(__dirname, '..', '..');

describe('the styling rules (docs/engineering/web.md, "Styling")', () => {
  it('hold for the whole of apps/web/src', () => {
    expect(violations(filesUnder(webRoot, join(webRoot, 'src')))).toEqual([]);
  });

  it('allow only the three style sheets, and only F02-S04\'s three remaining modules besides', () => {
    const sheets = filesUnder(webRoot, join(webRoot, 'src')).map((file) => file.path).filter((path) => STYLE_SHEET.test(path));
    for (const path of sheets) {
      expect([...STYLE_SHEETS, ...F02_S04_REMAINING_MODULES], path).toContain(path);
    }
    for (const path of STYLE_SHEETS) {
      expect(sheets, path).toContain(path);
    }
  });
});

describe('the styling rules catch', () => {
  const component = (content: string, path = 'src/components/shell/Example.tsx') => violations([{ path, content }]);

  it('a CSS Module, anywhere else than F02-S04\'s three', () => {
    expect(violations([{ path: 'src/components/shell/Example.module.css', content: '.x { color: inherit; }' }])).toHaveLength(1);
    expect(violations([{ path: 'src/components/account/Other.module.css', content: '' }])).toHaveLength(1);
    expect(violations([{ path: 'src/components/account/Account.module.css', content: '' }])).toEqual([]);
  });

  it('a second style sheet', () => {
    expect(violations([{ path: 'src/styles/extra.css', content: ':root{}' }])).toHaveLength(1);
    expect(violations([{ path: 'src/app/page.scss', content: '' }])).toHaveLength(1);
    expect(violations(STYLE_SHEETS.map((path) => ({ path, content: '' })))).toEqual([]);
  });

  it('a style sheet imported outside the root layout and the plain pages', () => {
    expect(component("import styles from './Example.module.css';")).toHaveLength(1);
    expect(component("import '../../app/globals.css';")).toHaveLength(1);
    expect(component("const Lazy = () => import('./lazy.css');")).toHaveLength(1);
    expect(component("// import styles from './Example.module.css';\nconst css = \"@import './theme.css'\";")).toEqual([]);
    expect(violations([{ path: 'src/app/layout.tsx', content: "import './other.css';" }])).toHaveLength(1);
    expect(violations([{ path: 'src/app/layout.tsx', content: "import './globals.css';" }])).toEqual([]);
    expect(violations([{ path: 'src/lib/plain-page/plain-page.ts', content: "import theme from '../../styles/theme.css?raw';" }])).toEqual([]);
    expect(violations([{ path: 'src/components/account/SignIn.tsx', content: "import styles from './Account.module.css';" }])).toEqual([]);
    expect(violations([{ path: 'src/components/shell/X.tsx', content: "import styles from '../account/Account.module.css';" }])).toHaveLength(1);
  });

  it('an arbitrary colour in a class name', () => {
    expect(component('<div className="bg-[#D6086A]" />')).toHaveLength(1);
    expect(component('<p className="p-2 text-[rgb(20_20_19)]" />')).toHaveLength(1);
    expect(component('<p className="border-[color:#fff]" />')).toHaveLength(1);
    expect(component('<p className="bg-[oklch(0.5_0.2_10)]" />')).toHaveLength(1);
    expect(component('<p className="bg-primary text-primary-foreground w-(--size-sidebar) h-[calc(100vh-var(--size-header))]" />')).toEqual([]);
  });

  it('a hex or rgb() colour in a component file', () => {
    expect(component("const brand = '#D6086A';")).toHaveLength(1);
    expect(component("const ink = '#111';")).toHaveLength(1);
    expect(component('const shade = "rgba(0, 0, 0, 0.5)";')).toHaveLength(1);
    expect(component("const brand = '#D6086A';", 'src/app/(workspace)/workspace/page.ts')).toHaveLength(1);
    expect(component('<a href="#main" data-testid="skip">{t("skip")}</a>')).toEqual([]);
    expect(component("const brand = '#D6086A';", 'src/components/shell/Example.test.tsx')).toEqual([]);
  });
});
