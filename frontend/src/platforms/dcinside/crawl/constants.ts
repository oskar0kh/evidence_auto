export const CRAWL_BATCH_SIZE = 100;
export const RESULTS_PAGE_SIZE = 10;

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
