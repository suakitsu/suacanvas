package com.openimage.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.mapper.SystemSettingMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRuntimeConfigServiceImageRoutingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void routesYunfeiKeyBySizeButAlwaysUsesGptImage2() throws Exception {
        AiRuntimeConfigService service = serviceWithProviders(List.of(
            provider("image_yunfei_1k", "yunfei-1k", "https://img.yunfei.best", "1K", 0, "not-allowed", "sk-1k"),
            provider("image_yunfei_hires", "yunfei-hires", "https://img.yunfei.best", "2K,4K", 0, "not-allowed", "sk-hires"),
            provider("image_hfsyapi", "hfsyapi", "https://www.hfsyapi.cn", "1K,2K,4K", 3)
        ));

        List<AiRuntimeConfigService.EffectiveImageConfig> oneK =
            service.imageProviderChainForTask("image_yunfei_1k", "yunfei-1k", "gpt-image-2", "1024x1024");
        List<AiRuntimeConfigService.EffectiveImageConfig> twoK =
            service.imageProviderChainForTask("image_yunfei_1k", "yunfei-1k", "gpt-image-2", "2048x2048");
        List<AiRuntimeConfigService.EffectiveImageConfig> fourK =
            service.imageProviderChainForTask("image_yunfei_1k", "yunfei-1k", "gpt-image-2", "2880x2880");

        assertYunfeiGptImage2(oneK, "image_yunfei_1k", "sk-1k");
        assertYunfeiGptImage2(twoK, "image_yunfei_hires", "sk-hires");
        assertYunfeiGptImage2(fourK, "image_yunfei_hires", "sk-hires");
        assertEquals(List.of("image_yunfei_1k", "image_hfsyapi"), ids(oneK));
        assertEquals(List.of("image_yunfei_hires", "image_hfsyapi"), ids(twoK));
        assertEquals(List.of("image_yunfei_hires", "image_hfsyapi"), ids(fourK));
    }

    @Test
    void doesNotFallbackHiresRequestToOneKOnlyProvider() throws Exception {
        AiRuntimeConfigService service = serviceWithProviders(List.of(
            provider("image_yunfei_1k", "yunfei-1k", "https://img.yunfei.best", "1K", 0, "gpt-image-2", "sk-1k"),
            provider("image_yunfei_hires", "yunfei-hires", "https://img.yunfei.best", "2K,4K", 0, "gpt-image-2", "sk-hires"),
            provider("image_other_1k", "other-1k", "https://one.example", "1K", 1),
            provider("image_other_hires", "other-hires", "https://hires.example", "2K,4K", 2)
        ));

        List<AiRuntimeConfigService.EffectiveImageConfig> chain =
            service.imageProviderChainForTask("image_yunfei_1k", "yunfei-1k", "gpt-image-2", "2048x2048");

        assertEquals("image_yunfei_hires", ids(chain).get(0));
        assertTrue(ids(chain).contains("image_other_hires"));
        assertFalse(ids(chain).contains("image_yunfei_1k"));
        assertFalse(ids(chain).contains("image_other_1k"));
    }

    @Test
    void enabledLocalWorkerIsPrependedWithoutChangingHttpFallbackOrder() throws Exception {
        Map<String, Object> local = provider(
            "local-cliproxy-image",
            "local-cliproxy-image",
            "",
            "1K,2K,4K",
            -100,
            "gpt-image-2",
            ""
        );
        local.put("execution_mode", "pull_worker");
        local.put("offline_action", "fallback");
        local.put("local_wait_seconds", 25);
        local.put("lease_seconds", 180);
        local.put("max_concurrency", 1);
        AiRuntimeConfigService service = serviceWithProviders(List.of(
            local,
            provider("image_hfsyapi", "hfsyapi", "https://www.hfsyapi.cn", "1K,2K,4K", 3)
        ));

        List<AiRuntimeConfigService.EffectiveImageConfig> chain =
            service.imageProviderChainForTask("image_hfsyapi", "hfsyapi", "gpt-image-2", "1024x1024");

        assertEquals(List.of("local-cliproxy-image", "image_hfsyapi"), ids(chain));
        assertTrue(chain.get(0).pullWorker());
        assertTrue(chain.get(0).apiKey().isBlank());
        assertEquals(new BigDecimal("0.001000"), chain.get(0).actualCost());
    }

    @Test
    void disabledLocalWorkerLeavesExistingHttpRoutingUntouched() throws Exception {
        Map<String, Object> local = provider(
            "local-cliproxy-image",
            "local-cliproxy-image",
            "",
            "1K,2K,4K",
            -100,
            "gpt-image-2",
            ""
        );
        local.put("enabled", false);
        local.put("execution_mode", "pull_worker");
        AiRuntimeConfigService service = serviceWithProviders(List.of(
            local,
            provider("image_hfsyapi", "hfsyapi", "https://www.hfsyapi.cn", "1K,2K,4K", 3)
        ));

        List<AiRuntimeConfigService.EffectiveImageConfig> chain =
            service.imageProviderChainForTask("image_hfsyapi", "hfsyapi", "gpt-image-2", "1024x1024");

        assertEquals(List.of("image_hfsyapi"), ids(chain));
        assertFalse(chain.get(0).pullWorker());
    }

    @Test
    void localProviderIdsCannotBeTurnedIntoHttpProviders() throws Exception {
        Map<String, Object> localImage = provider(
            "local-cliproxy-image",
            "tampered-http-provider",
            "https://should-not-be-called.example",
            "1K,2K,4K",
            -100,
            "tampered-model",
            "must-not-be-used"
        );
        localImage.put("execution_mode", "http");
        AiRuntimeConfigService imageService = serviceWithProviders(List.of(localImage));

        AiRuntimeConfigService.EffectiveImageConfig image =
            imageService.imageProviderChainForTask("", "", "", "1024x1024").get(0);

        assertEquals("local-cliproxy-image", image.provider());
        assertEquals("gpt-image-2", image.model());
        assertTrue(image.pullWorker());
        assertTrue(image.apiKey().isBlank());
    }

    @Test
    void enabledLocalTextWorkerRunsBeforeExistingHttpTextProvider() throws Exception {
        Map<String, Object> local = textProvider(
            "local-cliproxy-text",
            "local-cliproxy-text",
            "",
            -100,
            "gpt-5.5",
            ""
        );
        local.put("execution_mode", "pull_worker");
        local.put("offline_action", "fallback");
        AiRuntimeConfigService service = serviceWithTextProviders(List.of(
            textProvider("text_existing", "deepseek", "https://text.example", 2, "deepseek-v4-flash", "sk-http"),
            local
        ));

        List<AiRuntimeConfigService.EffectiveTextConfig> chain = service.textProviderChain();

        assertEquals("local-cliproxy-text", chain.get(0).id());
        assertTrue(chain.get(0).pullWorker());
        assertEquals("text_existing", chain.get(1).id());
    }

    @Test
    void disabledLocalTextWorkerLeavesExistingHttpTextRoutingUntouched() throws Exception {
        Map<String, Object> local = textProvider(
            "local-cliproxy-text",
            "local-cliproxy-text",
            "",
            -100,
            "gpt-5.5",
            ""
        );
        local.put("enabled", false);
        local.put("execution_mode", "pull_worker");
        AiRuntimeConfigService service = serviceWithTextProviders(List.of(
            local,
            textProvider("text_existing", "deepseek", "https://text.example", 2, "deepseek-v4-flash", "sk-http")
        ));

        List<AiRuntimeConfigService.EffectiveTextConfig> chain = service.textProviderChain();

        assertEquals("text_existing", chain.get(0).id());
        assertFalse(chain.stream().anyMatch(AiRuntimeConfigService.EffectiveTextConfig::pullWorker));
    }

    @Test
    void localTextProviderUsesOnlyV1ModelCatalog() throws Exception {
        Map<String, Object> local = textProvider(
            "local-cliproxy-text",
            "tampered-http-provider",
            "https://should-not-be-called.example",
            -100,
            "unknown-model",
            "must-not-be-used"
        );
        local.put("execution_mode", "http");
        AiRuntimeConfigService service = serviceWithTextProviders(List.of(local));

        AiRuntimeConfigService.EffectiveTextConfig text = service.textProviderChain().get(0);

        assertEquals("local-cliproxy-text", text.provider());
        assertEquals("gpt-5.5", text.model());
        assertTrue(text.pullWorker());
        assertTrue(text.apiKey().isBlank());
    }

    private AiRuntimeConfigService serviceWithProviders(List<Map<String, Object>> providers) throws Exception {
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        SystemSettingEntity setting = new SystemSettingEntity();
        setting.setSettingValue(json.writeValueAsString(Map.of("image_providers", providers)));
        when(settings.selectOne(any())).thenReturn(setting);
        return new AiRuntimeConfigService(settings, json, defaults(), false, "https://api.deepseek.com", "", "deepseek-v4-flash");
    }

    private AiRuntimeConfigService serviceWithTextProviders(List<Map<String, Object>> providers) throws Exception {
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        SystemSettingEntity setting = new SystemSettingEntity();
        setting.setSettingValue(json.writeValueAsString(Map.of("text_providers", providers)));
        when(settings.selectOne(any())).thenReturn(setting);
        return new AiRuntimeConfigService(settings, json, defaults(), false, "https://api.deepseek.com", "", "deepseek-v4-flash");
    }

    private AiImageProperties defaults() {
        return new AiImageProperties(true, "hfsyapi", "https://www.hfsyapi.cn", "sk-default", "gpt-image-2", "1024x1024", "b64_json", BigDecimal.ZERO, "https://suakitsu.com", 30);
    }

    private void assertYunfeiGptImage2(List<AiRuntimeConfigService.EffectiveImageConfig> chain, String id, String apiKey) {
        assertFalse(chain.isEmpty());
        assertEquals(id, chain.get(0).id());
        assertEquals(apiKey, chain.get(0).apiKey());
        assertEquals("gpt-image-2", chain.get(0).model());
    }

    private List<String> ids(List<AiRuntimeConfigService.EffectiveImageConfig> chain) {
        return chain.stream().map(AiRuntimeConfigService.EffectiveImageConfig::id).toList();
    }

    private Map<String, Object> provider(String id, String provider, String baseUrl, String supportedSizes, int priority) {
        return provider(id, provider, baseUrl, supportedSizes, priority, "gpt-image-2");
    }

    private Map<String, Object> provider(String id, String provider, String baseUrl, String supportedSizes, int priority, String model) {
        return provider(id, provider, baseUrl, supportedSizes, priority, model, "sk-test");
    }

    private Map<String, Object> provider(String id, String provider, String baseUrl, String supportedSizes, int priority, String model, String apiKey) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", provider);
        row.put("provider", provider);
        row.put("enabled", true);
        row.put("base_url", baseUrl);
        row.put("api_key", apiKey);
        row.put("model", model);
        row.put("priority", priority);
        row.put("timeout_seconds", 30);
        row.put("supported_sizes", supportedSizes);
        return row;
    }

    private Map<String, Object> textProvider(
        String id,
        String provider,
        String baseUrl,
        int priority,
        String model,
        String apiKey
    ) {
        Map<String, Object> row = provider(id, provider, baseUrl, "", priority, model, apiKey);
        row.put("timeout_seconds", 45);
        return row;
    }
}
