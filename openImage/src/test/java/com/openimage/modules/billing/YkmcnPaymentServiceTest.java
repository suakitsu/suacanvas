package com.openimage.modules.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.PaymentChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YkmcnPaymentServiceTest {
    @Test
    void environmentPaymentDomainsOverrideStoredTestUrls() {
        PaymentChannelEntity channel = new PaymentChannelEntity();
        channel.setCode("ykmcn_alipay");
        channel.setConfig("""
            {
              "mode": "ykmcn_legacy",
              "base_url": "https://pay.ykmcn.com",
              "pid": "1001",
              "key": "secret",
              "type": "alipay",
              "notify_url": "http://127.0.0.1:18080/api/billing/ykmcn/notify",
              "return_url_template": "http://127.0.0.1:13000/credits?order_id={order_id}"
            }
            """);

        YkmcnPaymentService service = new YkmcnPaymentService(
            new YkmcnPayClient(WebClient.builder()),
            null,
            null,
            null,
            new ObjectMapper(),
            "https://suakitsu.com/",
            "https://suakitsu.com/"
        );

        YkmcnPayClient.YkmcnConfig config = service.config(channel);

        assertEquals("https://suakitsu.com/api/billing/ykmcn/notify", config.notifyUrl());
        assertEquals("https://suakitsu.com/credits?order_id=ro_test", config.returnUrl("ro_test"));
    }
}
