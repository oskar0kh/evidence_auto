import {
  aggregateStepTimings,
  appendCrawlLogEntry,
  beginIncrementalCrawlLog,
  buildExtraStreamFailures,
  formatExecutedAt,
  formatFailureReasons,
  mergeCrawlFailures,
  pickFirstStepMs,
  pickStepMs,
  updateCrawlLogEntry,
  type IncrementalCrawlLogHandle,
} from './crawlLogExport';
import {
  countUrlBudgetTimeouts,
  createCrawlSessionMetrics,
  formatCrawlSessionMetricsForLog,
  mergeOperationEvents,
  observeCrawlHealth,
  observeCrawlProgress,
  observeUrlTiming,
  type CrawlSessionMetrics,
} from './crawlSessionMetrics';
import {
  appendBatchToSession,
  createCrawlPersistSession,
  type CrawlPersistSession,
  type PersistResultsOptions,
} from '../export/persistResults';
import { RESULTS_PREVIEW_SIZE } from './constants';
import type { CrawlFailureRecord, CrawlHealthEvent, CrawlLogEntry, CrawlProgressEvent, UrlTiming } from './types';
import type { GalleryCandidate } from '../search/types';
import type { DcinsidePostData } from '../types';

export interface SavedResultPreview {
  serial: number;
  url: string;
  title: string;
  galleryName: string;
  nickname: string;
  postDate: string;
  viewCount: number;
  commentCount: number;
  captureFilePath: string;
}

export function toResultPreview(post: DcinsidePostData, serial: number): SavedResultPreview {
  return {
    serial,
    url: post.url,
    title: post.title,
    galleryName: post.galleryName ?? '',
    nickname: post.nickname,
    postDate: post.postDate,
    viewCount: post.viewCount,
    commentCount: post.commentCount,
    captureFilePath: post.captureFilePath,
  };
}

export function appendResultPreviews(
  existing: SavedResultPreview[],
  posts: DcinsidePostData[],
  startSerial: number,
  max = RESULTS_PREVIEW_SIZE
): SavedResultPreview[] {
  if (posts.length === 0) {
    return existing;
  }
  const incoming = posts
    .map((post, index) => toResultPreview(post, startSerial + index))
    .reverse();
  const merged = [...incoming, ...existing];
  return merged.length > max ? merged.slice(0, max) : merged;
}

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
  urlAttempt?: number;
  urlAttemptMax?: number;
  urlAttemptPhase?: string;
  urlDeadlineRemainingMs?: number;
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
  if (health.message) {
    if (health.message.includes('보호 모드 해제')) {
      return health.message;
    }
    if (health.message.includes('보호 모드')) {
      return health.message;
    }
    if (health.message.includes('재시도')) {
      return health.message;
    }
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

export function formatDeadlineRemainingLabel(remainingMs?: number): string | null {
  if (remainingMs == null || remainingMs < 0) {
    return null;
  }
  const totalSeconds = Math.ceil(remainingMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `다음 URL까지 ${minutes}:${seconds.toString().padStart(2, '0')} 남음`;
}

export function mergeCrawlProgressEvent(
  prev: CrawlProgress | null,
  event: CrawlProgressEvent
): CrawlProgress {
  return {
    completed: event.completed,
    total: event.total,
    currentUrl: event.currentUrl,
    stage: event.stage,
    successCount: event.successCount,
    failCount: event.failCount,
    health: prev?.health ?? null,
    urlAttempt: event.urlAttempt ?? undefined,
    urlAttemptMax: event.urlAttemptMax ?? undefined,
    urlAttemptPhase: event.urlAttemptPhase ?? undefined,
    urlDeadlineRemainingMs: event.urlDeadlineRemainingMs ?? undefined,
  };
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

export interface CrawlLogSnapshot {
  attemptedCount: number;
  successCount: number;
  errors: CrawlFailureRecord[];
  timings: UrlTiming[];
  totalMs: number;
  extraFailures?: CrawlFailureRecord[];
  sessionMetrics?: CrawlSessionMetrics | null;
}

export function buildCrawlLogEntry(
  context: CrawlLogContext,
  snapshot: CrawlLogSnapshot,
  executedAt: string
): CrawlLogEntry {
  const extraFailures = snapshot.extraFailures ?? [];
  const mergedFailures = mergeCrawlFailures(snapshot.errors, snapshot.timings, extraFailures);
  const stepDetails = aggregateStepTimings(snapshot.timings);
  const metrics = snapshot.sessionMetrics ?? createCrawlSessionMetrics();
  countUrlBudgetTimeouts(metrics, mergedFailures);
  const formattedMetrics = formatCrawlSessionMetricsForLog(metrics);

  return {
    executedAt,
    keyword: context.keyword,
    searchDateRange: context.searchDateRange,
    galleryName: context.galleryName,
    inputMode: context.inputMode,
    attemptedCount: snapshot.attemptedCount,
    successCount: snapshot.successCount,
    failCount: Math.max(snapshot.attemptedCount - snapshot.successCount, mergedFailures.length),
    failureReasons: formatFailureReasons(mergedFailures),
    totalMs: snapshot.totalMs,
    textCrawlMs:
      pickFirstStepMs(stepDetails, 'text-crawl') ??
      pickStepMs(stepDetails, 'fetch-page', 'parse-html', 'fetch-comments', 'build-result'),
    pageNavigateMs: pickStepMs(stepDetails, 'page-navigate'),
    waitContentMs: pickStepMs(stepDetails, 'wait-gallview-head'),
    waitCommentsMs: pickStepMs(stepDetails, 'wait-comments'),
    captureImagesMs: pickStepMs(stepDetails, 'capture-images'),
    screenshotMs: pickFirstStepMs(stepDetails, 'screenshot'),
    stepDetails,
    operationLog: formattedMetrics.operationLog,
    urlRetrySummary: formattedMetrics.urlRetrySummary,
    healthSummary: formattedMetrics.healthSummary,
  };
}

export interface CrawlLogSession {
  handle: IncrementalCrawlLogHandle;
  flush: (snapshot: CrawlLogSnapshot) => Promise<void>;
}

export async function beginCrawlLogSession(
  directory: FileSystemDirectoryHandle | null,
  context: CrawlLogContext
): Promise<CrawlLogSession | null> {
  if (!directory) {
    return null;
  }

  const executedAt = formatExecutedAt();
  const initialEntry = buildCrawlLogEntry(
    context,
    {
      attemptedCount: 0,
      successCount: 0,
      errors: [],
      timings: [],
      totalMs: 0,
      sessionMetrics: createCrawlSessionMetrics(),
    },
    executedAt
  );

  try {
    const handle = await beginIncrementalCrawlLog(directory, initialEntry);
    return {
      handle,
      flush: async (snapshot) => {
        const entry = buildCrawlLogEntry(context, snapshot, handle.executedAt);
        await updateCrawlLogEntry(directory, handle, entry);
      },
    };
  } catch (e) {
    console.error('크롤링 로그 초기화 실패:', e);
    return null;
  }
}

export function buildLiveCrawlLogSnapshot(params: {
  successCount: number;
  totalFailCount: number;
  batchErrors: CrawlFailureRecord[];
  batchTimings: UrlTiming[];
  crawlStartAtMs: number | null;
  sessionMetrics?: CrawlSessionMetrics | null;
  plannedAttemptedCount?: number;
  interruptMessage?: string;
  savedCount?: number;
}): CrawlLogSnapshot {
  const totalMs = params.crawlStartAtMs !== null ? Date.now() - params.crawlStartAtMs : 0;
  const processedCount = new Set([
    ...params.batchTimings.map((timing) => timing.url),
    ...params.batchErrors.map((error) => error.url),
  ]).size;
  const successFromTimings = params.batchTimings.filter((timing) => timing.success).length;
  const successCount = Math.max(
    params.successCount,
    successFromTimings,
    params.savedCount ?? 0
  );
  const attemptedCount =
    params.plannedAttemptedCount ??
    Math.max(successCount + params.totalFailCount, processedCount);

  return {
    attemptedCount,
    successCount,
    errors: params.batchErrors,
    timings: params.batchTimings,
    totalMs,
    extraFailures: buildExtraStreamFailures(params.batchErrors, params.interruptMessage),
    sessionMetrics: params.sessionMetrics,
  };
}

export function scheduleCrawlLogFlush(
  session: CrawlLogSession | null,
  snapshot: CrawlLogSnapshot
): void {
  if (!session) {
    return;
  }
  void session.flush(snapshot).catch((e) => {
    console.error('크롤링 로그 증분 저장 실패:', e);
  });
}

export async function saveCrawlLog(
  directory: FileSystemDirectoryHandle | null,
  context: CrawlLogContext,
  attemptedCount: number,
  successCount: number,
  errors: CrawlFailureRecord[],
  totalMs: number,
  timings: UrlTiming[],
  extraFailures: CrawlFailureRecord[] = [],
  sessionMetrics?: CrawlSessionMetrics | null,
  incrementalHandle?: IncrementalCrawlLogHandle | null
): Promise<void> {
  if (!directory) {
    return;
  }

  const snapshot: CrawlLogSnapshot = {
    attemptedCount,
    successCount,
    errors,
    timings,
    totalMs,
    extraFailures,
    sessionMetrics,
  };
  const executedAt = incrementalHandle?.executedAt ?? formatExecutedAt();
  const entry = buildCrawlLogEntry(context, snapshot, executedAt);

  try {
    if (incrementalHandle) {
      await updateCrawlLogEntry(directory, incrementalHandle, entry);
      return;
    }
    await appendCrawlLogEntry(directory, entry);
  } catch (e) {
    console.error('크롤링 로그 저장 실패:', e);
  }
}

export async function saveBatchResults(
  session: CrawlPersistSession | null,
  batchResults: DcinsidePostData[],
  directory: FileSystemDirectoryHandle,
  options: PersistResultsOptions
): Promise<{ session: CrawlPersistSession; postsForExcel: DcinsidePostData[] }> {
  let activeSession = session;
  if (!activeSession) {
    activeSession = await createCrawlPersistSession(directory, options);
  }
  const postsForExcel = await appendBatchToSession(activeSession, batchResults);
  return { session: activeSession, postsForExcel };
}
