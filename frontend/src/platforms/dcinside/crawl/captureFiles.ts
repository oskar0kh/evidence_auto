import type { DcinsidePostData } from '../types';
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
  post: DcinsidePostData
): Promise<void> {
  if (!post.captureImageBase64) {
    if (!post.captureFilePath) {
      throw new Error(`캡처 이미지가 없습니다: ${post.url}`);
    }
    return;
  }
  if (!post.captureFilePath) {
    throw new Error(`캡처 이미지가 없습니다: ${post.url}`);
  }
  const filename = getCaptureFilename(post.captureFilePath);
  const blob = base64ToBlob(post.captureImageBase64);
  await writeBlobToDirectory(screenshotDir, filename, blob);
}
