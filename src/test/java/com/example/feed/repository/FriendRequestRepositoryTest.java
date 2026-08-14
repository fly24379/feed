package com.example.feed.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FriendRequestRepositoryTest {

    @Test
    void transitionSqlSeparatesAndFromRecipientColumn() {
        assertThat(FriendRequestRepository.transitionSql(true))
                .contains("AND recipient_id = :actor")
                .doesNotContain("ANDrecipient_id");
    }

    @Test
    void transitionSqlSeparatesAndFromRequesterColumn() {
        assertThat(FriendRequestRepository.transitionSql(false))
                .contains("AND requester_id = :actor")
                .doesNotContain("ANDrequester_id");
    }
}
