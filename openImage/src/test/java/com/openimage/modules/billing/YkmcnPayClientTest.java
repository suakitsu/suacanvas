package com.openimage.modules.billing;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YkmcnPayClientTest {
    @Test
    void signsSortedParamsAndIgnoresSignatureFieldsAndEmptyValues() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("money", "1.00");
        params.put("name", "PicPilot 100积分");
        params.put("sign", "bad");
        params.put("empty", "");
        params.put("pid", "1001");
        params.put("sign_type", "MD5");
        params.put("out_trade_no", "ro_test");

        String sign = YkmcnPayClient.sign(params, "secret");

        assertEquals("02cd4328129a7db365da7507e6141892", sign);
    }

    @Test
    void buildsSubmitUrlWithSignedOrderFields() {
        YkmcnPayClient client = new YkmcnPayClient(WebClient.builder());
        YkmcnPayClient.YkmcnConfig config = new YkmcnPayClient.YkmcnConfig(
            "https://pay.ykmcn.com",
            "1001",
            "secret",
            "alipay",
            "http://127.0.0.1:18080/api/billing/ykmcn/notify",
            "http://127.0.0.1:13000/credits?order_id={order_id}",
            "PicPilot {credits}积分",
            "{order_id}",
            "立即支付",
            "",
            15,
            30
        );

        String url = client.submitUrl(config, new YkmcnPayClient.YkmcnOrder("ro_test", 1990, 100));

        assertTrue(url.startsWith("https://pay.ykmcn.com/submit.php?"));
        assertTrue(url.contains("pid=1001"));
        assertTrue(url.contains("type=alipay"));
        assertTrue(url.contains("out_trade_no=ro_test"));
        assertTrue(url.contains("money=19.90"));
        assertTrue(url.contains("sign_type=MD5"));
        assertTrue(url.contains("sign="));
    }

    @Test
    void buildsWechatSubmitUrlWhenChannelTypeIsWxpay() {
        YkmcnPayClient client = new YkmcnPayClient(WebClient.builder());
        YkmcnPayClient.YkmcnConfig config = new YkmcnPayClient.YkmcnConfig(
            "https://pay.ykmcn.com",
            "1001",
            "secret",
            "wxpay",
            "http://127.0.0.1:18080/api/billing/ykmcn/notify",
            "http://127.0.0.1:13000/credits?order_id={order_id}",
            "PicPilot {credits}积分",
            "{order_id}",
            "微信支付",
            "",
            15,
            30
        );

        String url = client.submitUrl(config, new YkmcnPayClient.YkmcnOrder("ro_test", 990, 100));

        assertTrue(url.contains("type=wxpay"));
        assertTrue(url.contains("pid=1001"));
        assertTrue(url.contains("sign="));
    }
}
