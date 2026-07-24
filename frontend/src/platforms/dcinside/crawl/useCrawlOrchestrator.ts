import axios from 'axios';
import { useEffect, useRef, useState } from 'react';
import { crawlDcinsideStream, lookupDcinsideGalleries, searchCrawlDcinsideStream } from '../api';
import { filterUrlsByGalleryId } from '../search/searchUtils';
import {
  finalizeCrawlPersistSession,
  type CrawlPersistSession,
} from '../export/persistResults';
import { formatTimestamp } from '../export/pathUtils';
import { CRAWL_BATCH_SIZE, chunkArray } from './constants';
import { crawlInstagramStream, searchCrawlInstagramStream, cancelInstagramLogin, fetchInstagramSessionStatus, startInstagramLogin } from '../../instagram/api';
import {
  beginInstagramCrawlLogSession,
  saveInstagramBatchResults,
  saveInstagramCrawlLog,
  toInstagramUnifiedPreview,
} from '../../instagram/crawl/crawlHelpers';
import {
  finalizeCrawlPersistSession as finalizeInstagramPersistSession,
  type CrawlPersistSession as InstagramPersistSession,
} from '../../instagram/export/persistResults';
import { INSTAGRAM_COMMUNITY_NAME } from '../../instagram/export/pathUtils';
import {
  type CommunityId,
  classifyUrl,
  getCommunityDefinition,
  isDcinsideUrl,
  isInstagramUrl,
} from '../../../shared/crawl/communities';
import { resolveCommunityDirectory } from '../../../shared/crawl/communityStorage';
import type { InstagramPostData } from '../../instagram/types';
import {
  collectCrawlResponse,
  collectProcessedUrls,
  formatInterruptedMessage,
  buildPartialFailureMessage,
  resolveCrawlMessages,
} from './crawlSession';
import {
  type CrawlLogContext,
  type CrawlProgress,
  beginCrawlLogSession,
  buildLiveCrawlLogSnapshot,
  formatGalleryLabel,
  hasPartialDateRange,
  isValidDateRange,
  mergeCrawlProgressEvent,
  mergeSavedResults,
  parseUrls,
  saveBatchResults,
  saveCrawlLog,
  scheduleCrawlLogFlush,
  appendResultPreviews,
  type CrawlLogSession,
  type SavedResultPreview,
} from './crawlHelpers';
import {
  createCrawlSessionMetrics,
  mergeOperationEvents,
  observeCrawlHealth,
  observeCrawlProgress,
  observeUrlTiming,
  type CrawlSessionMetrics,
} from './crawlSessionMetrics';
import { buildExtraStreamFailures, mergeCrawlFailures, mergeUrlTimings } from './crawlLogExport';
import { isAbortError } from '../../../shared/lib/abort';
import { isNativeFolderPickerSupported, pickNativeDirectory } from '../../../shared/lib/nativeFolderPicker';
import type { CrawlFailureRecord, CrawlHealthEvent, CrawlProgressEvent, UrlTiming } from './types';
import type { GalleryCandidate } from '../search/types';
import type { DcinsidePostData } from '../types';

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
  const [selectedCommunities, setSelectedCommunities] = useState<CommunityId[]>([
    'dcinside',
    'instagram',
  ]);
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
  const [progress, setProgress] = useState<CrawlProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [infoMessage, setInfoMessage] = useState<string | null>(null);
  const [savedCount, setSavedCount] = useState(0);
  const [hasStartedCrawl, setHasStartedCrawl] = useState(false);
  const [resultsPreview, setResultsPreview] = useState<SavedResultPreview[]>([]);
  const [errors, setErrors] = useState<CrawlFailureRecord[]>([]);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [lastCrawlDurationMs, setLastCrawlDurationMs] = useState<number | null>(null);
  const [instagramLoggedIn, setInstagramLoggedIn] = useState(false);
  const [instagramLoginInProgress, setInstagramLoginInProgress] = useState(false);
  const [instagramLoginMessage, setInstagramLoginMessage] = useState<string | null>(null);
  const crawlStartAtRef = useRef<number | null>(null);
  const crawlAbortRef = useRef<AbortController | null>(null);
  const sessionMetricsRef = useRef<CrawlSessionMetrics | null>(null);
  const savedCountRef = useRef(0);
  const dcinsideSavedCountRef = useRef(0);
  const instagramSavedCountRef = useRef(0);
  const crawlStampRef = useRef<string | undefined>(undefined);
  const lastSearchKeywordRef = useRef<string | undefined>(undefined);
  const lastGalleryNameRef = useRef<string | undefined>(undefined);

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
    let cancelled = false;
    const refresh = async () => {
      try {
        const status = await fetchInstagramSessionStatus();
        if (cancelled) return;
        setInstagramLoggedIn(status.loggedIn);
        setInstagramLoginInProgress(status.loginInProgress);
        setInstagramLoginMessage(status.message || null);
      } catch {
        // backend not ready
      }
    };
    void refresh();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!instagramLoginInProgress) return;
    const timerId = window.setInterval(() => {
      void (async () => {
        try {
          const status = await fetchInstagramSessionStatus();
          setInstagramLoggedIn(status.loggedIn);
          setInstagramLoginInProgress(status.loginInProgress);
          setInstagramLoginMessage(status.message || null);
          if (!status.loginInProgress && status.phase === 'SUCCESS') {
            setInfoMessage(status.message);
          }
          if (!status.loginInProgress && status.phase === 'FAILED') {
            setError(status.message);
          }
        } catch (e) {
          setInstagramLoginInProgress(false);
          setError(e instanceof Error ? e.message : '인스타 세션 상태 조회 실패');
        }
      })();
    }, 1500);
    return () => window.clearInterval(timerId);
  }, [instagramLoginInProgress]);

  const handleInstagramLogin = async () => {
    setError(null);
    try {
      const result = await startInstagramLogin();
      setInstagramLoginInProgress(true);
      setInstagramLoggedIn(result.status.loggedIn);
      setInstagramLoginMessage(result.message || result.status.message);
      setInfoMessage(result.message);
    } catch (e) {
      setError(e instanceof Error ? e.message : '인스타 로그인 헬퍼를 시작할 수 없습니다.');
    }
  };

  const handleInstagramLoginCancel = async () => {
    try {
      const result = await cancelInstagramLogin();
      setInstagramLoginInProgress(result.status.loginInProgress);
      setInstagramLoggedIn(result.status.loggedIn);
      setInstagramLoginMessage(result.status.message);
    } catch (e) {
      setError(e instanceof Error ? e.message : '인스타 로그인 취소를 실패했습니다.');
    }
  };

  const syncSavedCountRef = () => {
    savedCountRef.current = savedCount;
  };

  const bumpSavedCount = (delta: number) => {
    if (delta <= 0) {
      return;
    }
    savedCountRef.current += delta;
    setSavedCount(savedCountRef.current);
  };

  const flushSavedCount = () => {
    if (savedCountRef.current !== savedCount) {
      setSavedCount(savedCountRef.current);
    }
  };

  const appendSavedPreviews = (posts: DcinsidePostData[]) => {
    if (posts.length === 0) {
      return;
    }
    const endSerial = savedCountRef.current;
    const startSerial = endSerial - posts.length + 1;
    setResultsPreview((prev) => appendResultPreviews(prev, posts, startSerial));
  };

  const appendInstagramPreviews = (posts: InstagramPostData[]) => {
    if (posts.length === 0) {
      return;
    }
    const endSerial = savedCountRef.current;
    const startSerial = endSerial - posts.length + 1;
    const previews = posts.map((post, index) =>
      toInstagramUnifiedPreview(post, startSerial + index)
    );
    setResultsPreview((prev) => [...previews.reverse(), ...prev].slice(0, 10));
  };

  const toggleCommunity = (id: CommunityId) => {
    setSelectedCommunities((prev) => {
      if (prev.includes(id)) {
        if (prev.length === 1) {
          return prev;
        }
        const next = prev.filter((c) => c !== id);
        if (id === 'dcinside') {
          setSearchGalleryName('');
          setSelectedGallery(null);
          setGalleryPickerOpen(false);
          setGalleryCandidates([]);
        }
        return next;
      }
      return [...prev, id];
    });
  };

  const ensureCommunitiesSelected = (): boolean => {
    if (selectedCommunities.length === 0) {
      setError('크롤링할 커뮤니티를 하나 이상 선택해 주세요.');
      return false;
    }
    return true;
  };

  const resolveCommunitySaveDir = async (id: CommunityId) => {
    if (!saveDirectoryRef.current) {
      throw new Error('저장 폴더가 선택되지 않았습니다.');
    }
    const def = getCommunityDefinition(id);
    return resolveCommunityDirectory(saveDirectoryRef.current, def.folderName);
  };

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

  const applyHealthUpdate = (health: CrawlHealthEvent) => {
    sessionMetricsRef.current && observeCrawlHealth(sessionMetricsRef.current, health);
    setProgress((prev) => (prev ? { ...prev, health } : prev));
  };

  const applyProgressUpdate = (event: CrawlProgressEvent) => {
    sessionMetricsRef.current && observeCrawlProgress(sessionMetricsRef.current, event);
    setProgress((prev) => mergeCrawlProgressEvent(prev, event));
  };

  const applyUrlTimingUpdate = (timing: UrlTiming) => {
    sessionMetricsRef.current && observeUrlTiming(sessionMetricsRef.current, timing);
  };

  const runSearchCrawl = async (options: {
    query: string;
    useDateRange: boolean;
    galleryId?: string;
    galleryLabel?: string;
    galleryName?: string;
    useGallerySearch: boolean;
    logContext: CrawlLogContext;
    clearSearchInput?: boolean;
    clearSearchDates?: boolean;
    clearSearchGallery?: boolean;
    communitySaveDirectory?: FileSystemDirectoryHandle;
    sharedStamp?: string;
    progressLabelPrefix?: string;
    skipInit?: boolean;
    isLastCommunity?: boolean;
  }) => {
    const abortSignal = options.skipInit
      ? (crawlAbortRef.current?.signal ?? beginCrawlSession())
      : beginCrawlSession();
    if (!options.skipInit) {
      crawlStartAtRef.current = Date.now();
      sessionMetricsRef.current = createCrawlSessionMetrics(crawlStartAtRef.current);

      setElapsedMs(0);
      setHasStartedCrawl(true);
      setLoading(true);
      setError(null);
      setInfoMessage(null);
      setErrors([]);
    }
    setProgress({
      completed: 0,
      total: 0,
      currentUrl: options.progressLabelPrefix
        ? `[${options.progressLabelPrefix}] 검색·수집 중…`
        : options.useGallerySearch
          ? `갤러리 ${options.galleryLabel} 검색·수집 중…`
          : options.useDateRange
            ? '기간 내 검색·수집 중…'
            : '검색·수집 중…',
      stage: 'search',
      successCount: 0,
      failCount: 0,
    });

    const batchErrors: CrawlFailureRecord[] = [];
    let batchTimings: UrlTiming[] = [];
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
    let crawlLogSession: CrawlLogSession | null = null;
    syncSavedCountRef();

    if (saveDirectoryRef.current && !options.skipInit) {
      crawlLogSession = await beginCrawlLogSession(
        saveDirectoryRef.current,
        options.logContext
      );
    }

    const flushCrawlLog = () => {
      scheduleCrawlLogFlush(
        crawlLogSession,
        buildLiveCrawlLogSnapshot({
          successCount,
          totalFailCount,
          batchErrors,
          batchTimings,
          crawlStartAtMs: crawlStartAtRef.current,
          sessionMetrics: sessionMetricsRef.current,
          interruptMessage,
          savedCount: totalSavedCount,
        })
      );
    };

    const onStreamUrlTiming = (timing: UrlTiming) => {
      applyUrlTimingUpdate(timing);
      batchTimings = mergeUrlTimings(batchTimings, [timing]);
      flushCrawlLog();
    };

    const onStreamUrlError = (error: CrawlFailureRecord) => {
      batchErrors.push(error);
      flushCrawlLog();
    };

    try {
      const communityDir =
        options.communitySaveDirectory ??
        (saveDirectoryRef.current ? await resolveCommunitySaveDir('dcinside') : null);

      const response = await searchCrawlDcinsideStream(
        options.query,
        {
          maxResults: 100,
          startDate: options.useDateRange ? searchStartDate : undefined,
          endDate: options.useDateRange ? searchEndDate : undefined,
          galleryId: options.useGallerySearch ? options.galleryId : undefined,
        },
        dcinsideSavedCountRef.current + 1,
        (event) => {
          applyProgressUpdate({
            ...event,
            currentUrl: options.progressLabelPrefix
              ? `[${options.progressLabelPrefix}] ${event.currentUrl}`
              : event.currentUrl,
          });
        },
        async (post) => {
          if (!communityDir) {
            return;
          }
          try {
            const saved = await saveBatchResults(
              persistSession,
              [post],
              communityDir,
              {
                keyword: lastSearchKeywordRef.current,
                galleryName: options.useGallerySearch ? options.galleryName : undefined,
                stamp: options.sharedStamp ?? crawlStampRef.current,
                ensureLogDirectory: false,
              }
            );
            persistSession = saved.session;
            bumpSavedCount(saved.postsForExcel.length);
            dcinsideSavedCountRef.current += saved.postsForExcel.length;
            appendSavedPreviews(saved.postsForExcel);
            totalSavedCount += saved.postsForExcel.length;
            autoSaved = true;
            flushCrawlLog();
          } catch (e) {
            const saveMessage =
              e instanceof Error ? e.message : '자동 저장 중 오류가 발생했습니다.';
            errorMessage = errorMessage
              ? `${errorMessage} (저장 실패: ${saveMessage})`
              : `저장 실패: ${saveMessage}`;
          }
        },
        abortSignal,
        applyHealthUpdate,
        onStreamUrlTiming,
        onStreamUrlError
      );

      if (sessionMetricsRef.current) {
        mergeOperationEvents(sessionMetricsRef.current, response.operationEvents ?? []);
      }

      const batchSuccess = collectCrawlResponse(response, batchErrors, batchTimings);
      batchTimings = mergeUrlTimings(batchTimings, response.timings ?? []);
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
        errorMessage = buildPartialFailureMessage(successCount, batchErrors.length);
      }
    } catch (e) {
      if (isAbortError(e)) {
        wasCancelled = true;
        errorMessage = '크롤링이 취소됐습니다.';
      } else {
        errorMessage = resolveCrawlError(e);
        batchErrors.push({ url: '(검색·크롤링)', error: errorMessage, stage: 'session' });
        setErrors(batchErrors);
      }
    } finally {
      flushSavedCount();
      if (persistSession) {
        try {
          await finalizeCrawlPersistSession(persistSession);
        } catch (e) {
          const flushMessage =
            e instanceof Error ? e.message : '저장 마무리 중 오류가 발생했습니다.';
          errorMessage = errorMessage
            ? `${errorMessage} (저장 마무리 실패: ${flushMessage})`
            : `저장 마무리 실패: ${flushMessage}`;
        }
      }
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
        successCount,
        batchErrors,
        processedUrls,
      });
      const extraStreamFailures = buildExtraStreamFailures(batchErrors, interruptMessage);
      const mergedErrors = mergeCrawlFailures(batchErrors, batchTimings, extraStreamFailures);

      if (options.isLastCommunity !== false) {
        setError(messages.errorMessage);
        setInfoMessage(messages.infoMessage);
        setErrors(mergedErrors);
      } else {
        setErrors((prev) => [...prev, ...mergedErrors]);
        if (messages.infoMessage) {
          setInfoMessage((prev) =>
            prev ? `${prev} · ${messages.infoMessage}` : messages.infoMessage
          );
        }
      }

      const totalMs =
        crawlStartAtRef.current !== null ? Date.now() - crawlStartAtRef.current : 0;
      if (options.isLastCommunity !== false && crawlStartAtRef.current !== null) {
        setLastCrawlDurationMs(totalMs);
      }
      if (options.isLastCommunity !== false) {
        await saveCrawlLog(
          saveDirectoryRef.current,
          options.logContext,
          successCount + totalFailCount,
          successCount,
          batchErrors,
          totalMs,
          batchTimings,
          extraStreamFailures,
          sessionMetricsRef.current,
          crawlLogSession?.handle ?? null
        );
      }
      sessionMetricsRef.current = null;
      if (options.isLastCommunity !== false) {
        setLoading(false);
        setProgress(null);
        crawlAbortRef.current = null;
      }
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

  const runInstagramSearchCrawl = async (options: {
    query: string;
    sharedStamp?: string;
    skipInit?: boolean;
    isLastCommunity?: boolean;
    logContext?: CrawlLogContext;
  }) => {
    const abortSignal = crawlAbortRef.current?.signal ?? beginCrawlSession();
    if (!options.skipInit) {
      crawlStartAtRef.current = Date.now();
      setElapsedMs(0);
      setHasStartedCrawl(true);
      setLoading(true);
      setError(null);
      setInfoMessage(null);
      setErrors([]);
    }

    setProgress({
      completed: 0,
      total: 0,
      currentUrl: `[${INSTAGRAM_COMMUNITY_NAME}] 검색·수집 중…`,
      stage: 'search',
      successCount: 0,
      failCount: 0,
    });

    let persistSession: InstagramPersistSession | null = null;
    const batchErrors: CrawlFailureRecord[] = [];
    let batchTimings: UrlTiming[] = [];
    let successCount = 0;
    let totalFailCount = 0;
    let totalSavedCount = 0;
    let wasCancelled = false;
    const logContext: CrawlLogContext = options.logContext ?? {
      keyword: options.query,
      galleryName: INSTAGRAM_COMMUNITY_NAME,
      inputMode: '검색어',
    };
    let crawlLogSession: CrawlLogSession | null = null;

    if (saveDirectoryRef.current && !options.skipInit) {
      crawlLogSession = await beginInstagramCrawlLogSession(saveDirectoryRef.current, logContext);
    }

    const flushCrawlLog = () => {
      scheduleCrawlLogFlush(
        crawlLogSession,
        buildLiveCrawlLogSnapshot({
          successCount,
          totalFailCount,
          batchErrors,
          batchTimings,
          crawlStartAtMs: crawlStartAtRef.current,
          savedCount: successCount,
        })
      );
    };

    try {
      const communityDir = saveDirectoryRef.current
        ? await resolveCommunitySaveDir('instagram')
        : null;

      const response = await searchCrawlInstagramStream(options.query, {
        maxResults: 100,
        startSerial: instagramSavedCountRef.current + 1,
        signal: abortSignal,
        onProgress: (event) => {
          setProgress((prev) =>
            mergeCrawlProgressEvent(prev, {
              ...event,
              currentUrl: `[${INSTAGRAM_COMMUNITY_NAME}] ${event.currentUrl}`,
            })
          );
        },
        onUrlResult: async (post) => {
          if (!communityDir) return;
          const saved = await saveInstagramBatchResults(persistSession, [post], communityDir, {
            keyword: options.query,
            stamp: options.sharedStamp ?? crawlStampRef.current,
            ensureLogDirectory: false,
          });
          persistSession = saved.session;
          bumpSavedCount(saved.postsForExcel.length);
          instagramSavedCountRef.current += saved.postsForExcel.length;
          appendInstagramPreviews(saved.postsForExcel);
          totalSavedCount += saved.postsForExcel.length;
          flushCrawlLog();
        },
        onUrlError: (err) => {
          batchErrors.push(err);
          setErrors((prev) => [...prev, err]);
          flushCrawlLog();
        },
        onUrlTiming: (timing) => {
          batchTimings = mergeUrlTimings(batchTimings, [timing]);
          flushCrawlLog();
        },
      });

      batchTimings = mergeUrlTimings(batchTimings, response.timings ?? []);
      successCount += response.successCount ?? response.data.length;
      totalFailCount += response.failCount ?? response.errors.length;
      for (const err of response.errors) {
        if (!batchErrors.some((e) => e.url === err.url && e.error === err.error)) {
          batchErrors.push(err);
        }
      }
    } catch (e) {
      if (isAbortError(e)) {
        wasCancelled = true;
      } else {
        const message = e instanceof Error ? e.message : '인스타그램 검색 크롤링 실패';
        batchErrors.push({ url: '(인스타그램 검색)', error: message, stage: 'session' });
        setErrors((prev) => [...prev, { url: '(인스타그램 검색)', error: message, stage: 'session' }]);
      }
    } finally {
      if (persistSession) {
        try {
          await finalizeInstagramPersistSession(persistSession);
        } catch {
          // ignore
        }
      }
      if (wasCancelled) {
        setError('크롤링이 취소됐습니다.');
      } else if (totalSavedCount > 0) {
        setInfoMessage((prev) =>
          prev
            ? `${prev} · 인스타그램 ${totalSavedCount}건 저장`
            : `인스타그램 ${totalSavedCount}건 저장 완료`
        );
      }
      const totalMs =
        crawlStartAtRef.current !== null ? Date.now() - crawlStartAtRef.current : 0;
      if (options.isLastCommunity !== false) {
        await saveInstagramCrawlLog(
          saveDirectoryRef.current,
          logContext,
          successCount + totalFailCount,
          successCount,
          batchErrors,
          totalMs,
          batchTimings,
          buildExtraStreamFailures(batchErrors),
          null,
          crawlLogSession?.handle ?? null
        );
        setLoading(false);
        setProgress(null);
        crawlAbortRef.current = null;
        if (crawlStartAtRef.current !== null) {
          setLastCrawlDurationMs(totalMs);
        }
      }
    }
  };

  const runInstagramCrawlForUrls = async (
    urls: string[],
    options?: {
      skipInit?: boolean;
      sharedStamp?: string;
      isLastCommunity?: boolean;
      clearUrlInput?: boolean;
      logContext?: CrawlLogContext;
    }
  ) => {
    if (!options?.skipInit) {
      crawlStartAtRef.current = Date.now();
      setElapsedMs(0);
      setHasStartedCrawl(true);
      setLoading(true);
      setError(null);
      setInfoMessage(null);
      setErrors([]);
    }

    const abortSignal = crawlAbortRef.current?.signal ?? beginCrawlSession();
    let persistSession: InstagramPersistSession | null = null;
    const batchErrors: CrawlFailureRecord[] = [];
    let batchTimings: UrlTiming[] = [];
    let successCount = 0;
    let totalFailCount = 0;
    let totalSavedCount = 0;
    const logContext: CrawlLogContext = options?.logContext ?? {
      galleryName: INSTAGRAM_COMMUNITY_NAME,
      inputMode: 'URL 직접입력',
    };
    let crawlLogSession: CrawlLogSession | null = null;

    if (saveDirectoryRef.current && !options?.skipInit) {
      crawlLogSession = await beginInstagramCrawlLogSession(saveDirectoryRef.current, logContext);
    }

    const flushCrawlLog = () => {
      scheduleCrawlLogFlush(
        crawlLogSession,
        buildLiveCrawlLogSnapshot({
          successCount,
          totalFailCount,
          batchErrors,
          batchTimings,
          crawlStartAtMs: crawlStartAtRef.current,
          plannedAttemptedCount: urls.length,
          savedCount: successCount,
        })
      );
    };

    setProgress({
      completed: 0,
      total: urls.length,
      currentUrl: `[${INSTAGRAM_COMMUNITY_NAME}] ${urls[0] ?? ''}`,
      stage: 'fetch',
      successCount: 0,
      failCount: 0,
    });

    try {
      const communityDir = saveDirectoryRef.current
        ? await resolveCommunitySaveDir('instagram')
        : null;
      const batches = chunkArray(urls, CRAWL_BATCH_SIZE);

      for (const batch of batches) {
        const response = await crawlInstagramStream(batch, {
          startSerial: instagramSavedCountRef.current + 1,
          signal: abortSignal,
          onProgress: (event) => {
            setProgress((prev) =>
              mergeCrawlProgressEvent(prev, {
                ...event,
                currentUrl: `[${INSTAGRAM_COMMUNITY_NAME}] ${event.currentUrl}`,
              })
            );
          },
          onUrlResult: async (post) => {
            if (!communityDir) return;
            const saved = await saveInstagramBatchResults(persistSession, [post], communityDir, {
              stamp: options?.sharedStamp ?? crawlStampRef.current,
              ensureLogDirectory: false,
            });
            persistSession = saved.session;
            bumpSavedCount(saved.postsForExcel.length);
            instagramSavedCountRef.current += saved.postsForExcel.length;
            appendInstagramPreviews(saved.postsForExcel);
            totalSavedCount += saved.postsForExcel.length;
            flushCrawlLog();
          },
          onUrlError: (err) => {
            batchErrors.push(err);
            setErrors((prev) => [...prev, err]);
            flushCrawlLog();
          },
          onUrlTiming: (timing) => {
            batchTimings = mergeUrlTimings(batchTimings, [timing]);
            flushCrawlLog();
          },
        });

        batchTimings = mergeUrlTimings(batchTimings, response.timings ?? []);
        successCount += response.successCount ?? response.data.length;
        totalFailCount += response.failCount ?? response.errors.length;
        for (const err of response.errors) {
          if (!batchErrors.some((e) => e.url === err.url && e.error === err.error)) {
            batchErrors.push(err);
          }
        }
      }

      if (batchErrors.length > 0 && totalSavedCount === 0) {
        setError('인스타그램 URL 처리에 모두 실패했습니다.');
      } else if (totalSavedCount > 0) {
        setInfoMessage((prev) =>
          prev
            ? `${prev} · 인스타그램 ${totalSavedCount}건 저장`
            : `인스타그램 ${totalSavedCount}건 저장 완료`
        );
      }
    } catch (e) {
      if (isAbortError(e)) {
        setError('크롤링이 취소됐습니다.');
      } else {
        setError(e instanceof Error ? e.message : '인스타그램 크롤링 중 오류가 발생했습니다.');
      }
    } finally {
      if (persistSession) {
        try {
          await finalizeInstagramPersistSession(persistSession);
        } catch {
          // ignore
        }
      }
      const totalMs =
        crawlStartAtRef.current !== null ? Date.now() - crawlStartAtRef.current : 0;
      if (options?.isLastCommunity !== false) {
        await saveInstagramCrawlLog(
          saveDirectoryRef.current,
          logContext,
          Math.max(urls.length, successCount + totalFailCount),
          successCount,
          batchErrors,
          totalMs,
          batchTimings,
          buildExtraStreamFailures(batchErrors),
          null,
          crawlLogSession?.handle ?? null
        );
        setLoading(false);
        setProgress(null);
        crawlAbortRef.current = null;
        if (crawlStartAtRef.current !== null) {
          setLastCrawlDurationMs(totalMs);
        }
      }
      if (options?.clearUrlInput) {
        setUrlInput('');
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
      communitySaveDirectory?: FileSystemDirectoryHandle;
      sharedStamp?: string;
      progressLabelPrefix?: string;
      isLastCommunity?: boolean;
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
      sessionMetricsRef.current = createCrawlSessionMetrics(crawlStartAtRef.current);
      setElapsedMs(0);
      setHasStartedCrawl(true);
      setLoading(true);
      setError(null);
      setInfoMessage(null);
      setErrors([]);
    } else if (!sessionMetricsRef.current) {
      const startedAt = crawlStartAtRef.current ?? Date.now();
      sessionMetricsRef.current = createCrawlSessionMetrics(startedAt);
    }

    const abortSignal = options?.skipInit
      ? (crawlAbortRef.current?.signal ?? beginCrawlSession())
      : beginCrawlSession();

    const batchErrors: CrawlFailureRecord[] = [];
    let batchTimings: UrlTiming[] = [];
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
    let nextSerial = dcinsideSavedCountRef.current + 1;
    let globalCompletedOffset = 0;
    syncSavedCountRef();

    let crawlLogSession: CrawlLogSession | null = null;
    if (saveDirectoryRef.current) {
      crawlLogSession = await beginCrawlLogSession(saveDirectoryRef.current, logContext);
    }

    const flushCrawlLog = () => {
      scheduleCrawlLogFlush(
        crawlLogSession,
        buildLiveCrawlLogSnapshot({
          successCount,
          totalFailCount,
          batchErrors,
          batchTimings,
          crawlStartAtMs: crawlStartAtRef.current,
          sessionMetrics: sessionMetricsRef.current,
          plannedAttemptedCount: crawlUrls.length,
          interruptMessage,
          savedCount: totalSavedCount,
        })
      );
    };

    const onStreamUrlTiming = (timing: UrlTiming) => {
      applyUrlTimingUpdate(timing);
      batchTimings = mergeUrlTimings(batchTimings, [timing]);
      flushCrawlLog();
    };

    const onStreamUrlError = (error: CrawlFailureRecord) => {
      batchErrors.push(error);
      flushCrawlLog();
    };

    setProgress({
      completed: 0,
      total: crawlUrls.length,
      currentUrl: options?.progressLabelPrefix
        ? `[${options.progressLabelPrefix}] ${crawlUrls[0] ?? ''}`
        : crawlUrls[0] ?? '',
      stage: 'text-crawl',
      successCount: 0,
      failCount: 0,
    });

    const communityDir =
      options?.communitySaveDirectory ??
      (saveDirectoryRef.current
        ? await resolveCommunitySaveDir('dcinside')
        : null);

    try {
      for (let batchIndex = 0; batchIndex < urlBatches.length; batchIndex++) {
        const batchUrls = urlBatches[batchIndex];
        let batchResults: DcinsidePostData[] = [];

        const response = await crawlDcinsideStream(
          batchUrls,
          nextSerial,
          (event) => {
            setProgress((prev) => ({
              ...mergeCrawlProgressEvent(prev, event),
              completed: globalCompletedOffset + event.completed,
              total: crawlUrls.length,
              successCount: successCount + event.successCount,
              failCount: totalFailCount + event.failCount,
              currentUrl: options?.progressLabelPrefix
                ? `[${options.progressLabelPrefix}] ${event.currentUrl}`
                : event.currentUrl,
            }));
          },
          (post) => {
            batchResults = mergeSavedResults(batchResults, [post]);
          },
          abortSignal,
          options?.galleryId,
          applyHealthUpdate,
          onStreamUrlTiming,
          onStreamUrlError
        );

        if (sessionMetricsRef.current) {
        mergeOperationEvents(sessionMetricsRef.current, response.operationEvents ?? []);
      }

        const batchSuccess = collectCrawlResponse(response, batchErrors, batchTimings);
        batchTimings = mergeUrlTimings(batchTimings, response.timings ?? []);
        collectProcessedUrls(response, processedUrls);
        successCount += batchSuccess;
        totalFailCount += response.failCount ?? response.errors.length;
        wasInterrupted = Boolean(response.interrupted);
        if (wasInterrupted) {
          wasCancelled = true;
        }
        interruptMessage = response.interruptMessage;

        if (batchResults.length > 0 && communityDir) {
          try {
            const saved = await saveBatchResults(
              persistSession,
              batchResults,
              communityDir,
              {
                keyword: lastSearchKeywordRef.current,
                galleryName: lastGalleryNameRef.current,
                stamp: options?.sharedStamp ?? crawlStampRef.current,
                ensureLogDirectory: false,
              }
            );
            persistSession = saved.session;
            bumpSavedCount(saved.postsForExcel.length);
            dcinsideSavedCountRef.current += saved.postsForExcel.length;
            appendSavedPreviews(saved.postsForExcel);
            totalSavedCount += saved.postsForExcel.length;
            autoSaved = true;
            flushCrawlLog();
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
        flushCrawlLog();

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
        errorMessage = buildPartialFailureMessage(successCount, batchErrors.length);
      }
    } catch (e) {
      if (isAbortError(e)) {
        wasCancelled = true;
        errorMessage = '크롤링이 취소됐습니다.';
      } else {
        errorMessage = resolveCrawlError(e);
        batchErrors.push({ url: '(크롤링)', error: errorMessage, stage: 'session' });
      }
    } finally {
      flushSavedCount();
      if (persistSession) {
        try {
          await finalizeCrawlPersistSession(persistSession);
        } catch (e) {
          const flushMessage =
            e instanceof Error ? e.message : '저장 마무리 중 오류가 발생했습니다.';
          errorMessage = errorMessage
            ? `${errorMessage} (저장 마무리 실패: ${flushMessage})`
            : `저장 마무리 실패: ${flushMessage}`;
        }
      }
      const messages = resolveCrawlMessages({
        wasCancelled,
        wasInterrupted,
        errorMessage,
        autoSaved,
        totalSavedCount,
        successCount,
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
      const extraStreamFailures = buildExtraStreamFailures(batchErrors, interruptMessage);
      const mergedErrors = mergeCrawlFailures(batchErrors, batchTimings, extraStreamFailures);
      setErrors(mergedErrors);
      await saveCrawlLog(
        saveDirectoryRef.current,
        logContext,
        crawlUrls.length,
        successCount,
        batchErrors,
        totalMs,
        batchTimings,
        extraStreamFailures,
        sessionMetricsRef.current,
        crawlLogSession?.handle ?? null
      );
      sessionMetricsRef.current = null;
      if (options?.isLastCommunity !== false) {
        setLoading(false);
        setProgress(null);
        crawlAbortRef.current = null;
      }
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
      setError('게시글 URL을 입력해 주세요.');
      return;
    }
    if (!ensureSaveDirectorySelected() || !ensureCommunitiesSelected()) {
      return;
    }

    const dcUrls = urls.filter(isDcinsideUrl);
    const igUrls = urls.filter(isInstagramUrl);
    const unknownUrls = urls.filter((url) => classifyUrl(url) === null);

    const runDc = selectedCommunities.includes('dcinside') && dcUrls.length > 0;
    const runIg = selectedCommunities.includes('instagram') && igUrls.length > 0;

    if (!runDc && !runIg) {
      if (unknownUrls.length > 0) {
        setError('지원하지 않는 URL입니다. 디시인사이드 또는 인스타그램 게시물 URL을 입력해 주세요.');
      } else if (selectedCommunities.includes('dcinside') && !selectedCommunities.includes('instagram')) {
        setError('선택한 커뮤니티(디시인사이드)에 해당하는 URL이 없습니다.');
      } else if (selectedCommunities.includes('instagram') && !selectedCommunities.includes('dcinside')) {
        setError('선택한 커뮤니티(인스타그램)에 해당하는 URL이 없습니다.');
      } else {
        setError('선택한 커뮤니티에 해당하는 URL이 없습니다.');
      }
      return;
    }

    crawlStampRef.current = formatTimestamp();
    lastSearchKeywordRef.current = undefined;
    lastGalleryNameRef.current = undefined;

    const communitiesToRun: CommunityId[] = [];
    if (runDc) communitiesToRun.push('dcinside');
    if (runIg) communitiesToRun.push('instagram');

    beginCrawlSession();
    crawlStartAtRef.current = Date.now();
    setHasStartedCrawl(true);
    setLoading(true);
    setError(null);
    setInfoMessage(null);
    setErrors([]);

    if (unknownUrls.length > 0) {
      const unknownErrors = unknownUrls.map((url) => ({
        url,
        error: '지원하지 않는 URL 형식입니다.',
      }));
      setErrors(unknownErrors);
    }

    for (let i = 0; i < communitiesToRun.length; i++) {
      const id = communitiesToRun[i];
      const isLast = i === communitiesToRun.length - 1;
      if (id === 'dcinside') {
        await runCrawlForUrls(dcUrls, {
          skipInit: true,
          sharedStamp: crawlStampRef.current,
          progressLabelPrefix: '디시인사이드',
          isLastCommunity: isLast,
          clearUrlInput: isLast,
          logContext: { inputMode: 'URL 직접입력' },
        });
      } else {
        await runInstagramCrawlForUrls(igUrls, {
          skipInit: true,
          sharedStamp: crawlStampRef.current,
          isLastCommunity: isLast,
          clearUrlInput: isLast,
          logContext: {
            galleryName: INSTAGRAM_COMMUNITY_NAME,
            inputMode: 'URL 직접입력',
          },
        });
      }
    }
  };

  const handleGalleryNameChange = (value: string) => {
    setSearchGalleryName(value);
    setSelectedGallery(null);
    setGalleryPickerOpen(false);
    setGalleryCandidates([]);
  };

  const handleGalleryClear = () => {
    setSearchGalleryName('');
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
    if (!ensureSaveDirectorySelected() || !ensureCommunitiesSelected()) {
      return;
    }

    const galleryName = searchGalleryName.trim();
    if (selectedCommunities.includes('dcinside') && galleryName && !selectedGallery) {
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
    const galleryName = gallery?.name;
    const useGallerySearch = Boolean(galleryId);

    const inputMode = useGallerySearch
      ? useDateRange
        ? '검색어+기간+갤러리'
        : '검색어+갤러리'
      : useDateRange
        ? '검색어+기간'
        : '검색어';

    lastSearchKeywordRef.current = query;
    lastGalleryNameRef.current = useGallerySearch ? galleryName : undefined;
    crawlStampRef.current = formatTimestamp();

    const searchCommunities = selectedCommunities.filter(
      (id) => id === 'instagram' || id === 'dcinside'
    );

    for (let i = 0; i < searchCommunities.length; i++) {
      const id = searchCommunities[i];
      const isLast = i === searchCommunities.length - 1;
      if (id === 'dcinside') {
        await runSearchCrawl({
          query,
          useDateRange,
          galleryId,
          galleryLabel,
          galleryName,
          useGallerySearch,
          logContext: {
            keyword: query,
            searchDateRange: useDateRange ? `${searchStartDate}~${searchEndDate}` : undefined,
            galleryName: useGallerySearch ? galleryLabel : undefined,
            inputMode,
          },
          clearSearchInput: isLast,
          clearSearchDates: isLast,
          clearSearchGallery: isLast,
          sharedStamp: crawlStampRef.current,
          progressLabelPrefix: '디시인사이드',
          skipInit: i > 0,
          isLastCommunity: isLast,
        });
      } else {
        await runInstagramSearchCrawl({
          query,
          sharedStamp: crawlStampRef.current,
          skipInit: i > 0,
          isLastCommunity: isLast,
          logContext: {
            keyword: query,
            searchDateRange: useDateRange ? `${searchStartDate}~${searchEndDate}` : undefined,
            galleryName: INSTAGRAM_COMMUNITY_NAME,
            inputMode: useDateRange ? '검색어+기간' : '검색어',
          },
        });
      }
    }
  };

  return {
    urlInput,
    setUrlInput,
    searchInput,
    setSearchInput,
    selectedCommunities,
    toggleCommunity,
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
    progress,
    error,
    infoMessage,
    savedCount,
    hasStartedCrawl,
    resultsPreview,
    errors,
    elapsedMs,
    lastCrawlDurationMs,
    handleCancelCrawl,
    handlePickDirectory,
    handleCrawl,
    handleGalleryNameChange,
    handleGalleryClear,
    handleGalleryLookup,
    handleSearchCrawl,
    handleGalleryPickerCancel,
    handleGallerySelect,
    instagramLoggedIn,
    instagramLoginInProgress,
    instagramLoginMessage,
    handleInstagramLogin,
    handleInstagramLoginCancel,
  };
}
