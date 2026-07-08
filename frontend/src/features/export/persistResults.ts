import ExcelJS from 'exceljs';
import { saveCaptureToScreenshotDir } from '../crawl/captureFiles';
import { getOrCreateLogDirectory } from '../crawl/crawlLogExport';
import {
  getOrCreateSubdirectory,
  writeArrayBufferToDirectory,
} from '../../shared/lib/localFileStorage';
import {
  addPostRowToWorkbook,
  createCrimeListWorkbook,
  serializeCrimeListWorkbook,
} from '../../platforms/dcinside/excelExport';
import { EXCEL_FLUSH_INTERVAL, EXCEL_SHARD_ROW_LIMIT } from '../crawl/constants';
import type { DcinsidePostData } from '../../platforms/dcinside/types';
import {
  buildExcelFilename,
  buildResultFolderName,
  formatTimestamp,
  getCaptureFilename,
  SCREENSHOT_DIR,
  toCaptureRelativePath,
} from './pathUtils';

export interface PersistResultsOptions {
  communityName: string;
  keyword?: string;
}

interface ShardState {
  resultDir: FileSystemDirectoryHandle;
  screenshotDir: FileSystemDirectoryHandle;
  excelFilename: string;
  workbook: ExcelJS.Workbook;
  sheet: ExcelJS.Worksheet;
  /** 현재 shard에 기록된 데이터 행 수 */
  rows: number;
  /** 마지막 flush 이후 아직 디스크에 반영되지 않은 행 수 */
  dirtySinceFlush: number;
}

/**
 * 크롤링 1회 실행 동안 유지되는 저장 세션.
 * - 워크북을 메모리에 들고 있다가 주기적으로만 디스크에 flush한다(반복 재로드 제거).
 * - shard(엑셀 파일 + 이미지 폴더)를 최대 EXCEL_SHARD_ROW_LIMIT행까지만 채우고 롤오버한다.
 * - seenUrls로 canonical URL 기준 전역 중복을 건너뛴다(파일이 나뉘어도 dedup 유지).
 */
export interface CrawlPersistSession {
  directory: FileSystemDirectoryHandle;
  stamp: string;
  communityName: string;
  keyword?: string;
  seenUrls: Set<string>;
  shardIndex: number;
  shard: ShardState | null;
}

export function stripCaptureBase64(post: DcinsidePostData): DcinsidePostData {
  return { ...post, captureImageBase64: '' };
}

export async function createCrawlPersistSession(
  directory: FileSystemDirectoryHandle,
  options: PersistResultsOptions
): Promise<CrawlPersistSession> {
  await getOrCreateLogDirectory(directory);
  return {
    directory,
    stamp: formatTimestamp(),
    communityName: options.communityName,
    keyword: options.keyword,
    seenUrls: new Set<string>(),
    shardIndex: 0,
    shard: null,
  };
}

async function openNextShard(session: CrawlPersistSession): Promise<ShardState> {
  session.shardIndex += 1;
  const part = session.shardIndex;
  const resultDir = await getOrCreateSubdirectory(
    session.directory,
    buildResultFolderName(session.stamp, part)
  );
  const screenshotDir = await getOrCreateSubdirectory(resultDir, SCREENSHOT_DIR);
  const excelFilename = buildExcelFilename(
    session.communityName,
    session.keyword,
    session.stamp,
    part
  );
  const { workbook, sheet } = createCrimeListWorkbook();
  const shard: ShardState = {
    resultDir,
    screenshotDir,
    excelFilename,
    workbook,
    sheet,
    rows: 0,
    dirtySinceFlush: 0,
  };
  session.shard = shard;
  return shard;
}

async function flushShard(shard: ShardState): Promise<void> {
  if (shard.dirtySinceFlush === 0) {
    return;
  }
  const buffer = await serializeCrimeListWorkbook(shard.workbook);
  await writeArrayBufferToDirectory(shard.resultDir, shard.excelFilename, buffer);
  shard.dirtySinceFlush = 0;
}

async function ensureShardWithCapacity(session: CrawlPersistSession): Promise<ShardState> {
  if (!session.shard) {
    return openNextShard(session);
  }
  if (session.shard.rows >= EXCEL_SHARD_ROW_LIMIT) {
    await flushShard(session.shard);
    return openNextShard(session);
  }
  return session.shard;
}

export async function appendBatchToSession(
  session: CrawlPersistSession,
  posts: DcinsidePostData[]
): Promise<DcinsidePostData[]> {
  const savedForExcel: DcinsidePostData[] = [];

  for (const post of posts) {
    const url = post.url?.trim();
    if (url) {
      if (session.seenUrls.has(url)) {
        continue;
      }
      session.seenUrls.add(url);
    }

    const shard = await ensureShardWithCapacity(session);

    const postForExcel: DcinsidePostData = {
      ...post,
      captureFilePath: toCaptureRelativePath(getCaptureFilename(post.captureFilePath)),
    };

    await saveCaptureToScreenshotDir(shard.screenshotDir, post);
    await addPostRowToWorkbook(shard.workbook, shard.sheet, postForExcel);
    shard.rows += 1;
    shard.dirtySinceFlush += 1;

    if (shard.dirtySinceFlush >= EXCEL_FLUSH_INTERVAL) {
      await flushShard(shard);
    }

    savedForExcel.push(stripCaptureBase64(postForExcel));
  }

  return savedForExcel;
}

/** 크롤링 종료 시 아직 디스크에 반영되지 않은 마지막 행들을 저장한다. */
export async function finalizeCrawlPersistSession(
  session: CrawlPersistSession
): Promise<void> {
  if (session.shard) {
    await flushShard(session.shard);
  }
}

/** 수동 저장: 전체 결과를 새 결과물 폴더 집합에 shard 단위로 내보낸다. */
export async function persistCrimeListAndCaptures(
  directory: FileSystemDirectoryHandle,
  posts: DcinsidePostData[],
  options: PersistResultsOptions
): Promise<{ stamp: string; postsForExcel: DcinsidePostData[] }> {
  const session = await createCrawlPersistSession(directory, options);
  const postsForExcel = await appendBatchToSession(session, posts);
  await finalizeCrawlPersistSession(session);
  return { stamp: session.stamp, postsForExcel };
}
