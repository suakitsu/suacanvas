package com.suakitsu.picpilot.feature.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.CreditLedger
import com.suakitsu.picpilot.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 10
private const val PAYMENT_POLL_INTERVAL_MILLIS = 8_000L
private const val PAYMENT_POLL_ATTEMPTS = 75

@HiltViewModel
class BillingViewModel @Inject constructor(
    private val gateway: BillingGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BillingUiState())
    val uiState: StateFlow<BillingUiState> = _uiState.asStateFlow()

    private val _effects = Channel<BillingEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var paymentPollingJob: Job? = null
    private var createBaselineIds: Set<String> = emptySet()
    private var redeemBaselineBalance: Int? = null
    private var redeemBaselineLedgerIds: Set<String> = emptySet()

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    fun selectSection(section: BillingSection) {
        _uiState.update { it.copy(selectedSection = section, errorMessage = null, infoMessage = null) }
    }

    fun selectChannel(code: String) {
        if (_uiState.value.enabledChannels.none { it.code == code }) return
        _uiState.update { it.copy(selectedChannelCode = code, errorMessage = null, infoMessage = null) }
    }

    fun preparePurchase(planId: String) {
        val state = _uiState.value
        if (!state.canCreateOrder) return
        val plan = state.plans.firstOrNull { it.id == planId && it.enabled } ?: return
        val channel = state.enabledChannels.firstOrNull { it.code == state.selectedChannelCode }
        if (channel == null) {
            _uiState.update { it.copy(errorMessage = "请先选择可用的支付方式") }
            return
        }
        _uiState.update {
            it.copy(purchaseConfirmation = PurchaseConfirmation(plan, channel), errorMessage = null, infoMessage = null)
        }
    }

    fun dismissPurchaseConfirmation() {
        if (!_uiState.value.isCreatingOrder) {
            _uiState.update { it.copy(purchaseConfirmation = null) }
        }
    }

    fun confirmPurchase() {
        val state = _uiState.value
        val confirmation = state.purchaseConfirmation ?: return
        if (!state.canCreateOrder) return
        _uiState.update {
            it.copy(isCreatingOrder = true, errorMessage = null, infoMessage = null)
        }
        viewModelScope.launch {
            when (val baseline = gateway.orders(1, PAGE_SIZE, "all")) {
                is ApiResult.Success -> createBaselineIds = baseline.value.rows.mapTo(linkedSetOf(), RechargeOrder::id)
                is ApiResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isCreatingOrder = false,
                            errorMessage = "暂时无法核对已有订单，本次没有创建新订单。请稍后重试。",
                        )
                    }
                    return@launch
                }
            }
            when (val result = gateway.createOrder(
                CreateRechargeOrderRequest(
                    planId = confirmation.plan.id,
                    channelCode = confirmation.channel.code,
                ),
            )) {
                is ApiResult.Success -> acceptCreatedOrder(result.value, confirmation)
                is ApiResult.Failure -> handleCreateFailure(result, confirmation)
            }
        }
    }

    fun reconcileUnknownOrder() {
        val state = _uiState.value
        val confirmation = state.purchaseConfirmation ?: return
        if (!state.creationOutcomeUnknown || state.isCreatingOrder) return
        _uiState.update { it.copy(isCreatingOrder = true, errorMessage = null, infoMessage = "正在核对最近订单") }
        viewModelScope.launch {
            val reconciled = reconcileCreatedOrder(confirmation, attempts = 2)
            _uiState.update {
                if (reconciled) {
                    it.copy(isCreatingOrder = false, creationOutcomeUnknown = false)
                } else {
                    it.copy(
                        isCreatingOrder = false,
                        creationOutcomeUnknown = false,
                        purchaseConfirmation = null,
                        infoMessage = "未发现新订单，可以重新提交",
                    )
                }
            }
        }
    }

    fun openPayment(link: PaymentLink) {
        val safeUrl = link.url.safePaymentUrl()
        if (safeUrl == null) {
            _uiState.update { it.copy(errorMessage = "付款链接不可用，请联系管理员") }
            return
        }
        viewModelScope.launch { _effects.send(BillingEffect.OpenPayment(safeUrl)) }
        startPaymentPolling()
    }

    fun dismissPendingPayment() {
        paymentPollingJob?.cancel()
        paymentPollingJob = null
        _uiState.update { it.copy(pendingPayment = null, infoMessage = null) }
    }

    fun onPaymentFlowResumed() {
        val pending = _uiState.value.pendingPayment ?: return
        if (pending.status == "pending") {
            syncOrder(pending.orderId, quiet = true)
            startPaymentPolling()
        }
    }

    fun onPaymentFlowPaused() {
        paymentPollingJob?.cancel()
        paymentPollingJob = null
    }

    fun syncOrder(orderId: String, quiet: Boolean = false) {
        val state = _uiState.value
        if (state.syncingOrderId != null || state.cancellingOrderId != null) return
        _uiState.update {
            it.copy(syncingOrderId = orderId, errorMessage = null, infoMessage = if (quiet) it.infoMessage else "正在同步支付状态")
        }
        viewModelScope.launch {
            when (val result = gateway.syncOrder(orderId)) {
                is ApiResult.Success -> applyOrderStatus(result.value, quiet)
                is ApiResult.Failure -> _uiState.update {
                    it.copy(syncingOrderId = null, errorMessage = if (quiet) it.errorMessage else result.message)
                }
            }
        }
    }

    fun requestCancel(order: RechargeOrder) {
        val state = _uiState.value
        if (order.status != "pending" || state.cancellingOrderId != null || state.syncingOrderId != null) return
        if (state.pendingPayment?.orderId == order.id) {
            paymentPollingJob?.cancel()
            paymentPollingJob = null
        }
        _uiState.update { it.copy(cancelConfirmation = order, errorMessage = null, infoMessage = null) }
    }

    fun dismissCancelConfirmation() {
        if (_uiState.value.cancellingOrderId == null) {
            val pending = _uiState.value.pendingPayment
            _uiState.update { it.copy(cancelConfirmation = null) }
            if (pending?.status == "pending") startPaymentPolling()
        }
    }

    fun confirmCancel() {
        val order = _uiState.value.cancelConfirmation ?: return
        if (_uiState.value.cancellingOrderId != null) return
        _uiState.update { it.copy(cancellingOrderId = order.id, errorMessage = null) }
        viewModelScope.launch {
            when (val result = gateway.cancelOrder(order.id)) {
                is ApiResult.Success -> {
                    if (_uiState.value.pendingPayment?.orderId == order.id) dismissPendingPayment()
                    _uiState.update {
                        it.copy(
                            cancellingOrderId = null,
                            cancelConfirmation = null,
                            infoMessage = result.value.message ?: "订单已关闭",
                        )
                    }
                    refreshFinancialData()
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(cancellingOrderId = null, errorMessage = result.message)
                }
            }
        }
    }

    fun selectOrderStatus(status: String) {
        if (status !in setOf("all", "pending", "paid", "failed", "channel_disabled")) return
        if (_uiState.value.orderStatus == status) return
        _uiState.update { it.copy(orderStatus = status) }
        loadOrders(page = 1)
    }

    fun loadOrderPage(page: Int) {
        val state = _uiState.value
        if (page !in 1..state.orderTotalPages || state.isLoadingOrders) return
        loadOrders(page)
    }

    fun loadLedgerPage(page: Int) {
        val state = _uiState.value
        if (page !in 1..state.ledgerTotalPages || state.isLoadingLedger) return
        _uiState.update { it.copy(isLoadingLedger = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = gateway.ledger(page, PAGE_SIZE)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoadingLedger = false,
                        ledger = result.value.rows,
                        ledgerPage = result.value.page,
                        ledgerTotalPages = result.value.totalPages.coerceAtLeast(1),
                    )
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoadingLedger = false, errorMessage = result.message)
                }
            }
        }
    }

    fun updateRedeemCode(value: String) {
        if (_uiState.value.isRedeeming || _uiState.value.redeemOutcomeUnknown) return
        val normalized = value.uppercase(Locale.ROOT).filterNot(Char::isWhitespace).take(64)
        _uiState.update { it.copy(redeemCode = normalized, errorMessage = null, infoMessage = null) }
    }

    fun prepareRedeem() {
        if (!_uiState.value.canRedeem) return
        _uiState.update { it.copy(showRedeemConfirmation = true, errorMessage = null, infoMessage = null) }
    }

    fun dismissRedeemConfirmation() {
        if (!_uiState.value.isRedeeming) {
            _uiState.update { it.copy(showRedeemConfirmation = false) }
        }
    }

    fun confirmRedeem() {
        val state = _uiState.value
        if (!state.canRedeem || !state.showRedeemConfirmation) return
        _uiState.update { it.copy(isRedeeming = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            val baseline = coroutineScope {
                val summary = async { gateway.summary() }
                val ledger = async { gateway.ledger(1, PAGE_SIZE) }
                summary.await() to ledger.await()
            }
            val baselineSummary = baseline.first.valueOrNull()
            val baselineLedger = baseline.second.valueOrNull()
            if (baselineSummary == null || baselineLedger == null) {
                _uiState.update {
                    it.copy(
                        isRedeeming = false,
                        errorMessage = "暂时无法核对当前积分，本次没有提交兑换。请稍后重试。",
                    )
                }
                return@launch
            }
            redeemBaselineBalance = baselineSummary.credits
            redeemBaselineLedgerIds = baselineLedger.rows.mapTo(linkedSetOf(), CreditLedger::id)
            _uiState.update {
                it.copy(
                    summary = baselineSummary,
                    ledger = baselineLedger.rows,
                    ledgerPage = baselineLedger.page,
                    ledgerTotalPages = baselineLedger.totalPages.coerceAtLeast(1),
                )
            }
            when (val result = gateway.redeem(state.redeemCode)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isRedeeming = false,
                            showRedeemConfirmation = false,
                            redeemCode = "",
                            summary = it.summary?.copy(credits = result.value.balanceAfter),
                            infoMessage = "兑换成功，已到账 ${result.value.credits} 积分",
                        )
                    }
                    refreshFinancialData(refreshSummary = false)
                }
                is ApiResult.Failure -> handleRedeemFailure(result)
            }
        }
    }

    fun reconcileUnknownRedeem() {
        if (!_uiState.value.redeemOutcomeUnknown || _uiState.value.isRedeeming) return
        _uiState.update { it.copy(isRedeeming = true, errorMessage = null, infoMessage = "正在核对余额与流水") }
        viewModelScope.launch {
            val reconciled = reconcileRedeem()
            _uiState.update {
                if (reconciled) {
                    it.copy(
                        isRedeeming = false,
                        redeemOutcomeUnknown = false,
                        showRedeemConfirmation = false,
                        redeemCode = "",
                        infoMessage = "已从积分流水确认兑换到账",
                    )
                } else {
                    it.copy(
                        isRedeeming = false,
                        redeemOutcomeUnknown = false,
                        showRedeemConfirmation = false,
                        infoMessage = "未发现兑换入账记录，可以重新提交",
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    private fun load(refresh: Boolean) {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing) return
        _uiState.update { it.copy(isLoading = !refresh, isRefreshing = refresh, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            val result = coroutineScope {
                val summary = async { gateway.summary() }
                val plans = async { gateway.plans() }
                val channels = async { gateway.channels() }
                val orders = async { gateway.orders(1, PAGE_SIZE, state.orderStatus) }
                val ledger = async { gateway.ledger(1, PAGE_SIZE) }
                BillingLoadResult(summary.await(), plans.await(), channels.await(), orders.await(), ledger.await())
            }
            _uiState.update { current ->
                val channels = result.channels.valueOrNull() ?: current.channels
                val selectedChannel = current.selectedChannelCode.takeIf { code -> channels.any { it.code == code && it.enabled } }
                    ?: channels.firstOrNull { it.visible && it.enabled }?.code.orEmpty()
                val orderPage = result.orders.valueOrNull()
                val ledgerPage = result.ledger.valueOrNull()
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = result.failures().joinToString("；").ifBlank { null },
                    summary = result.summary.valueOrNull() ?: current.summary,
                    plans = result.plans.valueOrNull() ?: current.plans,
                    channels = channels,
                    selectedChannelCode = selectedChannel,
                    orders = orderPage?.rows ?: current.orders,
                    orderPage = orderPage?.page ?: current.orderPage,
                    orderTotalPages = orderPage?.totalPages?.coerceAtLeast(1) ?: current.orderTotalPages,
                    ledger = ledgerPage?.rows ?: current.ledger,
                    ledgerPage = ledgerPage?.page ?: current.ledgerPage,
                    ledgerTotalPages = ledgerPage?.totalPages?.coerceAtLeast(1) ?: current.ledgerTotalPages,
                )
            }
        }
    }

    private suspend fun acceptCreatedOrder(response: CreateRechargeOrderResponse, confirmation: PurchaseConfirmation) {
        val links = buildList {
            response.paymentUrl?.takeIf { it.safePaymentUrl() != null }?.let { add(PaymentLink(confirmation.channel.name, it)) }
            response.paymentLinks.filterTo(this) { it.url.safePaymentUrl() != null && none { existing -> existing.url == it.url } }
        }
        val pending = PendingPayment(
            orderId = response.orderId,
            status = response.status,
            planName = confirmation.plan.name,
            credits = confirmation.plan.credits,
            amountCents = confirmation.plan.priceCents,
            currency = confirmation.plan.currency,
            channelName = confirmation.channel.name,
            links = links,
            hint = response.paymentHint ?: response.message,
        )
        _uiState.update {
            it.copy(
                isCreatingOrder = false,
                creationOutcomeUnknown = false,
                purchaseConfirmation = null,
                pendingPayment = pending,
                infoMessage = if (response.status == "pending") "充值订单已创建" else response.message,
            )
        }
        links.firstOrNull()?.let { _effects.send(BillingEffect.OpenPayment(it.url)) }
        if (response.status == "pending") startPaymentPolling()
        refreshFinancialData()
    }

    private suspend fun handleCreateFailure(failure: ApiResult.Failure, confirmation: PurchaseConfirmation) {
        if (!failure.isAmbiguous()) {
            _uiState.update {
                it.copy(isCreatingOrder = false, purchaseConfirmation = null, errorMessage = failure.message)
            }
            return
        }
        val reconciled = reconcileCreatedOrder(confirmation, attempts = 2)
        if (!reconciled) {
            _uiState.update {
                it.copy(
                    isCreatingOrder = false,
                    creationOutcomeUnknown = true,
                    errorMessage = "订单提交结果暂不确定。为避免重复订单，请先核对最近订单。",
                )
            }
        }
    }

    private suspend fun reconcileCreatedOrder(confirmation: PurchaseConfirmation, attempts: Int): Boolean {
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(1_500)
            when (val result = gateway.orders(1, PAGE_SIZE, "all")) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            orders = result.value.rows,
                            orderPage = result.value.page,
                            orderTotalPages = result.value.totalPages.coerceAtLeast(1),
                        )
                    }
                    val order = result.value.rows.firstOrNull {
                        it.id !in createBaselineIds &&
                            it.planId == confirmation.plan.id &&
                            it.channelCode == confirmation.channel.code &&
                            it.amountCents == confirmation.plan.priceCents &&
                            it.credits == confirmation.plan.credits
                    }
                    if (order != null) {
                        _uiState.update {
                            it.copy(
                                isCreatingOrder = false,
                                creationOutcomeUnknown = false,
                                purchaseConfirmation = null,
                                pendingPayment = PendingPayment(
                                    order.id,
                                    order.status,
                                    confirmation.plan.name,
                                    order.credits,
                                    order.amountCents,
                                    order.currency,
                                    confirmation.channel.name,
                                    emptyList(),
                                    order.remark,
                                ),
                                infoMessage = "已核对到新订单，可从订单记录同步支付状态",
                            )
                        }
                        return true
                    }
                }
                is ApiResult.Failure -> Unit
            }
        }
        return false
    }

    private fun loadOrders(page: Int) {
        val state = _uiState.value
        if (state.isLoadingOrders) return
        _uiState.update { it.copy(isLoadingOrders = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = gateway.orders(page, PAGE_SIZE, _uiState.value.orderStatus)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoadingOrders = false,
                        orders = result.value.rows,
                        orderPage = result.value.page,
                        orderTotalPages = result.value.totalPages.coerceAtLeast(1),
                    )
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoadingOrders = false, errorMessage = result.message)
                }
            }
        }
    }

    private fun startPaymentPolling() {
        if (paymentPollingJob?.isActive == true) return
        paymentPollingJob = viewModelScope.launch {
            repeat(PAYMENT_POLL_ATTEMPTS) {
                delay(PAYMENT_POLL_INTERVAL_MILLIS)
                val pending = _uiState.value.pendingPayment ?: return@launch
                if (pending.status != "pending") return@launch
                if (_uiState.value.syncingOrderId == null) {
                    when (val result = gateway.syncOrder(pending.orderId)) {
                        is ApiResult.Success -> {
                            applyOrderStatus(result.value, quiet = true)
                            if (result.value.status != "pending") return@launch
                        }
                        is ApiResult.Failure -> Unit
                    }
                }
                if (!isActive) return@launch
            }
        }
    }

    private suspend fun applyOrderStatus(response: SyncRechargeOrderResponse, quiet: Boolean) {
        val paid = response.status == "paid"
        _uiState.update { current ->
            val currentPending = current.pendingPayment
            current.copy(
                syncingOrderId = null,
                pendingPayment = currentPending?.takeIf { it.orderId == response.orderId }?.copy(status = response.status)
                    ?: currentPending,
                summary = response.balanceAfter?.let { balance -> current.summary?.copy(credits = balance) } ?: current.summary,
                infoMessage = when {
                    paid -> "支付成功，积分已到账"
                    response.status in setOf("failed", "channel_disabled") -> response.message ?: "订单已关闭"
                    quiet -> current.infoMessage
                    else -> response.message ?: "订单仍在等待支付"
                },
            )
        }
        if (response.status != "pending") {
            val pollingJob = paymentPollingJob
            paymentPollingJob = null
            refreshFinancialData()
            response.balanceAfter?.let { confirmedBalance ->
                _uiState.update { current ->
                    current.copy(summary = current.summary?.copy(credits = confirmedBalance))
                }
            }
            pollingJob?.cancel()
        }
    }

    private suspend fun handleRedeemFailure(failure: ApiResult.Failure) {
        if (!failure.isAmbiguous()) {
            _uiState.update {
                it.copy(isRedeeming = false, showRedeemConfirmation = false, errorMessage = failure.message)
            }
            return
        }
        val reconciled = reconcileRedeem()
        _uiState.update {
            if (reconciled) {
                it.copy(
                    isRedeeming = false,
                    redeemOutcomeUnknown = false,
                    showRedeemConfirmation = false,
                    redeemCode = "",
                    infoMessage = "已从积分流水确认兑换到账",
                )
            } else {
                it.copy(
                    isRedeeming = false,
                    redeemOutcomeUnknown = true,
                    errorMessage = "兑换结果暂不确定。为避免重复操作，请先核对余额与流水。",
                )
            }
        }
    }

    private suspend fun reconcileRedeem(): Boolean = coroutineScope {
        val summary = async { gateway.summary() }
        val ledger = async { gateway.ledger(1, PAGE_SIZE) }
        val summaryResult = summary.await()
        val ledgerResult = ledger.await()
        val nextSummary = summaryResult.valueOrNull()
        val nextLedger = ledgerResult.valueOrNull()
        _uiState.update {
            it.copy(
                summary = nextSummary ?: it.summary,
                ledger = nextLedger?.rows ?: it.ledger,
                ledgerPage = nextLedger?.page ?: it.ledgerPage,
                ledgerTotalPages = nextLedger?.totalPages?.coerceAtLeast(1) ?: it.ledgerTotalPages,
            )
        }
        val balanceIncreased = nextSummary != null && redeemBaselineBalance != null && nextSummary.credits > redeemBaselineBalance!!
        val newRedeemLedger = nextLedger?.rows?.any { it.id !in redeemBaselineLedgerIds && it.bizType == "redeem_code" } == true
        balanceIncreased || newRedeemLedger
    }

    private suspend fun refreshFinancialData(refreshSummary: Boolean = true) = coroutineScope {
        val summary = if (refreshSummary) async { gateway.summary() } else null
        val orders = async { gateway.orders(_uiState.value.orderPage, PAGE_SIZE, _uiState.value.orderStatus) }
        val ledger = async { gateway.ledger(_uiState.value.ledgerPage, PAGE_SIZE) }
        val summaryResult = summary?.await()?.valueOrNull()
        val ordersResult = orders.await().valueOrNull()
        val ledgerResult = ledger.await().valueOrNull()
        _uiState.update {
            it.copy(
                summary = summaryResult ?: it.summary,
                orders = ordersResult?.rows ?: it.orders,
                orderPage = ordersResult?.page ?: it.orderPage,
                orderTotalPages = ordersResult?.totalPages?.coerceAtLeast(1) ?: it.orderTotalPages,
                ledger = ledgerResult?.rows ?: it.ledger,
                ledgerPage = ledgerResult?.page ?: it.ledgerPage,
                ledgerTotalPages = ledgerResult?.totalPages?.coerceAtLeast(1) ?: it.ledgerTotalPages,
            )
        }
    }
}

private fun String.safePaymentUrl(): String? = runCatching {
    val uri = URI(this)
    takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() }
}.getOrNull()

private fun ApiResult.Failure.isAmbiguous(): Boolean = code == "NETWORK_ERROR" ||
    code == "REQUEST_FAILED" || code.startsWith("HTTP_5")

private fun <T> ApiResult<T>.valueOrNull(): T? = (this as? ApiResult.Success)?.value

private data class BillingLoadResult(
    val summary: ApiResult<com.suakitsu.picpilot.core.model.BillingSummary>,
    val plans: ApiResult<List<com.suakitsu.picpilot.core.model.RechargePlan>>,
    val channels: ApiResult<List<PaymentChannel>>,
    val orders: ApiResult<com.suakitsu.picpilot.core.model.PageResult<RechargeOrder>>,
    val ledger: ApiResult<com.suakitsu.picpilot.core.model.PageResult<CreditLedger>>,
) {
    fun failures(): List<String> = listOf(summary, plans, channels, orders, ledger)
        .mapNotNull { (it as? ApiResult.Failure)?.message }
        .distinct()
}
