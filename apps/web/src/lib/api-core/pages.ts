/** A workspace page: where it is, the view the workspace renders, who may see it, its title and its place in the menu. */
export type WorkspacePage = {
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

/**
 * A module's workspace pages, declared in its own `features/<module>/pages.ts` (the lint rule holds them there) and
 * registered with its BFF actions by its `server/routes.ts`.
 */
export function modulePages<Module extends string>(
  _module: Module,
  pages: readonly WorkspacePage[],
): readonly WorkspacePage[] {
  return pages;
}
