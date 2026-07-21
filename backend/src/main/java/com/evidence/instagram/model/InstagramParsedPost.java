package com.evidence.instagram.model;

import com.evidence.instagram.dto.InstagramCommentData;

import java.util.ArrayList;
import java.util.List;

public class InstagramParsedPost {

    private String url = "";
    private String shortcode = "";
    private String mediaPk = "";
    private String username = "";
    private String fullName = "";
    private String caption = "";
    private String captionPk = "";
    private String takenAt = "";
    private int likeCount;
    private int commentCount;
    private String commentsCursor = "";
    private boolean hasNextCommentsPage;
    private final List<InstagramCommentData> comments = new ArrayList<>();

    public String url() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url != null ? url : "";
    }

    public String shortcode() {
        return shortcode;
    }

    public void setShortcode(String shortcode) {
        this.shortcode = shortcode != null ? shortcode : "";
    }

    public String mediaPk() {
        return mediaPk;
    }

    public void setMediaPk(String mediaPk) {
        this.mediaPk = mediaPk != null ? mediaPk : "";
    }

    public String username() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username != null ? username : "";
    }

    public String fullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName != null ? fullName : "";
    }

    public String caption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption != null ? caption : "";
    }

    public String captionPk() {
        return captionPk;
    }

    public void setCaptionPk(String captionPk) {
        this.captionPk = captionPk != null ? captionPk : "";
    }

    public String takenAt() {
        return takenAt;
    }

    public void setTakenAt(String takenAt) {
        this.takenAt = takenAt != null ? takenAt : "";
    }

    public int likeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = Math.max(0, likeCount);
    }

    public int commentCount() {
        return commentCount;
    }

    public void setCommentCount(int commentCount) {
        this.commentCount = Math.max(0, commentCount);
    }

    public String commentsCursor() {
        return commentsCursor;
    }

    public void setCommentsCursor(String commentsCursor) {
        this.commentsCursor = commentsCursor != null ? commentsCursor : "";
    }

    public boolean hasNextCommentsPage() {
        return hasNextCommentsPage;
    }

    public void setHasNextCommentsPage(boolean hasNextCommentsPage) {
        this.hasNextCommentsPage = hasNextCommentsPage;
    }

    public List<InstagramCommentData> comments() {
        return comments;
    }

    public void addComment(InstagramCommentData comment) {
        if (comment == null || comment.pk() == null || comment.pk().isBlank()) {
            return;
        }
        boolean exists = comments.stream().anyMatch(c -> comment.pk().equals(c.pk()));
        if (!exists) {
            comments.add(comment);
        }
    }

    public void addComments(List<InstagramCommentData> more) {
        if (more == null) {
            return;
        }
        for (InstagramCommentData comment : more) {
            addComment(comment);
        }
    }
}
