// The staff module's server entry: the hours, holiday and closure calls the workspace BFF makes (F04-S03), its team
// calls and a team member's photo (F06-S01).
import 'server-only';

export { teamPhoto } from './server/team-photo';
export { staffRoutes } from './server/routes';
