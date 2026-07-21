export interface InstagramCommentData {
  pk: string;
  username: string;
  text: string;
  timestamp: string;
  likeCount: number;
  isReply: boolean;
  childCommentCount?: number;
}

export interface InstagramPostData {
  url: string;
  postDate: string;
  postType: string;
  nickname: string;
  title: string;
  content: string;
  crimeType: string;
  remarks: string;
  captureFilePath: string;
  captureImageBase64: string;
  commentCount: number;
  shortcode: string;
  commentPk: string;
  comments: InstagramCommentData[];
}

export const INSTAGRAM_TYPE_POST = '인스타그램 게시글';
export const INSTAGRAM_TYPE_COMMENT = '인스타그램 댓글';
