/**
 * Routes are registered per module (decision 121, docs/engineering/web.md, "Structure"; web H4, M5): each module
 * declares, in its own `features/<module>/server/routes.ts`, its workspace pages, its workspace BFF actions and the
 * permission each needs, through {@link moduleRoutes}; the workspace composes the modules' declarations into one table
 * that the proxy, the page, the frame, the navigation and the BFF read. A lint rule refuses a module's routes declared
 * from anywhere but that module's own `server/routes.ts`.
 */

/** The caller's credential and the request's API budget, for one upstream call. */
export type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };

/** What an upstream call answered: its data or the API's problem, and the response. */
export type Answer = {
  data?: unknown;
  error?: { code?: string; errors?: unknown };
  response: Response;
};

/** A BFF read of a module: what it asks the API, given the salon's hostname, the call and the read's query. */
export type BffRead = {
  /** The permission the BFF checks before the call, when it checks (`open` reads; a support view's reads). */
  permission?: string;
  /**
   * The read's endpoint answers anyone on the salon's host, so the BFF's own session and permission check is its
   * only gate and the principal is read before it (the lead's ruling on web H3).
   */
  open?: boolean;
  /** False for a read a support view never makes (F03-S04): it is refused 403 there. */
  supportView?: boolean;
  /** Null when the API is not configured. */
  read(hostname: string, call: Call, query: URLSearchParams): Promise<Answer> | null;
};

/** A BFF change of a module: always checked here, session and permission, before its call. */
export type BffChange = {
  permission: string;
  /** Null when the API is not configured. */
  change(hostname: string, call: Call, body: Record<string, unknown>): Promise<Answer> | null;
};

/** An upload: a multipart form of one text field and a file, read only after the session and permission check. */
type BffUpload = {
  permission: string;
  field: 'kind' | 'serviceId' | 'memberId';
  /** The status a stored file is answered with (a brand image is 201, a photo 200). */
  created?: boolean;
  /** Null when the API is not configured. */
  upload(hostname: string, call: Call, text: string, file: Blob): Promise<Answer> | null;
};

/** A workspace page: where it is, the view the workspace renders, who may see it, its title and its place in the menu. */
type WorkspacePage = {
  readonly path: string;
  readonly view: string;
  readonly permission: string;
  /** Its title's key in the `account` messages. */
  readonly title: string;
  /** Its item in the workspace navigation, if it has one: the first page of a view with one carries it. */
  readonly nav?: {
    readonly order: number;
    readonly label: string;
    readonly icon: string;
    readonly testId: string;
    /** The other views it is the current item for (the Team item for the Sign-in accounts tab). */
    readonly alsoFor?: readonly string[];
  };
};

export type ModuleRoutes<Module extends string = string> = {
  readonly module: Module;
  readonly pages: readonly WorkspacePage[];
  readonly reads: Readonly<Record<string, BffRead>>;
  readonly changes: Readonly<Record<string, BffChange>>;
  readonly uploads: Readonly<Record<string, BffUpload>>;
};

/** A module's routes, declared in its own `features/<module>/server/routes.ts` (the lint rule holds it there). */
export function moduleRoutes<Module extends string>(
  module: Module,
  routes: Partial<Omit<ModuleRoutes<Module>, 'module'>>,
): ModuleRoutes<Module> {
  return { module, pages: [], reads: {}, changes: {}, uploads: {}, ...routes };
}

/** The one table: every module's routes, an action or a path declared twice refused when the table is made. */
export type RouteTable = {
  readonly pages: readonly WorkspacePage[];
  readonly reads: Readonly<Record<string, BffRead>>;
  readonly changes: Readonly<Record<string, BffChange>>;
  readonly uploads: Readonly<Record<string, BffUpload>>;
};

export function routeTable(...modules: readonly ModuleRoutes[]): RouteTable {
  const pages: WorkspacePage[] = [];
  const reads: Record<string, BffRead> = {};
  const changes: Record<string, BffChange> = {};
  const uploads: Record<string, BffUpload> = {};
  const actions = new Map<string, string>();
  for (const routes of modules) {
    for (const page of routes.pages) {
      if (pages.some((each) => each.path === page.path))
        throw new Error(`The page ${page.path} is declared twice (${routes.module}).`);
      pages.push(page);
    }
    const declared: [Readonly<Record<string, unknown>>, Record<string, unknown>][] = [
      [routes.reads, reads],
      [routes.changes, changes],
      [routes.uploads, uploads],
    ];
    for (const [table, into] of declared) {
      for (const [action, route] of Object.entries(table)) {
        const owner = actions.get(action);
        if (owner) throw new Error(`The action ${action} of ${routes.module} is ${owner}'s already.`);
        actions.set(action, routes.module);
        into[action] = route;
      }
    }
  }
  return { pages, reads, changes, uploads };
}
