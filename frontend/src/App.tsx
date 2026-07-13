import CrawlForm from './platforms/dcinside/crawl/components/CrawlForm';
import CrawlProgressPanel from './platforms/dcinside/crawl/components/CrawlProgressPanel';
import ErrorSection from './platforms/dcinside/crawl/components/ErrorSection';
import ResultsSection from './platforms/dcinside/crawl/components/ResultsSection';
import { useCrawlOrchestrator } from './platforms/dcinside/crawl/useCrawlOrchestrator';
import './app/App.css';

export default function App() {
  const crawl = useCrawlOrchestrator();

  return (
    <div className="app">
      <header className="app-header">
        <h1>범죄일람표 크롤러</h1>
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
          savedResultsCount={crawl.savedCount}
          lastCrawlDurationMs={crawl.lastCrawlDurationMs}
          infoMessage={crawl.infoMessage}
          error={crawl.error}
          onCrawl={() => void crawl.handleCrawl()}
          onSearchCrawl={() => void crawl.handleSearchCrawl()}
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

        <ResultsSection savedCount={crawl.savedCount} resultsPreview={crawl.resultsPreview} />

        <ErrorSection errors={crawl.errors} />
      </main>
    </div>
  );
}
