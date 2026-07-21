package com.evidence.instagram.parse;

import com.evidence.instagram.model.InstagramParsedPost;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstagramHtmlParserTest {

    private final InstagramHtmlParser parser = new InstagramHtmlParser(new ObjectMapper());

    @Test
    void parsesPostAndCommentsFromPrefetchJson() {
        String html = """
                <html><head>
                <meta property="og:description" content="other_user - July 1, 2026: &quot;fallback caption&quot;"/>
                </head><body>
                <script type="application/json">
                {
                  "require": [[
                    "ScheduledServerJS", "handle", null, [{
                      "__bbox": {
                        "require": [[
                          "RelayPrefetchedStreamCache", "next", [], [
                            "adp_PolarisPostRootQueryRelayPreloader_test",
                            {
                              "__bbox": {
                                "result": {
                                  "data": {
                                    "xdt_api__v1__media__shortcode__web_info": {
                                      "items": [{
                                        "code": "AbCdEfGhIjK",
                                        "pk": "1234567890",
                                        "taken_at": 1783859579,
                                        "like_count": 10,
                                        "comment_count": 2,
                                        "user": {
                                          "username": "test_user",
                                          "full_name": "\\ud14c\\uc2a4\\ud2b8 \\uc720\\uc800"
                                        },
                                        "caption": {
                                          "pk": "111",
                                          "text": "\\ub610 \\ub538\\uc5d0\\uac8c \\ud63c\\ub098\\uba70"
                                        }
                                      }]
                                    }
                                  }
                                }
                              }
                            }
                          ]
                        ]]
                      }
                    }]
                  ]]
                }
                </script>
                <script type="application/json">
                {
                  "require": [[
                    "ScheduledServerJS", "handle", null, [{
                      "__bbox": {
                        "require": [[
                          "RelayPrefetchedStreamCache", "next", [], [
                            "adp_PolarisPostCommentsContainerQueryRelayPreloader_test",
                            {
                              "__bbox": {
                                "result": {
                                  "data": {
                                    "xdt_api__v1__media__media_id__comments__connection": {
                                      "edges": [{
                                        "node": {
                                          "pk": "999",
                                          "text": "\\ud560\\uc544\\ubc84\\uc9c0 \\ud667\\ud305",
                                          "created_at": 1783904465,
                                          "comment_like_count": 3,
                                          "user": { "username": "commenter1" },
                                          "__typename": "XDTCommentDict"
                                        }
                                      }],
                                      "page_info": {
                                        "end_cursor": null,
                                        "has_next_page": false
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          ]
                        ]]
                      }
                    }]
                  ]]
                }
                </script>
                </body></html>
                """;

        InstagramParsedPost post = parser.parse(
                "https://www.instagram.com/p/AbCdEfGhIjK/",
                "AbCdEfGhIjK",
                html
        );

        assertEquals("test_user", post.username());
        assertEquals("테스트 유저", post.fullName());
        assertEquals("또 딸에게 혼나며", post.caption());
        assertEquals("1234567890", post.mediaPk());
        assertEquals(1, post.comments().size());
        assertEquals("commenter1", post.comments().getFirst().username());
        assertEquals("할아버지 홧팅", post.comments().getFirst().text());
        assertFalse(post.hasNextCommentsPage());
        assertTrue(post.takenAt().startsWith("2026-"));
    }
}
