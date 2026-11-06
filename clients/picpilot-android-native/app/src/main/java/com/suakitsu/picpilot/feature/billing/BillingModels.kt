package com.suakitsu.picpilot.feature.billing

import androidx.compose.runtime.Immutable
import com.suakitsu.picpilot.core.model.BillingSummary
import com.suakitsu.picpilot.core.model.CreditLedger
import com.suakitsu.picpilot.core.model.RechargePlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class PaymentChannel(
    val id: String,
    val name: String,
    val code: String,
    val visible: Boolean = true,
    val enabled: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
@Immutable
data class RechargeOrder(
    val id: String,
    @SerialName("plan_id") val planId: String = "",
    @SerialName("channel_code") val channelCode: String = "",
    @SerialName("amount_cents") val amountCents: Int = 0,
    val credits: Int = 0,
    val currency: String = "CNY",
    val status: String = "pending",
    val remark: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
@Immutable
data class PaymentLink(
    val label: String = "付款入口",
    val url: String,
)

@Serializable
data class CreateRechargeOrderRequest(
    @SerialName("plan_id") val planId: String,
    @SerialName("channel_code") val channelCode: String,
    @SerialName("turnstile_token") val turnstileToken: String = "",
)

@Serializable
data class CreateRechargeOrderResponse(
    @SerialName("order_id") val orderId: String,
    val status: String = "pending",
    val message: String = "",
    @SerialName("payment_url") val paymentUrl: String? = null,
    @SerialName("payment_links") val paymentLinks: List<PaymentLink> = emptyList(),
    @SerialName("payment_hint") val paymentHint: String? = null,
)

@Serializable
data class SyncRechargeOrderResponse(
    @SerialName("order_id") val orderId: String,
    val status: String = "pending",
    val message: String? = null,
    val credits: Int? = null,
    @SerialName("balance_after") val balanceAfter: Int? = null,
    @SerialName("ledger_id") val ledgerId: String? = null,
    val idempotent: Boolean? = null,
)

@Serializable
data class RedeemCodeRequest(val code: String)

@Serializable
data class RedeemCodeResponse(
    val credits: Int,
    @SerialName("balance_after") val balanceAfter: Int,
    @SerialName("record_id") val recordId: String,
)

@Immutable
data class PendingPayment(
    val orderId: String,
    val status: String,
    val planName: String,
    val credits: Int,
    val amountCents: Int,
    val currency: String,
    val channelName: String,
    val links: List<PaymentLink>,
    val hint: String,
)

@Immutable
data class PurchaseConfirmation(
    val plan: RechargePlan,
    val channel: PaymentChannel,
)

enum class BillingSection {
    RECHARGE,
    REDEEM,
    ORDERS,
    LEDGER,
}

@Immutable
data class BillingUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val summary: BillingSummary? = null,
    val plans: List<RechargePlan> = emptyList(),
    val channels: List<PaymentChannel> = emptyList(),
    val selectedChannelCode: String = "",
    val selectedSection: BillingSection = BillingSection.RECHARGE,
    val orders: List<RechargeOrder> = emptyList(),
    val orderPage: Int = 1,
    val orderTotalPages: Int = 1,
    val orderStatus: String = "all",
    val isLoadingOrders: Boolean = false,
    val ledger: List<CreditLedger> = emptyList(),
    val ledgerPage: Int = 1,
    val ledgerTotalPages: Int = 1,
    val isLoadingLedger: Boolean = false,
    val purchaseConfirmation: PurchaseConfirmation? = null,
    val isCreatingOrder: Boolean = false,
    val creationOutcomeUnknown: Boolean = false,
    val pendingPayment: PendingPayment? = null,
    val syncingOrderId: String? = null,
    val cancelConfirmation: RechargeOrder? = null,
    val cancellingOrderId: String? = null,
    val redeemCode: String = "",
    val showRedeemConfirmation: Boolean = false,
    val isRedeeming: Boolean = false,
    val redeemOutcomeUnknown: Boolean = false,
) {
    val enabledChannels: List<PaymentChannel>
        get() = channels.filter { it.visible && it.enabled }.sortedBy(PaymentChannel::sortOrder)

    val canCreateOrder: Boolean
        get() = !isCreatingOrder && !creationOutcomeUnknown && syncingOrderId == null && cancellingOrderId == null

    val canRedeem: Boolean
        get() = redeemCode.isNotBlank() && !isRedeeming && !redeemOutcomeUnknown
}

sealed interface BillingEffect {
    data class OpenPayment(val url: String) : BillingEffect
}
