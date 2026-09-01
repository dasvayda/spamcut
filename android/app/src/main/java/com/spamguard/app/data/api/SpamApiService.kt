package com.spamguard.app.data.api

import retrofit2.http.*

data class SpamCheckResponse(
    val isSpam: Boolean,
    val tagType: String? = null,
    val score: Int? = null,
)

// 최근 수신 내역 "최신 정보 받기" — 여러 번호를 한 번에 조회
data class BatchCheckRequest(val numbers: List<String>)

data class BatchCheckItem(
    val number: String,
    val isSpam: Boolean,
    val tagType: String? = null,
    val score: Int? = null,
    val whitelisted: Boolean? = null,
    val company: String? = null,
)

data class BatchCheckResponse(val results: List<BatchCheckItem>)

data class ReportRequest(
    val phone_number: String,
    val tag_type: String,
    val description: String?,
)

data class AuthRequest(val phone_number: String, val invite_code: String? = null)
data class AuthResponse(val token: String, val userId: String)

data class WalletResponse(
    val balance: Int,
    val subscription: SubscriptionInfo,
    val recent_transactions: List<TxItem>,
)

data class SubscriptionInfo(
    val is_active: Boolean,
    val expires_at: String?,
    val days_remaining: Int,
)

data class TxItem(
    val transaction_type: String,
    val amount: Int,
    val created_at: String,
)

data class ActivateResponse(val success: Boolean, val expires_at: String)
data class TransferRequest(val target_phone_number: String, val amount: Int)
data class TransferResponse(val success: Boolean, val transferred: Int, val to: String)

data class TxHistoryItem(
    val id: String,
    val transaction_type: String,
    val amount: Int,
    val counterparty_phone: String?,
    val created_at: String,
)

data class FcmTokenRequest(val token: String)

interface SpamApiService {

    @GET("api/v1/check-spam")
    suspend fun checkSpam(@Query("number") number: String): SpamCheckResponse

    @POST("api/v1/check-spam/batch")
    suspend fun checkSpamBatch(@Body body: BatchCheckRequest): BatchCheckResponse

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: AuthRequest): AuthResponse

    @POST("api/v1/report")
    suspend fun report(
        @Header("Authorization") bearer: String,
        @Body body: ReportRequest,
    ): Any

    @GET("api/v1/wallet")
    suspend fun getWallet(@Header("Authorization") bearer: String): WalletResponse

    @POST("api/v1/wallet/activate")
    suspend fun activateSubscription(@Header("Authorization") bearer: String): ActivateResponse

    @POST("api/v1/wallet/transfer")
    suspend fun transfer(
        @Header("Authorization") bearer: String,
        @Body body: TransferRequest,
    ): TransferResponse

    @GET("api/v1/wallet/history")
    suspend fun getWalletHistory(
        @Header("Authorization") bearer: String,
        @Query("limit") limit: Int = 20,
        @Query("before") before: String? = null,
    ): List<TxHistoryItem>

    @POST("api/v1/auth/fcm-token")
    suspend fun registerFcmToken(
        @Header("Authorization") bearer: String,
        @Body body: FcmTokenRequest,
    ): Any

    @DELETE("api/v1/users/me")
    suspend fun deleteAccount(
        @Header("Authorization") bearer: String,
    ): Any
}
