import axios from 'axios';
import type { CrawlResponse, SearchOptions, SearchResponse } from './types';

const api = axios.create({
  baseURL: '/api',
  timeout: 300000,
});

export async function crawlDcinside(
  urls: string[],
  startSerial?: number
): Promise<CrawlResponse> {
  const { data } = await api.post<CrawlResponse>('/crawl/dcinside', {
    urls,
    startSerial: startSerial ?? null,
  });
  return data;
}

export async function searchDcinside(
  query: string,
  options: SearchOptions = {}
): Promise<SearchResponse> {
  const { maxResults = 100, startDate, endDate } = options;
  const { data } = await api.post<SearchResponse>('/search/dcinside', {
    query,
    maxResults,
    startDate: startDate ?? null,
    endDate: endDate ?? null,
  });
  return data;
}
