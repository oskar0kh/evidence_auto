package com.evidence.instagram.parse;

import com.evidence.instagram.dto.InstagramCommentData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InstagramHtmlParserApiCommentTest {

    private final InstagramHtmlParser parser = new InstagramHtmlParser(
            new com.fasterxml.jackson.databind.ObjectMapper()
    );

    @Test
    void toComment_fromApiV1Shape() throws Exception {
        String json = """
                {
                  "pk": "18089622572620002",
                  "text": "hello",
                  "created_at": 1783904465,
                  "comment_like_count": 2,
                  "child_comment_count": 1,
                  "user": { "username": "user_a" }
                }
                """;
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        InstagramCommentData comment = parser.toComment(node, false);
        assertEquals("18089622572620002", comment.pk());
        assertEquals("user_a", comment.username());
        assertEquals("hello", comment.text());
        assertEquals(1, comment.childCommentCount());
        assertFalse(comment.isReply());
    }
}
