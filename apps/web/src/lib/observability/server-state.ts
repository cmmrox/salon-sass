import type { Server } from 'node:http';

/**
 * What the server process knows about itself, shared by the instrumentation hook, which watches
 * every request, and the readiness route, which reports it. They are bundled separately and meet on
 * the global object.
 */
export type ServerState = {
  /** Set once the server has been told to stop: it is no longer ready for new traffic. */
  draining: boolean;
  /** Requests received and not yet answered. */
  inFlight: number;
  /** The servers requests have arrived on, so that a stop can close them. */
  servers: Set<Server>;
  /** Called whenever a request is answered. */
  onIdle?: () => void;
};

const STATE = Symbol.for('salon.web.server-state');

export function serverState(): ServerState {
  const holder = globalThis as { [STATE]?: ServerState };
  holder[STATE] ??= { draining: false, inFlight: 0, servers: new Set() };
  return holder[STATE];
}

/** Whether this instance should be sent new requests: false from the moment it is told to stop. */
export function isReady(): boolean {
  return !serverState().draining;
}
