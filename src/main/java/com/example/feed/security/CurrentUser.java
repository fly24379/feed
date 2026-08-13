package com.example.feed.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
