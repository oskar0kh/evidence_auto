import { sanitizeExcelText } from '../../../shared/lib/excelUtils';
import { formatLogDuration } from '../../../shared/lib/formatDuration';
import { getOrCreateSubdirectory, tryReadFileFromDirectory, writeArrayBufferToDirectory } from '../../../shared/lib/localFileStorage';
import type { CrawlFailureRecord, CrawlLogEntry, UrlTiming } from './types';

export const CRAWL_LOG_DIR = 'log';

const COLUMNS = [
  { header: '실행 일시', key: 'executedAt' },
  { header: '검색어', key: 'keyword' },
  { header: '검색 기간', key: 'searchDateRange' },
  { header: '갤러리명', key: 'galleryName' },
  { header: '수집 방식', key: 'inputMode' },
  { header: '시도 글 개수', key: 'attemptedCount' },
  { header: '성공 글 개수', key: 'successCount' },
  { header: '실패 글 개수', key: 'failCount' },
  { header: '성공률(%)', key: 'successRate' },
  { header: '실패 사유', key: 'failureReasons' },
  { header: '총 소요 시간', key: 'totalMs' },
  { header: '텍스트 수집', key: 'textCrawlMs' },
  { header: '페이지 접속', key: 'pageNavigateMs' },
  { header: '본문 대기', key: 'waitContentMs' },
  { header: '댓글 대기', key: 'waitCommentsMs' },
  { header: '이미지 캡처', key: 'captureImagesMs' },
  { header: '스크린샷 합계', key: 'screenshotMs' },
  { header: '평균 글당 소요', key: 'avgPerPostMs' },
  { header: '구간별 상세', key: 'stepDetails' },
  { header: '운영 이벤트', key: 'operationLog' },
  { header: 'URL 재시도', key: 'urlRetrySummary' },
  { header: '보호/상태 이벤트', key: 'healthSummary' },
] as const;

const ENTRY_MARKER_PATTERN = /^=== 크롤링 기록 #(\d+) ===$/;

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

const FAILURE_STAGE_LABELS: Record<string, string> = {
  search: 'URL 수집',
  'text-crawl': '텍스트 수집',
  screenshot: '스크린샷',
  'attach-capture': '결과 저장',
  timeout: '시간 초과',
  connection: 'HTTP 연결',
  session: '세션/스트림',
  'url-failed': '크롤 실패',
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

export function formatFailureStageLabel(stage?: string): string {
  if (!stage) {
    return '크롤 실패';
  }
  return FAILURE_STAGE_LABELS[stage] ?? stage;
}

function formatStepDetails(steps: Record<string, number>): string {
  return Object.entries(steps)
    .sort(([a], [b]) => formatStepLabel(a).localeCompare(formatStepLabel(b), 'ko'))
    .map(([name, ms]) => `${formatStepLabel(name)}: ${formatLogDuration(ms)}`)
    .join('\n');
}

function failureKey(record: CrawlFailureRecord): string {
  return `${record.url}\0${record.stage ?? ''}\0${record.error}`;
}

function inferStageFromTiming(timing: UrlTiming): string {
  const stepNames = Object.keys(timing.steps);
  if (stepNames.some((name) => name === 'screenshot' || name.startsWith('screenshot '))) {
    return 'screenshot';
  }
  if (stepNames.some((name) => name.includes('create-driver') || name.includes('page-navigate'))) {
    return 'screenshot';
  }
  if (stepNames.some((name) => name === 'text-crawl' || name.startsWith('fetch-') || name === 'parse-html')) {
    return 'text-crawl';
  }
  return 'url-failed';
}

function inferErrorFromTiming(timing: UrlTiming): string {
  const stage = inferStageFromTiming(timing);
  const label = formatFailureStageLabel(stage);
  return `[${label}] 해당 단계에서 실패했습니다. (총 ${formatLogDuration(timing.totalMs)})`;
}

export function mergeCrawlFailures(
  errors: CrawlFailureRecord[],
  timings: UrlTiming[] = [],
  streamErrors: CrawlFailureRecord[] = []
): CrawlFailureRecord[] {
  const merged: CrawlFailureRecord[] = [];
  const seen = new Set<string>();

  const push = (record: CrawlFailureRecord) => {
    const normalized: CrawlFailureRecord = {
      url: record.url,
      error: record.error,
      stage: record.stage,
    };
    const key = failureKey(normalized);
    if (seen.has(key)) {
      return;
    }
    seen.add(key);
    merged.push(normalized);
  };

  for (const error of errors) {
    push(error);
  }

  const erroredUrls = new Set(errors.map((error) => error.url));
  for (const timing of timings) {
    if (timing.success || erroredUrls.has(timing.url)) {
      continue;
    }
    push({
      url: timing.url,
      stage: inferStageFromTiming(timing),
      error: inferErrorFromTiming(timing),
    });
  }

  for (const streamError of streamErrors) {
    push(streamError);
  }

  return merged;
}

export function buildExtraStreamFailures(
  batchErrors: CrawlFailureRecord[],
  interruptMessage?: string
): CrawlFailureRecord[] {
  if (!interruptMessage?.trim()) {
    return [];
  }
  if (batchErrors.some((error) => error.url.startsWith('('))) {
    return [];
  }
  return [{ url: '(크롤 스트림)', error: interruptMessage.trim(), stage: 'session' }];
}

export function formatFailureReasons(errors: CrawlFailureRecord[]): string {
  if (errors.length === 0) {
    return '';
  }
  return errors
    .map((item) => {
      const stageLabel = formatFailureStageLabel(item.stage);
      return `[${stageLabel}] ${item.url}\n→ ${item.error}`;
    })
    .join('\n\n');
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
    operationLog: sanitizeExcelText(entry.operationLog ?? ''),
    urlRetrySummary: sanitizeExcelText(entry.urlRetrySummary ?? ''),
    healthSummary: sanitizeExcelText(entry.healthSummary ?? ''),
  };
}

function formatFieldLine(index: number, header: string, value: string | number): string {
  const label = `${index + 1}. ${header}:`;
  const text = String(value ?? '');

  if (!text.includes('\n')) {
    return `${label} ${text}`;
  }

  const [firstLine, ...restLines] = text.split('\n');
  const lines = [`${label} ${firstLine}`];
  for (const line of restLines) {
    lines.push(`    ${line}`);
  }
  return lines.join('\n');
}

function formatEntryBlock(entryIndex: number, entry: CrawlLogEntry): string {
  const rowValues = buildRowValues(entry);
  const lines = [`=== 크롤링 기록 #${entryIndex} ===`];

  COLUMNS.forEach((column, index) => {
    lines.push(formatFieldLine(index, column.header, rowValues[column.key]));
  });

  return lines.join('\n');
}

function decodeUtf8(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  const hasBom =
    bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf;
  const offset = hasBom ? 3 : 0;
  return new TextDecoder('utf-8').decode(bytes.slice(offset));
}

function encodeUtf8WithBom(text: string): ArrayBuffer {
  const encoded = new TextEncoder().encode(text);
  const bom = new Uint8Array([0xef, 0xbb, 0xbf]);
  const combined = new Uint8Array(bom.length + encoded.length);
  combined.set(bom);
  combined.set(encoded, bom.length);
  return combined.buffer;
}

async function tryReadTextFromDirectory(
  directory: FileSystemDirectoryHandle,
  filename: string
): Promise<string | null> {
  const existing = await tryReadFileFromDirectory(directory, filename);
  if (!existing) {
    return null;
  }
  return decodeUtf8(existing);
}

async function writeTextToDirectory(
  directory: FileSystemDirectoryHandle,
  filename: string,
  text: string
): Promise<void> {
  await writeArrayBufferToDirectory(directory, filename, encodeUtf8WithBom(text));
}

interface ParsedLogFile {
  preamble: string;
  entryIndices: number[];
}

function parseLogFile(content: string): ParsedLogFile {
  const lines = content.split('\n');
  const entryIndices: number[] = [];
  let firstEntryLine = -1;

  for (let index = 0; index < lines.length; index += 1) {
    const match = lines[index].match(ENTRY_MARKER_PATTERN);
    if (match) {
      if (firstEntryLine === -1) {
        firstEntryLine = index;
      }
      entryIndices.push(Number(match[1]));
    }
  }

  const preamble =
    firstEntryLine === -1 ? content.trimEnd() : lines.slice(0, firstEntryLine).join('\n').trimEnd();

  return { preamble, entryIndices };
}

function buildLogPreamble(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  const dateLabel = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  return `[크롤링 로그 ${dateLabel}]`;
}

function rebuildLogContent(
  preamble: string,
  entryBlocks: string[]
): string {
  const parts = [preamble, ...entryBlocks].filter((part) => part.length > 0);
  return `${parts.join('\n\n')}\n`;
}

async function loadLogEntryBlocks(
  directory: FileSystemDirectoryHandle,
  filename: string,
  date: Date
): Promise<{ preamble: string; entryBlocks: Map<number, string> }> {
  const existing = await tryReadTextFromDirectory(directory, filename);
  if (!existing?.trim()) {
    return {
      preamble: buildLogPreamble(date),
      entryBlocks: new Map<number, string>(),
    };
  }

  const { preamble, entryIndices } = parseLogFile(existing);
  const entryBlocks = new Map<number, string>();
  const markerRegex = /^=== 크롤링 기록 #(\d+) ===$/gm;
  const matches = [...existing.matchAll(markerRegex)];

  for (let index = 0; index < matches.length; index += 1) {
    const match = matches[index];
    const entryIndex = Number(match[1]);
    const start = match.index ?? 0;
    const end = index + 1 < matches.length ? (matches[index + 1].index ?? existing.length) : existing.length;
    entryBlocks.set(entryIndex, existing.slice(start, end).trimEnd());
  }

  if (entryIndices.length === 0) {
    return {
      preamble: existing.trimEnd(),
      entryBlocks,
    };
  }

  return {
    preamble: preamble || buildLogPreamble(date),
    entryBlocks,
  };
}

function getNextEntryIndex(entryBlocks: Map<number, string>): number {
  if (entryBlocks.size === 0) {
    return 1;
  }
  return Math.max(...entryBlocks.keys()) + 1;
}

async function persistLogEntries(
  directory: FileSystemDirectoryHandle,
  filename: string,
  preamble: string,
  entryBlocks: Map<number, string>
): Promise<void> {
  const sortedBlocks = [...entryBlocks.entries()]
    .sort(([left], [right]) => left - right)
    .map(([, block]) => block);
  await writeTextToDirectory(directory, filename, rebuildLogContent(preamble, sortedBlocks));
}

export function buildDailyCrawlLogFilename(date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}_log.txt`;
}

export async function getOrCreateLogDirectory(
  saveDirectory: FileSystemDirectoryHandle
): Promise<FileSystemDirectoryHandle> {
  return getOrCreateSubdirectory(saveDirectory, CRAWL_LOG_DIR);
}

export interface IncrementalCrawlLogHandle {
  rowNumber: number;
  logDate: Date;
  executedAt: string;
}

const logFileWriteQueues = new Map<string, Promise<void>>();

function enqueueLogFileWrite(filename: string, task: () => Promise<void>): Promise<void> {
  const previous = logFileWriteQueues.get(filename) ?? Promise.resolve();
  const next = previous.then(task, task).finally(() => {
    if (logFileWriteQueues.get(filename) === next) {
      logFileWriteQueues.delete(filename);
    }
  });
  logFileWriteQueues.set(filename, next);
  return next;
}

export async function beginIncrementalCrawlLog(
  saveDirectory: FileSystemDirectoryHandle,
  entry: CrawlLogEntry,
  date = new Date()
): Promise<IncrementalCrawlLogHandle> {
  const logDirectory = await getOrCreateLogDirectory(saveDirectory);
  const filename = buildDailyCrawlLogFilename(date);
  let rowNumber = 0;

  await enqueueLogFileWrite(filename, async () => {
    const { preamble, entryBlocks } = await loadLogEntryBlocks(logDirectory, filename, date);
    rowNumber = getNextEntryIndex(entryBlocks);
    entryBlocks.set(rowNumber, formatEntryBlock(rowNumber, entry));
    await persistLogEntries(logDirectory, filename, preamble, entryBlocks);
  });

  return { rowNumber, logDate: date, executedAt: entry.executedAt };
}

export async function updateCrawlLogEntry(
  saveDirectory: FileSystemDirectoryHandle,
  handle: IncrementalCrawlLogHandle,
  entry: CrawlLogEntry
): Promise<void> {
  const logDirectory = await getOrCreateLogDirectory(saveDirectory);
  const filename = buildDailyCrawlLogFilename(handle.logDate);

  await enqueueLogFileWrite(filename, async () => {
    const { preamble, entryBlocks } = await loadLogEntryBlocks(logDirectory, filename, handle.logDate);
    entryBlocks.set(handle.rowNumber, formatEntryBlock(handle.rowNumber, entry));
    await persistLogEntries(logDirectory, filename, preamble, entryBlocks);
  });
}

export async function appendCrawlLogEntry(
  saveDirectory: FileSystemDirectoryHandle,
  entry: CrawlLogEntry,
  date = new Date()
): Promise<void> {
  const logDirectory = await getOrCreateLogDirectory(saveDirectory);
  const filename = buildDailyCrawlLogFilename(date);

  await enqueueLogFileWrite(filename, async () => {
    const { preamble, entryBlocks } = await loadLogEntryBlocks(logDirectory, filename, date);
    const entryIndex = getNextEntryIndex(entryBlocks);
    entryBlocks.set(entryIndex, formatEntryBlock(entryIndex, entry));
    await persistLogEntries(logDirectory, filename, preamble, entryBlocks);
  });
}

export function mergeUrlTimings(existing: UrlTiming[], incoming: UrlTiming[]): UrlTiming[] {
  const byUrl = new Map<string, UrlTiming>();
  for (const timing of existing) {
    byUrl.set(timing.url, timing);
  }
  for (const timing of incoming) {
    byUrl.set(timing.url, timing);
  }
  return [...byUrl.values()];
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

export function formatExecutedAt(date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
