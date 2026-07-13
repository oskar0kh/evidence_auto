import type { InstagramPostData } from '../types';
import { getCaptureFilename } from '../export/pathUtils';
import { writeBlobToDirectory } from '../../../shared/lib/localFileStorage';

function base64ToBlob(base64: string): Blob {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new Blob([bytes], { type: 'image/png' });
}

export async function saveCaptureToScreenshotDir(
  screenshotDir: FileSystemDirectoryHandle,
  post: InstagramPostData
): Promise<void> {
  if (!post.captureImageBase64 || !post.captureFilePath) {
    return;
  }
  const filename = getCaptureFilename(post.captureFilePath);
  await writeBlobToDirectory(screenshotDir, filename, base64ToBlob(post.captureImageBase64));
}
