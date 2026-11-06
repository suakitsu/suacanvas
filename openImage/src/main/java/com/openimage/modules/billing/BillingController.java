package com.openimage.modules.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.PaymentChannelEntity;
import com.openimage.domain.entity.RedemptionCodeEntity;
import com.openimage.domain.entity.RedemptionRecordEntity;
import com.openimage.domain.entity.RechargeOrderEntity;
import com.openimage.domain.entity.RechargePlanEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.PaymentChannelMapper;
import com.openimage.domain.mapper.RedemptionCodeMapper;
import com.openimage.domain.mapper.RedemptionRecordMapper;
import com.openimage.domain.mapper.RechargeOrderMapper;
import com.openimage.domain.mapper.RechargePlanMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.security.CurrentUser;
import com.openimage.security.RequestRateLimitService;
import com.openimage.security.turnstile.TurnstileService;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.net.URLEncoder;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final RechargePlanMapper plans;
    private final PaymentChannelMapper channels;
    private final RechargeOrderMapper orders;
    private final CreditLedgerMapper ledgers;
    private final UserMapper users;
    private final RedemptionCodeMapper redemptionCodes;
    private final RedemptionRecordMapper redemptionRecords;
    private final BillingService billingService;
    private final AfdianPaymentService afdianPayments;
    private final YkmcnPaymentService ykmcnPayments;
    private final AfdianProperties afdianProperties;
    private final ObjectMapper json;
    private final boolean paymentEnabled;
    private final TurnstileService turnstile;
    private final RequestRateLimitService rateLimits;

    public BillingController(
        RechargePlanMapper plans,
        PaymentChannelMapper channels,
        RechargeOrderMapper orders,
        CreditLedgerMapper ledgers,
        UserMapper users,
        RedemptionCodeMapper redemptionCodes,
        RedemptionRecordMapper redemptionRecords,
        BillingService billingService,
        AfdianPaymentService afdianPayments,
        YkmcnPaymentService ykmcnPayments,
        AfdianProperties afdianProperties,
        ObjectMapper json,
        TurnstileService turnstile,
        RequestRateLimitService rateLimits,
        @Value("${openimage.features.payment-enabled:false}") boolean paymentEnabled
    ) {
        this.plans = plans;
        this.channels = channels;
        this.orders = orders;
        this.ledgers = ledgers;
        this.users = users;
        this.redemptionCodes = redemptionCodes;
        this.redemptionRecords = redemptionRecords;
        this.billingService = billingService;
        this.afdianPayments = afdianPayments;
        this.ykmcnPayments = ykmcnPayments;
        this.afdianProperties = afdianProperties;
        this.json = json;
        this.turnstile = turnstile;
        this.rateLimits = rateLimits;
        this.paymentEnabled = paymentEnabled;
    }

    @GetMapping("/plans")
    public ApiResponse<List<RechargePlanEntity>> plans() {
        return ApiResponse.ok(plans.selectList(new LambdaQueryWrapper<RechargePlanEntity>()
            .eq(RechargePlanEntity::getDeleted, 0)
            .eq(RechargePlanEntity::getEnabled, true)
            .orderByAsc(RechargePlanEntity::getSortOrder)));
    }

    @GetMapping("/channels")
    public ApiResponse<List<Map<String, Object>>> channels() {
        return ApiResponse.ok(channels.selectList(new LambdaQueryWrapper<PaymentChannelEntity>()
            .eq(PaymentChannelEntity::getDeleted, 0)
            .eq(PaymentChannelEntity::getVisible, true)
            .orderByAsc(PaymentChannelEntity::getSortOrder))
            .stream()
            .map(channel -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", channel.getId());
                row.put("name", channel.getName());
                row.put("code", channel.getCode());
                row.put("visible", channel.getVisible());
                row.put("enabled", channel.getEnabled());
                row.put("sort_order", channel.getSortOrder());
                row.put("config", "{}");
                return row;
            })
            .toList());
    }

    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> orders(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "5") Integer pageSize
    ) {
        LambdaQueryWrapper<RechargeOrderEntity> query = new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getUserId, user.id());
        String normalizedStatus = status == null ? "" : status.trim();
        if (!normalizedStatus.isBlank() && !"all".equals(normalizedStatus)) {
            query.eq(RechargeOrderEntity::getStatus, normalizedStatus);
        }
        long total = orders.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        List<RechargeOrderEntity> rows = orders.selectList(query.orderByDesc(RechargeOrderEntity::getCreatedAt).last(limitOffset(paging)));
        return ApiResponse.ok(paged(rows, total, paging));
    }

    @PostMapping("/orders")
    public ApiResponse<Map<String, Object>> createOrder(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateRechargeOrderRequest request, HttpServletRequest servletRequest) {
        requirePaymentEnabled();
        rateLimits.check("billing-create-order", servletRequest, user.id(), 20, Duration.ofMinutes(30));
        RechargePlanEntity plan = plans.selectById(request.planId());
        if (plan == null || plan.getDeleted() != 0 || !Boolean.TRUE.equals(plan.getEnabled())) {
            throw new IllegalArgumentException("Recharge plan not found");
        }
        PaymentChannelEntity channel = channels.selectOne(new LambdaQueryWrapper<PaymentChannelEntity>()
            .eq(PaymentChannelEntity::getDeleted, 0)
            .eq(PaymentChannelEntity::getCode, request.channelCode())
            .eq(PaymentChannelEntity::getVisible, true));
        if (channel == null) {
            throw new IllegalArgumentException("Payment channel not found");
        }
        AllowedPlanIds allowedPlanIds = allowedPlanIds(channel);
        if (allowedPlanIds.configured() && !allowedPlanIds.ids().contains(plan.getId())) {
            throw new IllegalArgumentException("当前支付渠道不支持该套餐，请重新选择套餐");
        }

        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId("ro_" + uuid());
        order.setUserId(user.id());
        order.setPlanId(plan.getId());
        order.setChannelCode(channel.getCode());
        order.setAmountCents(plan.getPriceCents());
        order.setCredits(plan.getCredits());
        order.setCurrency(plan.getCurrency());
        order.setStatus(Boolean.TRUE.equals(channel.getEnabled()) ? "pending" : "channel_disabled");
        order.setRemark(Boolean.TRUE.equals(channel.getEnabled()) ? paymentHint(channel, order) : "该支付渠道暂未启用");
        order.setCreatedAt(OffsetDateTime.now());
        order.setUpdatedAt(OffsetDateTime.now());
        order.setDeleted(0);
        orders.insert(order);

        return ApiResponse.ok(createOrderResponse(order, channel));
    }

    @PostMapping("/orders/{id}/sync")
    public ApiResponse<Map<String, Object>> syncOrder(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        requirePaymentEnabled();
        RechargeOrderEntity order = orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getId, id)
            .eq(RechargeOrderEntity::getUserId, user.id()));
        if (order == null) {
            throw new IllegalArgumentException("充值订单不存在");
        }
        if ("paid".equals(order.getStatus())) {
            return ApiResponse.ok(currentOrderResult(order));
        }
        if (ykmcnPayments != null && ykmcnPayments.supportsChannel(order.getChannelCode())) {
            return ApiResponse.ok(ykmcnPayments.syncOrderForUser(id, user.id()).toMap());
        }
        if ("afdian".equals(order.getChannelCode())) {
            return ApiResponse.ok(afdianPayments.syncOrderForUser(id, user.id()).toMap());
        }
        throw new IllegalArgumentException("当前支付渠道暂不支持自动同步，请联系管理员确认");
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelOrder(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        requirePaymentEnabled();
        RechargeOrderEntity order = orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getId, id)
            .eq(RechargeOrderEntity::getUserId, user.id()));
        if (order == null) {
            throw new IllegalArgumentException("充值订单不存在");
        }
        if ("paid".equals(order.getStatus())) {
            return ApiResponse.ok(billingService.confirmRechargeOrder(order.getId(), order.getRemark(), order.getExternalTradeNo(), order.getPaidAt()).toMap());
        }
        if (!"pending".equals(order.getStatus())) {
            Map<String, Object> response = currentOrderResult(order);
            response.put("message", "订单已关闭，可重新下单");
            return ApiResponse.ok(response);
        }
        if (ykmcnPayments != null && ykmcnPayments.supportsChannel(order.getChannelCode())) {
            try {
                BillingService.PaymentResult synced = ykmcnPayments.syncOrderForUser(id, user.id());
                if ("paid".equals(synced.status())) {
                    return ApiResponse.ok(synced.toMap());
                }
            } catch (IllegalArgumentException ignored) {
                // No successful payment was found. The local pending order can be closed safely.
            }
        }
        BillingService.PaymentResult result = billingService.failRechargeOrder(order.getId(), "用户取消支付，订单已作废，请重新下单");
        Map<String, Object> response = result.toMap();
        response.put("message", "支付已取消，订单已作废，请重新下单");
        return ApiResponse.ok(response);
    }

    @PostMapping("/afdian/webhook")
    public Map<String, Object> afdianWebhook(@RequestBody String body, @RequestHeader Map<String, String> headers, @RequestParam(value = "secret", required = false) String secret) {
        requirePaymentEnabled();
        verifyAfdianWebhook(body, headers, secret);
        try {
            JsonNode payload = json.readTree(body);
            afdianPayments.processWebhook(payload);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook processing failed");
        }
        return Map.of("ec", 200, "em", "");
    }

    @RequestMapping(value = "/ykmcn/notify", method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String ykmcnNotify(@RequestParam Map<String, String> params) {
        if (ykmcnPayments == null) {
            return "fail";
        }
        return ykmcnPayments.processNotify(params);
    }

    @GetMapping("/ledger")
    public ApiResponse<Map<String, Object>> ledger(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "5") Integer pageSize
    ) {
        LambdaQueryWrapper<CreditLedgerEntity> query = new LambdaQueryWrapper<CreditLedgerEntity>()
            .eq(CreditLedgerEntity::getDeleted, 0)
            .eq(CreditLedgerEntity::getUserId, user.id());
        long total = ledgers.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        List<CreditLedgerEntity> rows = ledgers.selectList(query.orderByDesc(CreditLedgerEntity::getCreatedAt).last(limitOffset(paging)));
        return ApiResponse.ok(paged(rows, total, paging));
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@AuthenticationPrincipal CurrentUser user) {
        UserEntity entity = users.selectById(user.id());
        long pendingOrders = orders.selectCount(new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getUserId, user.id())
            .eq(RechargeOrderEntity::getStatus, "pending"));
        return ApiResponse.ok(Map.of(
            "credits", entity == null ? 0 : entity.getCredits(),
            "pending_orders", pendingOrders
        ));
    }

    @PostMapping("/redeem")
    @Transactional
    public ApiResponse<Map<String, Object>> redeem(@AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody RedeemRequest request) {
        redemptionCodes.expireOverdueCodes();
        String normalizedCode = normalizeCode(request.code());
        RedemptionCodeEntity code = redemptionCodes.selectOne(new LambdaQueryWrapper<RedemptionCodeEntity>()
            .eq(RedemptionCodeEntity::getDeleted, 0)
            .eq(RedemptionCodeEntity::getCode, normalizedCode));
        if (code == null || !Boolean.TRUE.equals(code.getEnabled())) {
            throw new IllegalArgumentException("兑换码无效");
        }
        if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("兑换码已过期");
        }
        if (code.getUsedCount() != null && code.getMaxUses() != null && code.getUsedCount() >= code.getMaxUses()) {
            throw new IllegalArgumentException("兑换码已被用完");
        }
        long redeemedByUser = redemptionRecords.selectCount(new LambdaQueryWrapper<RedemptionRecordEntity>()
            .eq(RedemptionRecordEntity::getDeleted, 0)
            .eq(RedemptionRecordEntity::getCodeId, code.getId())
            .eq(RedemptionRecordEntity::getUserId, currentUser.id()));
        if (redeemedByUser > 0) {
            throw new IllegalArgumentException("你已兑换过该兑换码");
        }
        if (Boolean.TRUE.equals(code.getBatchExclusive()) && code.getBatchId() != null && !code.getBatchId().isBlank()) {
            long redeemedInBatch = redemptionRecords.selectCount(new LambdaQueryWrapper<RedemptionRecordEntity>()
                .eq(RedemptionRecordEntity::getDeleted, 0)
                .eq(RedemptionRecordEntity::getBatchId, code.getBatchId())
                .eq(RedemptionRecordEntity::getUserId, currentUser.id()));
            if (redeemedInBatch > 0) {
                throw new IllegalArgumentException("你已兑换过本轮福利卡，请等待下一轮活动");
            }
        }
        UserEntity user = users.lockById(currentUser.id());
        if (user == null || user.getDeleted() != 0 || !"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号不可用");
        }

        int credits = code.getCredits() == null ? 0 : code.getCredits();
        if (credits <= 0) {
            throw new IllegalArgumentException("兑换码积分配置无效");
        }
        int balanceAfter = user.getCredits() + credits;
        user.setCredits(balanceAfter);
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);

        RedemptionRecordEntity record = new RedemptionRecordEntity();
        record.setId("rr_" + uuid());
        record.setCodeId(code.getId());
        record.setCode(code.getCode());
        record.setBatchId(code.getBatchId());
        record.setUserId(user.getId());
        record.setUserEmailMasked(mask(user.getEmail()));
        record.setUserNickname(user.getNickname());
        record.setCredits(credits);
        record.setRedeemedAt(OffsetDateTime.now());
        record.setDeleted(0);
        try {
            redemptionRecords.insert(record);
        } catch (DuplicateKeyException duplicate) {
            if (Boolean.TRUE.equals(code.getBatchExclusive())) {
                throw new IllegalArgumentException("你已兑换过本轮福利卡，请等待下一轮活动");
            }
            throw new IllegalArgumentException("你已兑换过该兑换码");
        }

        int consumed = redemptionCodes.consumeIfAvailable(code.getId());
        if (consumed != 1) {
            throw new IllegalArgumentException("兑换码已被用完或已过期");
        }

        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + uuid());
        ledger.setUserId(user.getId());
        ledger.setChangeAmount(credits);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("redeem_code");
        ledger.setBizId(record.getId());
        ledger.setRemark("兑换码入账：" + code.getCode());
        ledger.setCreatedAt(OffsetDateTime.now());
        ledger.setDeleted(0);
        ledgers.insert(ledger);

        return ApiResponse.ok(Map.of(
            "credits", credits,
            "balance_after", balanceAfter,
            "record_id", record.getId()
        ));
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void requirePaymentEnabled() {
        if (!paymentEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment is disabled");
        }
    }

    void verifyAfdianWebhook(String body, Map<String, String> headers, String secretParam) {
        String secret = afdianProperties.getWebhookSecret();
        if (secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Afdian webhook secret is not configured");
        }
        String configuredToken = header(headers, "x-openimage-webhook-secret");
        if (configuredToken.isBlank() && secretParam != null) {
            configuredToken = secretParam.trim();
        }
        if (!configuredToken.isBlank() && MessageDigest.isEqual(configuredToken.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            return;
        }
        String signature = header(headers, "x-openimage-signature");
        String timestamp = header(headers, "x-openimage-timestamp");
        if (signature.isBlank() || timestamp.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook timestamp");
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > afdianProperties.getWebhookMaxSkewSeconds()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook timestamp expired");
        }
        String expected = hmacHex(secret, timestamp + "." + (body == null ? "" : body));
        String normalized = signature.startsWith("sha256=") ? signature.substring("sha256=".length()) : signature;
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), normalized.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
    }

    String hmacHex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Webhook signature check failed");
        }
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return "";
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() == null ? "" : entry.getValue().trim();
            }
        }
        return "";
    }

    private String normalizeCode(String value) {
        String normalized = value == null ? "" : value.trim().replace(" ", "").toUpperCase();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("请输入兑换码");
        }
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("兑换码格式不正确");
        }
        return normalized;
    }

    private String mask(String email) {
        if (email == null || email.isBlank()) {
            return "未绑定邮箱";
        }
        int at = email.indexOf('@');
        if (at <= 2) {
            return "***" + email.substring(at);
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private Map<String, Object> currentOrderResult(RechargeOrderEntity order) {
        UserEntity user = users.selectById(order.getUserId());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("order_id", order.getId());
        response.put("status", order.getStatus());
        response.put("balance_after", user == null ? 0 : user.getCredits());
        response.put("idempotent", true);
        return response;
    }

    private Map<String, Object> createOrderResponse(RechargeOrderEntity order, PaymentChannelEntity channel) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("order_id", order.getId());
        response.put("status", order.getStatus());
        response.put("message", order.getRemark());
        PaymentAction action = paymentAction(channel, order);
        if (action != null) {
            if (!action.paymentUrl().isBlank()) {
                response.put("payment_url", action.paymentUrl());
            }
            if (!action.links().isEmpty()) {
                response.put("payment_links", action.links());
            }
            if (!action.hint().isBlank()) {
                response.put("payment_hint", action.hint());
            }
        }
        return response;
    }

    private String paymentHint(PaymentChannelEntity channel, RechargeOrderEntity order) {
        PaymentAction action = paymentAction(channel, order);
        if (action != null && !action.hint().isBlank()) {
            return action.hint();
        }
        if ("afdian".equals(channel.getCode())) {
            return "系统已自动把 PicPilot 订单号带入爱发电订单：" + order.getId() + "。请确认套餐和金额后付款，支付成功后可点击同步支付状态。";
        }
        return "等待支付或管理员确认";
    }

    private PaymentAction paymentAction(PaymentChannelEntity channel, RechargeOrderEntity order) {
        if (channel == null || channel.getConfig() == null || channel.getConfig().isBlank()) {
            return null;
        }
        String orderId = order == null ? "" : order.getId();
        try {
            JsonNode config = json.readTree(channel.getConfig());
            if ("ykmcn".equals(channel.getCode()) || "ykmcn_legacy".equals(config.path("mode").asText(""))) {
                YkmcnPaymentService.PaymentLink payment = ykmcnPayments.paymentLink(channel, order);
                List<Map<String, String>> links = new ArrayList<>();
                if (!payment.url().isBlank()) {
                    links.add(payment.link());
                }
                return new PaymentAction(payment.url(), links, payment.hint());
            }
            String url = checkoutUrl(config, orderId);
            if (url.isBlank()) {
                url = config.path("payment_url").asText("");
            }
            String hint = config.path("instructions").asText("").replace("{order_id}", orderId);
            List<Map<String, String>> links = new ArrayList<>();
            if (!url.isBlank()) {
                Map<String, String> primary = new LinkedHashMap<>();
                primary.put("label", "爱发电付款");
                primary.put("url", url);
                links.add(primary);
            } else {
                JsonNode linkNodes = config.get("links");
                if (linkNodes != null && linkNodes.isArray()) {
                    for (JsonNode item : linkNodes) {
                        String itemUrl = item.path("url").asText("");
                        if (!itemUrl.isBlank()) {
                            Map<String, String> row = new LinkedHashMap<>();
                            row.put("label", item.path("label").asText("付款入口"));
                            row.put("url", itemUrl);
                            links.add(row);
                            if (url.isBlank()) {
                                url = itemUrl;
                            }
                        }
                    }
                }
            }
            if (url.isBlank() && links.isEmpty() && hint.isBlank()) {
                return null;
            }
            return new PaymentAction(url, links, hint);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String checkoutUrl(JsonNode config, String orderId) {
        String checkoutUrl = config.path("checkout_url").asText("");
        String planId = config.path("plan_id").asText("");
        if (checkoutUrl.isBlank() || planId.isBlank()) {
            return "";
        }
        String productType = config.path("product_type").asText("0");
        String remark = config.path("remark_template").asText("{order_id}").replace("{order_id}", orderId);
        String customOrderId = config.path("custom_order_id_template").asText("{order_id}").replace("{order_id}", orderId);
        String returnUrl = config.path("return_url_template").asText("").replace("{order_id}", orderId);
        StringBuilder url = new StringBuilder(checkoutUrl);
        url.append(checkoutUrl.contains("?") ? "&" : "?");
        url.append("product_type=").append(encode(productType));
        url.append("&plan_id=").append(encode(planId));
        url.append("&remark=").append(encode(remark));
        url.append("&custom_order_id=").append(encode(customOrderId));
        if (!returnUrl.isBlank()) {
            url.append("&return_url=").append(encode(returnUrl));
        }
        if (config.hasNonNull("sku")) {
            url.append("&sku=").append(encode(config.path("sku").asText("")));
        }
        if (config.hasNonNull("viokrz_ex")) {
            url.append("&viokrz_ex=").append(encode(config.path("viokrz_ex").asText("0")));
        }
        return url.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private AllowedPlanIds allowedPlanIds(PaymentChannelEntity channel) {
        if (channel == null || channel.getConfig() == null || channel.getConfig().isBlank()) {
            return new AllowedPlanIds(false, List.of());
        }
        try {
            JsonNode node = json.readTree(channel.getConfig()).get("allowed_plan_ids");
            if (node == null || !node.isArray()) {
                if (node != null) {
                    throw new IllegalArgumentException("支付渠道套餐配置异常，请联系管理员");
                }
                return new AllowedPlanIds(false, List.of());
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : node) {
                String id = item.asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                return new AllowedPlanIds(false, List.of());
            }
            return new AllowedPlanIds(true, ids);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("支付渠道套餐配置异常，请联系管理员");
        }
    }

    public record CreateRechargeOrderRequest(@NotBlank @JsonProperty("plan_id") @JsonAlias("planId") String planId, @NotBlank @JsonProperty("channel_code") @JsonAlias("channelCode") String channelCode, @JsonProperty("turnstile_token") @JsonAlias("turnstileToken") String turnstileToken) {}
    public record RedeemRequest(@NotBlank String code) {}
    private record PaymentAction(String paymentUrl, List<Map<String, String>> links, String hint) {}
    private record AllowedPlanIds(boolean configured, List<String> ids) {}
    private record PageParams(int page, int pageSize) {}

    private PageParams pageParams(Integer page, Integer pageSize) {
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = pageSize == null || pageSize < 1 ? 5 : Math.min(pageSize, 50);
        return new PageParams(normalizedPage, normalizedSize);
    }

    private String limitOffset(PageParams paging) {
        return "limit " + paging.pageSize() + " offset " + ((paging.page() - 1) * paging.pageSize());
    }

    private Map<String, Object> paged(List<?> rows, long total, PageParams paging) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", rows);
        payload.put("total", total);
        payload.put("page", paging.page());
        payload.put("page_size", paging.pageSize());
        payload.put("total_pages", total == 0 ? 0 : (long) Math.ceil(total / (double) paging.pageSize()));
        return payload;
    }
}
