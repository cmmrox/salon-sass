import 'server-only';
import { moduleRoutes, type BffChange, type BffRead, type Call } from '@/lib/api-core/routes';
import { salonHoursFor } from './salon-hours';
import { salonTeamFor } from './salon-team';

type Team = NonNullable<ReturnType<typeof salonTeamFor>>;
type Hours = NonNullable<ReturnType<typeof salonHoursFor>>;

const teamRead = (
  permission: string,
  ask: (team: Team, call: Call, query: URLSearchParams) => ReturnType<BffRead['read']>,
  open = false,
): BffRead => ({
  permission,
  open,
  read: (hostname, call, query) => {
    const team = salonTeamFor(hostname);
    return team ? ask(team, call, query) : null;
  },
});
const hoursRead = (
  ask: (hours: Hours, call: Call, query: URLSearchParams) => ReturnType<BffRead['read']>,
): BffRead => ({
  permission: 'settings.read',
  read: (hostname, call, query) => {
    const hours = salonHoursFor(hostname);
    return hours ? ask(hours, call, query) : null;
  },
});
const teamChange = (action: string, permission: string): [string, BffChange] => [
  action,
  { permission, change: (hostname, call, body) => salonTeamFor(hostname)?.change(call, action, body) ?? null },
];
const hoursChange = (
  ask: (hours: Hours, call: Call, body: Record<string, unknown>) => ReturnType<BffChange['change']>,
): BffChange => ({
  permission: 'settings.manage',
  change: (hostname, call, body) => {
    const hours = salonHoursFor(hostname);
    return hours ? ask(hours, call, body) : null;
  },
});

/**
 * The staff module's workspace pages and BFF actions: the Team page (F06-S01 to F06-S03), read with `team.read`, its
 * changes with `team.manage` (blocked time with `team.block`, decision 099), the accounts offered for linking and the
 * bookings leave would affect with `team.manage`, and a person's photo; and the Hours & closures tab of Settings
 * (F04-S03), read with `settings.read` and changed with `settings.manage`. The salon's opening week beside a schedule
 * is the one its customers see: its endpoint answers anyone.
 */
export const staffRoutes = moduleRoutes('staff', {
  pages: [
    {
      path: '/workspace/team',
      view: 'team',
      permission: 'team.read',
      title: 'team',
      nav: { order: 20, label: 'teamNav', icon: 'staff', testId: 'workspace-team', alsoFor: ['staff'] },
    },
    { path: '/workspace/settings/hours', view: 'settings', permission: 'settings.read', title: 'settings' },
  ],
  reads: {
    'team-members': teamRead('team.read', (team, call) => team.members(call)),
    'team-sign-in-accounts': teamRead('team.manage', (team, call) => team.signInAccounts(call)),
    'team-schedule': teamRead('team.read', (team, call, query) => team.schedule(call, query.get('memberId'))),
    'team-opening-hours': teamRead('team.read', (team, call) => team.openingHours(call), true),
    'team-leave-affected': teamRead('team.manage', (team, call, query) =>
      team.leaveAffected(call, query.get('memberId'), query.get('firstDay'), query.get('lastDay')),
    ),
    'team-block-affected': teamRead('team.block', (team, call, query) =>
      team.blockAffected(call, query.get('memberId'), query.get('date'), query.get('starts'), query.get('finishes')),
    ),
    hours: hoursRead((hours, call) => hours.hours(call)),
    holidays: hoursRead((hours, call) => hours.holidays(call)),
    closures: hoursRead((hours, call) => hours.closures(call)),
    'closure-impact': hoursRead((hours, call, query) => hours.closureImpact(call, query.get('date') ?? '')),
  },
  changes: {
    ...Object.fromEntries([
      ...[
        'team-member-add',
        'team-member-save',
        'team-member-remove',
        'team-photo-remove',
        'team-week-save',
        'team-date-change-add',
        'team-date-change-remove',
        'team-leave-add',
        'team-leave-remove',
      ].map((action) => teamChange(action, 'team.manage')),
      ...['team-block-add', 'team-block-remove'].map((action) => teamChange(action, 'team.block')),
    ]),
    'hours-save': hoursChange((hours, call, body) => hours.saveHours(call, body.version, body.weekly)),
    'holidays-accept': hoursChange((hours, call, body) =>
      hours.acceptHolidays(call, body.year, body.version, body.choices),
    ),
    'holiday-choose': hoursChange((hours, call, body) => hours.chooseHoliday(call, body.year, body.date, body)),
    'closure-add': hoursChange((hours, call, body) => hours.addClosure(call, body.date, body.reason)),
    'closure-reopen': hoursChange((hours, call, body) => hours.reopenClosure(call, body.id)),
  },
  uploads: {
    'team-photo': {
      permission: 'team.manage',
      field: 'memberId',
      upload: (hostname, call, memberId, file) => salonTeamFor(hostname)?.uploadPhoto(call, memberId, file) ?? null,
    },
  },
});
