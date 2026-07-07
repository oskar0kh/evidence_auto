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
  galleryName: string;
  nickname: string;
  title: string;
  content: string;
  crimeType: string;
  remarks: string;
  captureFilePath: string;
  captureImageBase64: string;
  viewCount: number;
  commentCount: number;
  comments: CommentData[];
}
