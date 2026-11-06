package com.openimage.modules.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.openimage.domain.entity.RechargeOrderEntity;
import com.openimage.domain.mapper.RechargeOrderMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AfdianPaymentService {
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("ro_[0-9a-fA-F]{32}");
    private static final int PAYMENT_WINDOW_MINUTES = 10;

    private final AfdianClient afdianClient;
    private final BillingService billingService;
    private final RechargeOrderMapper orders;

    public AfdianPaymentService(AfdianClient afdianClient, BillingService billingService, RechargeOrderMapper orders) {
        this.afdianClient = afdianClient;
        this.billingService = billingService;
        this.orders = orders;
    }

    public BillingService.PaymentResult syncOrderForUser(String orderId, String userId) {
        RechargeOrderEntity order = orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getId, orderId)
            .eq(RechargeOrderEntity::getUserId, userId));
        if (order == null) {
            throw new IllegalArgumentException("充值订单不存在");
        }
        if (!"afdian".equals(order.getChannelCode())) {
            throw new IllegalArgumentException("当前订单不是爱发电支付渠道");
        }
        if ("paid".equals(order.getStatus())) {
            return billingService.confirmRechargeOrder(order.getId(), order.getRemark(), order.getExternalTradeNo(), order.getPaidAt());
        }
        if (!"pending".equals(order.getStatus()) && !"failed".equals(order.getStatus())) {
            return currentResult(order);
        }
        for (AfdianClient.AfdianOrder afdianOrder : afdianClient.queryRecentOrders(5)) {
            if (matches(order, afdianOrder)) {
                ensureExternalOrderNotUsed(order.getId(), afdianOrder.outTradeNo());
                return billingService.confirmRechargeOrder(order.getId(), "爱发电自动确认：" + afdianOrder.outTradeNo(), afdianOrder.outTradeNo(), OffsetDateTime.now());
            }
        }
        if (expired(order)) {
            return billingService.failRechargeOrder(order.getId(), "支付超时，10 分钟内未查询到爱发电付款，订单已失败");
        }
        throw new IllegalArgumentException("暂未查询到匹配的爱发电付款。系统已自动带入订单号，请确认已完成付款，或稍后再同步。");
    }

    public void processWebhook(JsonNode payload) {
        JsonNode orderNode = payload == null ? null : payload.path("data").path("order");
        if (orderNode == null || orderNode.isMissingNode() || orderNode.isNull()) {
            orderNode = payload == null ? null : payload.path("order");
        }
        String outTradeNo = text(orderNode, "out_trade_no");
        if (outTradeNo.isBlank()) {
            return;
        }
        AfdianClient.AfdianOrder afdianOrder = afdianClient.queryOrder(outTradeNo);
        if (afdianOrder == null || !afdianOrder.paid()) {
            return;
        }
        String localOrderId = localOrderId(afdianOrder);
        if (localOrderId.isBlank()) {
            return;
        }
        RechargeOrderEntity order = orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getId, localOrderId)
            .eq(RechargeOrderEntity::getChannelCode, "afdian"));
        if (order == null || (!"pending".equals(order.getStatus()) && !"failed".equals(order.getStatus())) || !matches(order, afdianOrder)) {
            return;
        }
        ensureExternalOrderNotUsed(order.getId(), afdianOrder.outTradeNo());
        billingService.confirmRechargeOrder(order.getId(), "爱发电自动回调确认：" + afdianOrder.outTradeNo(), afdianOrder.outTradeNo(), OffsetDateTime.now());
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void syncPendingAfdianOrders() {
        if (!afdianClient.configured()) {
            return;
        }
        List<RechargeOrderEntity> pending = orders.selectList(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getChannelCode, "afdian")
            .eq(RechargeOrderEntity::getStatus, "pending")
            .orderByDesc(RechargeOrderEntity::getCreatedAt)
            .last("limit 30"));
        if (pending.isEmpty()) {
            return;
        }
        List<AfdianClient.AfdianOrder> recent;
        try {
            recent = afdianClient.queryRecentOrders(3);
        } catch (Exception ignored) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (RechargeOrderEntity order : pending) {
            boolean matched = false;
            for (AfdianClient.AfdianOrder afdianOrder : recent) {
                try {
                    if (matches(order, afdianOrder)) {
                        ensureExternalOrderNotUsed(order.getId(), afdianOrder.outTradeNo());
                        billingService.confirmRechargeOrder(order.getId(), "爱发电自动同步确认：" + afdianOrder.outTradeNo(), afdianOrder.outTradeNo(), OffsetDateTime.now());
                        matched = true;
                        break;
                    }
                } catch (Exception ignored) {
                    break;
                }
            }
            if (!matched && expired(order, now)) {
                try {
                    billingService.failRechargeOrder(order.getId(), "支付超时，10 分钟内未查询到爱发电付款，订单已失败");
                } catch (Exception ignored) {
                    // Another sync path may have already updated the order.
                }
            }
        }
    }

    private BillingService.PaymentResult currentResult(RechargeOrderEntity order) {
        return new BillingService.PaymentResult(order.getId(), order.getStatus(), 0, null, true);
    }

    private boolean expired(RechargeOrderEntity order) {
        return expired(order, OffsetDateTime.now());
    }

    private boolean expired(RechargeOrderEntity order, OffsetDateTime now) {
        OffsetDateTime createdAt = order == null ? null : order.getCreatedAt();
        return createdAt != null && createdAt.plusMinutes(PAYMENT_WINDOW_MINUTES).isBefore(now);
    }

    private boolean matches(RechargeOrderEntity order, AfdianClient.AfdianOrder afdianOrder) {
        if (order == null || afdianOrder == null || !afdianOrder.paid()) {
            return false;
        }
        String localOrderId = localOrderId(afdianOrder);
        if (!order.getId().equals(localOrderId)) {
            return false;
        }
        int paidCents = afdianOrder.totalAmountCents();
        return paidCents >= 0 && paidCents >= (order.getAmountCents() == null ? 0 : order.getAmountCents());
    }

    private void ensureExternalOrderNotUsed(String orderId, String outTradeNo) {
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return;
        }
        RechargeOrderEntity existing = orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getChannelCode, "afdian")
            .eq(RechargeOrderEntity::getExternalTradeNo, outTradeNo));
        if (existing != null && !orderId.equals(existing.getId())) {
            throw new IllegalArgumentException("该爱发电订单已绑定其他充值订单");
        }
    }

    private String localOrderId(AfdianClient.AfdianOrder afdianOrder) {
        if (afdianOrder == null) {
            return "";
        }
        String customOrderId = localOrderId(afdianOrder.customOrderId());
        return customOrderId.isBlank() ? localOrderId(afdianOrder.remark()) : customOrderId;
    }

    private String localOrderId(String remark) {
        if (remark == null || remark.isBlank()) {
            return "";
        }
        Matcher matcher = ORDER_ID_PATTERN.matcher(remark);
        return matcher.find() ? matcher.group() : "";
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
