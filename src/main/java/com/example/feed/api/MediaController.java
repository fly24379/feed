package com.example.feed.api;

import com.example.feed.domain.MediaAttachment;
import com.example.feed.security.CurrentUser;
import com.example.feed.service.MediaService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/media")
public class MediaController {
    private final MediaService media;
    private final CurrentUser currentUser;

    public MediaController(MediaService media, CurrentUser currentUser) {
        this.media = media;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaAttachment upload(@AuthenticationPrincipal Jwt jwt,
                                  @RequestPart("file") MultipartFile file) {
        return media.upload(currentUser.id(jwt), file);
    }

    @PostMapping("/uploads")
    public MediaService.UploadTicket initiateUpload(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestBody MediaService.InitiateUploadRequest request) {
        return media.initiateUpload(currentUser.id(jwt), request);
    }

    @PostMapping("/{mediaId}/confirm")
    public MediaAttachment confirmUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaId) {
        return media.confirmUpload(currentUser.id(jwt), mediaId);
    }

    @GetMapping("/{mediaId}/access")
    public MediaService.MediaAccess access(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaId,
                                           @RequestParam(defaultValue = "ORIGINAL") String variant) {
        return media.access(currentUser.id(jwt), mediaId, variant);
    }

    @GetMapping("/{mediaId}/content")
    public ResponseEntity<Resource> content(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String mediaId) {
        MediaService.MediaContent content = media.content(currentUser.id(jwt), mediaId);
        return response(content);
    }

    @GetMapping("/{mediaId}/preview")
    public ResponseEntity<Resource> preview(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String mediaId) {
        MediaService.MediaContent content = media.content(currentUser.id(jwt), mediaId, "PREVIEW");
        return response(content);
    }

    private ResponseEntity<Resource> response(MediaService.MediaContent content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(content.filename(), StandardCharsets.UTF_8).build().toString())
                .body(content.resource());
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaId) {
        media.deleteUnattached(currentUser.id(jwt), mediaId);
        return ResponseEntity.noContent().build();
    }
}
