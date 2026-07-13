import type { CrawlHealthEvent, CrawlProgressEvent, UrlTiming } from '../../dcinside/crawl/types';

export type { CrawlHealthEvent, CrawlProgressEvent, UrlTiming };

export interface CrawlFailureRecord {
  url: string;
  error: string;
  stage?: string;
}

export interface CrawlStreamResult {
  data: { url: string }[];
  errors: CrawlFailureRecord[];
  timings?: UrlTiming[];
  successCount?: number;
  failCount?: number;
  attemptedCount?: number;
}

export interface CrawlProgress {
  completed: number;
  total: number;
  currentUrl: string;
  stage?: string;
  successCount: number;
  failCount: number;
}
