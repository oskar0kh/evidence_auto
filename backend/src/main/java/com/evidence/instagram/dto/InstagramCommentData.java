package com.evidence.instagram.dto;

public record InstagramCommentData(
        String pk,
        String username,
        String text,
        String timestamp,
        int likeCount,
        boolean isReply,
        int childCommentCount
) {
    public InstagramCommentData(
            String pk,
            String username,
            String text,
            String timestamp,
            int likeCount,
            boolean isReply
    ) {
        this(pk, username, text, timestamp, likeCount, isReply, 0);
    }
}
