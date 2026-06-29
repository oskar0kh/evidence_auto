import axios from 'axios';
import { useState } from 'react';
import { crawlDcinside } from './api';
import { exportCrimeListExcel } from './excelExport';
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

interface CrawlProgress {
  completed: number;
  total: number;
  currentUrl: string;
  successCount: number;
  failCount: number;
}

export default function App() {
  const [urlInput, setUrlInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [progress, setProgress] = useState<CrawlProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [savedResults, setSavedResults] = useState<DcinsidePostData[]>([]);
  const [errors, setErrors] = useState<{ url: string; error: string }[]>([]);

  const handleCrawl = async () => {
    const urls = parseUrls(urlInput);
    if (urls.length === 0) {
      setError('디시인사이드 게시글 URL을 입력해 주세요.');
      return;
    }

    setLoading(true);
    setError(null);
    setErrors([]);

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
          const response = await crawlDcinside([url]);
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
      setLoading(false);
      setProgress(null);
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

  const handleDownload = async () => {
    if (savedResults.length === 0) {
      setError('다운로드할 데이터가 없습니다. 먼저 크롤링을 실행해 주세요.');
      return;
    }
    try {
      await exportCrimeListExcel(savedResults);
    } catch (e) {
      const message = e instanceof Error ? e.message : '엑셀 생성 중 오류가 발생했습니다.';
      setError(message);
    }
  };

  return (
    <div className="app">
      <header className="app-header">
        <h1>범죄일람표 크롤러</h1>
        <p>크롤링할 때마다 결과가 누적 저장되며, 엑셀 다운로드 시 저장된 전체 항목이 한 파일로 출력됩니다.</p>
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
          />

          <div className="button-row">
            <button type="button" className="btn primary" onClick={handleCrawl} disabled={loading}>
              {loading ? '크롤링·캡처 중…' : '크롤링 시작'}
            </button>
            <button
              type="button"
              className="btn secondary"
              onClick={handleDownload}
              disabled={loading || savedResults.length === 0}
            >
              엑셀 다운로드
            </button>
            <span className="saved-count">현재까지 저장된 링크 개수: {savedResults.length}</span>
          </div>

          {loading && progress && (
            <div className="progress-panel" aria-live="polite">
              <div className="progress-header">
                <span className="progress-label">
                  진행 {Math.min(progress.completed + (loading ? 1 : 0), progress.total)} / {progress.total}
                  <span className="progress-percent"> ({progressPercent}%)</span>
                </span>
                <span className="progress-stats">
                  성공 {progress.successCount} · 실패 {progress.failCount}
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
                      <dt>작성 형태</dt>
                      <dd>{post.writeType}</dd>
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
