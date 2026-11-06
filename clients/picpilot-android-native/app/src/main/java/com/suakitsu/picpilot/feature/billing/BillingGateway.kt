package com.suakitsu.picpilot.feature.billing

import com.suakitsu.picpilot.BuildConfig
import com.suakitsu.picpilot.core.model.ApiEnvelope
import com.suakitsu.picpilot.core.model.BillingSummary
import com.suakitsu.picpilot.core.model.CreditLedger
import com.suakitsu.picpilot.core.model.PageResult
import com.suakitsu.picpilot.core.model.RechargePlan
import com.suakitsu.picpilot.core.network.ApiResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface BillingGateway {
    suspend fun summary(): ApiResult<BillingSummary>
    suspend fun plans(): ApiResult<List<RechargePlan>>
    suspend fun channels(): ApiResult<List<PaymentChannel>>
    suspend fun orders(page: Int, pageSize: Int, status: String): ApiResult<PageResult<RechargeOrder>>
    suspend fun createOrder(request: CreateRechargeOrderRequest): ApiResult<CreateRechargeOrderResponse>
    suspend fun syncOrder(orderId: String): ApiResult<SyncRechargeOrderResponse>
    suspend fun cancelOrder(orderId: String): ApiResult<SyncRechargeOrderResponse>
    suspend fun ledger(page: Int, pageSize: Int): ApiResult<PageResult<CreditLedger>>
    suspend fun redeem(code: String): ApiResult<RedeemCodeResponse>
}

private interface BillingApi {
    @GET("api/billing/summary")
    suspend fun summary(): ApiEnvelope<BillingSummary>

    @GET("api/billing/plans")
    suspend fun plans(): ApiEnvelope<List<RechargePlan>>

    @GET("api/billing/channels")
    suspend fun channels(): ApiEnvelope<List<PaymentChannel>>

    @GET("api/billing/orders")
    suspend fun orders(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
        @Query("status") status: String,
    ): ApiEnvelope<PageResult<RechargeOrder>>

    @POST("api/billing/orders")
    suspend fun createOrder(@Body request: CreateRechargeOrderRequest): ApiEnvelope<CreateRechargeOrderResponse>

    @POST("api/billing/orders/{id}/sync")
    suspend fun syncOrder(@Path("id") orderId: String): ApiEnvelope<SyncRechargeOrderResponse>

    @POST("api/billing/orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") orderId: String): ApiEnvelope<SyncRechargeOrderResponse>

    @GET("api/billing/ledger")
    suspend fun ledger(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
    ): ApiEnvelope<PageResult<CreditLedger>>

    @POST("api/billing/redeem")
    suspend fun redeem(@Body request: RedeemCodeRequest): ApiEnvelope<RedeemCodeResponse>
}

@Singleton
class RetrofitBillingGateway @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
) : BillingGateway {
    private val api = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BillingApi::class.java)

    override suspend fun summary() = call { api.summary() }
    override suspend fun plans() = call { api.plans() }
    override suspend fun channels() = call { api.channels() }

    override suspend fun orders(page: Int, pageSize: Int, status: String) = call {
        api.orders(page.coerceAtLeast(1), pageSize.coerceIn(1, 50), status.takeUnless { it == "all" }.orEmpty())
    }

    override suspend fun createOrder(request: CreateRechargeOrderRequest) = call { api.createOrder(request) }
    override suspend fun syncOrder(orderId: String) = call { api.syncOrder(orderId) }
    override suspend fun cancelOrder(orderId: String) = call { api.cancelOrder(orderId) }

    override suspend fun ledger(page: Int, pageSize: Int) = call {
        api.ledger(page.coerceAtLeast(1), pageSize.coerceIn(1, 50))
    }

    override suspend fun redeem(code: String) = call { api.redeem(RedeemCodeRequest(code)) }

    private suspend fun <T> call(block: suspend () -> ApiEnvelope<T>): ApiResult<T> = try {
        val response = block()
        val value = response.data
        if (response.success && value != null) {
            ApiResult.Success(value)
        } else {
            ApiResult.Failure(response.message.ifBlank { "服务暂时不可用，请稍后重试" }, response.code)
        }
    } catch (_: IOException) {
        ApiResult.Failure("网络连接异常，请检查网络后重试", "NETWORK_ERROR")
    } catch (error: HttpException) {
        ApiResult.Failure(
            message = error.response()?.errorBody()?.string().safeServerMessage(json)
                ?: when (error.code()) {
                    400 -> "提交内容有误，请核对后重试"
                    401 -> "登录状态已失效，请重新登录"
                    403 -> "当前操作没有权限"
                    429 -> "操作过于频繁，请稍后再试"
                    503 -> "支付服务暂未开放"
                    else -> "服务暂时不可用，请稍后重试"
                },
            code = "HTTP_${error.code()}",
        )
    } catch (_: Throwable) {
        ApiResult.Failure("服务暂时不可用，请稍后重试", "REQUEST_FAILED")
    }
}

private fun String?.safeServerMessage(json: Json): String? {
    if (this.isNullOrBlank()) return null
    return runCatching {
        json.parseToJsonElement(this).jsonObject["message"]?.jsonPrimitive?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 160 }
    }.getOrNull()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BillingGatewayModule {
    @Binds
    @Singleton
    abstract fun bindBillingGateway(implementation: RetrofitBillingGateway): BillingGateway
}
