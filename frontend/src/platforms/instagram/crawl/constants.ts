export const CRAWL_BATCH_SIZE = 50;
export const RESULTS_PREVIEW_SIZE = 10;
export const EXCEL_SHARD_ROW_LIMIT = 200;
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

export function parseUrls(input: string): string[] {
  return input
    .split(/[\n,]+/)
    .map((url) => url.trim())
    .filter((url) => url.length > 0);
}
