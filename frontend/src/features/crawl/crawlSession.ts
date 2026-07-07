import type { CrawlStreamResult, UrlTiming } from './types';

export const USER_CANCEL_REASON = '사용자 중도 취소';

export function collectProcessedUrls(response: CrawlStreamResult, processedUrls: Set<string>): void {
  for (const post of response.data) {
    processedUrls.add(post.url);
  }
  for (const error of response.errors) {
    processedUrls.add(error.url);
  }
  for (const timing of response.timings ?? []) {
    processedUrls.add(timing.url);
  }
}

export function appendUserCancelErrors(
  urls: string[],
  batchErrors: { url: string; error: string }[],
  processedUrls: Set<string>
): void {
  const existingErrorUrls = new Set(batchErrors.map((error) => error.url));
  for (const url of urls) {
    if (!processedUrls.has(url) && !existingErrorUrls.has(url)) {
      batchErrors.push({ url, error: USER_CANCEL_REASON });
      existingErrorUrls.add(url);
    }
  }
}

export function collectCrawlResponse(
  response: CrawlStreamResult,
  batchErrors: { url: string; error: string }[],
  batchTimings: UrlTiming[]
): number {
  batchErrors.push(...response.errors);
  if (response.timings) {
    batchTimings.push(...response.timings);
  }
  return response.successCount ?? response.data.length;
}

export function formatInterruptedMessage(
  response: CrawlStreamResult,
  autoSaved: boolean,
  savedCount: number
): string {
  const base = response.interruptMessage ?? '크롤링이 중단됐습니다.';
  if (savedCount > 0) {
    if (autoSaved) {
      return `${base} 완료된 ${savedCount}건은 선택한 폴더에 자동 저장됐습니다.`;
    }
    return `${base} 완료된 ${savedCount}건은 화면에 보관됐으며, 범죄일람표·캡처화면 저장으로 보낼 수 있습니다.`;
  }
  return base;
}

export function appendAutoSaveNotice(message: string, savedCount: number): string {
  return `${message} 완료된 ${savedCount}건은 선택한 폴더에 자동 저장됐습니다.`;
}

export interface CrawlFinalizeInput {
  wasCancelled: boolean;
  wasInterrupted: boolean;
  errorMessage: string | null;
  autoSaved: boolean;
  totalSavedCount: number;
  batchErrors: { url: string; error: string }[];
  processedUrls: Set<string>;
  cancelUrls?: string[];
}

export function resolveCrawlMessages(input: CrawlFinalizeInput): {
  errorMessage: string | null;
  infoMessage: string | null;
} {
  const { wasCancelled, wasInterrupted, autoSaved, totalSavedCount, batchErrors, processedUrls } =
    input;
  let { errorMessage } = input;

  if (wasCancelled && input.cancelUrls) {
    appendUserCancelErrors(input.cancelUrls, batchErrors, processedUrls);
  }

  if (errorMessage) {
    if (autoSaved && !wasInterrupted) {
      errorMessage = appendAutoSaveNotice(errorMessage, totalSavedCount);
    }
    return { errorMessage, infoMessage: null };
  }

  if (autoSaved) {
    return {
      errorMessage: null,
      infoMessage: `크롤링이 완료됐습니다. ${totalSavedCount}건이 선택한 폴더에 저장됐습니다.`,
    };
  }

  return { errorMessage: null, infoMessage: null };
}
