import { describe, expect, it } from 'vitest';
import { moduleRoutes, routeTable } from './routes';

const read = { read: () => null };

// Decision 121: one table of every module's routes, in which nothing is declared twice.
describe('the route table', () => {
  it('merges each module’s pages and actions', () => {
    const table = routeTable(
      moduleRoutes('staff', {
        reads: { 'team-members': read },
        pages: [{ path: '/workspace/team', view: 'team', permission: 'team.read', title: 'team' }],
      }),
      moduleRoutes('catalogue', { reads: { 'catalogue-services': read } }),
    );
    expect(Object.keys(table.reads)).toEqual(['team-members', 'catalogue-services']);
    expect(table.pages.map((page) => page.path)).toEqual(['/workspace/team']);
  });

  it('refuses an action two modules declare', () => {
    expect(() =>
      routeTable(
        moduleRoutes('staff', { reads: { members: read } }),
        moduleRoutes('catalogue', { reads: { members: read } }),
      ),
    ).toThrow(/members/);
  });

  it('refuses a page two modules declare', () => {
    const page = { path: '/workspace/team', view: 'team', permission: 'team.read', title: 'team' };
    expect(() =>
      routeTable(moduleRoutes('staff', { pages: [page] }), moduleRoutes('identity', { pages: [page] })),
    ).toThrow(/\/workspace\/team/);
  });
});
