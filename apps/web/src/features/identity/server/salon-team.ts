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
 * The team paths the workspace BFF reaches (F06-S01, F06-S02, F06-S03): the list, the salon's sign-in accounts, a person's
 * schedule and the salon's week beside it, the bookings leave or a block would affect, every change and a member's photo.
 * Identity checks the session and the permission first;
 * staff makes the calls and builds each request from the contract's own fields, and the route serving the BFF hands its
 * implementation in, so that neither module imports the other.
 */
export interface SalonTeam {
  members(call: Call): Promise<Answer>;
  signInAccounts(call: Call): Promise<Answer>;
  schedule(call: Call, memberId: unknown): Promise<Answer>;
  leaveAffected(call: Call, memberId: unknown, firstDay: unknown, lastDay: unknown): Promise<Answer>;
  blockAffected(call: Call, memberId: unknown, date: unknown, starts: unknown, finishes: unknown): Promise<Answer>;
  openingHours(call: Call): Promise<Answer>;
  change(call: Call, action: string, body: unknown): Promise<Answer>;
  uploadPhoto(call: Call, memberId: string, file: Blob): Promise<Answer>;
}

/** The team calls for one salon's hostname; null when the API is not configured. */
export type SalonTeamFor = (hostname: string) => SalonTeam | null;

/**
 * The BFF's reads of the team: the list, a person's schedule and the salon's week with `team.read`, the accounts offered
 * for linking and the bookings leave would affect with `team.manage`, and those a block would affect with `team.block`.
 */
export const TEAM_READS = [
  'team-members',
  'team-sign-in-accounts',
  'team-schedule',
  'team-opening-hours',
  'team-leave-affected',
  'team-block-affected',
];

/** The changes to blocked time, each `team.block` (decision 099). */
const TEAM_BLOCK_WRITES = ['team-block-add', 'team-block-remove'];

/** The changes that need `team.manage`; the photo upload is the one multipart action. */
const TEAM_MANAGE_WRITES = [
  'team-member-add',
  'team-member-save',
  'team-member-remove',
  'team-photo-remove',
  'team-week-save',
  'team-date-change-add',
  'team-date-change-remove',
  'team-leave-add',
  'team-leave-remove',
];

/** Its changes. */
export const TEAM_WRITES = [...TEAM_MANAGE_WRITES, ...TEAM_BLOCK_WRITES];
export const TEAM_PHOTO = 'team-photo';

/** The permission each action needs. The API also requires `staff.list` for the accounts. */
export const TEAM_PERMISSIONS: Record<string, string> = Object.fromEntries([
  ['team-members', 'team.read'],
  ['team-schedule', 'team.read'],
  ['team-opening-hours', 'team.read'],
  ['team-sign-in-accounts', 'team.manage'],
  ['team-leave-affected', 'team.manage'],
  ['team-block-affected', 'team.block'],
  ...[...TEAM_MANAGE_WRITES, TEAM_PHOTO].map((action) => [action, 'team.manage']),
  ...TEAM_BLOCK_WRITES.map((action) => [action, 'team.block']),
]);

/** One read: a schedule and an impact name their person and period in the query, every other read nothing. */
export function readTeam(team: SalonTeam, action: string, call: Call, query: URLSearchParams): Promise<Answer> {
  switch (action) {
    case 'team-sign-in-accounts':
      return team.signInAccounts(call);
    case 'team-schedule':
      return team.schedule(call, query.get('memberId'));
    case 'team-leave-affected':
      return team.leaveAffected(call, query.get('memberId'), query.get('firstDay'), query.get('lastDay'));
    case 'team-block-affected':
      return team.blockAffected(
        call,
        query.get('memberId'),
        query.get('date'),
        query.get('starts'),
        query.get('finishes'),
      );
    case 'team-opening-hours':
      return team.openingHours(call);
    default:
      return team.members(call);
  }
}
