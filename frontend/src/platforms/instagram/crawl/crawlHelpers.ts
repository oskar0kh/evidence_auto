import type { CrawlProgressEvent } from './types';
import type { InstagramPostData } from '../types';
import { RESULTS_PREVIEW_SIZE } from './constants';
import {
  appendBatchToSession,
  createCrawlPersistSession,
  type CrawlPersistSession,
  type PersistResultsOptions,
} from '../export/persistResults';
import { INSTAGRAM_COMMUNITY_NAME } from '../export/pathUtils';
import type {
  CrawlLogContext,
  CrawlLogSession,
  CrawlLogSnapshot,
  SavedResultPreview as UnifiedSavedResultPreview,
} from '../../dcinside/crawl/crawlHelpers';
import {
  buildLiveCrawlLogSnapshot,
  scheduleCrawlLogFlush,
} from '../../dcinside/crawl/crawlHelpers';
import type { CrawlFailureRecord, CrawlLogEntry, UrlTiming } from '../../dcinside/crawl/types';
import {
  aggregateStepTimings,
  appendCrawlLogEntry,
  beginIncrementalCrawlLog,
  formatExecutedAt,
  formatFailureReasons,
  mergeCrawlFailures,
  pickFirstStepMs,
  pickStepMs,
  updateCrawlLogEntry,
  type IncrementalCrawlLogHandle,
} from '../../dcinside/crawl/crawlLogExport';
import {
  createCrawlSessionMetrics,
  formatCrawlSessionMetricsForLog,
  type CrawlSessionMetrics,
} from '../../dcinside/crawl/crawlSessionMetrics';

export type { CrawlLogSession, CrawlLogSnapshot };
export { buildLiveCrawlLogSnapshot, scheduleCrawlLogFlush };

/** 인스타그램 단계명에 맞춰 로그 컬럼을 채운다. (디시 buildCrawlLogEntry와 분리) */
export function buildInstagramCrawlLogEntry(
  context: CrawlLogContext,
  snapshot: CrawlLogSnapshot,
  executedAt: string
): CrawlLogEntry {
  const extraFailures = snapshot.extraFailures ?? [];
  const mergedFailures = mergeCrawlFailures(snapshot.errors, snapshot.timings, extraFailures);
  const stepDetails = aggregateStepTimings(snapshot.timings);
  const metrics = snapshot.sessionMetrics ?? createCrawlSessionMetrics();
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
      pickStepMs(stepDetails, 'fetch-page', 'parse-html', 'graphql-post', 'fetch-comments', 'build-result'),
    pageNavigateMs: pickStepMs(stepDetails, 'page-navigate'),
    waitContentMs: pickStepMs(stepDetails, 'wait-content'),
    waitCommentsMs: pickStepMs(stepDetails, 'fetch-comments', 'wait-comments'),
    captureImagesMs: pickStepMs(stepDetails, 'capture-images'),
    screenshotMs:
      pickFirstStepMs(stepDetails, 'screenshot') ??
      pickStepMs(stepDetails, 'page-navigate', 'wait-content', 'screenshot-scroll', 'capture-images'),
    stepDetails,
    operationLog: formattedMetrics.operationLog,
    urlRetrySummary: formattedMetrics.urlRetrySummary,
    healthSummary: formattedMetrics.healthSummary,
  };
}

export async function beginInstagramCrawlLogSession(
  directory: FileSystemDirectoryHandle | null,
  context: CrawlLogContext
): Promise<CrawlLogSession | null> {
  if (!directory) {
    return null;
  }

  const executedAt = formatExecutedAt();
  const initialEntry = buildInstagramCrawlLogEntry(
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
        const entry = buildInstagramCrawlLogEntry(context, snapshot, handle.executedAt);
        await updateCrawlLogEntry(directory, handle, entry);
      },
    };
  } catch (e) {
    console.error('인스타그램 크롤링 로그 초기화 실패:', e);
    return null;
  }
}

export async function saveInstagramCrawlLog(
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
  const entry = buildInstagramCrawlLogEntry(context, snapshot, executedAt);

  try {
    if (incrementalHandle) {
      await updateCrawlLogEntry(directory, incrementalHandle, entry);
      return;
    }
    await appendCrawlLogEntry(directory, entry);
  } catch (e) {
    console.error('인스타그램 크롤링 로그 저장 실패:', e);
  }
}

export async function saveInstagramBatchResults(
  session: CrawlPersistSession | null,
  batchResults: InstagramPostData[],
  directory: FileSystemDirectoryHandle,
  options: PersistResultsOptions
): Promise<{ session: CrawlPersistSession; postsForExcel: InstagramPostData[] }> {
  let activeSession = session;
  if (!activeSession) {
    activeSession = await createCrawlPersistSession(directory, {
      ...options,
      communityName: options.communityName ?? INSTAGRAM_COMMUNITY_NAME,
    });
  }
  const postsForExcel = await appendBatchToSession(activeSession, batchResults);
  return { session: activeSession, postsForExcel };
}

export function toInstagramUnifiedPreview(
  post: InstagramPostData,
  serial: number
): UnifiedSavedResultPreview {
  return {
    serial,
    url: post.url,
    title: post.title,
    galleryName: post.postType,
    nickname: post.nickname,
    postDate: post.postDate,
    viewCount: 0,
    commentCount: post.commentCount,
    captureFilePath: post.captureFilePath,
    community: INSTAGRAM_COMMUNITY_NAME,
  };
}

export interface CrawlProgress {
  completed: number;
  total: number;
  currentUrl: string;
  stage?: string;
  successCount: number;
  failCount: number;
}

export interface SavedResultPreview {
  serial: number;
  url: string;
  title: string;
  postType: string;
  nickname: string;
  postDate: string;
  commentCount: number;
  captureFilePath: string;
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
  };
}

export function computeProgressPercent(progress: CrawlProgress, loading: boolean): number {
  if (!loading) return 100;
  if (progress.total <= 0) return progress.stage === 'search' ? 5 : 0;
  return Math.min(100, Math.round((progress.completed / progress.total) * 100));
}

export function computeProgressLabel(progress: CrawlProgress, loading: boolean): string {
  if (!loading) return '완료';
  if (progress.total <= 0) return '검색·수집 중';
  return `${progress.completed} / ${progress.total} 처리 중`;
}

export function formatStageLabel(stage?: string): string {
  if (!stage) return '처리 중';
  if (stage.startsWith('search')) return '검색';
  if (stage.includes('screenshot')) return '화면 캡처';
  if (stage === 'fetch') return '데이터 수집';
  if (stage === 'skipped') return '건너뜀';
  if (stage === 'url-done') return '완료';
  return stage;
}

export function shortenUrl(url: string): string {
  if (url.length <= 60) return url;
  return url.slice(0, 57) + '...';
}

export function toResultPreview(post: InstagramPostData, serial: number): SavedResultPreview {
  return {
    serial,
    url: post.url,
    title: post.title,
    postType: post.postType,
    nickname: post.nickname,
    postDate: post.postDate,
    commentCount: post.commentCount,
    captureFilePath: post.captureFilePath,
  };
}

export function appendResultPreviews(
  existing: SavedResultPreview[],
  posts: InstagramPostData[],
  startSerial: number,
  max = RESULTS_PREVIEW_SIZE
): SavedResultPreview[] {
  const incoming = posts.map((post, index) => toResultPreview(post, startSerial + index)).reverse();
  const merged = [...incoming, ...existing];
  return merged.length > max ? merged.slice(0, max) : merged;
}
