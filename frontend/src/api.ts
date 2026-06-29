import axios from 'axios';
import type { CrawlResponse } from './types';

const api = axios.create({
  baseURL: '/api',
  timeout: 300000,
});

export async function crawlDcinside(urls: string[]): Promise<CrawlResponse> {
  const { data } = await api.post<CrawlResponse>('/crawl/dcinside', { urls });
  return data;
}
