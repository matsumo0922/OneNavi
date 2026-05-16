package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.drive.supporter.api.auth.domain.AuthState
import me.matsumo.drive.supporter.api.core.result.ApiFailure
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.onenavi.core.model.AppConfig
import kotlin.time.Duration.Companion.milliseconds

/**
 * 外部ナビ API ライブラリの認証ゲートウェイ。
 * ルート検索 / 案内 API コール前に [ensureSignedIn] を呼び、未ログインや session downgrade を検出して再認証する。
 */
class ExtNavAuthGateway(
    private val clientProvider: ExtNavClientProvider,
    private val appConfig: AppConfig,
    private val maxRetry: Int = 3,
    private val retryBaseDelayMillis: Long = 300L,
) {
    private val mutex = Mutex()

    /**
     * 現在のセッション状態を確認し、未認証 or downgrade なら credential で再ログインする。
     *
     * `signInWithCredentials` は 3 段階の web ログインフローで、最後の userstatuscheck が
     * まれに匿名セッションを返す（サーバー側の session 反映遅延）。即時リトライで回復するため、
     * downgrade を含む一時的な失敗は [maxRetry] 回まで指数バックオフ付きで再試行する。
     */
    suspend fun ensureSignedIn(): Result<Unit> = mutex.withLock {
        val loginId = appConfig.extNavLoginId
        val password = appConfig.extNavPassword

        if (loginId.isBlank() || password.isBlank()) {
            return Result.failure(MissingCredentialsException())
        }

        val client = clientProvider.get()
        val state = client.auth.currentState()

        if (state is AuthState.SignedIn) {
            return Result.success(Unit)
        }

        repeat(maxRetry) { attempt ->
            when (val result = client.auth.signInWithCredentials(loginId, password)) {
                is ApiResult.Success -> return Result.success(Unit)
                is ApiResult.Failure -> {
                    if (!result.failure.isRetryable() || attempt == maxRetry - 1) {
                        return Result.failure(AuthGatewayException(result.failure))
                    }
                    delay((retryBaseDelayMillis shl attempt).milliseconds)
                }
            }
        }

        Result.failure(AuthGatewayException(ApiFailure.Unexpected(IllegalStateException("sign-in retry exhausted"))))
    }

    private fun ApiFailure.isRetryable(): Boolean = when (this) {
        is ApiFailure.Network,
        is ApiFailure.EmptyResponse,
        is ApiFailure.Http,
        ApiFailure.Auth.Downgraded,
        -> true
        else -> false
    }
}

class MissingCredentialsException : Exception("EXT_NAV_LOGIN_ID / EXT_NAV_PASSWORD are not configured")

class AuthGatewayException(val failure: ApiFailure) : Exception("ExtNav sign-in failed: $failure")
