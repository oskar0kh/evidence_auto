package com.evidence.instagram.service;

import com.evidence.instagram.dto.InstagramCommentData;
import com.evidence.instagram.dto.InstagramPostData;
import com.evidence.instagram.http.InstagramHttpClient;
import com.evidence.instagram.model.InstagramParsedPost;
import com.evidence.instagram.parse.InstagramGraphqlClient;
import com.evidence.instagram.parse.InstagramHtmlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class InstagramCrawlServiceTest {

    @Test
    void buildRows_numbersCommentsInContent() {
        InstagramCrawlService service = new InstagramCrawlService(
                mock(InstagramHttpClient.class),
                mock(InstagramHtmlParser.class),
                mock(InstagramGraphqlClient.class),
                mock(InstagramCommentService.class)
        );

        InstagramParsedPost post = new InstagramParsedPost();
        post.setUrl("https://www.instagram.com/p/ABC/");
        post.setShortcode("ABC");
        post.setUsername("user");
        post.setCaption("caption text");
        post.setTakenAt("2026-01-01 00:00:00");
        post.addComment(new InstagramCommentData("1", "alice", "hello", "2026-01-01 01:00:00", 0, false));
        post.addComment(new InstagramCommentData("2", "bob", "reply", "2026-01-01 02:00:00", 0, true));

        List<InstagramPostData> rows = service.buildRows(post, null);
        String content = rows.getFirst().content();

        assertTrue(content.contains("[댓글 1] alice: hello (2026-01-01 01:00:00)"));
        assertTrue(content.contains("[댓글 2] bob (대댓글): reply (2026-01-01 02:00:00)"));
    }
}
