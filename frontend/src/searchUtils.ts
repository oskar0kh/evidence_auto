export function parseSearchTerms(query: string): string[] {
  return query
    .trim()
    .split(/[,\s]+/)
    .map((term) => term.trim())
    .filter((term) => term.length > 0);
}
