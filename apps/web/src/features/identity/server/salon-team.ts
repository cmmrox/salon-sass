import 'server-only';

/** What a call the workspace BFF hands on answers: its data or the API's problem, and the response. */
type Answer = {
  data?: unknown;
  error?: { code?: string; errors?: unknown };
  response: Response;
};

/** The caller's credential and the request's shared API budget, for one call. */
type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };

/**
 * The team paths the workspace BFF reaches (F06-S01): the list, the salon's sign-in accounts, every change and a
 * member's photo. Identity checks the session and the permission first; staff makes the calls and builds each request
 * from the contract's own fields, and the route serving the BFF hands its implementation in, so that neither module
 * imports the other.
 */
export interface SalonTeam {
  members(call: Call): Promise<Answer>;
  signInAccounts(call: Call): Promise<Answer>;
  change(call: Call, action: string, body: unknown): Promise<Answer>;
  uploadPhoto(call: Call, memberId: string, file: Blob): Promise<Answer>;
}

/** The team calls for one salon's hostname; null when the API is not configured. */
export type SalonTeamFor = (hostname: string) => SalonTeam | null;

/** The BFF's reads of the team: the list with `team.read`, and the accounts offered for linking with `team.manage`. */
export const TEAM_READS = ['team-members', 'team-sign-in-accounts'];

/** Its changes, each `team.manage`; the photo upload is the one multipart action. */
export const TEAM_WRITES = ['team-member-add', 'team-member-save', 'team-member-remove', 'team-photo-remove'];
export const TEAM_PHOTO = 'team-photo';

/** The permission each action needs. The API also requires `staff.list` for the accounts. */
export const TEAM_PERMISSIONS: Record<string, string> = Object.fromEntries([
  ['team-members', 'team.read'],
  ['team-sign-in-accounts', 'team.manage'],
  ...[...TEAM_WRITES, TEAM_PHOTO].map((action) => [action, 'team.manage']),
]);

/** One read. */
export function readTeam(team: SalonTeam, action: string, call: Call): Promise<Answer> {
  return action === 'team-sign-in-accounts' ? team.signInAccounts(call) : team.members(call);
}
