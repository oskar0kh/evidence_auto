import { getOrCreateSubdirectory } from '../lib/localFileStorage';
import { sanitizeFilenamePart } from '../../platforms/dcinside/export/pathUtils';

/** 저장 폴더 아래 커뮤니티별 하위 폴더 (예: .../디시인사이드, .../인스타그램) */
export async function resolveCommunityDirectory(
  baseDirectory: FileSystemDirectoryHandle,
  communityFolderName: string
): Promise<FileSystemDirectoryHandle> {
  return getOrCreateSubdirectory(baseDirectory, sanitizeFilenamePart(communityFolderName));
}
