import type { CrawlHealthEvent, CrawlProgressEvent, UrlTiming } from './types';

export interface CrawlSessionMetrics {
  startedAtMs: number;
  healthMessages: string[];
  operationEvents: string[];
  urlRetryNotes: string[];
  urlBudgetTimeouts: number;
}

export function createCrawlSessionMetrics(startedAtMs = Date.now()): CrawlSessionMetrics {
  return {
    startedAtMs,
    healthMessages: [],
    operationEvents: [],
    urlRetryNotes: [],
    urlBudgetTimeouts: 0,
  };
}

export function observeCrawlHealth(metrics: CrawlSessionMetrics, health: CrawlHealthEvent): void {
  if (!health.message) {
    return;
  }
  const message = health.message.trim();
  if (!message) {
    return;
  }
  metrics.healthMessages.push(withEventTimestamp(message, metrics.startedAtMs));
}

function shouldRecordUrlRetry(urlAttempt: number, urlAttemptPhase: string): boolean {
  return !(urlAttemptPhase === 'fast' && urlAttempt === 1);
}

export function observeCrawlProgress(metrics: CrawlSessionMetrics, event: CrawlProgressEvent): void {
  if (event.urlAttempt == null || event.urlAttemptMax == null || !event.urlAttemptPhase) {
    return;
  }
  if (!shouldRecordUrlRetry(event.urlAttempt, event.urlAttemptPhase)) {
    return;
  }
  const phaseLabel = event.urlAttemptPhase === 'protective' ? 'Protective' : 'Fast';
  const deadline =
    event.urlDeadlineRemainingMs != null
      ? ` · 예산 ${formatDeadlineSeconds(event.urlDeadlineRemainingMs)}`
      : '';
  const note = withEventTimestamp(
    `${shortenUrlForLog(event.currentUrl)}: ${phaseLabel} ${event.urlAttempt}/${event.urlAttemptMax}${deadline}`,
    metrics.startedAtMs
  );
  const urlPrefix = shortenUrlForLog(event.currentUrl) + ':';
  const existingIndex = metrics.urlRetryNotes.findIndex((item) =>
    stripEventTimestamp(item).startsWith(urlPrefix)
  );
  if (existingIndex >= 0) {
    metrics.urlRetryNotes[existingIndex] = note;
    return;
  }
  metrics.urlRetryNotes.push(note);
}

export function observeUrlTiming(metrics: CrawlSessionMetrics, timing: UrlTiming): void {
  if (timing.urlAttempt == null || timing.urlAttemptMax == null || !timing.urlAttemptPhase) {
    return;
  }
  if (!shouldRecordUrlRetry(timing.urlAttempt, timing.urlAttemptPhase)) {
    return;
  }
  const phaseLabel = timing.urlAttemptPhase === 'protective' ? 'Protective' : 'Fast';
  const resultLabel = timing.success ? '성공' : '실패';
  const deadline =
    timing.urlDeadlineRemainingMs != null
      ? ` · 예산 ${formatDeadlineSeconds(timing.urlDeadlineRemainingMs)}`
      : '';
  const note = withEventTimestamp(
    `${shortenUrlForLog(timing.url)}: ${phaseLabel} ${timing.urlAttempt}/${timing.urlAttemptMax} ${resultLabel}${deadline}`,
    metrics.startedAtMs
  );
  const urlPrefix = shortenUrlForLog(timing.url) + ':';
  const existingIndex = metrics.urlRetryNotes.findIndex((item) =>
    stripEventTimestamp(item).startsWith(urlPrefix)
  );
  if (existingIndex >= 0) {
    metrics.urlRetryNotes[existingIndex] = note;
    return;
  }
  metrics.urlRetryNotes.push(note);
}

export function mergeOperationEvents(
  metrics: CrawlSessionMetrics,
  backendEvents: string[] = []
): void {
  for (const event of backendEvents) {
    const trimmed = event.trim();
    if (!trimmed) {
      continue;
    }
    if (metrics.operationEvents.includes(trimmed)) {
      continue;
    }
    metrics.operationEvents.push(trimmed);
  }
}

export function countUrlBudgetTimeouts(
  metrics: CrawlSessionMetrics,
  errors: { error: string }[]
): void {
  metrics.urlBudgetTimeouts = errors.filter((item) =>
    item.error.includes('URL 처리 시간 예산')
  ).length;
}

export function formatCrawlSessionMetricsForLog(metrics: CrawlSessionMetrics): {
  operationLog: string;
  urlRetrySummary: string;
  healthSummary: string;
} {
  const operationLines = [...metrics.operationEvents];
  if (metrics.urlBudgetTimeouts > 0) {
    operationLines.push(
      withEventTimestamp(
        `URL 3분 예산 초과 ${metrics.urlBudgetTimeouts}건`,
        metrics.startedAtMs
      )
    );
  }

  return {
    operationLog: operationLines.join('\n'),
    urlRetrySummary: metrics.urlRetryNotes.join('\n'),
    healthSummary: metrics.healthMessages.join('\n'),
  };
}

function formatDeadlineSeconds(remainingMs: number): string {
  const totalSeconds = Math.max(0, Math.ceil(remainingMs / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')} 남음`;
}

function formatEventTimestamp(startedAtMs: number, date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  const wallClock = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  const elapsedMs = Math.max(0, date.getTime() - startedAtMs);
  const totalSeconds = Math.floor(elapsedMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return `[${wallClock}/${pad(hours)}:${pad(minutes)}:${pad(seconds)}]`;
}

function withEventTimestamp(message: string, startedAtMs: number, date = new Date()): string {
  const trimmed = message.trim();
  if (/^\[\d{2}:\d{2}\/\d{2}:\d{2}:\d{2}\]/.test(trimmed)) {
    return trimmed;
  }
  return `${formatEventTimestamp(startedAtMs, date)} ${trimmed}`;
}

function stripEventTimestamp(message: string): string {
  return message.replace(/^\[\d{2}:\d{2}\/\d{2}:\d{2}:\d{2}\]\s*/, '');
}

function shortenUrlForLog(url: string, max = 72): string {
  const trimmed = url.trim();
  return trimmed.length <= max ? trimmed : `${trimmed.slice(0, max)}…`;
}
