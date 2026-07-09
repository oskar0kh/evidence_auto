export const CRAWL_BATCH_SIZE = 100;
export const RESULTS_PAGE_SIZE = 10;
/** 크롤 중 화면에 보여줄 최근 결과 미리보기 개수 */
export const RESULTS_PREVIEW_SIZE = 10;
/** savedCount UI 갱신 주기(건). 건마다 setState 하지 않도록 배칭 */
export const SAVED_COUNT_UI_UPDATE_INTERVAL = 20;

/** 엑셀 파일(=결과물 폴더) 하나에 담는 최대 게시글 수. 초과 시 새 파일·이미지 폴더로 롤오버 */
export const EXCEL_SHARD_ROW_LIMIT = 200;
/** shard 워크북을 디스크로 flush하는 주기(게시글 수). 값이 클수록 빠르지만 충돌 시 유실 위험 증가 */
export const EXCEL_FLUSH_INTERVAL = 20;

export function chunkArray<T>(items: T[], size: number): T[][] {
  if (items.length === 0) {
    return [];
  }
  const chunkSize = size > 0 ? size : items.length;
  const chunks: T[][] = [];
  for (let i = 0; i < items.length; i += chunkSize) {
    chunks.push(items.slice(i, i + chunkSize));
  }
  return chunks;
}
