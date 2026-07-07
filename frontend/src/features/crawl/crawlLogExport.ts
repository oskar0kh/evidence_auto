import ExcelJS from 'exceljs';
import {
  CRAWL_LOG_HEADER_FILL,
  EXCEL_HEADER_FONT,
  sanitizeExcelText,
} from '../../shared/lib/excelUtils';
import { formatLogDuration } from '../../shared/lib/formatDuration';
import { getOrCreateSubdirectory, tryReadFileFromDirectory, writeArrayBufferToDirectory } from '../../shared/lib/localFileStorage';
import type { CrawlLogEntry } from './types';

export const CRAWL_LOG_DIR = 'log';
const SHEET_NAME = 'crawling_log';

const COLUMNS = [
  { header: '실행 일시', key: 'executedAt', width: 20 },
  { header: '검색어', key: 'keyword', width: 24 },
  { header: '검색 기간', key: 'searchDateRange', width: 22 },
  { header: '갤러리명', key: 'galleryName', width: 24 },
  { header: '수집 방식', key: 'inputMode', width: 14 },
  { header: '시도 글 개수', key: 'attemptedCount', width: 12 },
  { header: '성공 글 개수', key: 'successCount', width: 12 },
  { header: '실패 글 개수', key: 'failCount', width: 12 },
  { header: '성공률(%)', key: 'successRate', width: 10 },
  { header: '실패 사유', key: 'failureReasons', width: 60 },
  { header: '총 소요 시간', key: 'totalMs', width: 22 },
  { header: '텍스트 수집', key: 'textCrawlMs', width: 22 },
  { header: '페이지 접속', key: 'pageNavigateMs', width: 22 },
  { header: '본문 대기', key: 'waitContentMs', width: 22 },
  { header: '댓글 대기', key: 'waitCommentsMs', width: 22 },
  { header: '이미지 캡처', key: 'captureImagesMs', width: 22 },
  { header: '스크린샷 합계', key: 'screenshotMs', width: 22 },
  { header: '평균 글당 소요', key: 'avgPerPostMs', width: 22 },
  { header: '구간별 상세', key: 'stepDetails', width: 80 },
] as const;

const STEP_NAME_LABELS: Record<string, string> = {
  'apply-tracking-blocker': '광고/트래킹 차단',
  'attach-capture': '캡처 결과 연결',
  'build-result': '빌드 결과',
  'capture-images': '이미지 캡처',
  'capture-main-region': '본문 영역 캡처',
  'create-driver': '셀레니움 부팅',
  'detect-comment-pages': '댓글 페이지 탐지',
  'fetch-comments': '댓글 수집',
  'fetch-page': '페이지 요청',
  'find-capture-target': '캡처 대상 찾기',
  'merge-images': '이미지 병합',
  'page-navigate': '페이지 접속',
  'parse-html': 'HTML 파싱',
  'quit-driver': '드라이버 종료',
  'read-temp-file': '임시 파일 읽기',
  screenshot: '스크린샷',
  'text-crawl': '텍스트 수집',
  'wait-comments': '댓글 대기',
  'wait-gallview-head': '본문 대기',
  'write-temp-file': '임시 파일 저장',
};

function formatOptionalDuration(ms: number | undefined): string {
  if (ms === undefined || ms === null) {
    return '';
  }
  return formatLogDuration(ms);
}

export function formatStepLabel(stepName: string): string {
  const commentPageMatch = stepName.match(/^comment-page-(\d+)$/);
  if (commentPageMatch) {
    return `댓글 페이지 캡처(${stepName})`;
  }

  const label = STEP_NAME_LABELS[stepName];
  if (label) {
    return `${label}(${stepName})`;
  }
  return stepName;
}

function formatStepDetails(steps: Record<string, number>): string {
  return Object.entries(steps)
    .sort(([a], [b]) => formatStepLabel(a).localeCompare(formatStepLabel(b), 'ko'))
    .map(([name, ms]) => `${formatStepLabel(name)}: ${formatLogDuration(ms)}`)
    .join('\n');
}

function buildRowValues(entry: CrawlLogEntry): Record<string, string | number> {
  const successRate =
    entry.attemptedCount > 0
      ? Math.round((entry.successCount / entry.attemptedCount) * 1000) / 10
      : 0;
  const avgPerPostMs =
    entry.attemptedCount > 0 ? Math.round(entry.totalMs / entry.attemptedCount) : 0;

  return {
    executedAt: entry.executedAt,
    keyword: entry.keyword ?? '',
    searchDateRange: entry.searchDateRange ?? '',
    galleryName: entry.galleryName ?? '',
    inputMode: entry.inputMode,
    attemptedCount: entry.attemptedCount,
    successCount: entry.successCount,
    failCount: entry.failCount,
    successRate,
    failureReasons: sanitizeExcelText(entry.failureReasons),
    totalMs: formatLogDuration(entry.totalMs),
    textCrawlMs: formatOptionalDuration(entry.textCrawlMs),
    pageNavigateMs: formatOptionalDuration(entry.pageNavigateMs),
    waitContentMs: formatOptionalDuration(entry.waitContentMs),
    waitCommentsMs: formatOptionalDuration(entry.waitCommentsMs),
    captureImagesMs: formatOptionalDuration(entry.captureImagesMs),
    screenshotMs: formatOptionalDuration(entry.screenshotMs),
    avgPerPostMs: avgPerPostMs > 0 ? formatLogDuration(avgPerPostMs) : '',
    stepDetails: sanitizeExcelText(formatStepDetails(entry.stepDetails)),
  };
}

function ensureHeaderRow(sheet: ExcelJS.Worksheet): void {
  sheet.columns = COLUMNS.map((column) => ({
    key: column.key,
    width: column.width,
  }));

  const headerRow = sheet.getRow(1);
  COLUMNS.forEach((column, index) => {
    headerRow.getCell(index + 1).value = column.header;
  });
  headerRow.font = EXCEL_HEADER_FONT;
  headerRow.fill = CRAWL_LOG_HEADER_FILL;
  headerRow.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
  headerRow.height = 22;
  sheet.views = [{ state: 'frozen', ySplit: 1 }];
}

async function loadOrCreateWorkbook(
  directory: FileSystemDirectoryHandle,
  filename: string
): Promise<{ workbook: ExcelJS.Workbook; sheet: ExcelJS.Worksheet }> {
  const workbook = new ExcelJS.Workbook();
  const existing = await tryReadFileFromDirectory(directory, filename);

  if (existing) {
    await workbook.xlsx.load(existing);
    const sheet = workbook.getWorksheet(SHEET_NAME) ?? workbook.worksheets[0];
    if (!sheet) {
      throw new Error('기존 크롤링 로그 파일을 읽을 수 없습니다.');
    }
    ensureHeaderRow(sheet);
    return { workbook, sheet };
  }

  const sheet = workbook.addWorksheet(SHEET_NAME);
  ensureHeaderRow(sheet);
  return { workbook, sheet };
}

export function buildDailyCrawlLogFilename(date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}_log.xlsx`;
}

export async function getOrCreateLogDirectory(
  saveDirectory: FileSystemDirectoryHandle
): Promise<FileSystemDirectoryHandle> {
  return getOrCreateSubdirectory(saveDirectory, CRAWL_LOG_DIR);
}

export async function appendCrawlLogEntry(
  saveDirectory: FileSystemDirectoryHandle,
  entry: CrawlLogEntry,
  date = new Date()
): Promise<void> {
  const logDirectory = await getOrCreateLogDirectory(saveDirectory);
  const filename = buildDailyCrawlLogFilename(date);
  const { workbook, sheet } = await loadOrCreateWorkbook(logDirectory, filename);
  const rowValues = buildRowValues(entry);
  const row = sheet.addRow(COLUMNS.map((column) => rowValues[column.key]));
  row.alignment = { vertical: 'top', wrapText: true };

  const buffer = await workbook.xlsx.writeBuffer();
  await writeArrayBufferToDirectory(logDirectory, filename, buffer);
}

export function aggregateStepTimings(
  timings: { steps: Record<string, number> }[]
): Record<string, number> {
  const aggregated: Record<string, number> = {};
  for (const timing of timings) {
    for (const [name, ms] of Object.entries(timing.steps)) {
      aggregated[name] = (aggregated[name] ?? 0) + ms;
    }
  }
  return aggregated;
}

export function pickStepMs(steps: Record<string, number>, ...names: string[]): number | undefined {
  let total = 0;
  let found = false;
  for (const name of names) {
    if (steps[name] !== undefined) {
      total += steps[name];
      found = true;
    }
  }
  return found ? total : undefined;
}

export function pickFirstStepMs(steps: Record<string, number>, ...names: string[]): number | undefined {
  for (const name of names) {
    if (steps[name] !== undefined) {
      return steps[name];
    }
  }
  return undefined;
}

export function formatFailureReasons(errors: { url: string; error: string }[]): string {
  if (errors.length === 0) {
    return '';
  }
  return errors.map((item) => `${item.url}\n→ ${item.error}`).join('\n\n');
}

export function formatExecutedAt(date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
