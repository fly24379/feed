package com.example.feed.service;

import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import com.example.feed.repository.FanoutRepository;
import com.example.feed.repository.PostRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutBackfillBatchServiceTest {
    private final FanoutBackfillJobRepository jobs = mock(FanoutBackfillJobRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final FanoutRepository fanout = mock(FanoutRepository.class);
    private final AuthorTimelineCache timeline = mock(AuthorTimelineCache.class);
    private final FanoutBackfillBatchService service = new FanoutBackfillBatchService(
            jobs, posts, fanout, timeline);

    @Test
    void pullBatchPersistsCursorWithoutWritingFriendInboxes() {
        Instant second = Instant.parse("2026-08-16T00:00:02Z");
        Instant first = Instant.parse("2026-08-16T00:00:01Z");
        when(jobs.findForUpdate("job")).thenReturn(Optional.of(job(FanoutMode.PULL, 3, 0)));
        when(posts.findPostBatchForModeChange(7, FanoutMode.PULL, null, null, 2))
                .thenReturn(List.of(new PostRepository.ModeChangeCandidate("p2", second),
                        new PostRepository.ModeChangeCandidate("p1", first)));
        when(posts.updateDeliveryMode(List.of("p2", "p1"), FanoutMode.PULL)).thenReturn(2);

        var result = service.processBatch("job", "worker", 2);

        assertThat(result.completed()).isFalse();
        assertThat(result.processedPosts()).isEqualTo(2);
        verify(jobs).completeBatch("job", "worker", 2, 0, first, "p1", false);
        verify(fanout, never()).fanoutPosts(List.of("p2", "p1"));
        verify(timeline).evict(7);
    }

    @Test
    void pushBatchUpdatesModeAndInboxInOneBatch() {
        Instant publishedAt = Instant.parse("2026-08-16T00:00:01Z");
        when(jobs.findForUpdate("job")).thenReturn(Optional.of(job(FanoutMode.PUSH, 1, 0)));
        when(posts.findPostBatchForModeChange(7, FanoutMode.PUSH, null, null, 1))
                .thenReturn(List.of(new PostRepository.ModeChangeCandidate("p1", publishedAt)));
        when(posts.updateDeliveryMode(List.of("p1"), FanoutMode.PUSH)).thenReturn(1);
        when(fanout.fanoutPosts(List.of("p1"))).thenReturn(6);

        var result = service.processBatch("job", "worker", 100);

        assertThat(result.completed()).isTrue();
        assertThat(result.inboxRowsInserted()).isEqualTo(6);
        verify(jobs).completeBatch("job", "worker", 1, 6, publishedAt, "p1", true);
    }

    @Test
    void exhaustedSourceCompletesTaskAndReconcilesPlannedTotal() {
        FanoutBackfillJob job = job(FanoutMode.PULL, 10, 4);
        when(jobs.findForUpdate("job")).thenReturn(Optional.of(job));
        when(posts.findPostBatchForModeChange(7, FanoutMode.PULL,
                job.lastPublishedAt(), job.lastPostId(), 6)).thenReturn(List.of());

        var result = service.processBatch("job", "worker", 100);

        assertThat(result.completed()).isTrue();
        verify(jobs).completeWithoutMoreCandidates("job", "worker");
    }

    private FanoutBackfillJob job(FanoutMode target, long total, long processed) {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        Instant cursor = processed == 0 ? null : now;
        String cursorId = processed == 0 ? null : "cursor";
        return new FanoutBackfillJob("job", 7,
                target == FanoutMode.PULL ? FanoutMode.PUSH : FanoutMode.PULL,
                target, FanoutBackfillStatus.RUNNING, null, null, total, processed,
                0, cursor, cursorId, 0, null, now, now, "worker", 99L,
                now, now, null, now);
    }
}
