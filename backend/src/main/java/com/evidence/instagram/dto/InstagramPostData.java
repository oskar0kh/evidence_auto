package com.evidence.instagram.dto;

import java.util.List;

public record InstagramPostData(
        String url,
        String postDate,
        String postType,
        String nickname,
        String title,
        String content,
        String crimeType,
        String remarks,
        String captureFilePath,
        String captureImageBase64,
        int commentCount,
        String shortcode,
        String commentPk,
        List<InstagramCommentData> comments
) {
    public static final String TYPE_POST = "인스타그램 게시글";
    public static final String TYPE_COMMENT = "인스타그램 댓글";
}
