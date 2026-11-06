package com.openimage.modules.publicapi;

import com.openimage.storage.LocalStorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/public/files")
public class PublicFileController {
    private final LocalStorageService storage;

    public PublicFileController(LocalStorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/projects/{projectId}/{filename}")
    public ResponseEntity<Resource> projectFile(@PathVariable String projectId, @PathVariable String filename) throws Exception {
        return file(storage.publicProjectFile(projectId, filename));
    }

    @GetMapping("/avatars/{userId}/{filename}")
    public ResponseEntity<Resource> avatarFile(@PathVariable String userId, @PathVariable String filename) throws Exception {
        return file(storage.publicAvatarFile(userId, filename));
    }

    @GetMapping("/benefits/{kind}/{filename}")
    public ResponseEntity<Resource> benefitFile(@PathVariable String kind, @PathVariable String filename) throws Exception {
        return file(storage.publicBenefitFile(kind, filename));
    }

    @GetMapping("/videos/{filename}")
    public ResponseEntity<Resource> videoFile(@PathVariable String filename) throws Exception {
        return file(storage.publicVideoFile(filename));
    }

    private ResponseEntity<Resource> file(LocalStorageService.PublicFile file) {
        if (file == null || file.resource() == null) {
            return ResponseEntity.notFound().build();
        }
        String contentType = file.contentType();
        MediaType mediaType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        return ResponseEntity.ok()
            .contentType(mediaType)
            .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
            .body(file.resource());
    }
}
