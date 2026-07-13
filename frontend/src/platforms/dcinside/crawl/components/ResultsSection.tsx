import type { SavedResultPreview } from '../crawlHelpers';

interface ResultsSectionProps {
  savedCount: number;
  resultsPreview: SavedResultPreview[];
}

export default function ResultsSection({ savedCount, resultsPreview }: ResultsSectionProps) {
  const previewCount = resultsPreview.length;
  if (previewCount === 0 && savedCount === 0) {
    return null;
  }

  const displayCount = Math.max(savedCount, ...resultsPreview.map((post) => post.serial), 0);

  return (
    <section className="result-section">
      <div className="result-section-header">
        <h2>저장된 크롤링 결과 ({displayCount}건)</h2>
        {displayCount > previewCount && (
          <p className="field-hint">
            최근 {previewCount}건만 미리보기로 표시합니다. 전체 결과는 선택한 폴더에
            저장됐습니다.
          </p>
        )}
      </div>
      <div className="result-list">
        {resultsPreview.map((post) => (
          <article key={post.url} className="result-card">
            <h3>
              <span className="result-serial">{post.serial}.</span> {post.title}
            </h3>
            <dl>
              <div>
                <dt>커뮤니티</dt>
                <dd>{post.community?.trim() || '디시인사이드'}</dd>
              </div>
              <div>
                <dt>{post.community === '인스타그램' ? '작성 형태' : '갤러리명'}</dt>
                <dd>{post.galleryName?.trim() || '-'}</dd>
              </div>
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
          </article>
        ))}
      </div>
    </section>
  );
}
