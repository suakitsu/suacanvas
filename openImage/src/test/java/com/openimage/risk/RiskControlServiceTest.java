package com.openimage.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.RiskEventEntity;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.RiskEventMapper;
import com.openimage.domain.mapper.SystemSettingMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.modules.auth.email.TencentSesEmailService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskControlServiceTest {
    @Test
    void asciiBrandKeywordRequiresWordBoundary() {
        UserMapper users = mock(UserMapper.class);
        RiskEventMapper riskEvents = mock(RiskEventMapper.class);
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TencentSesEmailService email = mock(TencentSesEmailService.class);
        UserEntity user = new UserEntity();
        user.setId("u1");
        user.setEmail("user@example.com");
        user.setStatus("active");
        user.setDeleted(0);
        when(users.selectById("u1")).thenReturn(user);

        RiskControlService service = new RiskControlService(users, riskEvents, settings, new ObjectMapper(), email, new NoopTxManager());

        service.checkBeforeProvider("u1", "prod1", "img1", "creative", "Visible text policy involves short readable words.", "user_input");

        verify(riskEvents, never()).insert(any(RiskEventEntity.class));
    }

    @Test
    void levelOutsideBlockAndWarnListsIsIgnored() {
        UserMapper users = mock(UserMapper.class);
        RiskEventMapper riskEvents = mock(RiskEventMapper.class);
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TencentSesEmailService email = mock(TencentSesEmailService.class);
        UserEntity user = new UserEntity();
        user.setId("u1");
        user.setEmail("user@example.com");
        user.setStatus("active");
        user.setDeleted(0);
        when(users.selectById("u1")).thenReturn(user);
        SystemSettingEntity setting = new SystemSettingEntity();
        setting.setSettingValue("""
            {
              "enabled": true,
              "ban_threshold": 5,
              "block_levels": ["high", "medium"],
              "warn_levels": [],
              "keywords": {
                "high": [],
                "medium": [],
                "low": ["性感"]
              }
            }
            """);
        when(settings.selectOne(any())).thenReturn(setting);

        RiskControlService service = new RiskControlService(users, riskEvents, settings, new ObjectMapper(), email, new NoopTxManager());

        service.checkBeforeProvider("u1", "prod1", "img1", "creative", "性感海报风格，但低风险等级未启用提醒", "user_input");

        verify(riskEvents, never()).insert(any(RiskEventEntity.class));
        verify(email, never()).sendPlainText(any(), any(), any());
        verify(email, never()).sendPlainText(any(), any(), any(), any());
    }

    @Test
    void mediumHitRecordsAndBansAtThreshold() {
        UserMapper users = mock(UserMapper.class);
        RiskEventMapper riskEvents = mock(RiskEventMapper.class);
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TencentSesEmailService email = mock(TencentSesEmailService.class);
        UserEntity user = new UserEntity();
        user.setId("u1");
        user.setEmail("user@example.com");
        user.setStatus("active");
        user.setDeleted(0);
        when(users.selectById("u1")).thenReturn(user);
        when(users.lockById("u1")).thenReturn(user);
        when(riskEvents.selectCount(any())).thenReturn(5L);

        RiskControlService service = new RiskControlService(users, riskEvents, settings, new ObjectMapper(), email, new NoopTxManager());

        String rawInput = "用户原始输入：请做一张高仿商品图，背景用白色，标题写新品";
        assertThrows(RiskControlException.class, () ->
            service.checkBeforeProvider("u1", "prod1", "img1", "creative", rawInput, "user_input"));

        var eventCaptor = forClass(RiskEventEntity.class);
        verify(riskEvents).insert(eventCaptor.capture());
        assertEquals("medium", eventCaptor.getValue().getRiskLevel());
        assertEquals("高仿", eventCaptor.getValue().getKeyword());
        assertEquals(rawInput, eventCaptor.getValue().getMatchedText());
        assertEquals("blocked", eventCaptor.getValue().getAction());

        var userCaptor = forClass(UserEntity.class);
        verify(users).updateById(userCaptor.capture());
        assertEquals("banned", userCaptor.getValue().getStatus());
    }

    @Test
    void generatedPromptIsNotCountedAsUserInput() {
        UserMapper users = mock(UserMapper.class);
        RiskEventMapper riskEvents = mock(RiskEventMapper.class);
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TencentSesEmailService email = mock(TencentSesEmailService.class);
        UserEntity user = activeUser();
        when(users.selectById("u1")).thenReturn(user);

        RiskControlService service = new RiskControlService(users, riskEvents, settings, new ObjectMapper(), email, new NoopTxManager());

        service.checkBeforeProvider(
            "u1",
            "prod1",
            "img1",
            "creative_generate",
            "AI 生成的安全约束：避免低清、畸形手指、色情、血腥恐怖元素。",
            "ai_output"
        );

        verify(riskEvents, never()).insert(any(RiskEventEntity.class));
    }

    @Test
    void explicitNegativeConstraintInUserInputIsNotBlocked() {
        UserMapper users = mock(UserMapper.class);
        RiskEventMapper riskEvents = mock(RiskEventMapper.class);
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TencentSesEmailService email = mock(TencentSesEmailService.class);
        UserEntity user = activeUser();
        when(users.selectById("u1")).thenReturn(user);

        RiskControlService service = new RiskControlService(users, riskEvents, settings, new ObjectMapper(), email, new NoopTxManager());

        service.checkBeforeProvider(
            "u1",
            "prod1",
            "img1",
            "creative",
            "原创温馨海报，避免低清、畸形手指、色情、血腥恐怖元素。",
            "user_input"
        );

        verify(riskEvents, never()).insert(any(RiskEventEntity.class));
    }

    @Test
    void positiveDirectiveAfterNegativeClauseStillBlocks() {
        UserMapper users = mock(UserMapper.class);
        RiskEventMapper riskEvents = mock(RiskEventMapper.class);
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TencentSesEmailService email = mock(TencentSesEmailService.class);
        UserEntity user = activeUser();
        when(users.selectById("u1")).thenReturn(user);
        when(riskEvents.selectCount(any())).thenReturn(1L);

        RiskControlService service = new RiskControlService(users, riskEvents, settings, new ObjectMapper(), email, new NoopTxManager());

        assertThrows(RiskControlException.class, () -> service.checkBeforeProvider(
            "u1",
            "prod1",
            "img1",
            "creative",
            "避免低清，但是需要突出血腥冲击感。",
            "user_input"
        ));

        verify(riskEvents).insert(any(RiskEventEntity.class));
    }

    @Test
    void providerPolicyFailureIsRecordedOnlyOncePerTask() {
        UserMapper users = mock(UserMapper.class);
        RiskEventMapper riskEvents = mock(RiskEventMapper.class);
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TencentSesEmailService email = mock(TencentSesEmailService.class);
        UserEntity user = activeUser();
        when(users.selectById("u1")).thenReturn(user);
        when(riskEvents.selectCount(any())).thenReturn(0L, 1L, 1L);

        RiskControlService service = new RiskControlService(users, riskEvents, settings, new ObjectMapper(), email, new NoopTxManager());

        service.recordProviderError("u1", "prod1", "img1", "image_generation:creative", "blocked by content policy");
        service.recordProviderError("u1", "prod1", "img1", "image_generation:creative", "blocked by content policy");

        verify(riskEvents, times(1)).insert(any(RiskEventEntity.class));
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId("u1");
        user.setEmail("u@example.com");
        user.setStatus("active");
        user.setDeleted(0);
        return user;
    }

    private static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
