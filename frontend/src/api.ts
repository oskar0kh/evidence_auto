import axios from 'axios';
import type { CrawlResponse, SearchResponse } from './types';

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

export async function searchDcinside(query: string, maxResults = 100): Promise<SearchResponse> {
  const { data } = await api.post<SearchResponse>('/search/dcinside', {
    query,
    maxResults,
  });
  return data;
}
