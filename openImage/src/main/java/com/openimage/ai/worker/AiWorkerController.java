package com.openimage.ai.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(AiWorkerAuthenticationFilter.PREFIX)
public class AiWorkerController {
    private final AiWorkerJobService jobs;
    private final ObjectMapper protocolJson;

    public AiWorkerController(AiWorkerJobService jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.protocolJson = json.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(
        HttpServletRequest request,
        @RequestBody JsonNode payload
    ) {
        HeartbeatRequest body = strictBody(payload, HeartbeatRequest.class);
        if (body.maxConcurrency() == null
            || body.maxConcurrency() < 1
            || body.maxConcurrency() > 32) {
            throw schemaError("maxConcurrency must be between 1 and 32");
        }
        validateCapabilities(body.capabilities());
        return jobs.heartbeat(
            principal(request),
            body.workerId(),
            body.capabilities(),
            body.maxConcurrency()
        );
    }

    @PostMapping("/claim")
    public Map<String, Object> claim(
        HttpServletRequest request,
        @RequestBody JsonNode payload
    ) {
        ClaimRequest body = strictBody(payload, ClaimRequest.class);
        if (body.maxTasks() == null || body.maxTasks() != 1) {
            throw schemaError("maxTasks must equal 1");
        }
        if (body.waitSeconds() == null || body.waitSeconds() < 20 || body.waitSeconds() > 30) {
            throw schemaError("waitSeconds must be between 20 and 30");
        }
        validateCapabilities(body.capabilities());
        long started = System.nanoTime();
        AiWorkerJobService.ClaimedJob claimed = jobs.claim(
            principal(request),
            body.workerId(),
            body.capabilities(),
            body.maxTasks(),
            body.waitSeconds()
        );
        Map<String, Object> response = new LinkedHashMap<>();
        // Keep the protocol-required task:null even when the application omits null properties.
        response.put("task", claimed == null ? NullNode.getInstance() : claimed);
        response.put("empty", claimed == null);
        response.put("serverTime", OffsetDateTime.now());
        response.put("waitedMs", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        return response;
    }

    @PostMapping("/jobs/{jobId}/renew")
    public Map<String, Object> renew(
        HttpServletRequest request,
        @PathVariable String jobId,
        @RequestBody JsonNode payload
    ) {
        LeaseRequest body = strictBody(payload, LeaseRequest.class);
        return jobs.renew(
            principal(request),
            jobId,
            body.workerId(),
            body.leaseId()
        );
    }

    @PostMapping(path = "/jobs/{jobId}/artifacts", consumes = "multipart/form-data")
    public Map<String, Object> uploadArtifact(
        HttpServletRequest request,
        @PathVariable String jobId,
        @RequestParam("workerId") String workerId,
        @RequestParam("leaseId") String leaseId,
        @RequestParam("idempotencyKey") String idempotencyKey,
        @RequestParam("sha256") String sha256,
        @RequestPart("file") MultipartFile file
    ) {
        return jobs.uploadArtifact(
            principal(request),
            jobId,
            workerId,
            leaseId,
            idempotencyKey,
            sha256,
            file
        );
    }

    @PostMapping("/jobs/{jobId}/complete")
    public Map<String, Object> complete(
        HttpServletRequest request,
        @PathVariable String jobId,
        @RequestBody JsonNode payload
    ) {
        CompleteRequest body = strictBody(payload, CompleteRequest.class);
        boolean hasArtifact = body.artifactId() != null && !body.artifactId().isBlank();
        boolean hasResult = body.result() != null;
        if (hasArtifact == hasResult) {
            throw schemaError("Exactly one of artifactId or result is required");
        }
        return jobs.complete(
            principal(request),
            jobId,
            body.workerId(),
            body.leaseId(),
            body.idempotencyKey(),
            body.artifactId(),
            body.result(),
            body.metadata()
        );
    }

    @PostMapping("/jobs/{jobId}/fail")
    public Map<String, Object> fail(
        HttpServletRequest request,
        @PathVariable String jobId,
        @RequestBody JsonNode payload
    ) {
        FailRequest body = strictBody(payload, FailRequest.class);
        if (body.errorCode() == null
            || body.errorCode().isBlank()
            || body.errorCode().length() > 80
            || body.errorMessage() == null
            || body.errorMessage().length() > 500) {
            throw schemaError("errorCode and errorMessage must satisfy protocol limits");
        }
        return jobs.fail(
            principal(request),
            jobId,
            body.workerId(),
            body.leaseId(),
            body.idempotencyKey(),
            body.errorCode(),
            body.errorMessage()
        );
    }

    @GetMapping("/inputs/{jobId}/{inputIndex}")
    public ResponseEntity<Void> input(
        HttpServletRequest request,
        @PathVariable String jobId,
        @PathVariable int inputIndex,
        @RequestParam String workerId,
        @RequestParam long expires,
        @RequestParam String signature
    ) {
        URI location = jobs.signedInputRedirect(
            principal(request),
            jobId,
            inputIndex,
            workerId,
            expires,
            signature
        );
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .location(location)
            .build();
    }

    private AiWorkerPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(AiWorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        if (value instanceof AiWorkerPrincipal principal) {
            return principal;
        }
        throw new AiWorkerProtocolException(
            HttpStatus.UNAUTHORIZED,
            "WORKER_TOKEN_INVALID",
            "Worker authentication is required"
        );
    }

    private void validateCapabilities(List<String> capabilities) {
        if (capabilities == null
            || capabilities.isEmpty()
            || capabilities.size() != capabilities.stream().distinct().count()
            || capabilities.stream().anyMatch(value -> !"image".equals(value) && !"text".equals(value))) {
            throw schemaError("capabilities must contain unique image/text values");
        }
    }

    private <T> T strictBody(JsonNode payload, Class<T> type) {
        if (payload == null || payload.isNull() || !payload.isObject()) {
            throw schemaError("Request body must be a JSON object");
        }
        try {
            return protocolJson.treeToValue(payload, type);
        } catch (Exception exception) {
            throw schemaError("Request body does not match Local Worker Protocol v1");
        }
    }

    private AiWorkerProtocolException schemaError(String message) {
        return new AiWorkerProtocolException(
            HttpStatus.BAD_REQUEST,
            "REQUEST_SCHEMA_INVALID",
            message
        );
    }

    public record HeartbeatRequest(
        @JsonProperty("workerId") String workerId,
        List<String> capabilities,
        @JsonProperty("maxConcurrency") Integer maxConcurrency
    ) {
    }

    public record ClaimRequest(
        @JsonProperty("workerId") String workerId,
        List<String> capabilities,
        @JsonProperty("maxTasks") Integer maxTasks,
        @JsonProperty("waitSeconds") Integer waitSeconds
    ) {
    }

    public record LeaseRequest(
        @JsonProperty("workerId") String workerId,
        @JsonProperty("leaseId") String leaseId
    ) {
    }

    public record CompleteRequest(
        @JsonProperty("workerId") String workerId,
        @JsonProperty("leaseId") String leaseId,
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("artifactId") String artifactId,
        Object result,
        Map<String, Object> metadata
    ) {
    }

    public record FailRequest(
        @JsonProperty("workerId") String workerId,
        @JsonProperty("leaseId") String leaseId,
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("errorCode") String errorCode,
        @JsonProperty("errorMessage") String errorMessage
    ) {
    }
}
