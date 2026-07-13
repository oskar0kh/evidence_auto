import type { SavedResultPreview } from '../crawlHelpers';

interface ResultsSectionProps {
  savedCount: number;
  resultsPreview: SavedResultPreview[];
}

export default function ResultsSection({ savedCount, resultsPreview }: ResultsSectionProps) {
  if (savedCount === 0) {
    return null;
  }

  return (
    <section className="results-section">
      <h2>저장된 결과 ({savedCount}건)</h2>
      <ul className="results-list">
        {resultsPreview.map((item) => (
          <li key={`${item.serial}-${item.captureFilePath}`}>
            <span className="result-serial">#{item.serial}</span>
            <span className="result-type">{item.postType}</span>
            <span className="result-title">{item.title || item.url}</span>
            <span className="result-meta">
              {item.nickname} · {item.postDate}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
