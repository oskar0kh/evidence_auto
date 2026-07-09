import { formatFailureStageLabel } from '../crawlLogExport';
import type { CrawlFailureRecord } from '../types';

interface ErrorSectionProps {
  errors: CrawlFailureRecord[];
}

export default function ErrorSection({ errors }: ErrorSectionProps) {
  if (errors.length === 0) {
    return null;
  }

  return (
    <section className="error-section">
      <h2>실패한 URL</h2>
      <ul>
        {errors.map((item, index) => (
          <li key={`${item.url}-${item.stage ?? 'unknown'}-${index}`}>
            <strong>[{formatFailureStageLabel(item.stage)}] {item.url}</strong>: {item.error}
          </li>
        ))}
      </ul>
    </section>
  );
}
