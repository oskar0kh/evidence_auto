import axios from 'axios';
import { useEffect, useRef, useState } from 'react';
import { crawlDcinside, searchDcinside } from './api';
import { saveCapturesToDirectory } from './captureFiles';
import { exportCrimeListExcel } from './excelExport';
import { isNativeFolderPickerSupported, pickNativeDirectory } from './nativeFolderPicker';
import { getCaptureFilename } from './pathUtils';
import type { DcinsidePostData } from './types';
import './App.css';

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

interface CrawlProgress {
  completed: number;
  total: number;
  currentUrl: string;
  successCount: number;
  failCount: number;
}

export default function App() {
  const [urlInput, setUrlInput] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const saveDirectoryRef = useRef<FileSystemDirectoryHandle | null>(null);
  const [saveDirectoryPath, setSaveDirectoryPath] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [progress, setProgress] = useState<CrawlProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [savedResults, setSavedResults] = useState<DcinsidePostData[]>([]);
  const [errors, setErrors] = useState<{ url: string; error: string }[]>([]);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [lastCrawlDurationMs, setLastCrawlDurationMs] = useState<number | null>(null);
  const crawlStartAtRef = useRef<number | null>(null);

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

    await runCrawlForUrls(urls, { clearUrlInput: true });
  };

  const handleSearchCrawl = async () => {
    const query = searchInput.trim();
    if (!query) {
      setError('검색어를 입력해 주세요.');
      return;
    }
    if (!ensureSaveDirectorySelected()) {
      return;
    }

    crawlStartAtRef.current = Date.now();
    setElapsedMs(0);
    setLoading(true);
    setError(null);
    setErrors([]);
    setProgress({
      completed: 0,
      total: 1,
      currentUrl: '디시 통합검색 중…',
      successCount: 0,
      failCount: 0,
    });

    try {
      const searchResult = await searchDcinside(query, 100);
      if (searchResult.urls.length === 0) {
        setError('검색 결과가 없습니다.');
        setLoading(false);
        setProgress(null);
        return;
      }

      await runCrawlForUrls(searchResult.urls, { clearSearchInput: true, skipInit: true });
    } catch (e) {
      setError(resolveCrawlError(e));
      if (crawlStartAtRef.current !== null) {
        setLastCrawlDurationMs(Date.now() - crawlStartAtRef.current);
      }
      setLoading(false);
      setProgress(null);
    }
  };

  const runCrawlForUrls = async (
    urls: string[],
    options?: { clearUrlInput?: boolean; clearSearchInput?: boolean; skipInit?: boolean }
  ) => {
    if (!options?.skipInit) {
      crawlStartAtRef.current = Date.now();
      setElapsedMs(0);
      setLoading(true);
      setError(null);
      setErrors([]);
    }

    const batchErrors: { url: string; error: string }[] = [];
    let successCount = 0;

    setProgress({
      completed: 0,
      total: urls.length,
      currentUrl: urls[0],
      successCount: 0,
      failCount: 0,
    });

    try {
      for (let i = 0; i < urls.length; i++) {
        const url = urls[i];
        setProgress({
          completed: i,
          total: urls.length,
          currentUrl: url,
          successCount,
          failCount: batchErrors.length,
        });

        try {
          const startSerial = savedResults.length + successCount + 1;
          const response = await crawlDcinside([url], startSerial);
          if (response.data.length > 0) {
            successCount += response.data.length;
            setSavedResults((prev) => mergeSavedResults(prev, response.data));
          }
          batchErrors.push(...response.errors);
        } catch (e) {
          const message = resolveCrawlError(e);
          batchErrors.push({ url, error: message });
        }

        setProgress({
          completed: i + 1,
          total: urls.length,
          currentUrl: url,
          successCount,
          failCount: batchErrors.length,
        });
      }

      setErrors(batchErrors);
      if (successCount === 0 && batchErrors.length > 0) {
        setError('이번 요청의 모든 URL 처리에 실패했습니다.');
      }
    } finally {
      if (crawlStartAtRef.current !== null) {
        setLastCrawlDurationMs(Date.now() - crawlStartAtRef.current);
      }
      setLoading(false);
      setProgress(null);
      if (options?.clearUrlInput) {
        setUrlInput('');
      }
      if (options?.clearSearchInput) {
        setSearchInput('');
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
            ((progress.completed + (loading && progress.completed < progress.total ? 0.35 : 0)) /
              progress.total) *
              100
          )
        )
      : 0;

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
      const postsForExcel = savedResults.map((post) => ({
        ...post,
        captureFilePath: getCaptureFilename(post.captureFilePath),
      }));
      await saveCapturesToDirectory(saveDirectoryRef.current, savedResults);
      await exportCrimeListExcel(postsForExcel, saveDirectoryRef.current);
      setSavedResults(postsForExcel);
      setError(null);
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
        <p>크롤링 결과는 화면에 누적되며, 범죄일람표·캡처 저장 시 선택한 폴더에 파일이 저장됩니다.</p>
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

          <label htmlFor="search-input">통합검색어 (최대 100건)</label>
          <input
            id="search-input"
            className="search-input"
            type="text"
            placeholder="검색어를 입력하면 디시 통합검색 결과를 크롤링합니다"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            disabled={loading}
          />
          <p className="field-hint search-hint">
            URL 입력과 검색어 입력은 별도입니다. 검색은 디시 통합검색(최신순) 기준이며 최대 100건까지 수집합니다.
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
            폴더 선택으로 캡처·엑셀을 같은 위치에 저장합니다. 브라우저 보안상 Windows/Mac 전체 경로는
            가져올 수 없으며, 엑셀의 캡처파일 링크는 같은 폴더의 이미지를 엽니다.
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
              {loading ? '검색·크롤링 중…' : '입력한 검색어로 크롤링'}
            </button>
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
                {progress.completed < progress.total
                  ? `처리 중: ${shortenUrl(progress.currentUrl)}`
                  : '완료'}
              </p>
            </div>
          )}

          {error && <div className="message error">{error}</div>}
        </div>

        {savedResults.length > 0 && (
          <section className="result-section">
            <h2>저장된 크롤링 결과 ({savedResults.length}건)</h2>
            <div className="result-list">
              {savedResults.map((post, index) => (
                <article key={post.url} className="result-card">
                  <h3>
                    <span className="result-serial">{index + 1}.</span> {post.title}
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
