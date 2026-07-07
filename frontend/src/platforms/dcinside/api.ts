import axios from 'axios';
import { parseSseChunk } from '../../shared/lib/sse';
import { parseSearchTerms } from '../../features/search/searchUtils';
import type { CrawlProgressEvent, CrawlStreamResult, UrlTiming } from '../../features/crawl/types';
import type { DcinsidePostData } from './types';
import type { SearchOptions, SearchResponse } from '../../features/search/types';

const searchApi = axios.create({
  baseURL: '/api',
  timeout: 0,
});

function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError';
}

export async function crawlDcinsideStream(
  urls: string[],
  startSerial: number | undefined,
  onProgress: (progress: CrawlProgressEvent) => void,
  onUrlResult?: (post: DcinsidePostData) => void,
  signal?: AbortSignal
): Promise<CrawlStreamResult> {
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
    signal,
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
  let finalResult: CrawlStreamResult | null = null;
  let interruptMessage: string | undefined;
  const accumulated: CrawlStreamResult = {
    data: [],
    errors: [],
    timings: [],
  };

  const applySseMessage = (message: { event: string; data: string }) => {
    if (message.event === 'progress') {
      onProgress(JSON.parse(message.data) as CrawlProgressEvent);
      return;
    }

    if (message.event === 'url-result') {
      const post = JSON.parse(message.data) as DcinsidePostData;
      accumulated.data.push(post);
      onUrlResult?.(post);
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
        applySseMessage(message);
      }
    }

    buffer += decoder.decode();
    const parsed = parseSseChunk(buffer);
    for (const message of parsed.messages) {
      applySseMessage(message);
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

async function searchDcinside(
  query: string,
  options: SearchOptions = {},
  signal?: AbortSignal
): Promise<SearchResponse> {
  const { maxResults = 100, startDate, endDate } = options;
  const { data } = await searchApi.post<SearchResponse>(
    '/search/dcinside',
    {
      query,
      maxResults,
      startDate: startDate ?? null,
      endDate: endDate ?? null,
    },
    { signal }
  );
  return data;
}

export interface SearchAllTermsProgress {
  termIndex: number;
  termTotal: number;
  term: string;
  collectedUrlCount: number;
}

export async function searchDcinsideAllTerms(
  query: string,
  options: SearchOptions = {},
  onProgress?: (progress: SearchAllTermsProgress) => void,
  signal?: AbortSignal
): Promise<SearchResponse> {
  const terms = parseSearchTerms(query);
  if (terms.length === 0) {
    throw new Error('검색어를 입력해 주세요.');
  }

  const merged = new Set<string>();
  let totalSearchMs = 0;

  for (let i = 0; i < terms.length; i++) {
    if (signal?.aborted) {
      throw new DOMException('Aborted', 'AbortError');
    }

    const term = terms[i];
    onProgress?.({
      termIndex: i + 1,
      termTotal: terms.length,
      term,
      collectedUrlCount: merged.size,
    });

    const result = await searchDcinside(term, options, signal);
    totalSearchMs += result.searchMs ?? 0;
    for (const url of result.urls) {
      merged.add(url);
    }
  }

  return {
    urls: [...merged],
    count: merged.size,
    searchMs: totalSearchMs,
    dateRangeSearch: Boolean(options.startDate && options.endDate),
    startDate: options.startDate,
    endDate: options.endDate,
  };
}
