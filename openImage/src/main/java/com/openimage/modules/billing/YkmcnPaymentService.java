package com.openimage.modules.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.PaymentChannelEntity;
import com.openimage.domain.entity.RechargeOrderEntity;
import com.openimage.domain.mapper.PaymentChannelMapper;
import com.openimage.domain.mapper.RechargeOrderMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class YkmcnPaymentService {
    private static final int PAYMENT_WINDOW_MINUTES = 30;

    private final YkmcnPayClient client;
    private final BillingService billingService;
    private final RechargeOrderMapper orders;
    private final PaymentChannelMapper channels;
    private final ObjectMapper json;
    private final String publicApiBaseUrl;
    private final String userWebBaseUrl;

    public YkmcnPaymentService(
        YkmcnPayClient client,
        BillingService billingService,
        RechargeOrderMapper orders,
        PaymentChannelMapper channels,
        ObjectMapper json,
        @Value("${openimage.payments.public-api-base-url:}") String publicApiBaseUrl,
        @Value("${openimage.payments.user-web-base-url:}") String userWebBaseUrl
    ) {
        this.client = client;
        this.billingService = billingService;
        this.orders = orders;
        this.channels = channels;
        this.json = json;
        this.publicApiBaseUrl = trimTrailingSlash(publicApiBaseUrl);
        this.userWebBaseUrl = trimTrailingSlash(userWebBaseUrl);
    }

    public PaymentLink paymentLink(PaymentChannelEntity channel, RechargeOrderEntity order) {
        YkmcnPayClient.YkmcnConfig config = config(channel);
        if (!config.configured()) {
            return new PaymentLink("", config.labelValue(), "支付商户参数未配置，请联系管理员");
        }
        String url = client.submitUrl(config, new YkmcnPayClient.YkmcnOrder(order.getId(), order.getAmountCents(), order.getCredits()));
        String hint = config.hint(order.getId());
        if (hint.isBlank()) {
            hint = "订单已创建，点击立即支付。支付完成后系统会自动到账，也可以返回本页同步支付状态。";
        }
        return new PaymentLink(url, config.labelValue(), hint);
    }

    public BillingService.PaymentResult syncOrderForUser(String orderId, String userId) {
        RechargeOrderEntity order = orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getId, orderId)
            .eq(RechargeOrderEntity::getUserId, userId));
        if (order == null) {
            throw new IllegalArgumentException("充值订单不存在");
        }
        PaymentChannelEntity channel = requireChannel(order.getChannelCode());
        if (!isYkmcnChannel(channel)) {
            throw new IllegalArgumentException("当前订单不是聚合支付渠道");
        }
        if ("paid".equals(order.getStatus())) {
            return billingService.confirmRechargeOrder(order.getId(), order.getRemark(), order.getExternalTradeNo(), order.getPaidAt());
        }
        if (!"pending".equals(order.getStatus()) && !"failed".equals(order.getStatus())) {
            return currentResult(order);
        }
        YkmcnPayClient.YkmcnGatewayOrder gatewayOrder = client.queryOrder(config(channel), order.getId());
        if (matches(order, gatewayOrder)) {
            String tradeNo = externalTradeNo(gatewayOrder);
            ensureExternalOrderNotUsed(order.getId(), order.getChannelCode(), tradeNo);
            return billingService.confirmRechargeOrder(order.getId(), "支付自动确认：" + tradeNo, tradeNo, OffsetDateTime.now());
        }
        if (expired(order)) {
            return billingService.failRechargeOrder(order.getId(), "支付超时，未查询到付款记录，订单已失败");
        }
        throw new IllegalArgumentException("暂未查询到支付成功记录。请确认已完成付款，或稍后再同步。");
    }

    public String processNotify(Map<String, String> params) {
        try {
            String orderId = YkmcnPayClient.value(params, "out_trade_no");
            if (orderId.isBlank()) {
                return "fail";
            }
            RechargeOrderEntity order = orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getDeleted, 0)
                .eq(RechargeOrderEntity::getId, orderId));
            if (order == null) {
                return "fail";
            }
            PaymentChannelEntity channel = requireChannel(order.getChannelCode());
            if (!isYkmcnChannel(channel)) {
                return "fail";
            }
            YkmcnPayClient.YkmcnConfig config = config(channel);
            if (!config.configured() || !client.verifyNotify(params, config.key())) {
                return "fail";
            }
            String tradeStatus = YkmcnPayClient.value(params, "trade_status");
            if (!"TRADE_SUCCESS".equalsIgnoreCase(tradeStatus)) {
                return "fail";
            }
            int paidCents = moneyCents(YkmcnPayClient.value(params, "money"));
            if (paidCents < (order.getAmountCents() == null ? 0 : order.getAmountCents())) {
                return "fail";
            }
            if (!"pending".equals(order.getStatus()) && !"failed".equals(order.getStatus()) && !"paid".equals(order.getStatus())) {
                return "fail";
            }
            String tradeNo = YkmcnPayClient.value(params, "trade_no");
            ensureExternalOrderNotUsed(order.getId(), order.getChannelCode(), tradeNo);
            billingService.confirmRechargeOrder(order.getId(), "支付回调确认：" + tradeNo, tradeNo, OffsetDateTime.now());
            return "success";
        } catch (Exception ignored) {
            return "fail";
        }
    }

    public boolean supportsChannel(String channelCode) {
        try {
            return isYkmcnChannel(requireChannel(channelCode));
        } catch (Exception ignored) {
            return false;
        }
    }

    YkmcnPayClient.YkmcnConfig config(PaymentChannelEntity channel) {
        JsonNode node = null;
        try {
            node = channel == null || channel.getConfig() == null || channel.getConfig().isBlank() ? null : json.readTree(channel.getConfig());
        } catch (Exception ignored) {
            node = null;
        }
        String notifyUrl = paymentNotifyUrl(text(node, "notify_url", ""));
        String returnUrlTemplate = paymentReturnUrlTemplate(text(node, "return_url_template", ""));
        return new YkmcnPayClient.YkmcnConfig(
            text(node, "base_url", "https://pay.ykmcn.com"),
            text(node, "pid", ""),
            text(node, "key", ""),
            text(node, "type", "alipay"),
            notifyUrl,
            returnUrlTemplate,
            text(node, "name_template", "PicPilot {credits}积分"),
            text(node, "param_template", "{order_id}"),
            text(node, "label", "立即支付"),
            text(node, "instructions", ""),
            intValue(node, "timeout_seconds", 15),
            intValue(node, "payment_window_minutes", PAYMENT_WINDOW_MINUTES)
        );
    }

    private PaymentChannelEntity requireChannel(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            throw new IllegalArgumentException("支付渠道未配置");
        }
        PaymentChannelEntity channel = channels.selectOne(new LambdaQueryWrapper<PaymentChannelEntity>()
            .eq(PaymentChannelEntity::getDeleted, 0)
            .eq(PaymentChannelEntity::getCode, channelCode));
        if (channel == null) {
            throw new IllegalArgumentException("支付渠道未配置");
        }
        return channel;
    }

    private boolean isYkmcnChannel(PaymentChannelEntity channel) {
        if (channel == null) {
            return false;
        }
        if ("ykmcn".equals(channel.getCode()) || "ykmcn_alipay".equals(channel.getCode()) || "ykmcn_wxpay".equals(channel.getCode())) {
            return true;
        }
        try {
            JsonNode node = channel.getConfig() == null || channel.getConfig().isBlank() ? null : json.readTree(channel.getConfig());
            return node != null && "ykmcn_legacy".equals(node.path("mode").asText(""));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean matches(RechargeOrderEntity order, YkmcnPayClient.YkmcnGatewayOrder gatewayOrder) {
        if (order == null || gatewayOrder == null || !gatewayOrder.paid()) {
            return false;
        }
        if (!order.getId().equals(gatewayOrder.outTradeNo())) {
            return false;
        }
        return gatewayOrder.amountCents() >= (order.getAmountCents() == null ? 0 : order.getAmountCents());
    }

    private boolean expired(RechargeOrderEntity order) {
        OffsetDateTime createdAt = order == null ? null : order.getCreatedAt();
        int minutes = config(requireChannel(order == null ? "" : order.getChannelCode())).paymentWindowMinutes();
        return createdAt != null && createdAt.plusMinutes(Math.max(1, minutes)).isBefore(OffsetDateTime.now());
    }

    private void ensureExternalOrderNotUsed(String orderId, String channelCode, String tradeNo) {
        if (tradeNo == null || tradeNo.isBlank()) {
            return;
        }
        RechargeOrderEntity existing = orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getChannelCode, channelCode)
            .eq(RechargeOrderEntity::getExternalTradeNo, tradeNo));
        if (existing != null && !orderId.equals(existing.getId())) {
            throw new IllegalArgumentException("该支付流水已绑定其他充值订单");
        }
    }

    private String externalTradeNo(YkmcnPayClient.YkmcnGatewayOrder order) {
        if (order == null) {
            return "";
        }
        return order.tradeNo() == null || order.tradeNo().isBlank() ? order.outTradeNo() : order.tradeNo();
    }

    private BillingService.PaymentResult currentResult(RechargeOrderEntity order) {
        return new BillingService.PaymentResult(order.getId(), order.getStatus(), 0, null, true);
    }

    private int moneyCents(String money) {
        try {
            return new BigDecimal(money).multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String text(JsonNode node, String name, String fallback) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText("");
        return text.isBlank() ? fallback : text.trim();
    }

    private int intValue(JsonNode node, String name, int fallback) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    private String paymentNotifyUrl(String configured) {
        if (!publicApiBaseUrl.isBlank()) {
            return publicApiBaseUrl + "/api/billing/ykmcn/notify";
        }
        return configured == null ? "" : configured.trim();
    }

    private String paymentReturnUrlTemplate(String configured) {
        if (!userWebBaseUrl.isBlank()) {
            return userWebBaseUrl + "/credits?order_id={order_id}";
        }
        return configured == null ? "" : configured.trim();
    }

    private String trimTrailingSlash(String value) {
        String next = value == null ? "" : value.trim();
        while (next.endsWith("/")) {
            next = next.substring(0, next.length() - 1);
        }
        return next;
    }

    public record PaymentLink(String url, String label, String hint) {
        public Map<String, String> link() {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("label", label == null || label.isBlank() ? "立即支付" : label);
            row.put("url", url == null ? "" : url);
            return row;
        }
    }
}
