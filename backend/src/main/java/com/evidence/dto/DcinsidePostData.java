package com.evidence.dto;

import java.util.List;

public record DcinsidePostData(
        String url,
        String postDate,
        String nickname,
        String title,
        String body,
        String writeType,
        String content,
        String crimeType,
        String remarks,
        String captureFilePath,
        int viewCount,
        int commentCount,
        String postNo,
        List<CommentData> comments
) {
}
