package com.suakitsu.picpilot.feature.billing

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suakitsu.picpilot.ui.ForegroundRefreshEffect
import com.suakitsu.picpilot.core.model.CreditLedger
import com.suakitsu.picpilot.core.model.RechargePlan
import java.util.Locale

@Composable
fun BillingRoute(
    modifier: Modifier = Modifier,
    viewModel: BillingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = viewModel::refresh)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BillingEffect.OpenPayment -> runCatching {
                    CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                        .launchUrl(context, Uri.parse(effect.url))
                }.onFailure {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(effect.url)))
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onPaymentFlowResumed()
                Lifecycle.Event.ON_STOP -> viewModel.onPaymentFlowPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onPaymentFlowPaused()
        }
    }

    BillingScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onSectionSelected = viewModel::selectSection,
        onChannelSelected = viewModel::selectChannel,
        onPlanSelected = viewModel::preparePurchase,
        onDismissPurchase = viewModel::dismissPurchaseConfirmation,
        onConfirmPurchase = viewModel::confirmPurchase,
        onReconcileOrder = viewModel::reconcileUnknownOrder,
        onOpenPayment = viewModel::openPayment,
        onSyncOrder = { viewModel.syncOrder(it) },
        onDismissPayment = viewModel::dismissPendingPayment,
        onOrderStatusSelected = viewModel::selectOrderStatus,
        onOrderPage = viewModel::loadOrderPage,
        onRequestCancel = viewModel::requestCancel,
        onDismissCancel = viewModel::dismissCancelConfirmation,
        onConfirmCancel = viewModel::confirmCancel,
        onRedeemCodeChanged = viewModel::updateRedeemCode,
        onPrepareRedeem = viewModel::prepareRedeem,
        onDismissRedeem = viewModel::dismissRedeemConfirmation,
        onConfirmRedeem = viewModel::confirmRedeem,
        onReconcileRedeem = viewModel::reconcileUnknownRedeem,
        onLedgerPage = viewModel::loadLedgerPage,
        onClearMessage = viewModel::clearMessage,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(
    state: BillingUiState,
    onRefresh: () -> Unit,
    onSectionSelected: (BillingSection) -> Unit,
    onChannelSelected: (String) -> Unit,
    onPlanSelected: (String) -> Unit,
    onDismissPurchase: () -> Unit,
    onConfirmPurchase: () -> Unit,
    onReconcileOrder: () -> Unit,
    onOpenPayment: (PaymentLink) -> Unit,
    onSyncOrder: (String) -> Unit,
    onDismissPayment: () -> Unit,
    onOrderStatusSelected: (String) -> Unit,
    onOrderPage: (Int) -> Unit,
    onRequestCancel: (RechargeOrder) -> Unit,
    onDismissCancel: () -> Unit,
    onConfirmCancel: () -> Unit,
    onRedeemCodeChanged: (String) -> Unit,
    onPrepareRedeem: () -> Unit,
    onDismissRedeem: () -> Unit,
    onConfirmRedeem: () -> Unit,
    onReconcileRedeem: () -> Unit,
    onLedgerPage: (Int) -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.isLoading && state.summary == null -> LoadingState()
            state.errorMessage != null && state.summary == null -> ErrorState(state.errorMessage, onRefresh)
            else -> BillingContent(
                state,
                onRefresh,
                onSectionSelected,
                onChannelSelected,
                onPlanSelected,
                onReconcileOrder,
                onOpenPayment,
                onSyncOrder,
                onDismissPayment,
                onOrderStatusSelected,
                onOrderPage,
                onRequestCancel,
                onRedeemCodeChanged,
                onPrepareRedeem,
                onReconcileRedeem,
                onLedgerPage,
                onClearMessage,
            )
        }
    }

    state.purchaseConfirmation?.let { confirmation ->
        PurchaseDialog(
            confirmation = confirmation,
            isSubmitting = state.isCreatingOrder,
            outcomeUnknown = state.creationOutcomeUnknown,
            onDismiss = onDismissPurchase,
            onConfirm = onConfirmPurchase,
            onReconcile = onReconcileOrder,
        )
    }
    state.cancelConfirmation?.let { order ->
        AlertDialog(
            onDismissRequest = onDismissCancel,
            icon = { Icon(Icons.Outlined.Cancel, contentDescription = null) },
            title = { Text("关闭待支付订单？") },
            text = { Text("关闭后不能继续使用这笔订单付款；已经支付的订单不会被取消。") },
            confirmButton = {
                Button(onClick = onConfirmCancel, enabled = state.cancellingOrderId == null) {
                    if (state.cancellingOrderId != null) SmallProgress()
                    Text("确认关闭")
                }
            },
            dismissButton = { TextButton(onClick = onDismissCancel) { Text("保留订单") } },
        )
    }
    if (state.showRedeemConfirmation) {
        RedeemDialog(
            isSubmitting = state.isRedeeming,
            outcomeUnknown = state.redeemOutcomeUnknown,
            onDismiss = onDismissRedeem,
            onConfirm = onConfirmRedeem,
            onReconcile = onReconcileRedeem,
        )
    }
}

@Composable
private fun BillingContent(
    state: BillingUiState,
    onRefresh: () -> Unit,
    onSectionSelected: (BillingSection) -> Unit,
    onChannelSelected: (String) -> Unit,
    onPlanSelected: (String) -> Unit,
    onReconcileOrder: () -> Unit,
    onOpenPayment: (PaymentLink) -> Unit,
    onSyncOrder: (String) -> Unit,
    onDismissPayment: () -> Unit,
    onOrderStatusSelected: (String) -> Unit,
    onOrderPage: (Int) -> Unit,
    onRequestCancel: (RechargeOrder) -> Unit,
    onRedeemCodeChanged: (String) -> Unit,
    onPrepareRedeem: () -> Unit,
    onReconcileRedeem: () -> Unit,
    onLedgerPage: (Int) -> Unit,
    onClearMessage: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeader(onRefresh) }
        state.errorMessage?.let { message -> item { MessageBanner(message, error = true, onClearMessage) } }
        state.infoMessage?.let { message -> item { MessageBanner(message, error = false, onClearMessage) } }
        item { BalanceCard(state.summary?.credits ?: 0, state.summary?.pendingOrders ?: 0) }
        item { BillingSectionTabs(state.selectedSection, onSectionSelected) }
        when (state.selectedSection) {
            BillingSection.RECHARGE -> {
                item {
                    RechargeSection(
                        state = state,
                        onChannelSelected = onChannelSelected,
                        onPlanSelected = onPlanSelected,
                        onReconcileOrder = onReconcileOrder,
                    )
                }
                state.pendingPayment?.let { payment ->
                    item { PendingPaymentCard(payment, state, onOpenPayment, onSyncOrder, onDismissPayment) }
                }
            }
            BillingSection.REDEEM -> item {
                RedeemSection(state, onRedeemCodeChanged, onPrepareRedeem, onReconcileRedeem)
            }
            BillingSection.ORDERS -> {
                item { OrderFilters(state.orderStatus, onOrderStatusSelected) }
                if (state.isLoadingOrders) item { InlineLoading("正在加载订单") }
                if (!state.isLoadingOrders && state.orders.isEmpty()) item { EmptySection("暂无充值订单") }
                items(state.orders, key = RechargeOrder::id) { order ->
                    OrderCard(order, state, onSyncOrder, onRequestCancel)
                }
                item { Pager(state.orderPage, state.orderTotalPages, state.isLoadingOrders, onOrderPage) }
            }
            BillingSection.LEDGER -> {
                item { SectionHeader("积分流水", "充值、兑换、生成扣费、失败退款与管理调整均以这里为准") }
                if (state.isLoadingLedger) item { InlineLoading("正在加载流水") }
                if (!state.isLoadingLedger && state.ledger.isEmpty()) item { EmptySection("暂无积分流水") }
                items(state.ledger, key = CreditLedger::id) { ledger -> LedgerRow(ledger) }
                item { Pager(state.ledgerPage, state.ledgerTotalPages, state.isLoadingLedger, onLedgerPage) }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PageHeader(onRefresh: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("积分充值", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("购买积分、兑换福利并核对每一笔变动", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新积分与订单")
        }
    }
}

@Composable
private fun BalanceCard(credits: Int, pendingOrders: Int) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 420.dp
            if (compact) {
                Column(Modifier.padding(20.dp)) {
                    BalanceMain(credits)
                    if (pendingOrders > 0) {
                        Spacer(Modifier.height(16.dp))
                        PendingCount(pendingOrders)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BalanceMain(credits, Modifier.weight(1f))
                    if (pendingOrders > 0) PendingCount(pendingOrders)
                }
            }
        }
    }
}

@Composable
private fun BalanceMain(credits: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)) {
            Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.padding(12.dp).size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("当前积分", style = MaterialTheme.typography.labelLarge)
            Text(credits.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PendingCount(count: Int) {
    Column(horizontalAlignment = Alignment.End) {
        Text("待支付订单", style = MaterialTheme.typography.labelSmall)
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BillingSectionTabs(selected: BillingSection, onSelected: (BillingSection) -> Unit) {
    val entries = listOf(
        BillingSection.RECHARGE to "充值",
        BillingSection.REDEEM to "兑换",
        BillingSection.ORDERS to "订单",
        BillingSection.LEDGER to "流水",
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (section, label) ->
            FilterChip(selected = selected == section, onClick = { onSelected(section) }, label = { Text(label) })
        }
    }
}

@Composable
private fun RechargeSection(
    state: BillingUiState,
    onChannelSelected: (String) -> Unit,
    onPlanSelected: (String) -> Unit,
    onReconcileOrder: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("支付方式", "先选择渠道，再选择由服务端返回的积分套餐")
        if (state.channels.isEmpty()) {
            EmptySection("暂无支付渠道")
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.channels.sortedBy(PaymentChannel::sortOrder).forEach { channel ->
                    FilterChip(
                        selected = state.selectedChannelCode == channel.code,
                        onClick = { if (channel.enabled) onChannelSelected(channel.code) },
                        enabled = channel.enabled,
                        leadingIcon = { Icon(Icons.Outlined.CreditCard, null, Modifier.size(18.dp)) },
                        label = { Text(if (channel.enabled) channel.name else "${channel.name} · 未开放") },
                    )
                }
            }
        }
        SectionHeader("积分套餐", "金额和积分以后端本次返回为准")
        if (state.plans.none(RechargePlan::enabled)) {
            EmptySection("暂无可购买套餐")
        } else {
            PlanGrid(state.plans.filter(RechargePlan::enabled), state.canCreateOrder, onPlanSelected)
        }
        if (state.creationOutcomeUnknown) {
            ReconcileCard(
                title = "订单结果待核对",
                description = "网络中断时订单可能已在服务端创建。核对完成前不会再次创建订单。",
                busy = state.isCreatingOrder,
                onClick = onReconcileOrder,
            )
        }
    }
}

@Composable
private fun PlanGrid(plans: List<RechargePlan>, enabled: Boolean, onSelect: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 840.dp -> 3
            maxWidth >= 560.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            plans.sortedBy(RechargePlan::sortOrder).chunked(columns).forEach { rowPlans ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowPlans.forEach { plan ->
                        PlanCard(plan, enabled, { onSelect(plan.id) }, Modifier.weight(1f))
                    }
                    repeat(columns - rowPlans.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(plan: RechargePlan, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(plan.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Text("${plan.credits} 积分", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(plan.formattedPrice(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("选择并确认") }
        }
    }
}

@Composable
private fun PendingPaymentCard(
    payment: PendingPayment,
    state: BillingUiState,
    onOpenPayment: (PaymentLink) -> Unit,
    onSyncOrder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (payment.status == "paid") Icons.Outlined.CheckCircle else Icons.Outlined.Payments, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (payment.status == "paid") "支付成功" else "订单已创建", fontWeight = FontWeight.Bold)
                    Text("${payment.channelName} · ${payment.credits} 积分 · ${payment.formattedPrice()}", style = MaterialTheme.typography.bodySmall)
                }
                StatusPill(payment.status)
            }
            Text(payment.hint.ifBlank { "付款后返回 PicPilot，系统会自动同步到账状态。" }, style = MaterialTheme.typography.bodySmall)
            payment.links.forEach { link ->
                Button(onClick = { onOpenPayment(link) }, enabled = payment.status == "pending", modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(link.label.ifBlank { "打开付款页面" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (payment.links.isEmpty() && payment.status == "pending") {
                Text("这笔订单没有可直接打开的付款链接，请从原支付渠道完成付款或联系管理员。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSyncOrder(payment.orderId) },
                    enabled = state.syncingOrderId == null && payment.status == "pending",
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.syncingOrderId == payment.orderId) SmallProgress() else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("同步状态")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("稍后处理") }
            }
        }
    }
}

@Composable
private fun RedeemSection(
    state: BillingUiState,
    onCodeChanged: (String) -> Unit,
    onPrepare: () -> Unit,
    onReconcile: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ConfirmationNumber, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("兑换积分", fontWeight = FontWeight.Bold)
                    Text("同一兑换码或同一轮福利通常只能领取一次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = state.redeemCode,
                onValueChange = onCodeChanged,
                label = { Text("兑换码") },
                placeholder = { Text("例如 PP-ABCD-2345") },
                singleLine = true,
                enabled = !state.isRedeeming && !state.redeemOutcomeUnknown,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onPrepare, enabled = state.canRedeem, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (state.isRedeeming) SmallProgress()
                Text("确认兑换")
            }
            if (state.redeemOutcomeUnknown) {
                ReconcileCard(
                    "兑换结果待核对",
                    "核对完成前不会重复提交兑换码。",
                    state.isRedeeming,
                    onReconcile,
                )
            }
        }
    }
}

@Composable
private fun OrderFilters(selected: String, onSelected: (String) -> Unit) {
    val statuses = listOf("all" to "全部", "pending" to "待支付", "paid" to "已到账", "failed" to "已关闭")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        statuses.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelected(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun OrderCard(
    order: RechargeOrder,
    state: BillingUiState,
    onSync: (String) -> Unit,
    onCancel: (RechargeOrder) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${order.credits} 积分", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${order.channelLabel()} · ${order.formattedPrice()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(order.status)
            }
            Text(order.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (order.createdAt.isNotBlank()) Text(order.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (order.remark.isNotBlank()) Text(order.remark, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (order.status == "pending") {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onSync(order.id) },
                        enabled = state.syncingOrderId == null && state.cancellingOrderId == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.syncingOrderId == order.id) SmallProgress() else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("同步")
                    }
                    OutlinedButton(
                        onClick = { onCancel(order) },
                        enabled = state.cancellingOrderId == null && state.syncingOrderId == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun LedgerRow(item: CreditLedger) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (item.changeAmount >= 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ReceiptLong,
                    null,
                    Modifier.padding(10.dp),
                    tint = if (item.changeAmount >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.remark.ifBlank { item.bizType.label() }, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (item.changeAmount > 0) "+${item.changeAmount}" else item.changeAmount.toString(),
                    fontWeight = FontWeight.Bold,
                    color = if (item.changeAmount >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text("余额 ${item.balanceAfter}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Pager(page: Int, totalPages: Int, loading: Boolean, onPage: (Int) -> Unit) {
    if (totalPages <= 1) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onPage(page - 1) }, enabled = page > 1 && !loading) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一页")
        }
        Text("第 $page / $totalPages 页", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { onPage(page + 1) }, enabled = page < totalPages && !loading) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "下一页")
        }
    }
}

@Composable
private fun PurchaseDialog(
    confirmation: PurchaseConfirmation,
    isSubmitting: Boolean,
    outcomeUnknown: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onReconcile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CreditCard, null) },
        title = { Text(if (outcomeUnknown) "订单结果待核对" else "确认创建充值订单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("支付方式：${confirmation.channel.name}")
                Text("积分：${confirmation.plan.credits}")
                Text("金额：${confirmation.plan.formattedPrice()}", fontWeight = FontWeight.Bold)
                Text(
                    if (outcomeUnknown) "请先核对服务端订单，避免重复创建。" else "确认后会创建一笔订单，并通过系统浏览器打开付款页面。",
                    color = if (outcomeUnknown) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = if (outcomeUnknown) onReconcile else onConfirm,
                enabled = !isSubmitting,
            ) {
                if (isSubmitting) SmallProgress()
                Text(if (outcomeUnknown) "核对订单" else "确认并去支付")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting && !outcomeUnknown) { Text("再看看") } },
    )
}

@Composable
private fun RedeemDialog(
    isSubmitting: Boolean,
    outcomeUnknown: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onReconcile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ConfirmationNumber, null) },
        title = { Text(if (outcomeUnknown) "兑换结果待核对" else "确认兑换") },
        text = {
            Text(if (outcomeUnknown) "请先核对余额和积分流水，避免重复操作。" else "兑换成功后积分会立即加入当前账户。")
        },
        confirmButton = {
            Button(onClick = if (outcomeUnknown) onReconcile else onConfirm, enabled = !isSubmitting) {
                if (isSubmitting) SmallProgress()
                Text(if (outcomeUnknown) "核对到账" else "确认兑换")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting && !outcomeUnknown) { Text("取消") } },
    )
}

@Composable
private fun ReconcileCard(title: String, description: String, busy: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = onClick, enabled = !busy) {
                if (busy) SmallProgress() else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即核对")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusPill(status: String) {
    val (label, color) = when (status) {
        "paid" -> "已到账" to MaterialTheme.colorScheme.primary
        "pending" -> "待支付" to MaterialTheme.colorScheme.tertiary
        "channel_disabled" -> "渠道关闭" to MaterialTheme.colorScheme.error
        else -> "已关闭" to MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MessageBanner(message: String, error: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null)
            Spacer(Modifier.width(10.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun EmptySection(message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
        Text(message, Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InlineLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmallProgress() {
    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(7.dp))
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在加载积分与支付方式", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text(message)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("重新加载") }
        }
    }
}

private fun RechargePlan.formattedPrice(): String = priceCents.formattedMoney(currency)
private fun RechargeOrder.formattedPrice(): String = amountCents.formattedMoney(currency)
private fun PendingPayment.formattedPrice(): String = amountCents.formattedMoney(currency)

private fun Int.formattedMoney(currency: String): String = if (currency.equals("CNY", true)) {
    String.format(Locale.CHINA, "¥%.2f", this / 100.0)
} else {
    String.format(Locale.US, "%.2f %s", this / 100.0, currency)
}

private fun RechargeOrder.channelLabel(): String = when {
    channelCode.contains("wechat", true) || channelCode.contains("wx", true) -> "微信支付"
    channelCode.contains("alipay", true) -> "支付宝"
    else -> channelCode.ifBlank { "在线支付" }
}

private fun String.label(): String = when (this) {
    "recharge_paid" -> "充值到账"
    "redeem_code" -> "兑换码"
    "creative_generate", "listing_generate", "aplus_generate" -> "生成扣费"
    "image_refund" -> "失败退回"
    "manual_adjust" -> "管理调整"
    else -> ifBlank { "积分变动" }
}
