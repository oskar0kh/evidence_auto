import axios from 'axios';
import { useEffect, useRef, useState } from 'react';
import { crawlDcinsideStream, searchDcinsideAllTerms } from './platforms/dcinside/api';
import {
  aggregateStepTimings,
  appendCrawlLogEntry,
  formatExecutedAt,
  formatFailureReasons,
  pickFirstStepMs,
  pickStepMs,
} from './features/crawl/crawlLogExport';
import DateRangeInput from './shared/ui/DateRangeInput';
import {
  appendBatchToSession,
  createCrawlPersistSession,
  persistCrimeListAndCaptures,
  type CrawlPersistSession,
} from './features/export/persistResults';
import { CRAWL_BATCH_SIZE, RESULTS_PAGE_SIZE, chunkArray } from './features/crawl/constants';
import { isNativeFolderPickerSupported, pickNativeDirectory } from './shared/lib/nativeFolderPicker';
import type { CrawlLogEntry, CrawlStreamResult, UrlTiming } from './features/crawl/types';
import type { DcinsidePostData } from './platforms/dcinside/types';
import './app/App.css';

function deriveCommunityName(posts: DcinsidePostData[]): string {
  const galleryNames = posts
    .map((post) => post.galleryName?.trim())
    .filter((name): name is string => Boolean(name));
  if (galleryNames.length === 0) {
    return '디시인사이드';
  }
  const uniqueNames = [...new Set(galleryNames)];
  return uniqueNames.length === 1 ? uniqueNames[0] : '디시인사이드';
}

function parseUrls(input: string): string[] {
  return input
    .split(/\n|,/)
    .map((u) => u.trim())
    .filter((u) => u.length > 0);
}

function mergeSavedResults(
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

function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError';
}

const USER_CANCEL_REASON = '사용자 중도 취소';

function collectProcessedUrls(response: CrawlStreamResult, processedUrls: Set<string>): void {
  for (const post of response.data) {
    processedUrls.add(post.url);
  }
  for (const error of response.errors) {
    processedUrls.add(error.url);
  }
  for (const timing of response.timings ?? []) {
    processedUrls.add(timing.url);
  }
}

function appendUserCancelErrors(
  urls: string[],
  batchErrors: { url: string; error: string }[],
  processedUrls: Set<string>
): void {
  const existingErrorUrls = new Set(batchErrors.map((error) => error.url));
  for (const url of urls) {
    if (!processedUrls.has(url) && !existingErrorUrls.has(url)) {
      batchErrors.push({ url, error: USER_CANCEL_REASON });
      existingErrorUrls.add(url);
    }
  }
}

function shortenUrl(url: string, max = 56): string {
  return url.length <= max ? url : `${url.slice(0, max)}…`;
}

function formatDuration(ms: number): string {
  if (ms < 1000) {
    return `${ms}ms`;
  }
  const totalSeconds = ms / 1000;
  if (totalSeconds < 60) {
    return `${totalSeconds.toFixed(1)}초`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = (totalSeconds % 60).toFixed(1);
  return `${minutes}분 ${seconds}초`;
}

const STAGE_LABELS: Record<string, string> = {
  'text-crawl': '글·댓글 수집',
  screenshot: '화면 캡처',
  'attach-capture': '결과 저장',
  'url-done': '완료',
  'url-failed': '실패',
};

const STAGE_WEIGHTS: Record<string, number> = {
  'text-crawl': 0.15,
  screenshot: 0.55,
  'attach-capture': 0.85,
  'url-done': 1,
  'url-failed': 1,
};

function formatStageLabel(stage?: string): string {
  if (!stage) {
    return '처리 중';
  }
  return STAGE_LABELS[stage] ?? stage;
}

function hasPartialDateRange(startDate: string, endDate: string): boolean {
  return Boolean(startDate) !== Boolean(endDate);
}

function isValidDateRange(startDate: string, endDate: string): boolean {
  if (!startDate || !endDate) {
    return true;
  }
  return startDate <= endDate;
}

interface CrawlProgress {
  completed: number;
  total: number;
  currentUrl: string;
  stage?: string;
  successCount: number;
  failCount: number;
}

interface CrawlLogContext {
  keyword?: string;
  inputMode: CrawlLogEntry['inputMode'];
  searchMs?: number;
}

async function saveCrawlLog(
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
    inputMode: context.inputMode,
    attemptedCount,
    successCount,
    failCount: Math.max(attemptedCount - successCount, errors.length),
    failureReasons: formatFailureReasons(errors),
    totalMs,
    searchMs: context.searchMs,
    textCrawlMs:
      pickFirstStepMs(stepDetails, 'text-crawl') ??
      pickStepMs(stepDetails, 'fetch-page', 'parse-html', 'fetch-comments', 'build-result'),
    seleniumBootMs: pickStepMs(stepDetails, 'create-driver'),
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

function collectCrawlResponse(
  response: CrawlStreamResult,
  batchErrors: { url: string; error: string }[],
  batchTimings: UrlTiming[]
): number {
  batchErrors.push(...response.errors);
  if (response.timings) {
    batchTimings.push(...response.timings);
  }
  return response.successCount ?? response.data.length;
}

function formatInterruptedMessage(
  response: CrawlStreamResult,
  autoSaved: boolean,
  savedCount: number
): string {
  const base = response.interruptMessage ?? '크롤링이 중단됐습니다.';
  if (savedCount > 0) {
    if (autoSaved) {
      return `${base} 완료된 ${savedCount}건은 선택한 폴더에 자동 저장됐습니다.`;
    }
    return `${base} 완료된 ${savedCount}건은 화면에 보관됐으며, 범죄일람표·캡처화면 저장으로 보낼 수 있습니다.`;
  }
  return base;
}

function appendAutoSaveNotice(message: string, savedCount: number): string {
  return `${message} 완료된 ${savedCount}건은 선택한 폴더에 자동 저장됐습니다.`;
}

async function saveBatchResults(
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

export default function App() {
  const [urlInput, setUrlInput] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [searchStartDate, setSearchStartDate] = useState('');
  const [searchEndDate, setSearchEndDate] = useState('');
  const saveDirectoryRef = useRef<FileSystemDirectoryHandle | null>(null);
  const [saveDirectoryPath, setSaveDirectoryPath] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [progress, setProgress] = useState<CrawlProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [infoMessage, setInfoMessage] = useState<string | null>(null);
  const [savedResults, setSavedResults] = useState<DcinsidePostData[]>([]);
  const [resultPage, setResultPage] = useState(1);
  const [errors, setErrors] = useState<{ url: string; error: string }[]>([]);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [lastCrawlDurationMs, setLastCrawlDurationMs] = useState<number | null>(null);
  const crawlStartAtRef = useRef<number | null>(null);
  const crawlAbortRef = useRef<AbortController | null>(null);
  const lastSearchKeywordRef = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (!loading) {
      return;
    }
    const timerId = window.setInterval(() => {
      if (crawlStartAtRef.current !== null) {
        setElapsedMs(Date.now() - crawlStartAtRef.current);
      }
    }, 100);
    return () => window.clearInterval(timerId);
  }, [loading]);

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(savedResults.length / RESULTS_PAGE_SIZE));
    if (resultPage > totalPages) {
      setResultPage(totalPages);
    }
  }, [savedResults.length, resultPage]);

  const beginCrawlSession = (): AbortSignal => {
    crawlAbortRef.current?.abort();
    const controller = new AbortController();
    crawlAbortRef.current = controller;
    return controller.signal;
  };

  const handleCancelCrawl = () => {
    crawlAbortRef.current?.abort();
  };

  const handlePickDirectory = async () => {
    if (!isNativeFolderPickerSupported()) {
      setError('시스템 폴더 선택은 Chrome 또는 Edge에서만 지원됩니다.');
      return;
    }

    try {
      const handle = await pickNativeDirectory();
      saveDirectoryRef.current = handle;
      setSaveDirectoryPath(handle.name);
      setError(null);
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        return;
      }
      const message = e instanceof Error ? e.message : '폴더를 선택할 수 없습니다.';
      setError(message);
    }
  };

  const ensureSaveDirectorySelected = (): boolean => {
    if (!saveDirectoryRef.current) {
      setError('저장 폴더를 선택해 주세요.');
      return false;
    }
    return true;
  };

  const handleCrawl = async () => {
    const urls = parseUrls(urlInput);
    if (urls.length === 0) {
      setError('디시인사이드 게시글 URL을 입력해 주세요.');
      return;
    }
    if (!ensureSaveDirectorySelected()) {
      return;
    }

    lastSearchKeywordRef.current = undefined;
    await runCrawlForUrls(urls, {
      clearUrlInput: true,
      logContext: { inputMode: 'URL 직접입력' },
    });
  };

  const handleSearchCrawl = async () => {
    const query = searchInput.trim();
    if (!query) {
      setError('검색어를 입력해 주세요.');
      return;
    }
    if (hasPartialDateRange(searchStartDate, searchEndDate)) {
      setError('검색 기간의 시작일과 종료일을 모두 입력해 주세요.');
      return;
    }
    if (!isValidDateRange(searchStartDate, searchEndDate)) {
      setError('검색 기간의 시작일은 종료일보다 이후일 수 없습니다.');
      return;
    }
    if (!ensureSaveDirectorySelected()) {
      return;
    }

    const useDateRange = Boolean(searchStartDate && searchEndDate);
    const abortSignal = beginCrawlSession();

    crawlStartAtRef.current = Date.now();
    setElapsedMs(0);
    setLoading(true);
    setError(null);
    setInfoMessage(null);
    setErrors([]);
    setProgress({
      completed: 0,
      total: 1,
      currentUrl: useDateRange ? '기간 내 디시 통합검색 중…' : '디시 통합검색 중…',
      successCount: 0,
      failCount: 0,
    });

    const logContext: CrawlLogContext = {
      keyword: useDateRange ? `${query} (${searchStartDate}~${searchEndDate})` : query,
      inputMode: useDateRange ? '검색어+기간' : '검색어',
    };

    try {
      lastSearchKeywordRef.current = query;
      const searchResult = await searchDcinsideAllTerms(
        query,
        {
          maxResults: 100,
          startDate: useDateRange ? searchStartDate : undefined,
          endDate: useDateRange ? searchEndDate : undefined,
        },
        ({ termIndex, termTotal, term, collectedUrlCount }) => {
          setProgress({
            completed: termIndex - 1,
            total: termTotal,
            currentUrl: `검색어 ${termIndex}/${termTotal}: "${term}" (수집 URL ${collectedUrlCount}건)`,
            successCount: 0,
            failCount: 0,
          });
        },
        abortSignal
      );
      logContext.searchMs = searchResult.searchMs;

      if (searchResult.urls.length === 0) {
        setError(useDateRange ? '지정한 기간에 해당하는 검색 결과가 없습니다.' : '검색 결과가 없습니다.');
        await saveCrawlLog(
          saveDirectoryRef.current,
          logContext,
          0,
          0,
          [],
          Date.now() - (crawlStartAtRef.current ?? Date.now()),
          []
        );
        setLoading(false);
        setProgress(null);
        return;
      }

      await runCrawlForUrls(searchResult.urls, {
        clearSearchInput: true,
        clearSearchDates: true,
        skipInit: true,
        logContext,
      });
    } catch (e) {
      if (isAbortError(e)) {
        setError('크롤링이 취소됐습니다.');
        setInfoMessage(null);
        if (crawlStartAtRef.current !== null) {
          setLastCrawlDurationMs(Date.now() - crawlStartAtRef.current);
        }
        setLoading(false);
        setProgress(null);
        crawlAbortRef.current = null;
        return;
      }
      const message = resolveCrawlError(e);
      setError(message);
      await saveCrawlLog(
        saveDirectoryRef.current,
        logContext,
        0,
        0,
        [{ url: '(검색)', error: message }],
        Date.now() - (crawlStartAtRef.current ?? Date.now()),
        []
      );
      if (crawlStartAtRef.current !== null) {
        setLastCrawlDurationMs(Date.now() - crawlStartAtRef.current);
      }
      setLoading(false);
      setProgress(null);
      crawlAbortRef.current = null;
    }
  };

  const runCrawlForUrls = async (
    urls: string[],
    options?: {
      clearUrlInput?: boolean;
      clearSearchInput?: boolean;
      clearSearchDates?: boolean;
      skipInit?: boolean;
      logContext?: CrawlLogContext;
    }
  ) => {
    if (!options?.skipInit) {
      crawlStartAtRef.current = Date.now();
      setElapsedMs(0);
      setLoading(true);
      setError(null);
      setInfoMessage(null);
      setErrors([]);
    }

    const abortSignal = options?.skipInit
      ? (crawlAbortRef.current?.signal ?? beginCrawlSession())
      : beginCrawlSession();

    const batchErrors: { url: string; error: string }[] = [];
    const batchTimings: UrlTiming[] = [];
    const processedUrls = new Set<string>();
    let successCount = 0;
    let totalFailCount = 0;
    let wasInterrupted = false;
    let wasCancelled = false;
    let interruptMessage: string | undefined;
    let errorMessage: string | null = null;
    let autoSaved = false;
    let totalSavedCount = 0;
    let persistSession: CrawlPersistSession | null = null;
    const logContext: CrawlLogContext = options?.logContext ?? {
      inputMode: 'URL 직접입력',
    };

    const urlBatches = chunkArray(urls, CRAWL_BATCH_SIZE);
    let nextSerial = savedResults.length + 1;
    let globalCompletedOffset = 0;

    setProgress({
      completed: 0,
      total: urls.length,
      currentUrl: urls[0] ?? '',
      stage: 'text-crawl',
      successCount: 0,
      failCount: 0,
    });

    try {
      for (let batchIndex = 0; batchIndex < urlBatches.length; batchIndex++) {
        const batchUrls = urlBatches[batchIndex];
        let batchResults: DcinsidePostData[] = [];

        const response = await crawlDcinsideStream(
          batchUrls,
          nextSerial,
          (event) => {
            setProgress({
              completed: globalCompletedOffset + event.completed,
              total: urls.length,
              currentUrl: event.currentUrl,
              stage: event.stage,
              successCount: successCount + event.successCount,
              failCount: totalFailCount + event.failCount,
            });
          },
          (post) => {
            batchResults = mergeSavedResults(batchResults, [post]);
          },
          abortSignal
        );

        const batchSuccess = collectCrawlResponse(response, batchErrors, batchTimings);
        collectProcessedUrls(response, processedUrls);
        successCount += batchSuccess;
        totalFailCount += response.failCount ?? response.errors.length;
        wasInterrupted = Boolean(response.interrupted);
        if (wasInterrupted) {
          wasCancelled = true;
        }
        interruptMessage = response.interruptMessage;

        if (batchResults.length > 0 && saveDirectoryRef.current) {
          try {
            const saved = await saveBatchResults(
              persistSession,
              batchResults,
              saveDirectoryRef.current,
              deriveCommunityName(batchResults),
              lastSearchKeywordRef.current
            );
            persistSession = saved.session;
            setSavedResults((prev) => mergeSavedResults(prev, saved.postsForExcel));
            totalSavedCount += saved.postsForExcel.length;
            autoSaved = true;
          } catch (e) {
            const saveMessage =
              e instanceof Error ? e.message : '배치 자동 저장 중 오류가 발생했습니다.';
            errorMessage = errorMessage
              ? `${errorMessage} (배치 ${batchIndex + 1} 저장 실패: ${saveMessage})`
              : `배치 ${batchIndex + 1} 저장 실패: ${saveMessage}`;
          }
        }

        batchResults = [];
        nextSerial += batchSuccess;
        globalCompletedOffset += batchUrls.length;

        if (wasInterrupted) {
          break;
        }
      }

      setErrors(batchErrors);
      if (wasInterrupted) {
        errorMessage = formatInterruptedMessage(
          { data: [], errors: batchErrors, interrupted: true, interruptMessage },
          autoSaved,
          totalSavedCount
        );
      } else if (successCount === 0 && batchErrors.length > 0) {
        errorMessage = '이번 요청의 모든 URL 처리에 실패했습니다.';
      } else if (batchErrors.length > 0) {
        errorMessage = `일부 URL 처리에 실패했습니다. (성공 ${successCount}건, 실패 ${batchErrors.length}건)`;
      }
    } catch (e) {
      if (isAbortError(e)) {
        wasCancelled = true;
        errorMessage = '크롤링이 취소됐습니다.';
      } else {
        errorMessage = resolveCrawlError(e);
        batchErrors.push({ url: '(크롤링)', error: errorMessage });
      }
    } finally {
      if (wasCancelled) {
        appendUserCancelErrors(urls, batchErrors, processedUrls);
        setErrors(batchErrors);
      }

      if (errorMessage) {
        if (autoSaved && !wasInterrupted) {
          errorMessage = appendAutoSaveNotice(errorMessage, totalSavedCount);
        }
        setError(errorMessage);
        setInfoMessage(null);
      } else if (autoSaved) {
        setError(null);
        setInfoMessage(
          `크롤링이 완료됐습니다. ${totalSavedCount}건이 선택한 폴더에 저장됐습니다.`
        );
      } else {
        setError(null);
        setInfoMessage(null);
      }

      const totalMs =
        crawlStartAtRef.current !== null ? Date.now() - crawlStartAtRef.current : 0;
      if (crawlStartAtRef.current !== null) {
        setLastCrawlDurationMs(totalMs);
      }
      await saveCrawlLog(
        saveDirectoryRef.current,
        logContext,
        urls.length,
        successCount,
        batchErrors,
        totalMs,
        batchTimings
      );
      setLoading(false);
      setProgress(null);
      crawlAbortRef.current = null;
      if (options?.clearUrlInput) {
        setUrlInput('');
      }
      if (options?.clearSearchInput) {
        setSearchInput('');
      }
      if (options?.clearSearchDates) {
        setSearchStartDate('');
        setSearchEndDate('');
      }
    }
  };

  const resolveCrawlError = (e: unknown): string => {
    if (axios.isAxiosError(e) && e.response?.status === 500) {
      const serverError = e.response.data?.error;
      return serverError ? `서버 오류: ${serverError}` : '서버 오류(500)가 발생했습니다.';
    }
    return e instanceof Error ? e.message : '크롤링 중 오류가 발생했습니다.';
  };

  const progressPercent =
    progress && progress.total > 0
      ? Math.min(
          100,
          Math.round(
            ((progress.completed +
              (loading && progress.completed < progress.total
                ? STAGE_WEIGHTS[progress.stage ?? 'text-crawl'] ?? 0.15
                : 0)) /
              progress.total) *
              100
          )
        )
      : 0;

  const totalResultPages = Math.max(1, Math.ceil(savedResults.length / RESULTS_PAGE_SIZE));
  const paginatedResults = savedResults.slice(
    (resultPage - 1) * RESULTS_PAGE_SIZE,
    resultPage * RESULTS_PAGE_SIZE
  );
  const resultStartIndex = (resultPage - 1) * RESULTS_PAGE_SIZE;

  const handleSaveExcel = async () => {
    if (savedResults.length === 0) {
      setError('저장할 데이터가 없습니다. 먼저 크롤링을 실행해 주세요.');
      return;
    }
    if (!saveDirectoryRef.current) {
      setError('저장 폴더를 선택해 주세요.');
      return;
    }
    setSaving(true);
    try {
      const { postsForExcel } = await persistCrimeListAndCaptures(
        saveDirectoryRef.current,
        savedResults,
        {
          communityName: deriveCommunityName(savedResults),
          keyword: lastSearchKeywordRef.current,
        }
      );
      setSavedResults(postsForExcel);
      setError(null);
      setInfoMessage(null);
      window.alert('저장이 완료됐습니다.');
    } catch (e) {
      const message = e instanceof Error ? e.message : '엑셀 생성 중 오류가 발생했습니다.';
      setError(message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="app">
      <header className="app-header">
        <h1>범죄일람표 크롤러</h1>
        <p>
          크롤링은 100건 단위로 진행되며, 각 배치가 끝날 때마다 선택한 폴더의 동일한 결과물 폴더에
          엑셀·캡처가 자동 저장됩니다. 저장 버튼은 화면에 누적된 결과를 새 폴더에 다시 저장할 때
          사용합니다.
        </p>
      </header>

      <main className="app-main">
        <div className="input-card">
          <label htmlFor="url-input">게시글 URL (여러 개는 줄바꿈 또는 쉼표로 구분)</label>
          <textarea
            id="url-input"
            className="url-input"
            placeholder="https://gall.dcinside.com/mgallery/board/view/?id=shyameoho&no=9295"
            value={urlInput}
            onChange={(e) => setUrlInput(e.target.value)}
            rows={4}
            disabled={loading}
          />

          <label htmlFor="search-input">통합검색어 (쉼표·공백으로 OR 검색)</label>
          <input
            id="search-input"
            className="search-input"
            type="text"
            placeholder="예: 사기, 피해 또는 사기 피해 (각 검색어 결과를 합쳐 수집)"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            disabled={loading}
          />

          <label htmlFor="search-start-date">검색 기간 (선택, yyyy-mm-dd)</label>
          <DateRangeInput
            startDate={searchStartDate}
            endDate={searchEndDate}
            onStartDateChange={setSearchStartDate}
            onEndDateChange={setSearchEndDate}
            disabled={loading}
          />
          <p className="field-hint search-hint">
            URL 입력과 검색어 입력은 별도입니다. 검색어를 쉼표(,)나 공백으로 나누면 OR 조건으로 각각
            검색한 뒤 결과 URL을 합칩니다. 기간을 지정하지 않으면 검색어당 최대 100건(최신순)까지
            수집합니다. 기간을 지정하면 검색어당 해당 기간의 결과를 페이지 단위로 모두 수집한 뒤
            100건씩 배치로 크롤링·저장합니다.
          </p>

          <label htmlFor="save-directory">저장 폴더 (캡처·엑셀)</label>
          <div className="save-directory-row">
            <input
              id="save-directory"
              className="save-directory-input"
              type="text"
              readOnly
              placeholder="폴더 선택 버튼으로 지정"
              value={saveDirectoryPath}
            />
            <button
              type="button"
              className="btn secondary"
              onClick={() => void handlePickDirectory()}
              disabled={loading}
            >
              폴더 선택
            </button>
          </div>
          <p className="field-hint">
            폴더 선택 후 저장하면 <code>결과물_YYYYMMDD_HHMM</code> 하위에 엑셀과{' '}
            <code>Screenshot</code> 폴더(캡처 PNG)가 함께 생성됩니다. 엑셀의 캡처 링크는{' '}
            <code>Screenshot</code> 하위 이미지를 가리킵니다.
          </p>

          <div className="button-row">
            <button
              type="button"
              className="btn primary"
              onClick={() => void handleCrawl()}
              disabled={loading || !saveDirectoryPath}
            >
              {loading ? '크롤링·캡처 중…' : 'URL 크롤링'}
            </button>
            <button
              type="button"
              className="btn primary"
              onClick={() => void handleSearchCrawl()}
              disabled={loading || !saveDirectoryPath}
            >
              {loading ? '검색·크롤링·캡처 중…' : '검색어 크롤링'}
            </button>
            <div className="save-cancel-group">
              <button
                type="button"
                className="btn secondary btn-with-spinner"
                onClick={() => void handleSaveExcel()}
                disabled={loading || saving || savedResults.length === 0}
                aria-busy={saving}
              >
                <span className="btn-label">범죄일람표, 캡처화면 저장</span>
                {saving && <span className="btn-spinner" aria-hidden="true" />}
              </button>
              <button
                type="button"
                className="btn cancel-crawl"
                onClick={handleCancelCrawl}
                disabled={!loading}
                aria-label="크롤링 취소"
                title="크롤링 취소"
              >
                ✕
              </button>
            </div>
            <span className="saved-count">현재까지 저장된 링크 개수: {savedResults.length}</span>
          </div>
          {lastCrawlDurationMs !== null && !loading && (
            <div className="crawl-status">
              <span className="crawl-timer-summary">
                수집 시간: {formatDuration(lastCrawlDurationMs)}
              </span>
            </div>
          )}

          {loading && progress && (
            <div className="progress-panel" aria-live="polite">
              <div className="progress-header">
                <span className="progress-label">
                  진행 {Math.min(progress.completed + (loading ? 1 : 0), progress.total)} / {progress.total}
                  <span className="progress-percent"> ({progressPercent}%)</span>
                </span>
                <span className="progress-stats">
                  성공 {progress.successCount} · 실패 {progress.failCount} · 수집 {formatDuration(elapsedMs)}
                </span>
              </div>
              <div className="progress-track">
                <div
                  className="progress-fill"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>
              <p className="progress-url">
                {progress.completed < progress.total ? (
                  <>
                    <span className="progress-stage">{formatStageLabel(progress.stage)}</span>
                    {' · '}
                    {shortenUrl(progress.currentUrl)}
                  </>
                ) : (
                  '완료'
                )}
              </p>
            </div>
          )}

          {infoMessage && <div className="message info">{infoMessage}</div>}
          {error && <div className="message error">{error}</div>}
        </div>

        {savedResults.length > 0 && (
          <section className="result-section">
            <div className="result-section-header">
              <h2>저장된 크롤링 결과 ({savedResults.length}건)</h2>
              {totalResultPages > 1 && (
                <div className="pagination">
                  <button
                    type="button"
                    className="btn pagination-btn"
                    onClick={() => setResultPage((page) => Math.max(1, page - 1))}
                    disabled={resultPage <= 1}
                  >
                    이전
                  </button>
                  <span className="pagination-info">
                    {resultPage} / {totalResultPages}
                  </span>
                  <button
                    type="button"
                    className="btn pagination-btn"
                    onClick={() => setResultPage((page) => Math.min(totalResultPages, page + 1))}
                    disabled={resultPage >= totalResultPages}
                  >
                    다음
                  </button>
                </div>
              )}
            </div>
            <div className="result-list">
              {paginatedResults.map((post, index) => (
                <article key={post.url} className="result-card">
                  <h3>
                    <span className="result-serial">{resultStartIndex + index + 1}.</span> {post.title}
                  </h3>
                  <dl>
                    <div>
                      <dt>닉네임</dt>
                      <dd>{post.nickname}</dd>
                    </div>
                    <div>
                      <dt>게시일자</dt>
                      <dd>{post.postDate}</dd>
                    </div>
                    <div>
                      <dt>조회수 / 댓글</dt>
                      <dd>
                        {post.viewCount} / {post.commentCount}
                      </dd>
                    </div>
                    <div>
                      <dt>캡처파일</dt>
                      <dd className="capture-path">{post.captureFilePath}</dd>
                    </div>
                  </dl>
                  <p className="preview-content">{post.content.slice(0, 300)}…</p>
                </article>
              ))}
            </div>
          </section>
        )}

        {errors.length > 0 && (
          <section className="error-section">
            <h2>실패한 URL</h2>
            <ul>
              {errors.map((item) => (
                <li key={item.url}>
                  <strong>{item.url}</strong>: {item.error}
                </li>
              ))}
            </ul>
          </section>
        )}
      </main>
    </div>
  );
}
