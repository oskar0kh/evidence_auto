import { useEffect, useRef, useState } from 'react';
import { crawlInstagramStream, searchCrawlInstagramStream } from '../api';
import {
  appendBatchToSession,
  createCrawlPersistSession,
  finalizeCrawlPersistSession,
  type CrawlPersistSession,
} from '../export/persistResults';
import { CRAWL_BATCH_SIZE, chunkArray, parseUrls } from './constants';
import {
  appendResultPreviews,
  mergeCrawlProgressEvent,
  type CrawlProgress,
  type SavedResultPreview,
} from './crawlHelpers';
import { isAbortError } from '../../../shared/lib/abort';
import { isNativeFolderPickerSupported, pickNativeDirectory } from '../../../shared/lib/nativeFolderPicker';
import type { CrawlFailureRecord, CrawlProgressEvent } from './types';
import type { InstagramPostData } from '../types';

export function useCrawlOrchestrator() {
  const [urlInput, setUrlInput] = useState('');
  const [searchInput, setSearchInput] = useState('');
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
  const crawlStartAtRef = useRef<number | null>(null);
  const crawlAbortRef = useRef<AbortController | null>(null);
  const savedCountRef = useRef(0);

  useEffect(() => {
    if (!loading) return;
    const timerId = window.setInterval(() => {
      if (crawlStartAtRef.current !== null) {
        setElapsedMs(Date.now() - crawlStartAtRef.current);
      }
    }, 100);
    return () => window.clearInterval(timerId);
  }, [loading]);

  const bumpSavedCount = (delta: number) => {
    savedCountRef.current += delta;
    setSavedCount(savedCountRef.current);
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
      if (e instanceof DOMException && e.name === 'AbortError') return;
      setError(e instanceof Error ? e.message : '폴더를 선택할 수 없습니다.');
    }
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

  const saveRow = async (
    persistSession: CrawlPersistSession | null,
    post: InstagramPostData,
    keyword?: string
  ): Promise<CrawlPersistSession | null> => {
    if (!saveDirectoryRef.current) return persistSession;
    let session = persistSession;
    if (!session) {
      session = await createCrawlPersistSession(saveDirectoryRef.current, { keyword });
    }
    const saved = await appendBatchToSession(session, [post]);
    bumpSavedCount(saved.length);
    const endSerial = savedCountRef.current;
    setResultsPreview((prev) => appendResultPreviews(prev, saved, endSerial - saved.length + 1));
    return session;
  };

  const runCrawl = async (urls: string[], searchQuery?: string) => {
    if (!saveDirectoryRef.current) {
      setError('저장 폴더를 선택해 주세요.');
      return;
    }
    const signal = beginCrawlSession();
    crawlStartAtRef.current = Date.now();
    savedCountRef.current = savedCount;
    setLoading(true);
    setHasStartedCrawl(true);
    setError(null);
    setInfoMessage(null);
    setErrors([]);
    setProgress({ completed: 0, total: urls.length, currentUrl: '', stage: 'fetch', successCount: 0, failCount: 0 });

    let persistSession: CrawlPersistSession | null = null;
    const batchErrors: CrawlFailureRecord[] = [];

    try {
      const batches = chunkArray(urls, CRAWL_BATCH_SIZE);
      for (const batch of batches) {
        await crawlInstagramStream(batch, {
          startSerial: savedCountRef.current + 1,
          searchQuery,
          signal,
          onProgress: (event: CrawlProgressEvent) => {
            setProgress((prev) => mergeCrawlProgressEvent(prev, event));
          },
          onUrlResult: async (post) => {
            persistSession = await saveRow(persistSession, post, searchQuery);
          },
          onUrlError: (err) => {
            batchErrors.push(err);
            setErrors((prev) => [...prev, err]);
          },
        });
      }
      if (persistSession) {
        await finalizeCrawlPersistSession(persistSession);
        setInfoMessage(`저장 완료: ${savedCountRef.current}건`);
      }
      if (batchErrors.length > 0 && savedCountRef.current === 0) {
        setError('모든 URL 처리에 실패했습니다.');
      }
    } catch (e) {
      if (isAbortError(e)) {
        setError('크롤링이 취소됐습니다.');
      } else {
        setError(e instanceof Error ? e.message : '크롤링 중 오류가 발생했습니다.');
      }
      if (persistSession) {
        try {
          await finalizeCrawlPersistSession(persistSession);
        } catch {
          // ignore
        }
      }
    } finally {
      setLoading(false);
      if (crawlStartAtRef.current) {
        setLastCrawlDurationMs(Date.now() - crawlStartAtRef.current);
      }
    }
  };

  const handleCrawl = async () => {
    const urls = parseUrls(urlInput).filter((url) => url.includes('instagram.com'));
    if (urls.length === 0) {
      setError('Instagram 게시물 URL을 입력해 주세요.');
      return;
    }
    await runCrawl(urls);
  };

  const handleSearchCrawl = async () => {
    const query = searchInput.trim();
    if (!query) {
      setError('검색어를 입력해 주세요.');
      return;
    }
    if (!saveDirectoryRef.current) {
      setError('저장 폴더를 선택해 주세요.');
      return;
    }
    const signal = beginCrawlSession();
    crawlStartAtRef.current = Date.now();
    setLoading(true);
    setHasStartedCrawl(true);
    setError(null);
    setInfoMessage(null);
    setErrors([]);
    setProgress({ completed: 0, total: 0, currentUrl: '검색·수집 중…', stage: 'search', successCount: 0, failCount: 0 });

    let persistSession: CrawlPersistSession | null = null;
    const batchErrors: CrawlFailureRecord[] = [];

    try {
      const response = await searchCrawlInstagramStream(query, {
        maxResults: 100,
        startSerial: savedCountRef.current + 1,
        signal,
        onProgress: (event) => setProgress((prev) => mergeCrawlProgressEvent(prev, event)),
        onUrlResult: async (post) => {
          persistSession = await saveRow(persistSession, post, query);
        },
        onUrlError: (err) => {
          batchErrors.push(err);
          setErrors((prev) => [...prev, err]);
        },
      });
      if (persistSession) {
        await finalizeCrawlPersistSession(persistSession);
        setInfoMessage(`저장 완료: ${savedCountRef.current}건`);
      }
      if ((response.attemptedCount ?? 0) === 0 && !isAbortError(null)) {
        setError('검색 결과가 없습니다. 로그인 세션이 필요할 수 있습니다.');
      } else if (batchErrors.length > 0 && savedCountRef.current === 0) {
        setError('검색 결과 처리에 실패했습니다.');
      }
    } catch (e) {
      if (isAbortError(e)) {
        setError('크롤링이 취소됐습니다.');
      } else {
        setError(e instanceof Error ? e.message : '검색 크롤링 중 오류가 발생했습니다.');
      }
      if (persistSession) {
        try {
          await finalizeCrawlPersistSession(persistSession);
        } catch {
          // ignore
        }
      }
    } finally {
      setLoading(false);
      if (crawlStartAtRef.current) {
        setLastCrawlDurationMs(Date.now() - crawlStartAtRef.current);
      }
    }
  };

  return {
    urlInput,
    setUrlInput,
    searchInput,
    setSearchInput,
    saveDirectoryPath,
    handlePickDirectory,
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
    handleCrawl,
    handleSearchCrawl,
    handleCancelCrawl,
  };
}
