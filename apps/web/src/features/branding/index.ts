// The branding module's client-safe entry: the salon's brand type and the Profile tab's parts (F04-S01).
export type { BrandDraft, ProfileDraft, Salon, UploadedBrandImage } from './types';
export { LeaveDialog } from './components/LeaveDialog';
export { ProfileForm } from './components/ProfileForm';
export { ProfilePreview } from './components/ProfilePreview';
export { PublishBar } from './components/PublishBar';
export { useLeaveGuard } from './hooks/use-leave-guard';
export {
  fieldOf,
  formOf,
  sameForm,
  saveOf,
  type ProfileField,
  type ProfileForm as ProfileFormValues,
} from './client/profile-form';
export {
  brandFieldOf,
  brandFormOf,
  brandSaveOf,
  sameBrandForm,
  typedColour,
  type BrandField,
  type BrandFormValues,
} from './client/brand-form';
export { BrandForm } from './components/BrandForm';
export { BrandPreview } from './components/BrandPreview';
