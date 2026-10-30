// The platform module's client-safe entry: the console's plan, billing, export and directory
// components and controls, the salon's billing banner, and the browser's side of the operator session.
export { BillingBanner } from './components/BillingBanner';
export { InvoiceList, SubscriptionCard } from './components/BillingScreens';
export {
  ConsoleChoiceField,
  ConsoleCodeField,
  ConsoleDenied,
  ConsoleFailure,
  ConsoleLoading,
  ConsolePasswordField,
  ConsoleTextArea,
  ConsoleTextField,
  uncertain,
} from './components/ConsoleControls';
export { ConsoleSheet } from './components/ConsoleSheet';
export { DirectoryPager, DirectorySearch } from './components/DirectoryControls';
export { ExportDownload } from './components/ExportDownload';
export { PlanPicker } from './components/PlanPicker';
export { PlanDetail, PlanDirectory, useIdempotencyKey, type Plan } from './components/PlanScreens';
export { PAGE_SIZE, useDirectory } from './hooks/use-directory';
export {
  ensurePrincipal,
  OperatorFailure,
  operatorRead,
  operatorRequest,
  principalInsideLock,
  supportsLocks,
  withOperatorLock,
} from './client/operator-session';
export type { Entitlements, OperatorPrincipal } from './types';
