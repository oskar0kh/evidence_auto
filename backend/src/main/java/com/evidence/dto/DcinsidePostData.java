package com.evidence.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public record DcinsidePostData(
        String url,
        String postDate,
        String nickname,
        String title,
        @JsonIgnore String body,
        String content,
        String crimeType,
        String remarks,
        String captureFilePath,
        String captureImageBase64,
        int viewCount,
        int commentCount,
        @JsonIgnore String postNo,
        @JsonIgnore List<CommentData> comments
) {
}
