import DateRangeInput from '../../../../shared/ui/DateRangeInput';
import { formatElapsedForUi } from '../../../../shared/lib/formatDuration';
import { COMMUNITIES, type CommunityId } from '../../../../shared/crawl/communities';
import {
  formatGalleryLabel,
  formatGalleryTypeLabel,
} from '../crawlHelpers';
import type { GalleryCandidate } from '../../search/types';

interface CrawlFormProps {
  selectedCommunities: CommunityId[];
  onCommunityToggle: (id: CommunityId) => void;
  urlInput: string;
  onUrlInputChange: (value: string) => void;
  searchInput: string;
  onSearchInputChange: (value: string) => void;
  searchGalleryName: string;
  onGalleryNameChange: (value: string) => void;
  onGalleryClear: () => void;
  selectedGallery: GalleryCandidate | null;
  galleryCandidates: GalleryCandidate[];
  galleryPickerOpen: boolean;
  galleryResolving: boolean;
  searchStartDate: string;
  searchEndDate: string;
  onSearchStartDateChange: (value: string) => void;
  onSearchEndDateChange: (value: string) => void;
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
  onGalleryLookup: () => void;
  onGallerySelect: (candidate: GalleryCandidate) => void;
  onGalleryPickerCancel: () => void;
  progressPanel?: React.ReactNode;
}

export default function CrawlForm({
  selectedCommunities,
  onCommunityToggle,
  urlInput,
  onUrlInputChange,
  searchInput,
  onSearchInputChange,
  searchGalleryName,
  onGalleryNameChange,
  onGalleryClear,
  selectedGallery,
  galleryCandidates,
  galleryPickerOpen,
  galleryResolving,
  searchStartDate,
  searchEndDate,
  onSearchStartDateChange,
  onSearchEndDateChange,
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
  onGalleryLookup,
  onGallerySelect,
  onGalleryPickerCancel,
  progressPanel,
}: CrawlFormProps) {
  const showGallerySection = selectedCommunities.includes('dcinside');

  return (
    <div className="input-card">
      <div className="community-select">
        <span className="community-select-label">커뮤니티</span>
        <div className="community-select-buttons">
          {COMMUNITIES.map((community) => (
            <button
              key={community.id}
              type="button"
              className={`community-toggle${
                selectedCommunities.includes(community.id) ? ' active' : ''
              }`}
              onClick={() => onCommunityToggle(community.id)}
              disabled={loading}
            >
              {community.label}
            </button>
          ))}
        </div>
      </div>

      <label htmlFor="url-input">게시글 URL (여러 개는 줄바꿈 또는 쉼표로 구분)</label>
      <textarea
        id="url-input"
        className="url-input"
        placeholder="https://gall.dcinside.com/... 또는 https://www.instagram.com/p/..."
        value={urlInput}
        onChange={(e) => onUrlInputChange(e.target.value)}
        rows={4}
        disabled={loading}
      />

      <div className="search-row">
        <div className="search-row-main">
          <label htmlFor="search-input">통합검색어 (쉼표·공백으로 OR 검색)</label>
          <input
            id="search-input"
            className="search-input"
            type="text"
            placeholder="예: 사기, 피해 또는 사기 피해 (각 검색어 결과를 합쳐 수집)"
            value={searchInput}
            onChange={(e) => onSearchInputChange(e.target.value)}
            disabled={loading}
          />
        </div>
        {showGallerySection && (
          <div className="search-row-gallery">
            <label htmlFor="search-gallery">갤러리 (선택)</label>
            <div className="gallery-lookup-row">
              <input
                id="search-gallery"
                className="gallery-input"
                type="text"
                placeholder="예: 포스트락"
                value={searchGalleryName}
                onChange={(e) => onGalleryNameChange(e.target.value)}
                disabled={loading || galleryResolving}
              />
              <button
                type="button"
                className="btn secondary gallery-find-btn"
                onClick={onGalleryLookup}
                disabled={loading || galleryResolving || !searchGalleryName.trim()}
              >
                {galleryResolving ? '찾는 중…' : '찾기'}
              </button>
              {selectedGallery && (
                <button
                  type="button"
                  className="btn gallery-clear-btn"
                  onClick={onGalleryClear}
                  disabled={loading || galleryResolving}
                  aria-label="갤러리 지정 해제"
                  title="갤러리 지정 해제"
                >
                  ✕
                </button>
              )}
            </div>
            <p className="gallery-selected-hint">
              {selectedGallery ? `선택됨: ${formatGalleryLabel(selectedGallery)}` : ''}
            </p>
          </div>
        )}
      </div>

      {showGallerySection && galleryPickerOpen && galleryCandidates.length > 0 && (
        <div className="gallery-picker" role="dialog" aria-label="갤러리 선택">
          <p className="gallery-picker-title">
            '{searchGalleryName}' 검색 결과 {galleryCandidates.length}건 — 갤러리를 선택하세요.
          </p>
          <ul className="gallery-picker-list">
            {galleryCandidates.map((candidate) => (
              <li key={candidate.id}>
                <button
                  type="button"
                  className={`gallery-picker-item${
                    selectedGallery?.id === candidate.id ? ' selected' : ''
                  }`}
                  onClick={() => onGallerySelect(candidate)}
                >
                  <span className="gallery-picker-label">{formatGalleryLabel(candidate)}</span>
                  <span className="gallery-picker-type">{formatGalleryTypeLabel(candidate.type)}</span>
                </button>
              </li>
            ))}
          </ul>
          <button
            type="button"
            className="btn secondary gallery-picker-cancel"
            onClick={onGalleryPickerCancel}
          >
            닫기
          </button>
        </div>
      )}

      <label htmlFor="search-start-date">검색 기간 (선택, yyyy-mm-dd)</label>
      <DateRangeInput
        startDate={searchStartDate}
        endDate={searchEndDate}
        onStartDateChange={onSearchStartDateChange}
        onEndDateChange={onSearchEndDateChange}
        disabled={loading}
      />
      <p className="field-hint search-hint">
        URL 입력과 검색어 입력은 별도입니다. 선택한 커뮤니티에서 동시에 검색·크롤링합니다.
        검색어를 쉼표(,)나 공백으로 나누면 OR 조건으로 각각 검색한 뒤 결과 URL을 합칩니다.
        디시인사이드를 선택한 경우에만 갤러리 지정이 가능합니다. 갤러리를 비우면 전체
        통합검색입니다. 기간을 지정하지 않으면 검색어당 최대 100건(최신순)까지 수집합니다.
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
          onClick={onPickDirectory}
          disabled={loading}
        >
          폴더 선택
        </button>
      </div>
      <p className="field-hint">
        폴더 선택 후 저장하면 커뮤니티별 하위 폴더(예: <code>디시인사이드/</code>,{' '}
        <code>인스타그램/</code>) 아래 <code>결과물_YYYYMMDD_HHMM</code>에 엑셀과{' '}
        <code>Screenshot</code> 폴더가 생성됩니다.
      </p>

      <div className="button-row">
        <button
          type="button"
          className="btn primary"
          onClick={onCrawl}
          disabled={loading || !saveDirectoryPath}
        >
          {loading ? '크롤링·캡처 중…' : 'URL 크롤링'}
        </button>
        <button
          type="button"
          className="btn primary"
          onClick={onSearchCrawl}
          disabled={loading || !saveDirectoryPath}
        >
          {loading ? '검색·크롤링·캡처 중…' : '검색어 크롤링'}
        </button>
        <div className="save-cancel-group">
          <button
            type="button"
            className="btn cancel-crawl"
            onClick={onCancelCrawl}
            disabled={!loading}
            aria-label="크롤링 취소"
            title="크롤링 취소"
          >
            ✕
          </button>
        </div>
        {showSavedCount && (
          <span className="saved-count">현재까지 저장된 링크 개수: {savedResultsCount}</span>
        )}
      </div>

      {lastCrawlDurationMs !== null && !loading && (
        <div className="crawl-status">
          <span className="crawl-timer-summary">
            수집 시간: {formatElapsedForUi(lastCrawlDurationMs)}
          </span>
        </div>
      )}

      {progressPanel}

      {infoMessage && <div className="message info">{infoMessage}</div>}
      {error && <div className="message error">{error}</div>}
    </div>
  );
}
