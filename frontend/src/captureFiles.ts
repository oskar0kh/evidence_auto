import type { DcinsidePostData } from './types';
import { downloadCaptureFile } from './api';
import { writeBlobToDirectory } from './localFileStorage';

export async function saveCapturesToDirectory(
  directory: FileSystemDirectoryHandle,
  posts: DcinsidePostData[]
): Promise<DcinsidePostData[]> {
  const saved: DcinsidePostData[] = [];

  for (const post of posts) {
    const filename = post.captureFilePath;
    const blob = await downloadCaptureFile(filename);
    await writeBlobToDirectory(directory, filename, blob);
    saved.push(post);
  }

  return saved;
}
