import {
  aggregateStepTimings,
  appendCrawlLogEntry,
  formatExecutedAt,
  formatFailureReasons,
  pickFirstStepMs,
  pickStepMs,
} from './crawlLogExport';
import {
  appendBatchToSession,
  createCrawlPersistSession,
  type CrawlPersistSession,
} from '../export/persistResults';
import type { CrawlHealthEvent, CrawlLogEntry, UrlTiming } from './types';
import type { GalleryCandidate } from '../search/types';
import type { DcinsidePostData } from '../../platforms/dcinside/types';

export const STAGE_LABELS: Record<string, string> = {
  search: '검색',
  'text-crawl': '글·댓글 수집',
  screenshot: '화면 캡처',
  'attach-capture': '결과 저장',
  'url-done': '완료',
  'url-failed': '실패',
};

export const STAGE_WEIGHTS: Record<string, number> = {
  search: 0.05,
  'text-crawl': 0.15,
  screenshot: 0.55,
  'attach-capture': 0.85,
  'url-done': 1,
  'url-failed': 1,
};

export interface CrawlProgress {
  completed: number;
  total: number;
  currentUrl: string;
  stage?: string;
  successCount: number;
  failCount: number;
  health?: CrawlHealthEvent | null;
}

export interface CrawlLogContext {
  keyword?: string;
  searchDateRange?: string;
  galleryName?: string;
  inputMode: CrawlLogEntry['inputMode'];
}

export function formatHealthLabel(health: CrawlHealthEvent | null | undefined): string | null {
  if (!health) {
    return null;
  }
  if (health.protectiveMode) {
    if (health.preferBrowser) {
      return `차단 감지 · 보호 모드(${health.currentPhaseLabel}) · 연속 실패 ${health.consecutiveFailures}회`;
    }
    return `차단 감지 · 보호 모드 활성 · 속도 조절 중`;
  }
  if (health.consecutiveFailures > 0 && health.lastBlockSignalLabel) {
    return `일시 차단(${health.lastBlockSignalLabel}) · 재시도 중`;
  }
  return null;
}

export function deriveCommunityName(posts: DcinsidePostData[]): string {
  const galleryNames = posts
    .map((post) => post.galleryName?.trim())
    .filter((name): name is string => Boolean(name));
  if (galleryNames.length === 0) {
    return '디시인사이드';
  }
  const uniqueNames = [...new Set(galleryNames)];
  return uniqueNames.length === 1 ? uniqueNames[0] : '디시인사이드';
}

export function parseUrls(input: string): string[] {
  return input
    .split(/\n|,/)
    .map((u) => u.trim())
    .filter((u) => u.length > 0);
}

export function mergeSavedResults(
  existing: DcinsidePostData[],
  incoming: DcinsidePostData[]
): DcinsidePostData[] {
  const merged = [...existing];
  for (const item of incoming) {
    const index = merged.findIndex((saved) => saved.url === item.url);
    if (index >= 0) {
      merged[index] = item;
    } else {
      merged.push(item);
    }
  }
  return merged;
}

export function shortenUrl(url: string, max = 56): string {
  return url.length <= max ? url : `${url.slice(0, max)}…`;
}

export function formatStageLabel(stage?: string): string {
  if (!stage) {
    return '처리 중';
  }
  return STAGE_LABELS[stage] ?? stage;
}

export function hasPartialDateRange(startDate: string, endDate: string): boolean {
  return Boolean(startDate) !== Boolean(endDate);
}

export function isValidDateRange(startDate: string, endDate: string): boolean {
  if (!startDate || !endDate) {
    return true;
  }
  return startDate <= endDate;
}

export function formatGalleryLabel(candidate: GalleryCandidate): string {
  return `${candidate.name}(${candidate.id})`;
}

export function formatGalleryTypeLabel(type: string): string {
  if (type === 'mgallery') {
    return '마이너';
  }
  if (type === 'mini') {
    return '미니';
  }
  return '메인';
}

export function computeProgressPercent(progress: CrawlProgress | null, loading: boolean): number {
  if (!progress) {
    return 0;
  }
  if (progress.total > 0) {
    return Math.min(
      100,
      Math.round(
        ((progress.completed +
          (loading && progress.completed < progress.total
            ? STAGE_WEIGHTS[progress.stage ?? 'text-crawl'] ?? 0.15
            : 0)) /
          progress.total) *
          100
      )
    );
  }
  return Math.min(
    95,
    progress.completed * 8 + (STAGE_WEIGHTS[progress.stage ?? 'search'] ?? 0.05) * 100
  );
}

export function computeProgressLabel(progress: CrawlProgress | null, loading: boolean): string {
  if (!progress) {
    return '';
  }
  if (progress.total > 0) {
    return `진행 ${Math.min(progress.completed + (loading ? 1 : 0), progress.total)} / ${progress.total}`;
  }
  const discovered =
    progress.successCount + progress.failCount > 0
      ? ` (발견 ${Math.max(progress.completed, progress.successCount + progress.failCount)}건)`
      : '';
  return `처리 ${progress.completed}건${discovered}`;
}

export async function saveCrawlLog(
  directory: FileSystemDirectoryHandle | null,
  context: CrawlLogContext,
  attemptedCount: number,
  successCount: number,
  errors: { url: string; error: string }[],
  totalMs: number,
  timings: UrlTiming[]
): Promise<void> {
  if (!directory) {
    return;
  }

  const stepDetails = aggregateStepTimings(timings);
  const entry: CrawlLogEntry = {
    executedAt: formatExecutedAt(),
    keyword: context.keyword,
    searchDateRange: context.searchDateRange,
    galleryName: context.galleryName,
    inputMode: context.inputMode,
    attemptedCount,
    successCount,
    failCount: Math.max(attemptedCount - successCount, errors.length),
    failureReasons: formatFailureReasons(errors),
    totalMs,
    textCrawlMs:
      pickFirstStepMs(stepDetails, 'text-crawl') ??
      pickStepMs(stepDetails, 'fetch-page', 'parse-html', 'fetch-comments', 'build-result'),
    pageNavigateMs: pickStepMs(stepDetails, 'page-navigate'),
    waitContentMs: pickStepMs(stepDetails, 'wait-gallview-head'),
    waitCommentsMs: pickStepMs(stepDetails, 'wait-comments'),
    captureImagesMs: pickStepMs(stepDetails, 'capture-images'),
    screenshotMs: pickFirstStepMs(stepDetails, 'screenshot'),
    stepDetails,
  };

  try {
    await appendCrawlLogEntry(directory, entry);
  } catch (e) {
    console.error('크롤링 로그 저장 실패:', e);
  }
}

export async function saveBatchResults(
  session: CrawlPersistSession | null,
  batchResults: DcinsidePostData[],
  directory: FileSystemDirectoryHandle,
  communityName: string,
  keyword: string | undefined
): Promise<{ session: CrawlPersistSession; postsForExcel: DcinsidePostData[] }> {
  let activeSession = session;
  if (!activeSession) {
    activeSession = await createCrawlPersistSession(directory, {
      communityName,
      keyword,
    });
  }
  const postsForExcel = await appendBatchToSession(activeSession, batchResults);
  return { session: activeSession, postsForExcel };
}
