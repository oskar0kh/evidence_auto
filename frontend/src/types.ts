export interface CommentData {
  no: string;
  name: string;
  userId: string;
  ip: string;
  memo: string;
  regDate: string;
  isDelete: string;
}

export interface DcinsidePostData {
  url: string;
  postDate: string;
  nickname: string;
  title: string;
  body: string;
  writeType: string;
  content: string;
  crimeType: string;
  remarks: string;
  captureFilePath: string;
  viewCount: number;
  commentCount: number;
  postNo: string;
  comments: CommentData[];
}

export interface CrawlResponse {
  data: DcinsidePostData[];
  errors: { url: string; error: string }[];
}
