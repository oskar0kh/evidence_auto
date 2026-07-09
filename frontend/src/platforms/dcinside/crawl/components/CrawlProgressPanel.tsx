import { formatElapsedForUi } from '../../../../shared/lib/formatDuration';
import {
  type CrawlProgress,
  computeProgressLabel,
  computeProgressPercent,
  formatDeadlineRemainingLabel,
  formatHealthLabel,
  formatStageLabel,
  formatUrlAttemptLabel,
  shortenUrl,
} from '../crawlHelpers';

interface CrawlProgressPanelProps {
  progress: CrawlProgress;
  loading: boolean;
  elapsedMs: number;
}

export default function CrawlProgressPanel({
  progress,
  loading,
  elapsedMs,
}: CrawlProgressPanelProps) {
  const progressPercent = computeProgressPercent(progress, loading);
  const progressLabel = computeProgressLabel(progress, loading);
  const healthLabel = formatHealthLabel(progress.health);
  const attemptLabel = formatUrlAttemptLabel(progress);
  const deadlineLabel = formatDeadlineRemainingLabel(progress.urlDeadlineRemainingMs);

  return (
    <div className="progress-panel" aria-live="polite">
      <div className="progress-header">
        <span className="progress-label">
          {progressLabel}
          <span className="progress-percent"> ({progressPercent}%)</span>
        </span>
        <span className="progress-stats">
          성공 {progress.successCount} · 실패 {progress.failCount} · 수집{' '}
          {formatElapsedForUi(elapsedMs)}
          {attemptLabel ? ` · ${attemptLabel}` : ''}
          {deadlineLabel ? ` · ${deadlineLabel}` : ''}
        </span>
      </div>
      <div className="progress-track">
        <div className="progress-fill" style={{ width: `${progressPercent}%` }} />
      </div>
      <p className="progress-url">
        {healthLabel ? <span className="progress-health">{healthLabel}</span> : null}
        {healthLabel ? <br /> : null}
        {progress.total === 0 || progress.completed < progress.total ? (
          <>
            <span className="progress-stage">{formatStageLabel(progress.stage)}</span>
            {' · '}
            {progress.stage === 'search'
              ? progress.currentUrl
              : shortenUrl(progress.currentUrl)}
          </>
        ) : (
          '완료'
        )}
      </p>
    </div>
  );
}
