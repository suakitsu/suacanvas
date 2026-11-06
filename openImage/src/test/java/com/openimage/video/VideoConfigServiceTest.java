package com.openimage.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.SystemSettingMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoConfigServiceTest {
    @Test
    void developmentDefaultsStayMockButExposeConfiguredPrices() {
        VideoProperties properties = new VideoProperties();
        properties.setMockEnabled(true);
        properties.setPaidProviderEnabled(false);
        properties.setAgnesEnabled(false);

        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"seedance_enabled":true,"agnes_route_enabled":true}
            """, properties);

        assertThat(service.providerFor("standard", "standard-task")).isIn("agnes", "gemini");
        assertThat(service.providerFor("quality", "quality-task")).isEqualTo("seedance");
        assertThat(service.publicConfig().mockMode()).isTrue();
        assertThat(service.publicConfig().standard().price()).isEqualTo(900);
        assertThat(service.publicConfig().quality().price()).isEqualTo(1200);
        assertThat(service.billableCreditsFor("standard", "mock")).isZero();
    }

    @Test
    void publicModelMatrixUsesFinalPricesAndMockNeverBills() {
        VideoProperties properties = new VideoProperties();
        properties.setMockEnabled(true);
        properties.setPaidProviderEnabled(false);
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"seedance_enabled":true}
            """, properties);

        assertThat(service.publicConfig().defaultModel()).isEqualTo("gemini-omni");
        assertThat(service.publicConfig().modelConfigs()).hasSize(5);
        VideoConfigService.ModelSelection omni = service.selectModel("gemini-omni", "720p", 10, "16:9");
        VideoConfigService.ModelSelection mini = service.selectModel("seedance-mini", "720p", 10, "1:1");
        VideoConfigService.ModelSelection full = service.selectModel("seedance-2", "4K", 15, "adaptive");

        assertThat(omni.model()).isEqualTo("gemini-omni-flash-preview");
        assertThat(omni.price()).isEqualTo(950);
        assertThat(mini.model()).isEqualTo("doubao-seedance-2.0-mini");
        assertThat(mini.price()).isEqualTo(610);
        assertThat(full.model()).isEqualTo("doubao-seedance-2.0");
        assertThat(full.price()).isEqualTo(11550);
        assertThat(service.billableCreditsFor(full)).isZero();
    }

    @Test
    void agnesIsAnExplicitSingleImageModelWithFixedDocumentedSpec() {
        VideoProperties properties = new VideoProperties();
        properties.setMockEnabled(true);
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"agnes_route_enabled":true,
             "agnes_route_percent":0,"agnes_daily_limit":40}
            """, properties);

        VideoConfigService.ModelSelection agnes = service.selectModel(
            "agnes-v2", "720p", 10, "3:4"
        );

        assertThat(agnes.provider()).isEqualTo("agnes");
        assertThat(agnes.model()).isEqualTo("agnes-video-v2.0");
        assertThat(agnes.price()).isEqualTo(100);
        assertThat(agnes.durationSeconds()).isEqualTo(10);
        assertThat(agnes.maxReferenceImages()).isEqualTo(1);
        assertThat(agnes.audioToggle()).isFalse();
    }

    @Test
    void modelSelectionRejectsUnsupportedOrDisabledVariants() {
        VideoProperties properties = new VideoProperties();
        properties.setMockEnabled(true);
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"seedance_enabled":true,
             "model_configs":[
               {"code":"seedance-mini","enabled":true,"variants":[
                 {"resolution":"720p","duration_seconds":10,"price":610,"enabled":false}
               ]}
             ]}
            """, properties);

        assertThatThrownBy(() -> service.selectModel("seedance-mini", "1080p", 10, "9:16"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持");
        assertThatThrownBy(() -> service.selectModel("seedance-mini", "720p", 10, "9:16"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("关闭");
        assertThatThrownBy(() -> service.selectModel("gemini-omni", "720p", 10, "1:1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("画面比例");
    }

    @Test
    void mockGatewayPreservesTheUsersSelectedSeedanceModel() {
        VideoConfigService config = mock(VideoConfigService.class);
        VideoProviderGateway gateway = new VideoProviderGateway(new VideoProperties(), config, new ObjectMapper());
        VideoTaskEntity task = new VideoTaskEntity();
        task.setModel("doubao-seedance-2.0-mini");

        VideoProviderGateway.Submission submission = gateway.mockSubmission(task, "seedance");

        assertThat(submission.provider()).isEqualTo("seedance");
        assertThat(submission.model()).isEqualTo("doubao-seedance-2.0-mini");
        assertThat(submission.upstreamTaskId()).startsWith("mock_");
    }

    @Test
    void apimartEnvironmentLockBlocksRealSubmissionBeforeAnyNetworkCall() {
        VideoProperties properties = new VideoProperties();
        properties.setPaidProviderEnabled(false);
        VideoConfigService config = mock(VideoConfigService.class);
        when(config.apimartApiKey()).thenReturn("configured-but-locked");
        VideoProviderGateway gateway = new VideoProviderGateway(properties, config, new ObjectMapper());
        VideoTaskEntity task = new VideoTaskEntity();
        task.setModel("gemini-omni-flash-preview");

        assertThatThrownBy(() -> gateway.submit(task, "gemini", List.of("https://example.com/product.jpg")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未由后端环境启用");
    }

    @Test
    void realRoutesAreFixedAndNoProviderHasAnAutomaticFallback() {
        VideoProperties properties = new VideoProperties();
        properties.setPaidProviderEnabled(true);
        properties.setApimartApiKey("apimart-key");
        properties.setAgnesEnabled(false);

        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"seedance_enabled":true}
            """, properties);

        assertThat(service.providerFor("standard", "standard-task")).isEqualTo("gemini");
        assertThat(service.providerFor("quality", "quality-task")).isEqualTo("seedance");
        assertThat(service.fallbackAfter("agnes")).isEmpty();
        assertThat(service.fallbackAfter("gemini")).isEmpty();
        assertThat(service.fallbackAfter("seedance")).isEmpty();
        assertThat(service.fallbackAfter("hfsy")).isEmpty();
    }

    @Test
    void hfsyOnlyReceivesExactDocumentedSeedanceVariantsBeforeSubmission() {
        VideoProperties properties = new VideoProperties();
        properties.setPaidProviderEnabled(true);
        properties.setApimartApiKey("apimart-key");
        properties.setHfsyApiKey("hfsy-key");
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"seedance_enabled":true,"hfsy_enabled":true}
            """, properties);

        assertThat(service.selectModel("seedance-fast", "480p", 10, "9:16"))
            .extracting(
                VideoConfigService.ModelSelection::provider,
                VideoConfigService.ModelSelection::model,
                VideoConfigService.ModelSelection::routingReason
            )
            .containsExactly("hfsy", "sd-2.0-fast-480", "fixed_hfsy_exact_match");
        assertThat(service.selectModel("seedance-fast", "720p", 10, "16:9").model())
            .isEqualTo("sd-2.0-fast");
        assertThat(service.selectModel("seedance-2", "480p", 8, "1:1").model())
            .isEqualTo("sd-2.0-480");
        assertThat(service.selectModel("seedance-2", "720p", 15, "9:16").model())
            .isEqualTo("sd-2.0");

        assertThat(service.selectModel("seedance-mini", "480p", 10, "9:16"))
            .extracting(
                VideoConfigService.ModelSelection::provider,
                VideoConfigService.ModelSelection::model
            )
            .containsExactly("hfsy", "sd-2.0-mini");
        assertThat(service.selectModel("seedance-mini", "720p", 10, "9:16"))
            .extracting(
                VideoConfigService.ModelSelection::provider,
                VideoConfigService.ModelSelection::model
            )
            .containsExactly("hfsy", "sd-2.0-mini");
        assertThat(service.selectModel("seedance-2", "1080p", 10, "9:16").provider())
            .isEqualTo("seedance");
        assertThat(service.selectModel("seedance-fast", "720p", 10, "3:4").provider())
            .isEqualTo("seedance");
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminViewPublishesTheSameHfsyMappingsUsedByTaskRouting() {
        VideoConfigService service = service("{}", new VideoProperties());

        List<Map<String, Object>> mappings =
            (List<Map<String, Object>>) service.adminView().get("hfsy_mappings");

        assertThat(mappings).hasSize(6);
        assertThat(mappings)
            .anySatisfy(mapping -> assertThat(mapping)
                .containsEntry("model_code", "seedance-mini")
                .containsEntry("resolution", "480p")
                .containsEntry("hfsy_model", "sd-2.0-mini")
                .containsEntry("minimum_credits", 128))
            .anySatisfy(mapping -> assertThat(mapping)
                .containsEntry("model_code", "seedance-2")
                .containsEntry("resolution", "720p")
                .containsEntry("hfsy_model", "sd-2.0")
                .containsEntry("minimum_credits", 426));
    }

    @Test
    void hfsyPriceFloorProtectsTheKnownPerCallCost() {
        VideoProperties properties = new VideoProperties();
        properties.setHfsyApiKey("hfsy-key");
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"hfsy_enabled":true,
             "model_configs":[
               {"code":"seedance-mini","enabled":true,"variants":[
                 {"resolution":"720p","duration_seconds":5,"price":1,"enabled":true}
               ]}
             ]}
            """, properties);

        VideoConfigService.ModelSelection selection =
            service.selectModel("seedance-mini", "720p", 5, "9:16");

        assertThat(selection.provider()).isEqualTo("hfsy");
        assertThat(selection.model()).isEqualTo("sd-2.0-mini");
        assertThat(selection.price()).isEqualTo(128);
        assertThat(service.billableCreditsFor(selection)).isEqualTo(128);
    }

    @Test
    void paidConfigurationRejectsZeroPriceOnSaveAndTaskCreation() {
        VideoProperties properties = new VideoProperties();
        properties.setPaidProviderEnabled(true);
        properties.setApimartApiKey("apimart-key");
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"seedance_enabled":true,
             "standard_price":0,"quality_price":1200}
            """, properties);

        VideoDtos.AdminConfigUpdate unchanged = update(null, null, false);

        assertThatThrownBy(() -> service.save(unchanged))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("售价");
        assertThatThrownBy(() -> service.billableCreditsFor("standard", "gemini"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("售价");
    }

    @Test
    void agnesRouteSettingsUseRequiredDefaultsAndMaximum() {
        VideoProperties properties = new VideoProperties();
        VideoConfigService defaults = service(null, properties);
        VideoConfigService bounded = service("""
            {"config_version":2,"agnes_route_percent":99,"agnes_daily_limit":40,
             "agnes_concurrency":1,"agnes_circuit_failures":3}
            """, properties);

        assertThat(defaults.current().agnesRoutePercent()).isZero();
        assertThat(defaults.current().agnesDailyLimit()).isEqualTo(40);
        assertThat(defaults.current().agnesConcurrency()).isEqualTo(1);
        assertThat(defaults.current().agnesCircuitFailures()).isEqualTo(3);
        assertThat(defaults.current().enabled()).isFalse();
        assertThat(defaults.current().paidProviderRequested()).isFalse();
        assertThat(defaults.current().geminiEnabled()).isFalse();
        assertThat(defaults.current().seedanceEnabled()).isFalse();
        assertThat(defaults.current().agnesRouteEnabled()).isFalse();
        assertThat(bounded.current().agnesRoutePercent()).isEqualTo(25);
    }

    @Test
    void agnesBusyStateNeverChangesAssignmentToGemini() {
        VideoProperties properties = new VideoProperties();
        properties.setPaidProviderEnabled(true);
        properties.setApimartApiKey("apimart-key");
        properties.setAgnesEnabled(true);
        properties.setAgnesApiKey("agnes-key");
        VideoTaskEntity active = new VideoTaskEntity();
        active.setProvider("agnes");
        active.setStatus("generating");
        active.setProviderAttempts("[]");
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"agnes_route_enabled":true,
             "agnes_route_percent":25,"agnes_daily_limit":40,"agnes_concurrency":1}
            """, properties, List.of(active));

        String routeKey = routeKeyUnder(25);
        VideoConfigService.ProviderAssignment assignment = service.assignmentFor(
            "standard", routeKey, "商品保持静止，镜头缓慢推进"
        );

        assertThat(assignment.provider()).isEqualTo("agnes");
        assertThat(assignment.reason()).isEqualTo("agnes_selected");
    }

    @Test
    void qualityAlwaysUsesSeedanceAndUnsuitableStandardSceneUsesGemini() {
        VideoProperties properties = new VideoProperties();
        properties.setMockEnabled(true);
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"seedance_enabled":true,
             "agnes_route_enabled":true,"agnes_route_percent":25}
            """, properties);

        assertThat(service.assignmentFor("quality", "any", "任意场景").provider()).isEqualTo("seedance");
        VideoConfigService.ProviderAssignment standard = service.assignmentFor(
            "standard", routeKeyUnder(25), "快速切换多个镜头并替换文字"
        );
        assertThat(standard.provider()).isEqualTo("gemini");
        assertThat(standard.reason()).isEqualTo("agnes_scene_unsuitable");
    }

    @Test
    void adminAgnesKeyIsEncryptedNeverReturnedAndCustomModelIsAssigned() {
        VideoProperties properties = new VideoProperties();
        properties.setMockEnabled(true);
        properties.setConfigEncryptionKey("unit-test-video-encryption-secret");
        AtomicReference<SystemSettingEntity> stored = new AtomicReference<>();
        VideoConfigService service = statefulService("""
            {"config_version":2,"enabled":true,"agnes_route_enabled":true,"agnes_route_percent":25}
            """, properties, stored);

        service.save(update("agnes-video-v2.1-custom", "sk-super-secret-value", false));

        String persisted = stored.get().getSettingValue();
        assertThat(persisted).contains("agnes_api_key_encrypted", "v1:");
        assertThat(persisted).doesNotContain("sk-super-secret-value");
        assertThat(service.agnesApiKey()).isEqualTo("sk-super-secret-value");
        assertThat(service.agnesModel()).isEqualTo("agnes-video-v2.1-custom");
        Map<String, Object> view = service.adminView();
        assertThat(view).containsEntry("agnes_key_set", true)
            .containsEntry("agnes_stored_key_set", true)
            .containsEntry("agnes_key_source", "admin")
            .containsEntry("agnes_model", "agnes-video-v2.1-custom")
            .doesNotContainKeys("agnes_api_key", "agnes_api_key_encrypted");
        assertThat(service.assignmentFor("standard", routeKeyUnder(25), "商品缓慢旋转").model())
            .isEqualTo("agnes-video-v2.1-custom");
    }

    @Test
    void blankKeyPreservesStoredSecretAndClearFallsBackToEnvironment() {
        VideoProperties properties = new VideoProperties();
        properties.setConfigEncryptionKey("unit-test-video-encryption-secret");
        properties.setAgnesApiKey("environment-key");
        AtomicReference<SystemSettingEntity> stored = new AtomicReference<>();
        VideoConfigService service = statefulService("""
            {"config_version":2,"enabled":true,"agnes_route_enabled":true}
            """, properties, stored);

        service.save(update("agnes-video-v2.0", "stored-key", false));
        service.save(update("agnes-video-v2.0", "", false));
        assertThat(service.agnesApiKey()).isEqualTo("stored-key");

        service.save(update("agnes-video-v2.0", "", true));
        assertThat(service.agnesApiKey()).isEqualTo("environment-key");
        assertThat(service.adminView()).containsEntry("agnes_stored_key_set", false)
            .containsEntry("agnes_key_source", "environment");
    }

    @Test
    void sharedApimartKeyIsEncryptedAndUsedForGeminiAndSeedance() {
        VideoProperties properties = new VideoProperties();
        properties.setConfigEncryptionKey("unit-test-video-encryption-secret");
        properties.setPaidProviderEnabled(true);
        AtomicReference<SystemSettingEntity> stored = new AtomicReference<>();
        VideoConfigService service = statefulService("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,
             "gemini_enabled":true,"seedance_enabled":true}
            """, properties, stored);

        service.save(updateApimart("sk-apimart-shared-secret", false));

        String persisted = stored.get().getSettingValue();
        assertThat(persisted).contains("apimart_api_key_encrypted", "v1:")
            .doesNotContain("sk-apimart-shared-secret");
        assertThat(service.apimartApiKey()).isEqualTo("sk-apimart-shared-secret");
        assertThat(service.paidEffective()).isTrue();
        assertThat(service.providerFor("quality", "quality-task")).isEqualTo("seedance");
        assertThat(service.assignmentFor("standard", "standard-task", "字幕翻译").provider()).isEqualTo("gemini");
        assertThat(service.adminView()).containsEntry("apimart_key_set", true)
            .containsEntry("apimart_stored_key_set", true)
            .containsEntry("apimart_key_source", "admin")
            .doesNotContainKeys("apimart_api_key", "apimart_api_key_encrypted");
    }

    @Test
    void hfsyKeyIsEncryptedNotReturnedAndControlsExactRoutes() throws Exception {
        VideoProperties properties = new VideoProperties();
        properties.setConfigEncryptionKey("unit-test-video-encryption-secret");
        AtomicReference<SystemSettingEntity> stored = new AtomicReference<>();
        VideoConfigService service = statefulService("""
            {"config_version":2,"enabled":true,"hfsy_enabled":false}
            """, properties, stored);
        VideoDtos.AdminConfigUpdate update = new ObjectMapper().readValue("""
            {"hfsy_enabled":true,"hfsy_api_key":"sk-hfsy-secret"}
            """, VideoDtos.AdminConfigUpdate.class);

        service.save(update);

        assertThat(stored.get().getSettingValue())
            .contains("\"hfsy_enabled\":true", "hfsy_api_key_encrypted", "v1:")
            .doesNotContain("sk-hfsy-secret");
        assertThat(service.hfsyApiKey()).isEqualTo("sk-hfsy-secret");
        assertThat(service.adminView())
            .containsEntry("hfsy_enabled", true)
            .containsEntry("hfsy_effective", true)
            .containsEntry("hfsy_key_set", true)
            .doesNotContainKeys("hfsy_api_key", "hfsy_api_key_encrypted");
        assertThat(service.selectModel("seedance-fast", "720p", 10, "9:16").provider())
            .isEqualTo("hfsy");
    }

    @Test
    void agnesKeyPoolEncryptsEverySecretAndPinsTaskToAssignedKey() {
        VideoProperties properties = new VideoProperties();
        properties.setConfigEncryptionKey("unit-test-video-encryption-secret");
        properties.setAgnesEnabled(true);
        AtomicReference<SystemSettingEntity> stored = new AtomicReference<>();
        VideoConfigService service = statefulService("""
            {"config_version":2,"enabled":true,"agnes_route_enabled":true}
            """, properties, stored);

        service.save(updateKeys(List.of(
            new VideoDtos.AgnesKeyUpdate("key-1", "Agnes 1", true, 1, "sk-first-secret", false),
            new VideoDtos.AgnesKeyUpdate("key-2", "Agnes 2", true, 2, "sk-second-secret", false)
        )));

        String persisted = stored.get().getSettingValue();
        assertThat(persisted).contains("\"agnes_keys\"", "api_key_encrypted", "v1:")
            .doesNotContain("sk-first-secret", "sk-second-secret");
        assertThat(service.dispatchableAgnesKeys()).extracting(VideoConfigService.AgnesKeyRuntime::id)
            .containsExactly("key-1", "key-2");
        VideoTaskEntity task = new VideoTaskEntity();
        task.setMetadata("{\"agnes_key_id\":\"key-2\"}");
        assertThat(service.agnesApiKeyFor(task)).isEqualTo("sk-second-secret");
        assertThat(service.adminView().toString()).doesNotContain("sk-first-secret", "sk-second-secret");
    }

    @Test
    void onlyAgnesEnabledRoutesStandardButKeepsQualityFixedToSeedance() {
        VideoProperties properties = new VideoProperties();
        properties.setAgnesEnabled(true);
        properties.setAgnesApiKey("agnes-key");
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":false,"agnes_route_enabled":true,
             "gemini_enabled":true,"seedance_enabled":true}
            """, properties);

        assertThat(service.mockMode()).isFalse();
        assertThat(service.paidEffective()).isFalse();
        assertThat(service.agnesEffective()).isTrue();
        assertThat(service.assignmentFor("standard", "standard-task", "商品连续展示").provider()).isEqualTo("agnes");
        assertThat(service.assignmentFor("quality", "quality-task", "商品连续展示").provider()).isEqualTo("disabled");
        assertThat(service.billableCreditsFor("standard", "agnes")).isEqualTo(900);
        assertThat(service.fallbackAfter("agnes")).isEmpty();
    }

    @Test
    void individualModelSwitchesRouteToTheOnlyEffectiveApimartModel() {
        VideoProperties properties = new VideoProperties();
        properties.setPaidProviderEnabled(true);
        properties.setApimartApiKey("apimart-key");
        VideoConfigService geminiOnly = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,"gemini_enabled":true,
             "seedance_enabled":false,"agnes_route_enabled":false}
            """, properties);
        VideoConfigService seedanceOnly = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,"gemini_enabled":false,
             "seedance_enabled":true,"agnes_route_enabled":false}
            """, properties);

        assertThat(geminiOnly.providerFor("standard", "one")).isEqualTo("gemini");
        assertThat(geminiOnly.providerFor("quality", "two")).isEqualTo("disabled");
        assertThat(seedanceOnly.providerFor("standard", "three")).isEqualTo("disabled");
        assertThat(seedanceOnly.providerFor("quality", "four")).isEqualTo("seedance");
    }

    @Test
    void allProvidersEnabledUseDefaultTierRouting() {
        VideoProperties properties = new VideoProperties();
        properties.setPaidProviderEnabled(true);
        properties.setApimartApiKey("apimart-key");
        properties.setAgnesEnabled(true);
        properties.setAgnesApiKey("agnes-key");
        VideoConfigService service = service("""
            {"config_version":2,"enabled":true,"paid_provider_requested":true,"gemini_enabled":true,
             "seedance_enabled":true,"agnes_route_enabled":true,"agnes_route_percent":25}
            """, properties);

        assertThat(service.assignmentFor("standard", routeKeyUnder(25), "商品缓慢旋转").provider()).isEqualTo("agnes");
        assertThat(service.assignmentFor("quality", "quality-task", "商品缓慢旋转").provider()).isEqualTo("seedance");
    }

    @Test
    void agnesCircuitReopensAfterCooldownInsteadOfStayingLockedAllDay() {
        VideoProperties properties = new VideoProperties();
        properties.setAgnesEnabled(true);
        properties.setAgnesApiKey("agnes-key");
        String config = """
            {"config_version":2,"enabled":true,"agnes_route_enabled":true,
             "agnes_circuit_failures":3}
            """;

        VideoConfigService freshFailures = service(config, properties, List.of(
            failedAgnesTask("fresh-1", OffsetDateTime.now().minusMinutes(1)),
            failedAgnesTask("fresh-2", OffsetDateTime.now().minusMinutes(2)),
            failedAgnesTask("fresh-3", OffsetDateTime.now().minusMinutes(3))
        ));
        VideoConfigService cooledFailures = service(config, properties, List.of(
            failedAgnesTask("old-1", OffsetDateTime.now().minusMinutes(11)),
            failedAgnesTask("old-2", OffsetDateTime.now().minusMinutes(12)),
            failedAgnesTask("old-3", OffsetDateTime.now().minusMinutes(13))
        ));

        assertThat(freshFailures.agnesCircuitOpen()).isTrue();
        assertThat(cooledFailures.agnesCircuitOpen()).isFalse();
        assertThat(cooledFailures.assignmentFor("standard", "probe", "商品缓慢旋转").provider())
            .isEqualTo("agnes");
    }

    private String routeKeyUnder(int threshold) {
        for (int index = 0; index < 10000; index++) {
            String key = "route-" + index;
            if (Math.floorMod(key.hashCode(), 100) < threshold) return key;
        }
        throw new IllegalStateException("No deterministic route key found");
    }

    private VideoConfigService service(String storedJson, VideoProperties properties) {
        return service(storedJson, properties, List.of());
    }

    private VideoConfigService service(String storedJson, VideoProperties properties, List<VideoTaskEntity> recentTasks) {
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(recentTasks);
        if (storedJson == null) {
            when(settings.selectOne(any())).thenReturn(null);
        } else {
            SystemSettingEntity entity = new SystemSettingEntity();
            entity.setId("setting_video_runtime_config");
            entity.setSettingKey("video_runtime_config");
            entity.setSettingValue(storedJson);
            entity.setDeleted(0);
            when(settings.selectOne(any())).thenReturn(entity);
            when(settings.selectById(entity.getId())).thenReturn(entity);
        }
        return new VideoConfigService(settings, tasks, new ObjectMapper(), properties);
    }

    private VideoConfigService statefulService(
        String storedJson,
        VideoProperties properties,
        AtomicReference<SystemSettingEntity> stored
    ) {
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(List.of());
        SystemSettingEntity initial = new SystemSettingEntity();
        initial.setId("setting_video_runtime_config");
        initial.setSettingKey("video_runtime_config");
        initial.setSettingValue(storedJson);
        initial.setDeleted(0);
        stored.set(initial);
        when(settings.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(settings.selectById(any())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(settings).insert(any(SystemSettingEntity.class));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(settings).updateById(any(SystemSettingEntity.class));
        return new VideoConfigService(settings, tasks, new ObjectMapper(), properties);
    }

    private VideoTaskEntity failedAgnesTask(String id, OffsetDateTime failedAt) {
        VideoTaskEntity task = new VideoTaskEntity();
        task.setId(id);
        task.setProvider("agnes");
        task.setStatus("failed");
        task.setProviderAttempts("""
            [{"provider":"agnes","status":"failed","time":"%s"}]
            """.formatted(failedAt));
        task.setCreatedAt(failedAt);
        task.setUpdatedAt(failedAt);
        task.setCompletedAt(failedAt);
        task.setDeleted(0);
        return task;
    }

    private VideoDtos.AdminConfigUpdate update(String model, String apiKey, boolean clearApiKey) {
        return new VideoDtos.AdminConfigUpdate(
            null, null, null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null,
            model, apiKey, clearApiKey,
            null,
            null, null
        );
    }

    private VideoDtos.AdminConfigUpdate updateApimart(String apiKey, boolean clearApiKey) {
        return new VideoDtos.AdminConfigUpdate(
            null, null, null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null,
            null, null, null,
            null,
            apiKey, clearApiKey
        );
    }

    private VideoDtos.AdminConfigUpdate updateKeys(List<VideoDtos.AgnesKeyUpdate> keys) {
        return new VideoDtos.AdminConfigUpdate(
            null, null, null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null,
            null, null, null,
            keys,
            null, null
        );
    }
}
