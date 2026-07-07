export const CRAWL_BATCH_SIZE = 100;

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
