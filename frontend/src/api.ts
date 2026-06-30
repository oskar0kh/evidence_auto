import axios from 'axios';
import type { CrawlResponse } from './types';

const api = axios.create({
  baseURL: '/api',
  timeout: 300000,
});

function extractApiError(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const serverError = error.response?.data?.error;
    if (typeof serverError === 'string' && serverError.length > 0) {
      return serverError;
    }
  }
  return error instanceof Error ? error.message : fallback;
}

export async function downloadCaptureFile(filename: string): Promise<Blob> {
  try {
    const { data } = await api.get<Blob>(`/files/captures/${encodeURIComponent(filename)}`, {
      responseType: 'blob',
    });
    return data;
  } catch (error) {
    throw new Error(extractApiError(error, '캡처 파일을 불러올 수 없습니다.'));
  }
}

export async function crawlDcinside(
  urls: string[],
  startSerial?: number
): Promise<CrawlResponse> {
  const { data } = await api.post<CrawlResponse>('/crawl/dcinside', {
    urls,
    saveDirectory: null,
    startSerial: startSerial ?? null,
  });
  return data;
}
