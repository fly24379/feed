package com.example.feed.api;

import com.example.feed.security.CurrentUser;
import com.example.feed.security.SecurityConfig;
import com.example.feed.service.FeedQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(properties = {
        "feed.security.jwt.secret=test-secret-with-at-least-thirty-two-bytes",
        "feed.security.jwt.issuer=https://friend-feed.test"
}, controllers = FeedController.class)
@Import({SecurityConfig.class, CurrentUser.class})
class FeedSecurityTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FeedQueryService feed;

    @Test
    void forgedUserHeaderDoesNotAuthenticateRequest() throws Exception {
        mvc.perform(get("/api/feed").header("X-User-Id", "42"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedSubjectBecomesViewerIdentity() throws Exception {
        when(feed.getFeed(42, null, null))
                .thenReturn(new FeedQueryService.FeedPage(List.of(), null, false));

        mvc.perform(get("/api/feed").with(jwt().jwt(token -> token.subject("42"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }
}
