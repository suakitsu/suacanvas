package com.openimage.modules.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.RechargeOrderEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.RechargeOrderMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BillingControllerWebhookTest {
    private final AfdianProperties properties = properties();
    private final BillingController controller = new BillingController(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        properties,
        new ObjectMapper(),
        null,
        null,
        true
    );

    @Test
    void acceptsConfiguredSecretFromQueryOrHeader() {
        assertDoesNotThrow(() -> controller.verifyAfdianWebhook("{}", Map.of(), "test-secret"));
        assertDoesNotThrow(() -> controller.verifyAfdianWebhook("{}", Map.of("X-OpenImage-Webhook-Secret", "test-secret"), null));
    }

    @Test
    void acceptsTimestampedHmacSignature() {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String body = "{\"data\":{\"order\":{\"out_trade_no\":\"afdian_1\"}}}";
        String signature = controller.hmacHex("test-secret", timestamp + "." + body);

        assertDoesNotThrow(() -> controller.verifyAfdianWebhook(body, Map.of(
            "X-OpenImage-Timestamp", timestamp,
            "X-OpenImage-Signature", "sha256=" + signature
        ), null));
    }

    @Test
    void rejectsInvalidSignature() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.verifyAfdianWebhook("{}", Map.of(
            "X-OpenImage-Timestamp", String.valueOf(System.currentTimeMillis() / 1000),
            "X-OpenImage-Signature", "sha256=bad"
        ), null));

        assertEquals(401, error.getStatusCode().value());
    }

    @Test
    void ykmcnNotifyReturnsFailWhenServiceIsUnavailableInsteadOfThrowing() {
        BillingController disabledController = new BillingController(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            properties,
            new ObjectMapper(),
            null,
            null,
            false
        );

        assertEquals("fail", disabledController.ykmcnNotify(Map.of("out_trade_no", "ro_test")));
    }

    @Test
    void syncPaidOrderReturnsCurrentResultWithoutCallingPaymentProvider() {
        RechargeOrderMapper orders = mock(RechargeOrderMapper.class);
        UserMapper users = mock(UserMapper.class);
        YkmcnPaymentService ykmcnPayments = mock(YkmcnPaymentService.class);
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId("ro_paid");
        order.setUserId("u_1");
        order.setChannelCode("ykmcn_wxpay");
        order.setStatus("paid");
        order.setDeleted(0);
        UserEntity user = new UserEntity();
        user.setCredits(1000);
        when(orders.selectOne(any())).thenReturn(order);
        when(users.selectById("u_1")).thenReturn(user);
        BillingController paidController = new BillingController(
            null,
            null,
            orders,
            null,
            users,
            null,
            null,
            null,
            null,
            ykmcnPayments,
            properties,
            new ObjectMapper(),
            null,
            null,
            true
        );

        ApiResponse<Map<String, Object>> response = paidController.syncOrder(new CurrentUser("u_1", "u@example.com", "user"), "ro_paid");

        assertEquals("paid", response.data().get("status"));
        assertEquals(1000, response.data().get("balance_after"));
        verifyNoInteractions(ykmcnPayments);
    }

    private static AfdianProperties properties() {
        AfdianProperties properties = new AfdianProperties();
        properties.setWebhookSecret("test-secret");
        properties.setWebhookMaxSkewSeconds(300);
        return properties;
    }
}
