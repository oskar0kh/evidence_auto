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
      <h2>오류 ({errors.length}건)</h2>
      <ul>
        {errors.map((item, index) => (
          <li key={`${item.url}-${index}`}>
            <strong>{item.url}</strong>: {item.error}
          </li>
        ))}
      </ul>
    </section>
  );
}
