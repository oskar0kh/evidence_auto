export type CommunityId = 'dcinside' | 'instagram';

export interface CommunityDefinition {
  id: CommunityId;
  label: string;
  folderName: string;
  communityName: string;
}

export const COMMUNITIES: CommunityDefinition[] = [
  { id: 'dcinside', label: '디시인사이드', folderName: '디시인사이드', communityName: '디시인사이드' },
  { id: 'instagram', label: '인스타그램', folderName: '인스타그램', communityName: '인스타그램' },
];

export function getCommunityDefinition(id: CommunityId): CommunityDefinition {
  const found = COMMUNITIES.find((c) => c.id === id);
  if (!found) {
    throw new Error(`Unknown community: ${id}`);
  }
  return found;
}

export function isDcinsideUrl(url: string): boolean {
  return /gall\.dcinside\.com/i.test(url);
}

export function isInstagramUrl(url: string): boolean {
  return /instagram\.com\/(p|reel|tv)\//i.test(url);
}

export function classifyUrl(url: string): CommunityId | null {
  if (isDcinsideUrl(url)) {
    return 'dcinside';
  }
  if (isInstagramUrl(url)) {
    return 'instagram';
  }
  return null;
}
