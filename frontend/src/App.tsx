import CrawlForm from './features/crawl/components/CrawlForm';
import CrawlProgressPanel from './features/crawl/components/CrawlProgressPanel';
import ErrorSection from './features/crawl/components/ErrorSection';
import ResultsSection from './features/crawl/components/ResultsSection';
import { useCrawlOrchestrator } from './features/crawl/useCrawlOrchestrator';
import './app/App.css';

export default function App() {
  const crawl = useCrawlOrchestrator();

  return (
    <div className="app">
      <header className="app-header">
        <h1>범죄일람표 크롤러</h1>
        <p>
          검색 크롤링은 URL을 찾는 즉시 수집·캡처합니다. URL 직접 입력은 100건 단위로 진행되며, 각
          배치가 끝날 때마다 선택한 폴더의 동일한 결과물 폴더에 엑셀·캡처가 자동 저장됩니다. 저장
          버튼은 화면에 누적된 결과를 새 폴더에 다시 저장할 때 사용합니다.
        </p>
      </header>

      <main className="app-main">
        <CrawlForm
          urlInput={crawl.urlInput}
          onUrlInputChange={crawl.setUrlInput}
          searchInput={crawl.searchInput}
          onSearchInputChange={crawl.setSearchInput}
          searchGalleryName={crawl.searchGalleryName}
          onGalleryNameChange={crawl.handleGalleryNameChange}
          selectedGallery={crawl.selectedGallery}
          galleryCandidates={crawl.galleryCandidates}
          galleryPickerOpen={crawl.galleryPickerOpen}
          galleryResolving={crawl.galleryResolving}
          searchStartDate={crawl.searchStartDate}
          searchEndDate={crawl.searchEndDate}
          onSearchStartDateChange={crawl.setSearchStartDate}
          onSearchEndDateChange={crawl.setSearchEndDate}
          saveDirectoryPath={crawl.saveDirectoryPath}
          onPickDirectory={() => void crawl.handlePickDirectory()}
          loading={crawl.loading}
          saving={crawl.saving}
          savedResultsCount={crawl.savedResults.length}
          lastCrawlDurationMs={crawl.lastCrawlDurationMs}
          infoMessage={crawl.infoMessage}
          error={crawl.error}
          onCrawl={() => void crawl.handleCrawl()}
          onSearchCrawl={() => void crawl.handleSearchCrawl()}
          onSaveExcel={() => void crawl.handleSaveExcel()}
          onCancelCrawl={crawl.handleCancelCrawl}
          onGalleryLookup={() => void crawl.handleGalleryLookup()}
          onGallerySelect={crawl.handleGallerySelect}
          onGalleryPickerCancel={crawl.handleGalleryPickerCancel}
          progressPanel={
            crawl.loading && crawl.progress ? (
              <CrawlProgressPanel
                progress={crawl.progress}
                loading={crawl.loading}
                elapsedMs={crawl.elapsedMs}
              />
            ) : undefined
          }
        />

        <ResultsSection
          savedResults={crawl.savedResults}
          paginatedResults={crawl.paginatedResults}
          resultStartIndex={crawl.resultStartIndex}
          resultPage={crawl.resultPage}
          totalResultPages={crawl.totalResultPages}
          onPageChange={crawl.setResultPage}
        />

        <ErrorSection errors={crawl.errors} />
      </main>
    </div>
  );
}
