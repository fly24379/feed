package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.repository.MediaRepository;
import com.example.feed.repository.MediaRepository.StoredMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServiceTest {
    @TempDir
    Path tempDir;

    private final MediaRepository media = mock(MediaRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);

    @Test
    void uploadsAllowedImageAndServesItToOwner() throws Exception {
        MediaService service = new MediaService(media, permissions, tempDir.toString(), DataSize.ofMegabytes(1));
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});

        var attachment = service.upload(7, file);

        ArgumentCaptor<StoredMedia> captor = ArgumentCaptor.forClass(StoredMedia.class);
        verify(media).insert(captor.capture());
        StoredMedia stored = captor.getValue();
        assertThat(attachment.mediaType()).isEqualTo("IMAGE");
        assertThat(Files.readAllBytes(tempDir.resolve(stored.storageKey()))).containsExactly(1, 2, 3);

        when(media.find(stored.id())).thenReturn(Optional.of(stored));
        assertThat(service.content(7, stored.id()).sizeBytes()).isEqualTo(3);
    }

    @Test
    void rejectsUnsupportedContentType() {
        MediaService service = new MediaService(media, permissions, tempDir.toString(), DataSize.ofMegabytes(1));
        MockMultipartFile file = new MockMultipartFile("file", "payload.exe",
                "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> service.upload(7, file)).isInstanceOf(BadRequestException.class);
    }
}
