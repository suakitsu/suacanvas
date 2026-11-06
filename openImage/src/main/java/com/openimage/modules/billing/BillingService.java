package com.openimage.modules.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.RechargeOrderEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.RechargeOrderMapper;
import com.openimage.domain.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingService {
    private final RechargeOrderMapper orders;
    private final CreditLedgerMapper ledgers;
    private final UserMapper users;

    public BillingService(RechargeOrderMapper orders, CreditLedgerMapper ledgers, UserMapper users) {
        this.orders = orders;
        this.ledgers = ledgers;
        this.users = users;
    }

    @Transactional
    public PaymentResult confirmRechargeOrder(String orderId, String remark, String externalTradeNo, OffsetDateTime paidAt) {
        RechargeOrderEntity order = orders.lockById(orderId);
        if (order == null || order.getDeleted() != 0) {
            throw new IllegalArgumentException("Recharge order not found");
        }
        return confirmLockedOrder(order, remark, externalTradeNo, paidAt);
    }

    @Transactional
    public PaymentResult confirmLockedOrder(RechargeOrderEntity order, String remark, String externalTradeNo, OffsetDateTime paidAt) {
        if ("paid".equals(order.getStatus())) {
            CreditLedgerEntity paidLedger = findRechargeLedger(order);
            UserEntity paidUser = users.selectById(order.getUserId());
            return new PaymentResult(order.getId(), order.getStatus(), paidLedger == null ? (paidUser == null ? 0 : paidUser.getCredits()) : paidLedger.getBalanceAfter(), paidLedger == null ? null : paidLedger.getId(), true);
        }
        if (!"pending".equals(order.getStatus()) && !"failed".equals(order.getStatus())) {
            throw new IllegalArgumentException("Only pending orders can be confirmed");
        }
        UserEntity user = users.lockById(order.getUserId());
        if (user == null || user.getDeleted() != 0) {
            throw new IllegalArgumentException("Order user not found");
        }
        CreditLedgerEntity existing = findRechargeLedger(order);
        if (existing != null) {
            order.setStatus("paid");
            order.setRemark(cleanRemark(remark));
            if (externalTradeNo != null && !externalTradeNo.isBlank()) {
                order.setExternalTradeNo(externalTradeNo);
            }
            order.setPaidAt(paidAt == null ? OffsetDateTime.now() : paidAt);
            order.setUpdatedAt(OffsetDateTime.now());
            orders.updateById(order);
            return new PaymentResult(order.getId(), order.getStatus(), existing.getBalanceAfter(), existing.getId(), true);
        }

        int balanceAfter = user.getCredits() + order.getCredits();
        user.setCredits(balanceAfter);
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);

        order.setStatus("paid");
        order.setRemark(cleanRemark(remark));
        if (externalTradeNo != null && !externalTradeNo.isBlank()) {
            order.setExternalTradeNo(externalTradeNo);
        }
        order.setPaidAt(paidAt == null ? OffsetDateTime.now() : paidAt);
        order.setUpdatedAt(OffsetDateTime.now());
        orders.updateById(order);

        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + uuid());
        ledger.setUserId(user.getId());
        ledger.setChangeAmount(order.getCredits());
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("recharge");
        ledger.setBizId(order.getId());
        ledger.setRemark("充值订单确认到账");
        ledger.setCreatedAt(OffsetDateTime.now());
        ledger.setDeleted(0);
        try {
            ledgers.insert(ledger);
        } catch (DuplicateKeyException duplicate) {
            CreditLedgerEntity duplicateLedger = findRechargeLedger(order);
            if (duplicateLedger != null) {
                return new PaymentResult(order.getId(), order.getStatus(), duplicateLedger.getBalanceAfter(), duplicateLedger.getId(), true);
            }
            throw duplicate;
        }

        return new PaymentResult(order.getId(), order.getStatus(), balanceAfter, ledger.getId(), false);
    }

    @Transactional
    public PaymentResult failRechargeOrder(String orderId, String remark) {
        RechargeOrderEntity order = orders.lockById(orderId);
        if (order == null || order.getDeleted() != 0) {
            throw new IllegalArgumentException("Recharge order not found");
        }
        if ("paid".equals(order.getStatus())) {
            return confirmLockedOrder(order, order.getRemark(), order.getExternalTradeNo(), order.getPaidAt());
        }
        if (!"pending".equals(order.getStatus())) {
            UserEntity user = users.selectById(order.getUserId());
            return new PaymentResult(order.getId(), order.getStatus(), user == null ? 0 : user.getCredits(), null, true);
        }
        UserEntity user = users.selectById(order.getUserId());
        order.setStatus("failed");
        order.setRemark(cleanRemark(remark == null || remark.isBlank() ? "支付超时，订单已失败" : remark));
        order.setUpdatedAt(OffsetDateTime.now());
        orders.updateById(order);
        return new PaymentResult(order.getId(), order.getStatus(), user == null ? 0 : user.getCredits(), null, false);
    }

    private CreditLedgerEntity findRechargeLedger(RechargeOrderEntity order) {
        return ledgers.selectOne(new LambdaQueryWrapper<CreditLedgerEntity>()
            .eq(CreditLedgerEntity::getDeleted, 0)
            .eq(CreditLedgerEntity::getUserId, order.getUserId())
            .eq(CreditLedgerEntity::getBizType, "recharge")
            .eq(CreditLedgerEntity::getBizId, order.getId()));
    }

    private String cleanRemark(String remark) {
        String value = remark == null || remark.isBlank() ? "管理员确认到账" : remark.trim();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record PaymentResult(String orderId, String status, int balanceAfter, String ledgerId, boolean idempotent) {
        public Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order_id", orderId);
            row.put("status", status);
            row.put("balance_after", balanceAfter);
            if (ledgerId != null && !ledgerId.isBlank()) {
                row.put("ledger_id", ledgerId);
            }
            if (idempotent) {
                row.put("idempotent", true);
            }
            return row;
        }
    }
}
