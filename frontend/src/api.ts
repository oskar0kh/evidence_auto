import axios from 'axios';
import { parseSseChunk } from './sse';
import type { CrawlProgressEvent, CrawlResponse, SearchOptions, SearchResponse } from './types';

const api = axios.create({
  baseURL: '/api',
  timeout: 300000,
});

export async function crawlDcinsideStream(
  urls: string[],
  startSerial: number | undefined,
  onProgress: (progress: CrawlProgressEvent) => void
): Promise<CrawlResponse> {
  const response = await fetch('/api/crawl/dcinside/stream', {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      urls,
      startSerial: startSerial ?? null,
    }),
  });

  if (!response.ok) {
    let message = `서버 오류(${response.status})`;
    try {
      const body = (await response.json()) as { error?: string };
      if (body.error) {
        message = body.error;
      }
    } catch {
      // ignore JSON parse errors
    }
    throw new Error(message);
  }

  if (!response.body) {
    throw new Error('스트리밍 응답을 받지 못했습니다.');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finalResult: CrawlResponse | null = null;

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    const parsed = parseSseChunk(buffer);
    buffer = parsed.remainder;

    for (const message of parsed.messages) {
      if (message.event === 'progress') {
        onProgress(JSON.parse(message.data) as CrawlProgressEvent);
        continue;
      }

      if (message.event === 'complete') {
        finalResult = JSON.parse(message.data) as CrawlResponse;
        continue;
      }

      if (message.event === 'error') {
        const body = JSON.parse(message.data) as { error?: string };
        throw new Error(body.error ?? '크롤링 중 오류가 발생했습니다.');
      }
    }
  }

  if (!finalResult) {
    throw new Error('크롤링 결과를 받지 못했습니다.');
  }

  return finalResult;
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
