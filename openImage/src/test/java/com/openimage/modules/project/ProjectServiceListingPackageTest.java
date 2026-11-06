package com.openimage.modules.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageClient;
import com.openimage.ai.AiRuntimeConfigService;
import com.openimage.ai.AiTextCallAuditService;
import com.openimage.ai.AiTextService;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.CreativeChatMapper;
import com.openimage.domain.mapper.CreativeChatMessageMapper;
import com.openimage.domain.mapper.CreativeChatVersionMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.domain.mapper.ImageTypeConfigMapper;
import com.openimage.domain.mapper.ProductImageMapper;
import com.openimage.domain.mapper.ProductProjectMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.modules.project.dto.ProjectDtos;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import com.openimage.storage.LocalStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceListingPackageTest {
    private final CurrentUser user = new CurrentUser("user-1", "user@example.com", "user");
    private final ObjectMapper json = new ObjectMapper();
    private ProductProjectMapper projects;
    private ProjectService service;
    private ProductProjectEntity source;

    @BeforeEach
    void setUp() throws Exception {
        projects = mock(ProductProjectMapper.class);
        service = spy(new ProjectService(
            projects,
            mock(CreativeChatMapper.class),
            mock(CreativeChatMessageMapper.class),
            mock(CreativeChatVersionMapper.class),
            mock(ImageTaskMapper.class),
            mock(ImageTypeConfigMapper.class),
            mock(ProductImageMapper.class),
            mock(UserMapper.class),
            mock(CreditLedgerMapper.class),
            json,
            mock(LocalStorageService.class),
            mock(AiTextService.class),
            mock(AiTextCallAuditService.class),
            mock(AiImageClient.class),
            mock(RiskControlService.class),
            mock(AiRuntimeConfigService.class),
            mock(TransactionTemplate.class)
        ));
        source = new ProductProjectEntity();
        source.setId("project-1");
        source.setUserId(user.id());
        source.setLanguage("zh-CN");
        source.setOriginalInfo("{}");
        source.setDeleted(0);
        when(projects.lockById(source.getId())).thenReturn(source);
        doReturn(List.of()).when(service).enqueueSevenImages(user, source.getId());
    }

    @Test
    void persistsFinalAplusChoiceAndEnqueuesBothPartsOnce() throws Exception {
        doReturn(null).when(service).createLinkedAplusProject(any(), any(), any());

        ProjectDtos.ListingPackageResponse response = service.enqueueListingPackage(
            user,
            source.getId(),
            new ProjectDtos.ListingPackageRequest(true, "核心卖点模块", "970x600", "突出三个卖点", "zh-CN")
        );

        ArgumentCaptor<ProductProjectEntity> projectCaptor = ArgumentCaptor.forClass(ProductProjectEntity.class);
        verify(projects).updateById(projectCaptor.capture());
        assertThat(json.readTree(projectCaptor.getValue().getOriginalInfo()).path("include_aplus").asBoolean()).isTrue();
        assertThat(json.readTree(projectCaptor.getValue().getOriginalInfo()).path("aplus_module").asText()).isEqualTo("核心卖点模块");
        verify(service).enqueueSevenImages(user, source.getId());
        verify(service).createLinkedAplusProject(any(), any(), any());
        assertThat(response.aplusRequested()).isTrue();
        assertThat(response.aplusStatus()).isEqualTo("queued");
    }

    @Test
    void skipsAplusCreationWhenFinalChoiceIsOff() throws Exception {
        ProjectDtos.ListingPackageResponse response = service.enqueueListingPackage(
            user,
            source.getId(),
            new ProjectDtos.ListingPackageRequest(false, "核心卖点模块", "970x600", "", "zh-CN")
        );

        verify(service).enqueueSevenImages(user, source.getId());
        verify(service, never()).createLinkedAplusProject(any(), any(), any());
        assertThat(response.aplusRequested()).isFalse();
        assertThat(response.aplusStatus()).isEqualTo("not_requested");
    }
}
