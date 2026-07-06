export interface CommentData {
  no: string;
  name: string;
  userId: string;
  ip: string;
  memo: string;
  regDate: string;
  isDelete: string;
}

export interface DcinsidePostData {
  url: string;
  postDate: string;
  galleryName: string;
  nickname: string;
  title: string;
  content: string;
  crimeType: string;
  remarks: string;
  captureFilePath: string;
  captureImageBase64: string;
  viewCount: number;
  commentCount: number;
  comments: CommentData[];
}

export interface CrawlResponse {
  data: DcinsidePostData[];
  errors: { url: string; error: string; stage?: string }[];
  timings?: UrlTiming[];
}

export interface UrlTiming {
  url: string;
  success: boolean;
  totalMs: number;
  steps: Record<string, number>;
}

export interface SearchResponse {
  urls: string[];
  count: number;
  searchMs?: number;
  dateRangeSearch?: boolean;
  startDate?: string;
  endDate?: string;
}

export interface SearchOptions {
  maxResults?: number;
  startDate?: string;
  endDate?: string;
}

export interface CrawlLogEntry {
  executedAt: string;
  keyword?: string;
  inputMode: '검색어' | '검색어+기간' | 'URL 직접입력';
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
