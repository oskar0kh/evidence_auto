import ExcelJS from 'exceljs';
import { saveCaptureToScreenshotDir } from '../crawl/captureFiles';
import {
  getOrCreateSubdirectory,
  writeArrayBufferToDirectory,
  writeBlobToDirectory,
} from '../../../shared/lib/localFileStorage';
import {
  addPostRowToWorkbook,
  createCrimeListWorkbook,
  mergeCrimeListWorkbooks,
  serializeCrimeListWorkbook,
} from '../excelExport';
import { EXCEL_FLUSH_INTERVAL, EXCEL_SHARD_ROW_LIMIT } from '../crawl/constants';
import type { InstagramPostData } from '../types';
import {
  buildExcelFilename,
  buildResultFolderName,
  buildShardFolderName,
  formatTimestamp,
  getCaptureFilename,
  rowDedupKey,
  SCREENSHOT_DIR,
  toCaptureRelativePath,
} from './pathUtils';

export interface PersistResultsOptions {
  keyword?: string;
  communityName?: string;
  stamp?: string;
}

interface ShardState {
  resultDir: FileSystemDirectoryHandle;
  screenshotDir: FileSystemDirectoryHandle;
  excelFilename: string;
  workbook: ExcelJS.Workbook;
  sheet: ExcelJS.Worksheet;
  startSerial: number;
  folderName: string;
  rows: number;
  dirtySinceFlush: number;
}

export interface CrawlPersistSession {
  directory: FileSystemDirectoryHandle;
  resultsRoot: FileSystemDirectoryHandle;
  stamp: string;
  keyword?: string;
  communityName?: string;
  seenKeys: Set<string>;
  shardIndex: number;
  completedShards: ShardState[];
  shard: ShardState | null;
}

export function stripCaptureBase64(post: InstagramPostData): InstagramPostData {
  return { ...post, captureImageBase64: '' };
}

export async function createCrawlPersistSession(
  directory: FileSystemDirectoryHandle,
  options: PersistResultsOptions
): Promise<CrawlPersistSession> {
  const stamp = options.stamp ?? formatTimestamp();
  const resultsRoot = await getOrCreateSubdirectory(directory, buildResultFolderName(stamp));
  return {
    directory,
    resultsRoot,
    stamp,
    keyword: options.keyword,
    communityName: options.communityName,
    seenKeys: new Set<string>(),
    shardIndex: 0,
    completedShards: [],
    shard: null,
  };
}

async function openNextShard(session: CrawlPersistSession): Promise<ShardState> {
  session.shardIndex += 1;
  const part = session.shardIndex;
  const startSerial = (part - 1) * EXCEL_SHARD_ROW_LIMIT + 1;
  const optimisticEnd = startSerial + EXCEL_SHARD_ROW_LIMIT - 1;
  const folderName = buildShardFolderName(startSerial, optimisticEnd);
  const resultDir = await getOrCreateSubdirectory(session.resultsRoot, folderName);
  const screenshotDir = await getOrCreateSubdirectory(resultDir, SCREENSHOT_DIR);
  const excelFilename = buildExcelFilename({
    keyword: session.keyword,
    communityName: session.communityName,
    stamp: session.stamp,
  });
  const { workbook, sheet } = createCrimeListWorkbook();
  const shard: ShardState = {
    resultDir,
    screenshotDir,
    excelFilename,
    workbook,
    sheet,
    startSerial,
    folderName,
    rows: 0,
    dirtySinceFlush: 0,
  };
  session.shard = shard;
  return shard;
}

async function flushShard(shard: ShardState): Promise<void> {
  if (shard.dirtySinceFlush === 0) return;
  const buffer = await serializeCrimeListWorkbook(shard.workbook);
  await writeArrayBufferToDirectory(shard.resultDir, shard.excelFilename, buffer);
  shard.dirtySinceFlush = 0;
}

async function ensureShardWithCapacity(session: CrawlPersistSession): Promise<ShardState> {
  if (!session.shard) return openNextShard(session);
  if (session.shard.rows >= EXCEL_SHARD_ROW_LIMIT) {
    await flushShard(session.shard);
    session.completedShards.push(session.shard);
    return openNextShard(session);
  }
  return session.shard;
}

export async function appendBatchToSession(
  session: CrawlPersistSession,
  posts: InstagramPostData[]
): Promise<InstagramPostData[]> {
  const savedForExcel: InstagramPostData[] = [];
  for (const post of posts) {
    const dedupKey = rowDedupKey(post);
    if (session.seenKeys.has(dedupKey)) continue;
    session.seenKeys.add(dedupKey);

    const shard = await ensureShardWithCapacity(session);
    const postForExcel: InstagramPostData = {
      ...post,
      captureFilePath: toCaptureRelativePath(getCaptureFilename(post.captureFilePath)),
    };
    await saveCaptureToScreenshotDir(shard.screenshotDir, post);
    await addPostRowToWorkbook(
      shard.workbook,
      shard.sheet,
      postForExcel,
      shard.startSerial + shard.rows,
      session.keyword
    );
    shard.rows += 1;
    shard.dirtySinceFlush += 1;
    if (shard.dirtySinceFlush >= EXCEL_FLUSH_INTERVAL) {
      await flushShard(shard);
    }
    savedForExcel.push(stripCaptureBase64(postForExcel));
  }
  return savedForExcel;
}

async function copyDirectoryContents(
  src: FileSystemDirectoryHandle,
  dest: FileSystemDirectoryHandle
): Promise<void> {
  for await (const [name, handle] of (src as unknown as { entries(): AsyncIterableIterator<[string, FileSystemHandle]> }).entries()) {
    if (handle.kind === 'file') {
      const file = await (handle as FileSystemFileHandle).getFile();
      await writeBlobToDirectory(dest, name, file);
    } else {
      const subDest = await getOrCreateSubdirectory(dest, name);
      await copyDirectoryContents(handle as FileSystemDirectoryHandle, subDest);
    }
  }
}

async function mergeShardResults(session: CrawlPersistSession): Promise<void> {
  const shards = [...session.completedShards];
  if (session.shard && session.shard.rows > 0) shards.push(session.shard);
  if (shards.length === 0) return;

  const excelFilename = buildExcelFilename({
    keyword: session.keyword,
    communityName: session.communityName,
    stamp: session.stamp,
  });
  const mergedScreenshotDir = await getOrCreateSubdirectory(session.resultsRoot, SCREENSHOT_DIR);
  for (const shard of shards) {
    await copyDirectoryContents(shard.screenshotDir, mergedScreenshotDir);
  }

  const resolveThumbnail = async (captureRelativePath: string) => {
    const filename = getCaptureFilename(captureRelativePath);
    try {
      const fileHandle = await mergedScreenshotDir.getFileHandle(filename);
      const file = await fileHandle.getFile();
      const url = URL.createObjectURL(file);
      try {
        return await new Promise<HTMLImageElement>((resolve, reject) => {
          const image = new Image();
          image.onload = () => resolve(image);
          image.onerror = () => reject(new Error('캡처 이미지를 읽을 수 없습니다.'));
          image.src = url;
        });
      } finally {
        URL.revokeObjectURL(url);
      }
    } catch {
      return null;
    }
  };

  const mergedWorkbook = await mergeCrimeListWorkbooks(
    shards.map((shard) => shard.workbook),
    resolveThumbnail
  );
  const buffer = await serializeCrimeListWorkbook(mergedWorkbook);
  await writeArrayBufferToDirectory(session.resultsRoot, excelFilename, buffer);
  for (const shard of shards) {
    try {
      await session.resultsRoot.removeEntry(shard.folderName, { recursive: true });
    } catch (error) {
      console.warn(`shard 폴더 제거 실패 (${shard.folderName}):`, error);
    }
  }
}

export async function finalizeCrawlPersistSession(session: CrawlPersistSession): Promise<void> {
  if (session.shard) await flushShard(session.shard);
  await mergeShardResults(session);
}
