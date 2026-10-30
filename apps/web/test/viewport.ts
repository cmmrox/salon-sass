/**
 * A viewport for component tests. jsdom has no layout and no `matchMedia`; this answers
 * `(max-width: Npx)` and `(min-width: Npx)` queries from `window.innerWidth` (1024 by default), and
 * `setViewportWidth` changes the width and tells every listener, as resizing a browser does.
 */
type Listener = (event: MediaQueryListEvent) => void;

const lists = new Set<{ query: string; listeners: Set<Listener>; matches: () => boolean }>();

function evaluate(query: string): boolean {
  const width = window.innerWidth;
  return [...query.matchAll(/\((max|min)-width:\s*(\d+(?:\.\d+)?)px\)/g)].every(([, kind, value]) =>
    kind === 'max' ? width <= Number(value) : width >= Number(value),
  );
}

export function installMatchMedia() {
  window.matchMedia = (query: string): MediaQueryList => {
    const entry = { query, listeners: new Set<Listener>(), matches: () => evaluate(query) };
    lists.add(entry);
    return {
      get matches() {
        return entry.matches();
      },
      media: query,
      onchange: null,
      addEventListener: (_type: string, listener: Listener) => entry.listeners.add(listener),
      removeEventListener: (_type: string, listener: Listener) => entry.listeners.delete(listener),
      addListener: (listener: Listener) => entry.listeners.add(listener),
      removeListener: (listener: Listener) => entry.listeners.delete(listener),
      dispatchEvent: () => true,
    } as unknown as MediaQueryList;
  };
}

/** Sets the viewport's width and notifies every media query list, as a resize does. */
export function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: width });
  for (const entry of lists) {
    for (const listener of entry.listeners) {
      listener({ matches: entry.matches(), media: entry.query } as MediaQueryListEvent);
    }
  }
}
