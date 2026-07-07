export interface SearchResponse {
  urls: string[];
  count: number;
  searchMs?: number;
  dateRangeSearch?: boolean;
  gallerySearch?: boolean;
  galleryId?: string;
  startDate?: string;
  endDate?: string;
}

export interface GalleryCandidate {
  name: string;
  id: string;
  type: string;
}

export interface GalleryLookupResponse {
  galleries: GalleryCandidate[];
  count: number;
  searchMs?: number;
}

export interface SearchOptions {
  maxResults?: number;
  startDate?: string;
  endDate?: string;
  galleryId?: string;
}
