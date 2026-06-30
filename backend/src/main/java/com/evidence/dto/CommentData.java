package com.evidence.dto;

public record CommentData(
        String no,
        String name,
        String userId,
        String ip,
        String memo,
        String regDate,
        String isDelete
) {
}
