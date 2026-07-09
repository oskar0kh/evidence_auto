import type { DcinsidePostData } from '../../types';

interface ResultsSectionProps {
  savedResults: DcinsidePostData[];
  paginatedResults: DcinsidePostData[];
  resultStartIndex: number;
  resultPage: number;
  totalResultPages: number;
  onPageChange: (page: number) => void;
}

export default function ResultsSection({
  savedResults,
  paginatedResults,
  resultStartIndex,
  resultPage,
  totalResultPages,
  onPageChange,
}: ResultsSectionProps) {
  if (savedResults.length === 0) {
    return null;
  }

  return (
    <section className="result-section">
      <div className="result-section-header">
        <h2>저장된 크롤링 결과 ({savedResults.length}건)</h2>
        {totalResultPages > 1 && (
          <div className="pagination">
            <button
              type="button"
              className="btn pagination-btn"
              onClick={() => onPageChange(Math.max(1, resultPage - 1))}
              disabled={resultPage <= 1}
            >
              이전
            </button>
            <span className="pagination-info">
              {resultPage} / {totalResultPages}
            </span>
            <button
              type="button"
              className="btn pagination-btn"
              onClick={() => onPageChange(Math.min(totalResultPages, resultPage + 1))}
              disabled={resultPage >= totalResultPages}
            >
              다음
            </button>
          </div>
        )}
      </div>
      <div className="result-list">
        {paginatedResults.map((post, index) => (
          <article key={post.url} className="result-card">
            <h3>
              <span className="result-serial">{resultStartIndex + index + 1}.</span> {post.title}
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
            <p className="preview-content">{post.content.slice(0, 300)}…</p>
          </article>
        ))}
      </div>
    </section>
  );
}
