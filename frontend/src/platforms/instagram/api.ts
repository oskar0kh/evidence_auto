import { parseSseChunk } from '../../shared/lib/sse';
import { isAbortError } from '../../shared/lib/abort';
import type { CrawlFailureRecord, CrawlProgressEvent, CrawlStreamResult, UrlTiming } from './crawl/types';
import type { InstagramPostData } from '../types';

type UrlResultHandler = (post: InstagramPostData) => void | Promise<void>;
type UrlErrorHandler = (error: CrawlFailureRecord) => void;
type UrlTimingHandler = (timing: UrlTiming) => void;

async function consumeCrawlSseStream(
  response: Response,
  onProgress: (progress: CrawlProgressEvent) => void,
  onUrlResult?: UrlResultHandler,
  onUrlError?: UrlErrorHandler,
  onUrlTiming?: UrlTimingHandler
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
      const post = JSON.parse(message.data) as InstagramPostData;
      await onUrlResult?.(post);
      accumulated.data.push({ url: post.url });
      return;
    }
    if (message.event === 'url-error') {
      const error = JSON.parse(message.data) as CrawlFailureRecord;
      accumulated.errors.push(error);
      onUrlError?.(error);
      return;
    }
    if (message.event === 'url-timing') {
      const timing = JSON.parse(message.data) as UrlTiming;
      accumulated.timings!.push(timing);
      onUrlTiming?.(timing);
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
      accumulated.errors.push({ url: '(크롤 스트림)', error: interruptMessage, stage: 'session' });
    }
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
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
    if (!isAbortError(e)) {
      throw e;
    }
    interruptMessage = '크롤링이 취소되었습니다.';
  }

  if (finalResult) {
    return finalResult;
  }
  return {
    ...accumulated,
    successCount: accumulated.data.length,
    failCount: accumulated.errors.length,
    attemptedCount: accumulated.data.length + accumulated.errors.length,
    interruptMessage,
  } as CrawlStreamResult & { interruptMessage?: string };
}

export async function crawlInstagramStream(
  urls: string[],
  options: {
    startSerial?: number;
    searchQuery?: string;
    signal?: AbortSignal;
    onProgress: (progress: CrawlProgressEvent) => void;
    onUrlResult?: UrlResultHandler;
    onUrlError?: UrlErrorHandler;
    onUrlTiming?: UrlTimingHandler;
  }
): Promise<CrawlStreamResult> {
  const response = await fetch('/api/crawl/instagram/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      urls,
      startSerial: options.startSerial,
      searchQuery: options.searchQuery,
    }),
    signal: options.signal,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error ?? `서버 오류 (${response.status})`);
  }
  return consumeCrawlSseStream(
    response,
    options.onProgress,
    options.onUrlResult,
    options.onUrlError,
    options.onUrlTiming
  );
}

export async function searchCrawlInstagramStream(
  query: string,
  options: {
    maxResults?: number;
    startSerial?: number;
    signal?: AbortSignal;
    onProgress: (progress: CrawlProgressEvent) => void;
    onUrlResult?: UrlResultHandler;
    onUrlError?: UrlErrorHandler;
    onUrlTiming?: UrlTimingHandler;
  }
): Promise<CrawlStreamResult> {
  const response = await fetch('/api/crawl/instagram/search-stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query,
      maxResults: options.maxResults ?? 100,
      startSerial: options.startSerial,
    }),
    signal: options.signal,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error ?? `서버 오류 (${response.status})`);
  }
  return consumeCrawlSseStream(
    response,
    options.onProgress,
    options.onUrlResult,
    options.onUrlError,
    options.onUrlTiming
  );
}
