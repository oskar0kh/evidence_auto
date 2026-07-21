package com.evidence.instagram.parse;

import com.evidence.instagram.dto.InstagramCommentData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstagramCommentParseTest {

    private final InstagramHtmlParser parser = new InstagramHtmlParser(new ObjectMapper());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseCommentsConnection_decodesUnicodeAndChildCount() throws Exception {
        String json = """
                {
                  "edges": [
                    {
                      "node": {
                        "pk": "111",
                        "text": "\\ud560\\uc544\\ubc84\\uc9c0",
                        "created_at": 1783904465,
                        "comment_like_count": 2,
                        "child_comment_count": 3,
                        "user": { "username": "user_a" },
                        "__typename": "XDTCommentDict"
                      }
                    }
                  ],
                  "page_info": {
                    "end_cursor": "CURSOR1",
                    "has_next_page": true
                  }
                }
                """;
        JsonNode connection = mapper.readTree(json);
        List<InstagramCommentData> comments = parser.parseCommentsConnection(connection);
        assertEquals(1, comments.size());
        assertEquals("111", comments.getFirst().pk());
        assertEquals("user_a", comments.getFirst().username());
        assertEquals("할아버지", comments.getFirst().text());
        assertEquals(3, comments.getFirst().childCommentCount());
        assertFalse(comments.getFirst().isReply());

        InstagramHtmlParser.PageCursor cursor = parser.readPageCursor(connection);
        assertTrue(cursor.hasNextPage());
        assertEquals("CURSOR1", cursor.endCursor());
    }
}
