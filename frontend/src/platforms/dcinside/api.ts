import axios from 'axios';
import { parseSseChunk } from '../../shared/lib/sse';
import { isAbortError } from '../../shared/lib/abort';
import type { CrawlProgressEvent, CrawlStreamResult, UrlTiming } from '../../features/crawl/types';
import type { DcinsidePostData } from './types';
import type { GalleryLookupResponse, SearchOptions } from '../../features/search/types';

const searchApi = axios.create({
  baseURL: '/api',
  timeout: 0,
});

async function consumeCrawlSseStream(
  response: Response,
  onProgress: (progress: CrawlProgressEvent) => void,
  onUrlResult?: (post: DcinsidePostData) => void | Promise<void>
): Promise<CrawlStreamResult> {
  if (!response.body) {
    throw new Error('스트리밍 응답을 받지 못했습니다.');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finalResult: CrawlStreamResult | null = null;
  let interruptMessage: string | undefined;
  const accumulated: CrawlStreamResult = {
    data: [],
    errors: [],
    timings: [],
  };

  const applySseMessage = async (message: { event: string; data: string }) => {
    if (message.event === 'progress') {
      onProgress(JSON.parse(message.data) as CrawlProgressEvent);
      return;
    }

    if (message.event === 'url-result') {
      const post = JSON.parse(message.data) as DcinsidePostData;
      accumulated.data.push(post);
      await onUrlResult?.(post);
      return;
    }

    if (message.event === 'url-error') {
      accumulated.errors.push(
        JSON.parse(message.data) as { url: string; error: string; stage?: string }
      );
      return;
    }

    if (message.event === 'url-timing') {
      accumulated.timings!.push(JSON.parse(message.data) as UrlTiming);
      return;
    }

    if (message.event === 'complete') {
      const summary = JSON.parse(message.data) as {
        successCount: number;
        failCount: number;
        attemptedCount: number;
      };
      finalResult = {
        ...accumulated,
        successCount: summary.successCount,
        failCount: summary.failCount,
        attemptedCount: summary.attemptedCount,
      };
      return;
    }

    if (message.event === 'error') {
      const body = JSON.parse(message.data) as { error?: string };
      interruptMessage = body.error ?? '크롤링 중 오류가 발생했습니다.';
    }
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const parsed = parseSseChunk(buffer);
      buffer = parsed.remainder;

      for (const message of parsed.messages) {
        await applySseMessage(message);
      }
    }

    buffer += decoder.decode();
    const parsed = parseSseChunk(buffer);
    for (const message of parsed.messages) {
      await applySseMessage(message);
    }
  } catch (e) {
    if (isAbortError(e)) {
      return {
        ...accumulated,
        interrupted: true,
        interruptMessage: '크롤링이 취소됐습니다.',
      };
    }
    if (accumulated.data.length > 0 || accumulated.errors.length > 0) {
      return {
        ...accumulated,
        interrupted: true,
        interruptMessage:
          e instanceof Error ? e.message : '네트워크 오류로 크롤링이 중단됐습니다.',
      };
    }
    throw e;
  }

  if (finalResult) {
    return finalResult;
  }

  if (accumulated.data.length > 0 || accumulated.errors.length > 0) {
    return {
      ...accumulated,
      interrupted: true,
      interruptMessage: interruptMessage ?? '크롤링이 중단됐습니다.',
    };
  }

  throw new Error(interruptMessage ?? '크롤링 결과를 받지 못했습니다.');
}

async function postCrawlStream(
  endpoint: string,
  body: Record<string, unknown>,
  onProgress: (progress: CrawlProgressEvent) => void,
  onUrlResult?: (post: DcinsidePostData) => void | Promise<void>,
  signal?: AbortSignal
): Promise<CrawlStreamResult> {
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
    signal,
  });

  if (!response.ok) {
    let message = `서버 오류(${response.status})`;
    try {
      const errorBody = (await response.json()) as { error?: string };
      if (errorBody.error) {
        message = errorBody.error;
      }
    } catch {
      // ignore JSON parse errors
    }
    throw new Error(message);
  }

  return consumeCrawlSseStream(response, onProgress, onUrlResult);
}

export async function crawlDcinsideStream(
  urls: string[],
  startSerial: number | undefined,
  onProgress: (progress: CrawlProgressEvent) => void,
  onUrlResult?: (post: DcinsidePostData) => void | Promise<void>,
  signal?: AbortSignal,
  galleryId?: string
): Promise<CrawlStreamResult> {
  return postCrawlStream(
    '/api/crawl/dcinside/stream',
    {
      urls,
      startSerial: startSerial ?? null,
      galleryId: galleryId?.trim() || null,
    },
    onProgress,
    onUrlResult,
    signal
  );
}

export async function searchCrawlDcinsideStream(
  query: string,
  options: SearchOptions = {},
  startSerial: number | undefined,
  onProgress: (progress: CrawlProgressEvent) => void,
  onUrlResult?: (post: DcinsidePostData) => void | Promise<void>,
  signal?: AbortSignal
): Promise<CrawlStreamResult> {
  const { maxResults = 100, startDate, endDate, galleryId } = options;
  return postCrawlStream(
    '/api/crawl/dcinside/search-stream',
    {
      query,
      maxResults,
      startDate: startDate ?? null,
      endDate: endDate ?? null,
      galleryId: galleryId?.trim() || null,
      startSerial: startSerial ?? null,
    },
    onProgress,
    onUrlResult,
    signal
  );
}

export async function lookupDcinsideGalleries(
  name: string,
  signal?: AbortSignal
): Promise<GalleryLookupResponse> {
  const { data } = await searchApi.post<GalleryLookupResponse>(
    '/search/dcinside/galleries',
    { name: name.trim() },
    { signal }
  );
  return data;
}
