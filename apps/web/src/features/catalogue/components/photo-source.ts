/** Where the web serves a service photo on the salon's own hostname (F05-S01 AC-10): through its BFF route. */
export function photoSource(photoId: string | null): string | undefined {
  return photoId ? `/service-photos/${photoId}` : undefined;
}
