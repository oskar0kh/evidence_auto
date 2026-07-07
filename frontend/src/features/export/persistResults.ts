import { saveCapturesToDirectory } from '../crawl/captureFiles';
import { getOrCreateLogDirectory } from '../crawl/crawlLogExport';
import { getOrCreateSubdirectory } from '../../shared/lib/localFileStorage';
import { exportCrimeListExcel } from '../../platforms/dcinside/excelExport';
import type { DcinsidePostData } from '../../platforms/dcinside/types';
import {
  buildResultFolderName,
  formatTimestamp,
  getCaptureFilename,
  toCaptureRelativePath,
} from './pathUtils';

export interface PersistResultsOptions {
  communityName: string;
  keyword?: string;
}

export async function persistCrimeListAndCaptures(
  directory: FileSystemDirectoryHandle,
  posts: DcinsidePostData[],
  options: PersistResultsOptions
): Promise<{ stamp: string; postsForExcel: DcinsidePostData[] }> {
  await getOrCreateLogDirectory(directory);
  const stamp = formatTimestamp();
  const resultDir = await getOrCreateSubdirectory(directory, buildResultFolderName(stamp));
  const postsForExcel = posts.map((post) => ({
    ...post,
    captureFilePath: toCaptureRelativePath(getCaptureFilename(post.captureFilePath)),
  }));
  await saveCapturesToDirectory(resultDir, posts);
  await exportCrimeListExcel(postsForExcel, resultDir, {
    communityName: options.communityName,
    keyword: options.keyword,
    stamp,
  });
  return { stamp, postsForExcel };
}
