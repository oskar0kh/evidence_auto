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
    <section className="result-section">
      <div className="result-section-header">
        <h2>저장된 크롤링 결과 ({savedCount}건)</h2>
        {savedCount > resultsPreview.length && (
          <p className="field-hint">
            최근 {resultsPreview.length}건만 미리보기로 표시합니다. 전체 결과는 선택한 폴더에
            저장됐습니다.
          </p>
        )}
      </div>
      <div className="result-list">
        {resultsPreview.map((post, index) => (
          <article key={post.url} className="result-card">
            <h3>
              <span className="result-serial">{savedCount - index}.</span> {post.title}
            </h3>
            <dl>
              <div>
                <dt>갤러리명</dt>
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
