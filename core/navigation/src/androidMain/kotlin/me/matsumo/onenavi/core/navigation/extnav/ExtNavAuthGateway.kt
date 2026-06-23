package me.matsumo.onenavi.core.navigation.extnav

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.drive.supporter.api.auth.domain.AuthSession
import me.matsumo.drive.supporter.api.auth.domain.AuthState
import me.matsumo.drive.supporter.api.core.result.ApiFailure
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.onenavi.core.model.AppConfig
import kotlin.time.Duration.Companion.milliseconds

/**
 * 外部APIライブラリの認証ゲートウェイ。
 *
 * ルート検索 / 案内 API コール前に [ensureSignedIn] を呼び、未ログイン・session downgrade・
 * 不完全 session を検出して再認証する。
 *
 * ## session 必須判定基準（仮決め：人間の確認待ち）
 *
 * 以下の条件をすべて満たす場合のみ「有効な SignedIn session」と見なし、後続 API へ進む。
 * いずれか一つでも欠けていれば local session を破棄して [signInWithCredentials] で再取得する。
 *
 * 1. [AuthSession.authToken] が空文字でない（JWT ヘッダとして送信できる）
 * 2. [AuthSession.sid] が空文字でない（セッション追跡に必要）
 * 3. [AuthSession.isAnonymous] が false（`nid` と `courseType` が揃い、匿名でない）
 *
 * 根拠: 外部APIライブラリの [AuthState.SignedIn] に到達するには authToken / sid / nid /
 * courseType が全て必要。いずれかが欠けると後続 API の認証ヘッダが不完全になり、
 * サーバー側で 403 / 降格応答が返る。古い永続 session にこれらが揃っていない可能性があるため、
 * ライブラリ側の [AuthState] 判定に加えて OneNavi 側でも独立して検証する。
 *
 * ## 制限系エラーの扱い
 *
 * [ApiFailure.Auth.Restricted] / HTTP 403 / HTTP 429 はリトライ連打しない。
 * 検知時はログのみ記録し、[AuthGatewayException] として上位に伝播する。UI 表現は別 issue。
 */
class ExtNavAuthGateway internal constructor(
    private val backend: ExtNavAuthBackend,
    private val appConfig: AppConfig,
    private val maxRetry: Int = 3,
    private val retryBaseDelayMillis: Long = 300L,
) {
    private val mutex = Mutex()

    constructor(
        clientProvider: ExtNavClientProvider,
        appConfig: AppConfig,
        maxRetry: Int = 3,
        retryBaseDelayMillis: Long = 300L,
    ) : this(
        backend = DefaultExtNavAuthBackend(clientProvider),
        appConfig = appConfig,
        maxRetry = maxRetry,
        retryBaseDelayMillis = retryBaseDelayMillis,
    )

    /**
     * 現在の session 状態を検証し、不完全・未認証・downgrade なら credential で再ログインする。
     *
     * `signInWithCredentials` は 3 段階の web ログインフローで、最後の userstatuscheck が
     * まれに匿名セッションを返す（サーバー側の session 反映遅延）。即時リトライで回復するため、
     * downgrade を含む一時的な失敗は [maxRetry] 回まで指数バックオフ付きで再試行する。
     *
     * 制限系エラー（[ApiFailure.Auth.Restricted] / HTTP 403 / HTTP 429）は連打せず即座に失敗する。
     */
    suspend fun ensureSignedIn(): Result<Unit> = mutex.withLock {
        val loginId = appConfig.extNavLoginId
        val password = appConfig.extNavPassword

        if (loginId.isBlank() || password.isBlank()) {
            return Result.failure(MissingCredentialsException())
        }

        val state = backend.currentState()

        val isComplete = isSessionComplete(state)
        if (isComplete) {
            return Result.success(Unit)
        }

        if (state is AuthState.SignedIn) {
            Log.w(TAG, "SignedIn だが必須フィールドが欠落しているため local session を破棄して再ログインする")
            backend.signOut()
        }

        repeat(maxRetry) { attempt ->
            when (val result = backend.signInWithCredentials(loginId, password)) {
                is ApiResult.Success -> return Result.success(Unit)
                is ApiResult.Failure -> {
                    val isRestricted = result.failure.isRestricted()
                    if (isRestricted) {
                        Log.w(TAG, "利用制限を検知: ${result.failure}。再試行しない")
                        return Result.failure(AuthGatewayException(result.failure))
                    }

                    val shouldStop = !result.failure.isRetryable() || attempt == maxRetry - 1
                    if (shouldStop) {
                        return Result.failure(AuthGatewayException(result.failure))
                    }

                    delay((retryBaseDelayMillis shl attempt).milliseconds)
                }
            }
        }

        Result.failure(
            AuthGatewayException(
                ApiFailure.Unexpected(IllegalStateException("sign-in retry exhausted")),
            ),
        )
    }

    private companion object {
        private const val TAG = "ExtNavAuthGateway"
    }
}

/**
 * [ExtNavAuthGateway] が利用する認証操作 backend。
 */
internal interface ExtNavAuthBackend {
    /** 現在の認証状態を返す。 */
    suspend fun currentState(): AuthState

    /** credential でサインインする。 */
    suspend fun signInWithCredentials(loginId: String, password: String): ApiResult<AuthState.SignedIn>

    /** ローカル session を破棄してサインアウトする。 */
    suspend fun signOut(): ApiResult<Unit>
}

/**
 * 外部APIライブラリの [me.matsumo.drive.supporter.api.auth.AuthClient] を使う既定 backend。
 */
private class DefaultExtNavAuthBackend(
    private val clientProvider: ExtNavClientProvider,
) : ExtNavAuthBackend {

    override suspend fun currentState(): AuthState =
        clientProvider.get().auth.currentState()

    override suspend fun signInWithCredentials(
        loginId: String,
        password: String,
    ): ApiResult<AuthState.SignedIn> =
        clientProvider.get().auth.signInWithCredentials(loginId, password)

    override suspend fun signOut(): ApiResult<Unit> =
        clientProvider.get().auth.signOut()
}

/**
 * 外部APIライブラリの [AuthState] が「完全な SignedIn session」かどうかを判定する純粋関数。
 *
 * 以下の条件をすべて満たす場合のみ true を返す（仮決め：人間の確認待ち）:
 * - [AuthState.SignedIn] である
 * - [AuthSession.authToken] が空文字でない
 * - [AuthSession.sid] が空文字でない
 * - [AuthSession.isAnonymous] が false
 *
 * テストから直接呼び出せるよう internal 関数として公開する。
 */
internal fun isSessionComplete(state: AuthState): Boolean {
    if (state !is AuthState.SignedIn) return false
    val session = state.session

    val hasAuthToken = session.authToken.isNotEmpty()
    val hasSid = session.sid.isNotEmpty()
    val isNotAnonymous = !session.isAnonymous

    return hasAuthToken && hasSid && isNotAnonymous
}

/**
 * [ApiFailure] が制限系エラー（再試行連打すべきでない）かどうかを判定する純粋関数。
 *
 * - [ApiFailure.Auth.Restricted]: 外部APIライブラリが明示的に制限として分類したエラー
 * - HTTP 403: 権限なし / 利用制限
 * - HTTP 429: レートリミット
 *
 * これらは連打しても回復しないため、リトライ対象から除外する。
 */
internal fun ApiFailure.isRestricted(): Boolean = when (this) {
    is ApiFailure.Auth.Restricted -> true
    is ApiFailure.Http -> statusCode == 403 || statusCode == 429
    else -> false
}

/**
 * [ApiFailure] がリトライ可能かどうかを判定する純粋関数。
 *
 * 制限系（[isRestricted]）は事前に除外する前提で呼ぶこと。
 * ネットワーク一時障害・サーバー側の session 反映遅延（downgrade）は再試行で回復する可能性がある。
 */
internal fun ApiFailure.isRetryable(): Boolean = when (this) {
    is ApiFailure.Network -> true
    is ApiFailure.EmptyResponse -> true
    ApiFailure.Auth.Downgraded -> true
    else -> false
}

/** 外部APIライブラリのログイン ID / パスワードが未設定 */
class MissingCredentialsException : Exception("EXT_NAV_LOGIN_ID / EXT_NAV_PASSWORD are not configured")

/** 外部APIライブラリへのサインインに失敗 */
class AuthGatewayException(val failure: ApiFailure) : Exception("ExtNav sign-in failed: $failure")
