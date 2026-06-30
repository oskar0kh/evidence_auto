import type { DcinsidePostData } from './types';
import { downloadCaptureFile } from './api';
import { writeBlobToDirectory } from './localFileStorage';

export function extractCaptureFilename(captureFilePath: string): string {
  const normalized = captureFilePath.replace(/\\/g, '/');
  const index = normalized.lastIndexOf('/');
  return index >= 0 ? normalized.slice(index + 1) : normalized;
}

export async function saveCapturesToDirectory(
  directory: FileSystemDirectoryHandle,
  posts: DcinsidePostData[]
): Promise<DcinsidePostData[]> {
  const saved: DcinsidePostData[] = [];

  for (const post of posts) {
    const filename = extractCaptureFilename(post.captureFilePath);
    const blob = await downloadCaptureFile(filename);
    await writeBlobToDirectory(directory, filename, blob);
    saved.push({
      ...post,
      captureFilePath: filename,
    });
  }

  return saved;
}
