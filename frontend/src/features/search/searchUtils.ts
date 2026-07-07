export function parseSearchTerms(query: string): string[] {
  return query
    .trim()
    .split(/[,\s]+/)
    .map((term) => term.trim())
    .filter((term) => term.length > 0);
}

const GALLERY_ID_FROM_URL_PATTERN = /[?&]id=([^&\s]+)/i;

export function extractGalleryIdFromUrl(url: string): string | null {
  const match = url.match(GALLERY_ID_FROM_URL_PATTERN);
  if (!match) {
    return null;
  }
  const galleryId = match[1]?.trim();
  return galleryId ? galleryId : null;
}

export function filterUrlsByGalleryId(urls: string[], galleryId?: string): string[] {
  const normalizedGalleryId = galleryId?.trim();
  if (!normalizedGalleryId) {
    return urls;
  }
  return urls.filter((url) => extractGalleryIdFromUrl(url) === normalizedGalleryId);
}
