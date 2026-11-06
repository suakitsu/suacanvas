package com.openimage.modules.publicapi;

import com.openimage.ai.worker.CanvasWorkerInputService;
import com.openimage.storage.LocalStorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/canvas-worker-inputs")
public class CanvasWorkerInputController {
    private final CanvasWorkerInputService inputs;

    public CanvasWorkerInputController(CanvasWorkerInputService inputs) {
        this.inputs = inputs;
    }

    @GetMapping("/{scope}/{filename}")
    public ResponseEntity<Resource> input(
        @PathVariable String scope,
        @PathVariable String filename,
        @RequestParam long expires,
        @RequestParam String signature
    ) throws Exception {
        LocalStorageService.PublicFile file = inputs.open(scope, filename, expires, signature);
        String contentType = file.contentType();
        MediaType mediaType = contentType == null
            ? MediaType.APPLICATION_OCTET_STREAM
            : MediaType.parseMediaType(contentType);
        return ResponseEntity.ok()
            .contentType(mediaType)
            .cacheControl(CacheControl.noStore())
            .body(file.resource());
    }
}
