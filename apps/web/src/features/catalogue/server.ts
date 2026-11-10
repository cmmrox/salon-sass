// The catalogue module's server entry: the catalogue calls the workspace BFF makes, and a service photo (F05-S01).
import 'server-only';

export { servicePhoto } from './server/service-photo';
export { catalogueRoutes } from './server/routes';
