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
import type { SavedResultPreview as UnifiedSavedResultPreview } from '../../dcinside/crawl/crawlHelpers';

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
