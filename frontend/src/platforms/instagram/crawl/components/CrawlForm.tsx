import { formatElapsedForUi } from '../../../../shared/lib/formatDuration';
import CrawlProgressPanel from './CrawlProgressPanel';

interface CrawlFormProps {
  urlInput: string;
  onUrlInputChange: (value: string) => void;
  searchInput: string;
  onSearchInputChange: (value: string) => void;
  saveDirectoryPath: string;
  onPickDirectory: () => void;
  loading: boolean;
  savedResultsCount: number;
  showSavedCount: boolean;
  lastCrawlDurationMs: number | null;
  infoMessage: string | null;
  error: string | null;
  onCrawl: () => void;
  onSearchCrawl: () => void;
  onCancelCrawl: () => void;
  progressPanel?: React.ReactNode;
}

export default function CrawlForm({
  urlInput,
  onUrlInputChange,
  searchInput,
  onSearchInputChange,
  saveDirectoryPath,
  onPickDirectory,
  loading,
  savedResultsCount,
  showSavedCount,
  lastCrawlDurationMs,
  infoMessage,
  error,
  onCrawl,
  onSearchCrawl,
  onCancelCrawl,
  progressPanel,
}: CrawlFormProps) {
  return (
    <div className="input-card">
      <label htmlFor="ig-url-input">Instagram 게시물 URL (여러 개는 줄바꿈 또는 쉼표로 구분)</label>
      <textarea
        id="ig-url-input"
        className="url-input"
        placeholder="https://www.instagram.com/p/SHORTCODE/"
        value={urlInput}
        onChange={(e) => onUrlInputChange(e.target.value)}
        rows={4}
        disabled={loading}
      />

      <label htmlFor="ig-search-input">검색어 (Instagram 탐색 검색)</label>
      <input
        id="ig-search-input"
        className="search-input"
        type="text"
        placeholder="예: 샤머호"
        value={searchInput}
        onChange={(e) => onSearchInputChange(e.target.value)}
        disabled={loading}
      />

      <div className="directory-row">
        <button type="button" className="secondary-button" onClick={onPickDirectory} disabled={loading}>
          저장 폴더 선택
        </button>
        <span className="directory-path">{saveDirectoryPath || '선택된 폴더 없음'}</span>
      </div>

      {showSavedCount ? (
        <p className="info-text">
          저장된 행: {savedResultsCount}건
          {lastCrawlDurationMs != null ? ` · 소요 ${formatElapsedForUi(lastCrawlDurationMs)}` : ''}
        </p>
      ) : null}
      {infoMessage ? <p className="info-text">{infoMessage}</p> : null}
      {error ? <p className="error-text">{error}</p> : null}

      <div className="button-row">
        <button type="button" className="primary-button" onClick={onCrawl} disabled={loading}>
          URL 크롤링
        </button>
        <button type="button" className="primary-button" onClick={onSearchCrawl} disabled={loading}>
          검색어 크롤링
        </button>
        {loading ? (
          <button type="button" className="secondary-button" onClick={onCancelCrawl}>
            취소
          </button>
        ) : null}
      </div>

      {progressPanel}
    </div>
  );
}
