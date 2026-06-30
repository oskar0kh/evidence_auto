import type { DcinsidePostData } from './types';
import { writeBlobToDirectory } from './localFileStorage';

function base64ToBlob(base64: string): Blob {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new Blob([bytes], { type: 'image/png' });
}

export async function saveCapturesToDirectory(
  directory: FileSystemDirectoryHandle,
  posts: DcinsidePostData[]
): Promise<void> {
  for (const post of posts) {
    if (!post.captureImageBase64 || !post.captureFilePath) {
      throw new Error(`캡처 이미지가 없습니다: ${post.url}`);
    }
    const blob = base64ToBlob(post.captureImageBase64);
    await writeBlobToDirectory(directory, post.captureFilePath, blob);
  }
}
