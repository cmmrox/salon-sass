// @vitest-environment node
import { join } from 'node:path';

import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';

/**
 * The conformance rules of eslint.config.mjs (docs/engineering/web.md, "Styling", rules 1 and 4;
 * CR-016, CR-017): the project's own configuration, run on code it must refuse and code it must
 * accept, at the paths where that code would live. `pnpm lint` applies them to the whole of src.
 */
const web = join(__dirname, '..', '..');
const eslint = new ESLint({ cwd: web });

/** The conformance messages ESLint gives this code at this path (other rules' messages are left out). */
async function conformance(code: string, path: string): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath: join(web, path) });
  return result!.messages.filter((message) => message.ruleId === 'no-restricted-syntax').map((m) => m.message);
}

const SCREEN = 'src/features/catalogue/components/Example.tsx';
const component = (jsx: string) => `export function Example() {\n  return ${jsx};\n}\n`;

describe('the conformance lint refuses', () => {
  it.each(['button', 'input', 'select', 'textarea', 'table', 'dialog'])(
    'a raw <%s> in a product component',
    async (element) => {
      expect(await conformance(component(`<${element} />`), SCREEN)).toEqual([
        expect.stringContaining('shadcn/ui component from components/ui'),
      ]);
      expect(
        await conformance(component(`<${element} />`), 'src/app/(workspace)/workspace/example/page.tsx'),
      ).toHaveLength(1);
    },
  );

  it('Native Select, by import or by element', async () => {
    expect(await conformance("import { NativeSelect } from '@/components/ui/native-select';\n", SCREEN)).toHaveLength(
      1,
    );
    expect(await conformance(component('<NativeSelectOption value="a" />'), SCREEN)).toHaveLength(1);
  });

  it('a CSS Module import, anywhere in src, components/ui included', async () => {
    for (const path of [SCREEN, 'src/lib/example.ts', 'src/components/ui/example.tsx']) {
      expect(
        await conformance("import styles from './Example.module.css';\nexport const s = styles;\n", path),
        path,
      ).toEqual([expect.stringContaining('No CSS Modules')]);
    }
    expect(await conformance("export const load = () => import('./Lazy.module.css');\n", SCREEN)).toHaveLength(1);
  });

  it('a style prop outside the allow-list, components/ui included', async () => {
    for (const path of [SCREEN, 'src/components/ui/example.tsx', 'src/features/branding/components/Other.tsx']) {
      expect(await conformance(component('<div style={{ color: "red" }} />'), path), path).toEqual([
        expect.stringContaining('No style prop'),
      ]);
    }
  });
});

describe('the conformance lint accepts', () => {
  it('the shadcn/ui components, plain text and layout elements', async () => {
    expect(
      await conformance(
        component('<div><Button>x</Button><Input /><Select /><Table /><Dialog /><output /></div>'),
        SCREEN,
      ),
    ).toEqual([]);
  });

  it('the raw elements inside components/ui, which the components are built from', async () => {
    expect(await conformance(component('<button />'), 'src/components/ui/example.tsx')).toEqual([]);
  });

  it('a comment that names a raw element', async () => {
    expect(await conformance(`// a <button> in a comment\n${component('<p />')}`, SCREEN)).toEqual([]);
  });

  it('a run-time style in the allow-listed branding files, which still may not write a raw control', async () => {
    const path = 'src/features/branding/components/ColourField.tsx';
    expect(await conformance(component('<span style={{ backgroundColor: swatch }} />'), path)).toEqual([]);
    expect(await conformance(component('<button />'), path)).toHaveLength(1);
  });
});
