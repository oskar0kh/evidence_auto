import type { DcinsidePostData } from '../../platforms/dcinside/types';

export interface CrawlResponse {
  data: DcinsidePostData[];
  errors: { url: string; error: string; stage?: string }[];
  timings?: UrlTiming[];
}

export interface CrawlStreamResult extends CrawlResponse {
  successCount?: number;
  failCount?: number;
  attemptedCount?: number;
  interrupted?: boolean;
  interruptMessage?: string;
}

export interface CrawlProgressEvent {
  completed: number;
  total: number;
  currentUrl: string;
  stage: string;
  successCount: number;
  failCount: number;
}

export interface UrlTiming {
  url: string;
  success: boolean;
  totalMs: number;
  steps: Record<string, number>;
}

export interface CrawlLogEntry {
  executedAt: string;
  keyword?: string;
  inputMode: '검색어' | '검색어+기간' | '검색어+갤러리' | '검색어+기간+갤러리' | 'URL 직접입력';
  attemptedCount: number;
  successCount: number;
  failCount: number;
  failureReasons: string;
  totalMs: number;
  searchMs?: number;
  textCrawlMs?: number;
  seleniumBootMs?: number;
  pageNavigateMs?: number;
  waitContentMs?: number;
  waitCommentsMs?: number;
  captureImagesMs?: number;
  screenshotMs?: number;
  stepDetails: Record<string, number>;
}
