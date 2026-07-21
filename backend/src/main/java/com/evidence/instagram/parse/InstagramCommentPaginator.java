package com.evidence.instagram.parse;

import com.evidence.instagram.model.InstagramParsedPost;
import com.evidence.instagram.service.InstagramCommentService;
import org.springframework.stereotype.Component;

/**
 * 하위 호환용 래퍼. 실제 로직은 {@link InstagramCommentService}에 있다.
 */
@Component
public class InstagramCommentPaginator {

    private final InstagramCommentService commentService;

    public InstagramCommentPaginator(InstagramCommentService commentService) {
        this.commentService = commentService;
    }

    public void fetchAllComments(InstagramParsedPost post) {
        commentService.collectComments(post);
    }

    @Deprecated
    public void fetchRemainingComments(InstagramParsedPost post) {
        commentService.collectComments(post);
    }
}
