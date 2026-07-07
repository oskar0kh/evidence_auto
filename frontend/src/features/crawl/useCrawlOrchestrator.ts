import axios from 'axios';
import { useEffect, useRef, useState } from 'react';
import { crawlDcinsideStream, lookupDcinsideGalleries, searchCrawlDcinsideStream } from '../../platforms/dcinside/api';
import { filterUrlsByGalleryId } from '../search/searchUtils';
import { persistCrimeListAndCaptures, type CrawlPersistSession } from '../export/persistResults';
import { CRAWL_BATCH_SIZE, RESULTS_PAGE_SIZE, chunkArray } from './constants';
import {
  collectCrawlResponse,
  collectProcessedUrls,
  formatInterruptedMessage,
  resolveCrawlMessages,
} from './crawlSession';
import {
  type CrawlLogContext,
  type CrawlProgress,
  deriveCommunityName,
  formatGalleryLabel,
  hasPartialDateRange,
  isValidDateRange,
  mergeSavedResults,
  parseUrls,
  saveBatchResults,
  saveCrawlLog,
} from './crawlHelpers';
import { isAbortError } from '../../shared/lib/abort';
import { isNativeFolderPickerSupported, pickNativeDirectory } from '../../shared/lib/nativeFolderPicker';
import type { UrlTiming } from './types';
import type { GalleryCandidate } from '../search/types';
import type { DcinsidePostData } from '../../platforms/dcinside/types';

function resolveCrawlError(e: unknown): string {
  if (axios.isAxiosError(e) && e.response?.status === 500) {
    const serverError = e.response.data?.error;
    return serverError ? `서버 오류: ${serverError}` : '서버 오류(500)가 발생했습니다.';
  }
  return e instanceof Error ? e.message : '크롤링 중 오류가 발생했습니다.';
}

export function useCrawlOrchestrator() {
  const [urlInput, setUrlInput] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [searchGalleryName, setSearchGalleryName] = useState('');
  const [selectedGallery, setSelectedGallery] = useState<GalleryCandidate | null>(null);
  const [galleryCandidates, setGalleryCandidates] = useState<GalleryCandidate[]>([]);
  const [galleryPickerOpen, setGalleryPickerOpen] = useState(false);
  const [galleryResolving, setGalleryResolving] = useState(false);
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

  const runSearchCrawl = async (options: {
    query: string;
    useDateRange: boolean;
    galleryId?: string;
    galleryLabel?: string;
    useGallerySearch: boolean;
    logContext: CrawlLogContext;
    clearSearchInput?: boolean;
    clearSearchDates?: boolean;
    clearSearchGallery?: boolean;
  }) => {
    const abortSignal = beginCrawlSession();

    crawlStartAtRef.current = Date.now();
    setElapsedMs(0);
    setLoading(true);
    setError(null);
    setInfoMessage(null);
    setErrors([]);
    setProgress({
      completed: 0,
      total: 0,
      currentUrl: options.useGallerySearch
        ? `갤러리 ${options.galleryLabel} 검색·수집 중…`
        : options.useDateRange
          ? '기간 내 검색·수집 중…'
          : '검색·수집 중…',
      stage: 'search',
      successCount: 0,
      failCount: 0,
    });

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

    try {
      const response = await searchCrawlDcinsideStream(
        options.query,
        {
          maxResults: 100,
          startDate: options.useDateRange ? searchStartDate : undefined,
          endDate: options.useDateRange ? searchEndDate : undefined,
          galleryId: options.useGallerySearch ? options.galleryId : undefined,
        },
        savedResults.length + 1,
        (event) => {
          setProgress({
            completed: event.completed,
            total: event.total,
            currentUrl: event.currentUrl,
            stage: event.stage,
            successCount: event.successCount,
            failCount: event.failCount,
          });
        },
        async (post) => {
          if (!saveDirectoryRef.current) {
            return;
          }
          try {
            const saved = await saveBatchResults(
              persistSession,
              [post],
              saveDirectoryRef.current,
              deriveCommunityName([post]),
              lastSearchKeywordRef.current
            );
            persistSession = saved.session;
            setSavedResults((prev) => mergeSavedResults(prev, saved.postsForExcel));
            totalSavedCount += saved.postsForExcel.length;
            autoSaved = true;
          } catch (e) {
            const saveMessage =
              e instanceof Error ? e.message : '자동 저장 중 오류가 발생했습니다.';
            errorMessage = errorMessage
              ? `${errorMessage} (저장 실패: ${saveMessage})`
              : `저장 실패: ${saveMessage}`;
          }
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

      setErrors(batchErrors);

      const attemptedCount = response.attemptedCount ?? successCount + totalFailCount;
      if (!wasInterrupted && attemptedCount === 0) {
        const emptyMessage = options.useGallerySearch
          ? options.useDateRange
            ? `갤러리 ${options.galleryLabel}에서 지정한 기간에 해당하는 검색 결과가 없습니다.`
            : `갤러리 ${options.galleryLabel}에서 검색 결과가 없습니다.`
          : options.useDateRange
            ? '지정한 기간에 해당하는 검색 결과가 없습니다.'
            : '검색 결과가 없습니다.';
        setError(emptyMessage);
      } else if (wasInterrupted) {
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
        batchErrors.push({ url: '(검색·크롤링)', error: errorMessage });
        setErrors(batchErrors);
      }
    } finally {
      if (wasCancelled) {
        for (const err of batchErrors) {
          processedUrls.add(err.url);
        }
        setErrors(batchErrors);
      }

      const messages = resolveCrawlMessages({
        wasCancelled,
        wasInterrupted,
        errorMessage,
        autoSaved,
        totalSavedCount,
        batchErrors,
        processedUrls,
      });
      setError(messages.errorMessage);
      setInfoMessage(messages.infoMessage);

      const totalMs =
        crawlStartAtRef.current !== null ? Date.now() - crawlStartAtRef.current : 0;
      if (crawlStartAtRef.current !== null) {
        setLastCrawlDurationMs(totalMs);
      }
      await saveCrawlLog(
        saveDirectoryRef.current,
        options.logContext,
        successCount + totalFailCount,
        successCount,
        batchErrors,
        totalMs,
        batchTimings
      );
      setLoading(false);
      setProgress(null);
      crawlAbortRef.current = null;
      if (options.clearSearchInput) {
        setSearchInput('');
      }
      if (options.clearSearchDates) {
        setSearchStartDate('');
        setSearchEndDate('');
      }
      if (options.clearSearchGallery) {
        setSearchGalleryName('');
        setSelectedGallery(null);
        setGalleryPickerOpen(false);
        setGalleryCandidates([]);
      }
    }
  };

  const runCrawlForUrls = async (
    urls: string[],
    options?: {
      clearUrlInput?: boolean;
      clearSearchInput?: boolean;
      clearSearchDates?: boolean;
      clearSearchGallery?: boolean;
      skipInit?: boolean;
      logContext?: CrawlLogContext;
      galleryId?: string;
    }
  ) => {
    const crawlUrls = filterUrlsByGalleryId(urls, options?.galleryId);
    if (options?.galleryId?.trim() && crawlUrls.length === 0 && urls.length > 0) {
      setError('선택한 갤러리에 해당하는 URL이 없습니다.');
      setLoading(false);
      setProgress(null);
      return;
    }

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

    const urlBatches = chunkArray(crawlUrls, CRAWL_BATCH_SIZE);
    let nextSerial = savedResults.length + 1;
    let globalCompletedOffset = 0;

    setProgress({
      completed: 0,
      total: crawlUrls.length,
      currentUrl: crawlUrls[0] ?? '',
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
              total: crawlUrls.length,
              currentUrl: event.currentUrl,
              stage: event.stage,
              successCount: successCount + event.successCount,
              failCount: totalFailCount + event.failCount,
            });
          },
          (post) => {
            batchResults = mergeSavedResults(batchResults, [post]);
          },
          abortSignal,
          options?.galleryId
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
      const messages = resolveCrawlMessages({
        wasCancelled,
        wasInterrupted,
        errorMessage,
        autoSaved,
        totalSavedCount,
        batchErrors,
        processedUrls,
        cancelUrls: wasCancelled ? crawlUrls : undefined,
      });
      if (wasCancelled) {
        setErrors(batchErrors);
      }
      setError(messages.errorMessage);
      setInfoMessage(messages.infoMessage);

      const totalMs =
        crawlStartAtRef.current !== null ? Date.now() - crawlStartAtRef.current : 0;
      if (crawlStartAtRef.current !== null) {
        setLastCrawlDurationMs(totalMs);
      }
      await saveCrawlLog(
        saveDirectoryRef.current,
        logContext,
        crawlUrls.length,
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
      if (options?.clearSearchGallery) {
        setSearchGalleryName('');
        setSelectedGallery(null);
        setGalleryPickerOpen(false);
        setGalleryCandidates([]);
      }
    }
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

  const handleGalleryNameChange = (value: string) => {
    setSearchGalleryName(value);
    setSelectedGallery(null);
    setGalleryPickerOpen(false);
    setGalleryCandidates([]);
  };

  const handleGalleryLookup = async () => {
    const galleryName = searchGalleryName.trim();
    if (!galleryName) {
      setError('갤러리명을 입력해 주세요.');
      return;
    }

    setError(null);
    setInfoMessage(null);
    setSelectedGallery(null);
    setGalleryResolving(true);
    try {
      const lookup = await lookupDcinsideGalleries(galleryName);
      if (lookup.galleries.length === 0) {
        setGalleryPickerOpen(false);
        setGalleryCandidates([]);
        setError(`'${galleryName}'에 해당하는 갤러리를 찾을 수 없습니다.`);
        return;
      }
      setGalleryCandidates(lookup.galleries);
      setGalleryPickerOpen(true);
    } catch (e) {
      setError(resolveCrawlError(e));
    } finally {
      setGalleryResolving(false);
    }
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

    const galleryName = searchGalleryName.trim();
    if (galleryName && !selectedGallery) {
      setError('갤러리 찾기 버튼으로 갤러리를 선택해 주세요.');
      return;
    }

    setError(null);
    setInfoMessage(null);
    await executeSearchCrawl(selectedGallery ?? undefined);
  };

  const handleGalleryPickerCancel = () => {
    setGalleryPickerOpen(false);
    setGalleryCandidates([]);
  };

  const handleGallerySelect = (candidate: GalleryCandidate) => {
    setSelectedGallery(candidate);
    setGalleryPickerOpen(false);
  };

  const executeSearchCrawl = async (gallery?: GalleryCandidate) => {
    const query = searchInput.trim();
    const useDateRange = Boolean(searchStartDate && searchEndDate);
    const galleryId = gallery?.id;
    const galleryLabel = gallery ? formatGalleryLabel(gallery) : undefined;
    const useGallerySearch = Boolean(galleryId);

    const inputMode = useGallerySearch
      ? useDateRange
        ? '검색어+기간+갤러리'
        : '검색어+갤러리'
      : useDateRange
        ? '검색어+기간'
        : '검색어';

    lastSearchKeywordRef.current = query;
    await runSearchCrawl({
      query,
      useDateRange,
      galleryId,
      galleryLabel,
      useGallerySearch,
      logContext: {
        keyword: query,
        searchDateRange: useDateRange ? `${searchStartDate}~${searchEndDate}` : undefined,
        galleryName: useGallerySearch ? galleryLabel : undefined,
        inputMode,
      },
      clearSearchInput: true,
      clearSearchDates: true,
      clearSearchGallery: true,
    });
  };

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

  const totalResultPages = Math.max(1, Math.ceil(savedResults.length / RESULTS_PAGE_SIZE));
  const paginatedResults = savedResults.slice(
    (resultPage - 1) * RESULTS_PAGE_SIZE,
    resultPage * RESULTS_PAGE_SIZE
  );
  const resultStartIndex = (resultPage - 1) * RESULTS_PAGE_SIZE;

  return {
    urlInput,
    setUrlInput,
    searchInput,
    setSearchInput,
    searchGalleryName,
    selectedGallery,
    galleryCandidates,
    galleryPickerOpen,
    galleryResolving,
    searchStartDate,
    setSearchStartDate,
    searchEndDate,
    setSearchEndDate,
    saveDirectoryPath,
    loading,
    saving,
    progress,
    error,
    infoMessage,
    savedResults,
    resultPage,
    setResultPage,
    errors,
    elapsedMs,
    lastCrawlDurationMs,
    totalResultPages,
    paginatedResults,
    resultStartIndex,
    handleCancelCrawl,
    handlePickDirectory,
    handleCrawl,
    handleGalleryNameChange,
    handleGalleryLookup,
    handleSearchCrawl,
    handleGalleryPickerCancel,
    handleGallerySelect,
    handleSaveExcel,
  };
}
