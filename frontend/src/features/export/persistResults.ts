import { saveCapturesToDirectory } from '../crawl/captureFiles';
import { getOrCreateLogDirectory } from '../crawl/crawlLogExport';
import { getOrCreateSubdirectory } from '../../shared/lib/localFileStorage';
import { appendCrimeListExcel } from '../../platforms/dcinside/excelExport';
import type { DcinsidePostData } from '../../platforms/dcinside/types';
import {
  buildExcelFilename,
  buildResultFolderName,
  formatTimestamp,
  getCaptureFilename,
  toCaptureRelativePath,
} from './pathUtils';

export interface PersistResultsOptions {
  communityName: string;
  keyword?: string;
}

export interface CrawlPersistSession {
  resultDir: FileSystemDirectoryHandle;
  stamp: string;
  excelFilename: string;
}

export function stripCaptureBase64(post: DcinsidePostData): DcinsidePostData {
  return { ...post, captureImageBase64: '' };
}

export async function createCrawlPersistSession(
  directory: FileSystemDirectoryHandle,
  options: PersistResultsOptions
): Promise<CrawlPersistSession> {
  await getOrCreateLogDirectory(directory);
  const stamp = formatTimestamp();
  const resultDir = await getOrCreateSubdirectory(directory, buildResultFolderName(stamp));
  const excelFilename = buildExcelFilename(options.communityName, options.keyword, stamp);
  return { resultDir, stamp, excelFilename };
}

export async function appendBatchToSession(
  session: CrawlPersistSession,
  posts: DcinsidePostData[]
): Promise<DcinsidePostData[]> {
  if (posts.length === 0) {
    return [];
  }

  const postsForExcel = posts.map((post) => ({
    ...post,
    captureFilePath: toCaptureRelativePath(getCaptureFilename(post.captureFilePath)),
  }));
  await saveCapturesToDirectory(session.resultDir, posts);
  await appendCrimeListExcel(postsForExcel, session.resultDir, session.excelFilename);
  return postsForExcel.map(stripCaptureBase64);
}

/** 수동 저장: 새 결과물 폴더에 전체보내기 */
export async function persistCrimeListAndCaptures(
  directory: FileSystemDirectoryHandle,
  posts: DcinsidePostData[],
  options: PersistResultsOptions
): Promise<{ stamp: string; postsForExcel: DcinsidePostData[] }> {
  const session = await createCrawlPersistSession(directory, options);
  const postsForExcel = await appendBatchToSession(session, posts);
  return { stamp: session.stamp, postsForExcel };
}
