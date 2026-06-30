export interface DcinsidePostData {
  url: string;
  postDate: string;
  nickname: string;
  title: string;
  writeType: string;
  content: string;
  crimeType: string;
  remarks: string;
  captureFilePath: string;
  captureImageBase64: string;
  viewCount: number;
  commentCount: number;
}

export interface CrawlResponse {
  data: DcinsidePostData[];
  errors: { url: string; error: string }[];
}
