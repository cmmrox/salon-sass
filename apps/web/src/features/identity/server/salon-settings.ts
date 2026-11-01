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
 * The branding module's settings paths the workspace BFF reaches for a settings user: the salon's profile
 * draft (F04-S01) and brand draft (F04-S02), saving, publishing and discarding each, resetting the brand and
 * uploading its images. Identity checks the session and the
 * permission first; branding makes the calls and builds each request from the contract's own fields,
 * and the route serving the BFF hands its implementation in, so that neither module imports the other.
 */
export interface SalonSettings {
  profileDraft(call: Call): Promise<Answer>;
  saveProfileDraft(call: Call, version: unknown, profile: unknown): Promise<Answer>;
  publishProfile(call: Call, version: unknown): Promise<Answer>;
  discardProfileDraft(call: Call, version: unknown): Promise<Answer>;
  /** The brand (F04-S02), on the same pattern, with its reset and its image upload. */
  brandDraft(call: Call): Promise<Answer>;
  saveBrandDraft(call: Call, version: unknown, brand: unknown): Promise<Answer>;
  resetBrandDraft(call: Call, version: unknown): Promise<Answer>;
  publishBrand(call: Call, version: unknown): Promise<Answer>;
  discardBrandDraft(call: Call, version: unknown): Promise<Answer>;
  uploadBrandImage(call: Call, kind: string, file: Blob): Promise<Answer>;
}

/** The settings calls for one salon's hostname; null when the API is not configured. */
export type SalonSettingsFor = (hostname: string) => SalonSettings | null;
