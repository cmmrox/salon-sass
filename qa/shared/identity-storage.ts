/** Database observations only; all negative writes are explicit rolled-back transactions. */
import { spawnSync } from 'node:child_process';

export function identitySql(query: string, tenant?: string | null, strict = true): string {
  const application = tenant !== undefined;
  const role = application ? process.env.SALON_DB_APP_USER ?? 'salon_app' : process.env.SALON_DB_USER ?? 'salon';
  const sql = tenant ? `BEGIN;SET LOCAL app.tenant_id='${tenant}';${query};ROLLBACK;` : query;
  const options = { input: sql, encoding: 'utf8' as const, timeout: 15000 };
  const args = ['exec','-i', ...(application?['-e',`PGPASSWORD=${process.env.SALON_DB_APP_PASSWORD??'salon-app-local-only'}`]:[]),
    'salon-postgres','psql',...(application?['-h','localhost']:[]),'-U',role,'-d',process.env.SALON_DB_NAME??'salon','-X','-qAt',
    ...(strict?['-v','ON_ERROR_STOP=1']:[])];
  const result = spawnSync('docker',args,options);
  if(result.error) throw new Error('ENVIRONMENT: database observer could not start');
  if(strict && (result.status !== 0 || /ERROR:/.test(result.stderr))) throw new Error('ENVIRONMENT: database observation failed');
  return `${result.stdout}${strict?'':result.stderr}`.trim();
}
