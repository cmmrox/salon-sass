import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fireEvent, screen } from '@testing-library/react';
import type { AnchorHTMLAttributes } from 'react';
import { describe, expect, it, vi } from 'vitest';
import NotFound from './not-found';
import { Notice } from '@/components/shared/Notice';
import { AppShell } from '@/components/shared/AppShell';
import { WorkspaceShell } from '@/surfaces/workspace/WorkspaceShell';
import { StatusBody, StatusPanel } from '@/components/shared/Status';
import { renderWithMessages } from '@/test/render';
import { setViewportWidth } from '@/test/viewport';

/**
 * docs/engineering/web.md, "Styling", rules 1 and 10 (CR-016, CR-017): every product component
 * under src/app, src/surfaces, src/features and src/components is composed of shadcn/ui components from components/ui. This
 * fails on a raw control, table or dialog element, on Native Select, and when a screen stops
 * rendering the shadcn/ui component (its `data-slot`) the component map names for its job.
 * Fix the code, never this test.
 */

/** A product component file, by its path from apps/web (always with `/`). */
type SourceFile = { path: string; content: string };

const web = join(__dirname, '..', '..');
const RAW_ELEMENT = /<(button|input|select|textarea|table|dialog)\b/g;
const NATIVE_SELECT = /from\s+['"][^'"]*\/native-select['"]|\bNativeSelect\w*\b/g;
const COMMENT = /\/\*[\s\S]*?\*\/|(?<![:'"\w])\/\/[^\n]*/g;

/** Every rule a set of product component files breaks, one line each. */
export function compositionViolations(files: SourceFile[]): string[] {
  const found: string[] = [];
  for (const { path, content } of files) {
    const code = content.replace(COMMENT, '');
    for (const [, element] of code.matchAll(RAW_ELEMENT)) {
      found.push(`${path}: a raw <${element}>; use the shadcn/ui component from components/ui`);
    }
    if ([...code.matchAll(NATIVE_SELECT)].length > 0) {
      found.push(`${path}: Native Select; use the shadcn/ui Select`);
    }
  }
  return found;
}

/** The product's component files: .tsx under src/app, src/surfaces, src/features and src/components, except components/ui and tests. */
function productComponents(): SourceFile[] {
  const walk = (dir: string): string[] => readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
  return ['app', 'surfaces', 'features', 'components']
    .flatMap((area) => walk(join(web, 'src', area)))
    .map((path) => relative(web, path).split(sep).join('/'))
    .filter((path) => path.endsWith('.tsx') && !path.includes('.test.') && !path.startsWith('src/components/ui/'))
    .map((path) => ({ path, content: readFileSync(join(web, path), 'utf8') }));
}

describe('every product component (src/app, src/surfaces, src/features and src/components)', () => {
  it('writes no raw control, table or dialog element and no Native Select', () => {
    const files = productComponents();
    expect(files.length).toBeGreaterThan(20);
    expect(files.map((file) => file.path)).toEqual(expect.arrayContaining(['src/app/not-found.tsx', 'src/components/shared/Navigation.tsx', 'src/features/identity/components/AccountScreen.tsx']));
    expect(compositionViolations(files)).toEqual([]);
  });

  it('has no Native Select to use: ui/native-select.tsx is gone (CR-017)', () => {
    expect(existsSync(join(web, 'src', 'components', 'ui', 'native-select.tsx'))).toBe(false);
  });

  it('catches each violation', () => {
    const one = (content: string) => compositionViolations([{ path: 'src/components/x/X.tsx', content }]);
    for (const element of ['button', 'input type="checkbox"', 'select', 'textarea', 'table', 'dialog open']) expect(one(`<${element} />`), element).toHaveLength(1);
    expect(one("import { NativeSelect } from '@/components/ui/native-select';")).toHaveLength(1);
    expect(one('<NativeSelectOption value="a" />')).toHaveLength(1);
    expect(one('<Button>x</Button><Input /><Select /><SelectTrigger /><Table />')).toEqual([]);
    expect(one('// a <button> in a comment\n<p>{t("x")}</p>')).toEqual([]);
  });
});

vi.mock('next/link', () => ({ default: (props: AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props} /> }));

const slot = (testId: string) => screen.getByTestId(testId).getAttribute('data-slot');
const salon = { slug: 'beta', name: 'Beta Hair and Beauty', brandColour: '#1F2937', timeZone: 'Asia/Colombo' };

describe('the shell, status and notice screens render the shadcn/ui components of the component map', () => {
  it('workspace frame from 900px: SidebarProvider, Sidebar rail with SidebarMenu buttons, SidebarTrigger and SidebarInset', () => {
    setViewportWidth(1280);
    renderWithMessages(<WorkspaceShell salon={salon} permissions={['staff.list', 'audit.list']} view="staff"><p /></WorkspaceShell>);
    expect(slot('workspace-shell')).toBe('sidebar-wrapper');
    expect(slot('workspace-main')).toBe('sidebar-inset');
    expect(slot('workspace-menu')).toBe('sidebar-trigger');
    const navigation = screen.getByTestId('workspace-navigation');
    expect(navigation.closest('[data-slot="sidebar"]')).not.toBeNull();
    expect(navigation.closest('[data-slot="sidebar-content"]')).not.toBeNull();
    expect(navigation.querySelectorAll('[data-slot="sidebar-menu"]')).toHaveLength(1);
    expect(navigation.querySelectorAll('[data-slot="sidebar-menu-item"]')).toHaveLength(3);
    for (const id of ['workspace-account', 'workspace-staff', 'workspace-audit']) expect(slot(id)).toBe('sidebar-menu-button');
  });

  it('workspace frame below 900px: the Sidebar is its Sheet, opened by the SidebarTrigger', () => {
    setViewportWidth(360);
    renderWithMessages(<WorkspaceShell salon={salon} permissions={['staff.list']}><p /></WorkspaceShell>);
    fireEvent.click(screen.getByTestId('workspace-menu'));
    const sheet = screen.getByRole('dialog');
    expect(sheet).toHaveAttribute('data-slot', 'sidebar');
    expect(document.querySelector('[data-slot="sheet-overlay"]')).not.toBeNull();
    expect(sheet.querySelectorAll('[data-slot="sidebar-menu-button"]')).toHaveLength(2);
  });

  it('status page: Card and CardTitle, Skeleton while loading, a positive Alert when reachable, a caution Alert and Button when not', () => {
    const { unmount } = renderWithMessages(<AppShell><StatusPanel><StatusBody state={{ kind: 'loading' }} retryHref="/" /></StatusPanel></AppShell>);
    expect(slot('status-panel')).toBe('card');
    expect(slot('status-heading')).toBe('card-title');
    expect(screen.getByTestId('status-loading').querySelectorAll('[data-slot="skeleton"]')).toHaveLength(3);
    unmount();

    renderWithMessages(<StatusBody state={{ kind: 'loaded', service: 'salon-api', version: '1' }} retryHref="/" />);
    expect(slot('status-summary')).toBe('alert');
    expect(screen.getByTestId('status-summary')).toHaveClass('bg-positive-surface');
    expect(screen.getByTestId('status-summary')).toHaveAttribute('role', 'status');
    expect(screen.getByTestId('status-summary').querySelector('[data-slot="alert-title"]')).not.toBeNull();

    renderWithMessages(<StatusBody state={{ kind: 'unavailable' }} retryHref="/" />);
    expect(slot('status-unavailable')).toBe('alert');
    expect(slot('status-unavailable-title')).toBe('alert-title');
    expect(slot('status-retry')).toBe('button');
  });

  it('notices and the application not-found page: Card and CardTitle', () => {
    renderWithMessages(<Notice testId="salon-not-found" title="t" body="b" />);
    expect(slot('salon-not-found')).toBe('card');
    expect(slot('salon-not-found-title')).toBe('card-title');

    renderWithMessages(<NotFound />);
    expect(slot('not-found')).toBe('card');
    expect(slot('not-found-title')).toBe('card-title');
  });
});
