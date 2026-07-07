export interface SearchResponse {
  urls: string[];
  count: number;
  searchMs?: number;
  dateRangeSearch?: boolean;
  startDate?: string;
  endDate?: string;
}

export interface SearchOptions {
  maxResults?: number;
  startDate?: string;
  endDate?: string;
}
