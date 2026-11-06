package com.openimage.modules.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageClient;
import com.openimage.ai.AiRuntimeConfigService;
import com.openimage.ai.AiTextCallAuditService;
import com.openimage.ai.AiTextService;
import com.openimage.domain.entity.CreativeChatVersionEntity;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ProductImageEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.CreativeChatMapper;
import com.openimage.domain.mapper.CreativeChatMessageMapper;
import com.openimage.domain.mapper.CreativeChatVersionMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.domain.mapper.ImageTypeConfigMapper;
import com.openimage.domain.mapper.ProductImageMapper;
import com.openimage.domain.mapper.ProductProjectMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.risk.RiskControlService;
import com.openimage.storage.LocalStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceCreativeReferenceTest {
    private CreativeChatVersionMapper versions;
    private ProductImageMapper productImages;
    private ImageTaskMapper tasks;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        versions = mock(CreativeChatVersionMapper.class);
        productImages = mock(ProductImageMapper.class);
        tasks = mock(ImageTaskMapper.class);
        service = new ProjectService(
            mock(ProductProjectMapper.class),
            mock(CreativeChatMapper.class),
            mock(CreativeChatMessageMapper.class),
            versions,
            tasks,
            mock(ImageTypeConfigMapper.class),
            productImages,
            mock(UserMapper.class),
            mock(CreditLedgerMapper.class),
            new ObjectMapper(),
            mock(LocalStorageService.class),
            mock(AiTextService.class),
            mock(AiTextCallAuditService.class),
            mock(AiImageClient.class),
            mock(RiskControlService.class),
            mock(AiRuntimeConfigService.class),
            mock(TransactionTemplate.class)
        );
    }

    @Test
    void resolvesOnlyAssetsOwnedByTheCurrentChatAndPreservesComposerOrder() {
        when(versions.selectList(any())).thenReturn(List.of(version("chat_1", "user_1", "project_1")));
        when(productImages.selectList(any())).thenReturn(List.of(productReference("/api/public/files/projects/reference.jpg")));
        when(tasks.selectList(any())).thenReturn(List.of(successfulResult("/api/public/files/generated/result.png")));

        List<ProjectService.OwnedCreativeReference> resolved = service.resolveOwnedCreativeReferences(
            "user_1",
            "chat_1",
            List.of(
                "/api/public/files/generated/result.png",
                "/api/public/files/projects/reference.jpg",
                "/api/public/files/generated/result.png"
            )
        );

        assertThat(resolved)
            .extracting(ProjectService.OwnedCreativeReference::url)
            .containsExactly(
                "/api/public/files/generated/result.png",
                "/api/public/files/projects/reference.jpg"
            );
    }

    @Test
    void rejectsUnknownOrTamperedReferenceUrl() {
        when(versions.selectList(any())).thenReturn(List.of(version("chat_1", "user_1", "project_1")));
        when(productImages.selectList(any())).thenReturn(List.of());
        when(tasks.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveOwnedCreativeReferences(
            "user_1",
            "chat_1",
            List.of("https://attacker.example/forged.jpg")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("引用图片不存在或无权访问");
    }

    @Test
    void resolvesAbsolutePicPilotMediaUrlsAgainstStoredRelativeUrls() {
        when(versions.selectList(any())).thenReturn(List.of(version("chat_1", "user_1", "project_1")));
        when(productImages.selectList(any())).thenReturn(List.of(productReference("/api/public/files/projects/reference.jpg")));
        when(tasks.selectList(any())).thenReturn(List.of(successfulResult("/api/public/files/generated/result.png")));

        List<ProjectService.OwnedCreativeReference> resolved = service.resolveOwnedCreativeReferences(
            "user_1",
            "chat_1",
            List.of(
                "https://suakitsu.com/api/public/files/projects/reference.jpg",
                "https://suakitsu.com/api/public/files/generated/result.png"
            )
        );

        assertThat(resolved)
            .extracting(ProjectService.OwnedCreativeReference::url)
            .containsExactly(
                "/api/public/files/projects/reference.jpg",
                "/api/public/files/generated/result.png"
            );
    }

    @Test
    void rejectsHistoryReferenceWithoutAnExistingChat() {
        assertThatThrownBy(() -> service.resolveOwnedCreativeReferences(
            "user_1",
            "",
            List.of("/api/public/files/generated/result.png")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("引用图片不存在或无权访问");
    }

    @Test
    void copiesResolvedReferencesIntoTheNewProjectInSelectionOrder() {
        List<ProjectService.OwnedCreativeReference> references = List.of(
            new ProjectService.OwnedCreativeReference(
                "/api/public/files/generated/result.png",
                "/api/public/files/generated/result.png",
                "generated-image",
                "image/url",
                "",
                0L,
                null,
                null
            ),
            new ProjectService.OwnedCreativeReference(
                "/api/public/files/projects/reference.jpg",
                "storage/reference.jpg",
                "reference.jpg",
                "image/jpeg",
                "jpeg",
                1234L,
                800,
                1200
            )
        );

        service.storeOwnedCreativeReferences("user_1", "project_new", references);

        ArgumentCaptor<ProductImageEntity> captor = ArgumentCaptor.forClass(ProductImageEntity.class);
        verify(productImages, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(ProductImageEntity::getUrl)
            .containsExactly(
                "/api/public/files/generated/result.png",
                "/api/public/files/projects/reference.jpg"
            );
        assertThat(captor.getAllValues()).allSatisfy(image -> {
            assertThat(image.getProjectId()).isEqualTo("project_new");
            assertThat(image.getUserId()).isEqualTo("user_1");
            assertThat(image.getImageType()).isEqualTo("reference_history");
            assertThat(image.getDeleted()).isZero();
        });
        assertThat(captor.getAllValues().get(1).getWidth()).isEqualTo(800);
        assertThat(captor.getAllValues().get(1).getContentType()).isEqualTo("image/jpeg");
    }

    private CreativeChatVersionEntity version(String chatId, String userId, String projectId) {
        CreativeChatVersionEntity version = new CreativeChatVersionEntity();
        version.setChatId(chatId);
        version.setUserId(userId);
        version.setProjectId(projectId);
        version.setDeleted(0);
        return version;
    }

    private ProductImageEntity productReference(String url) {
        ProductImageEntity image = new ProductImageEntity();
        image.setUrl(url);
        image.setStoragePath("storage/reference.jpg");
        image.setOriginalFilename("reference.jpg");
        image.setContentType("image/jpeg");
        image.setImageFormat("jpeg");
        image.setFileSize(1234L);
        image.setWidth(800);
        image.setHeight(1200);
        return image;
    }

    private ImageTaskEntity successfulResult(String url) {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setResultUrl(url);
        task.setStatus("success");
        return task;
    }
}
