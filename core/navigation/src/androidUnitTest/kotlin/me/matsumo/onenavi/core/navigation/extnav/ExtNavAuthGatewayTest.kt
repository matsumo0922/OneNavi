package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.coroutines.test.runTest
import me.matsumo.drive.supporter.api.auth.domain.AuthSession
import me.matsumo.drive.supporter.api.auth.domain.AuthState
import me.matsumo.drive.supporter.api.core.result.ApiFailure
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.onenavi.core.model.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ExtNavAuthGateway] の session guard および制限系エラーの分離テスト。
 */
class ExtNavAuthGatewayTest {

    // ---- isSessionComplete 純粋関数テスト ----

    @Test
    fun `isSessionComplete は SignedIn かつ全必須フィールドが揃っていれば true を返す`() {
        val state = buildSignedInState()

        assertTrue(isSessionComplete(state))
    }

    @Test
    fun `isSessionComplete は SignedOut なら false を返す`() {
        assertFalse(isSessionComplete(AuthState.SignedOut))
    }

    @Test
    fun `isSessionComplete は Anonymous なら false を返す`() {
        val state = AuthState.Anonymous(
            session = buildCompleteSession(),
        )

        assertFalse(isSessionComplete(state))
    }

    @Test
    fun `isSessionComplete は authToken が空なら false を返す`() {
        val state = buildSignedInState(
            session = buildCompleteSession().copy(authToken = ""),
        )

        assertFalse(isSessionComplete(state))
    }

    @Test
    fun `isSessionComplete は sid が空なら false を返す`() {
        val state = buildSignedInState(
            session = buildCompleteSession().copy(sid = ""),
        )

        assertFalse(isSessionComplete(state))
    }

    @Test
    fun `isSessionComplete は nid が空で isAnonymous になるなら false を返す`() {
        val state = buildSignedInState(
            session = buildCompleteSession().copy(nid = ""),
        )

        assertFalse(isSessionComplete(state))
    }

    @Test
    fun `isSessionComplete は courseType が空で isAnonymous になるなら false を返す`() {
        val state = buildSignedInState(
            session = buildCompleteSession().copy(courseType = ""),
        )

        assertFalse(isSessionComplete(state))
    }

    // ---- isRestricted 純粋関数テスト ----

    @Test
    fun `isRestricted は Restricted エラーなら true を返す`() {
        assertTrue(ApiFailure.Auth.Restricted(statusCode = 403).isRestricted())
    }

    @Test
    fun `isRestricted は Http 403 なら true を返す`() {
        assertTrue(ApiFailure.Http(statusCode = 403).isRestricted())
    }

    @Test
    fun `isRestricted は Http 429 なら true を返す`() {
        assertTrue(ApiFailure.Http(statusCode = 429).isRestricted())
    }

    @Test
    fun `isRestricted は Http 500 なら false を返す`() {
        assertFalse(ApiFailure.Http(statusCode = 500).isRestricted())
    }

    @Test
    fun `isRestricted は Network エラーなら false を返す`() {
        assertFalse(ApiFailure.Network(RuntimeException()).isRestricted())
    }

    // ---- isRetryable 純粋関数テスト ----

    @Test
    fun `isRetryable は Network なら true を返す`() {
        assertTrue(ApiFailure.Network(RuntimeException()).isRetryable())
    }

    @Test
    fun `isRetryable は EmptyResponse なら true を返す`() {
        assertTrue(ApiFailure.EmptyResponse.isRetryable())
    }

    @Test
    fun `isRetryable は Auth Downgraded なら true を返す`() {
        assertTrue(ApiFailure.Auth.Downgraded.isRetryable())
    }

    @Test
    fun `isRetryable は Auth Restricted なら false を返す`() {
        assertFalse(ApiFailure.Auth.Restricted(statusCode = 403).isRetryable())
    }

    @Test
    fun `isRetryable は Auth Unauthenticated なら false を返す`() {
        assertFalse(ApiFailure.Auth.Unauthenticated.isRetryable())
    }

    @Test
    fun `isRetryable は Http 403 なら false を返す`() {
        assertFalse(ApiFailure.Http(statusCode = 403).isRetryable())
    }

    // ---- ExtNavAuthGateway 統合テスト (FakeExtNavAuthBackend 使用) ----

    @Test
    fun `session が完全な SignedIn なら signIn を呼ばない`() = runTest {
        val fakeBackend = FakeExtNavAuthBackend(
            initialState = buildSignedInState(),
        )
        val gateway = buildGateway(fakeBackend)

        val result = gateway.ensureSignedIn()

        assertTrue(result.isSuccess)
        assertEquals(0, fakeBackend.signInCallCount)
    }

    @Test
    fun `SignedOut の場合は signIn を呼んで成功する`() = runTest {
        val fakeBackend = FakeExtNavAuthBackend(
            initialState = AuthState.SignedOut,
            signInResult = ApiResult.Success(
                buildSignedInState(),
            ),
        )
        val gateway = buildGateway(fakeBackend)

        val result = gateway.ensureSignedIn()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeBackend.signInCallCount)
    }

    @Test
    fun `SignedIn だが必須フィールド欠落の場合は signOut して再ログインする`() = runTest {
        val fakeBackend = FakeExtNavAuthBackend(
            initialState = buildSignedInState(
                session = buildCompleteSession().copy(sid = ""),
            ),
            signInResult = ApiResult.Success(
                buildSignedInState(),
            ),
        )
        val gateway = buildGateway(fakeBackend)

        val result = gateway.ensureSignedIn()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeBackend.signOutCallCount)
        assertEquals(1, fakeBackend.signInCallCount)
    }

    @Test
    fun `Anonymous の場合は再ログインする`() = runTest {
        val fakeBackend = FakeExtNavAuthBackend(
            initialState = AuthState.Anonymous(session = buildCompleteSession()),
            signInResult = ApiResult.Success(
                buildSignedInState(),
            ),
        )
        val gateway = buildGateway(fakeBackend)

        val result = gateway.ensureSignedIn()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeBackend.signInCallCount)
    }

    @Test
    fun `Restricted エラーでは signIn は 1 回しか呼ばれない（連打しない）`() = runTest {
        val fakeBackend = FakeExtNavAuthBackend(
            initialState = AuthState.SignedOut,
            signInResult = ApiResult.Failure(ApiFailure.Auth.Restricted(statusCode = 403)),
        )
        val gateway = buildGateway(fakeBackend, maxRetry = 3)

        val result = gateway.ensureSignedIn()

        assertFalse(result.isSuccess)
        assertEquals(1, fakeBackend.signInCallCount, "制限エラーは 1 回で止まること")
        val exception = assertIs<AuthGatewayException>(result.exceptionOrNull())
        assertIs<ApiFailure.Auth.Restricted>(exception.failure)
    }

    @Test
    fun `Http 403 エラーでは連打しない`() = runTest {
        val fakeBackend = FakeExtNavAuthBackend(
            initialState = AuthState.SignedOut,
            signInResult = ApiResult.Failure(ApiFailure.Http(statusCode = 403)),
        )
        val gateway = buildGateway(fakeBackend, maxRetry = 3)

        val result = gateway.ensureSignedIn()

        assertFalse(result.isSuccess)
        assertEquals(1, fakeBackend.signInCallCount, "HTTP 403 は 1 回で止まること")
    }

    @Test
    fun `Http 429 エラーでは連打しない`() = runTest {
        val fakeBackend = FakeExtNavAuthBackend(
            initialState = AuthState.SignedOut,
            signInResult = ApiResult.Failure(ApiFailure.Http(statusCode = 429)),
        )
        val gateway = buildGateway(fakeBackend, maxRetry = 3)

        val result = gateway.ensureSignedIn()

        assertFalse(result.isSuccess)
        assertEquals(1, fakeBackend.signInCallCount, "HTTP 429 は 1 回で止まること")
    }

    @Test
    fun `Network エラーは maxRetry 回リトライする`() = runTest {
        val maxRetry = 3
        val fakeBackend = FakeExtNavAuthBackend(
            initialState = AuthState.SignedOut,
            signInResult = ApiResult.Failure(ApiFailure.Network(RuntimeException("timeout"))),
        )
        val gateway = buildGateway(fakeBackend, maxRetry = maxRetry, retryBaseDelayMillis = 0L)

        val result = gateway.ensureSignedIn()

        assertFalse(result.isSuccess)
        assertEquals(maxRetry, fakeBackend.signInCallCount, "Network エラーは maxRetry 回試みること")
    }

    @Test
    fun `credential が空の場合は signIn を呼ばず即座に失敗する`() = runTest {
        val fakeBackend = FakeExtNavAuthBackend(initialState = AuthState.SignedOut)
        val gateway = buildGateway(fakeBackend, loginId = "", password = "")

        val result = gateway.ensureSignedIn()

        assertFalse(result.isSuccess)
        assertIs<MissingCredentialsException>(result.exceptionOrNull())
        assertEquals(0, fakeBackend.signInCallCount)
    }

    // ---- ヘルパー ----

    private fun buildGateway(
        fakeBackend: FakeExtNavAuthBackend,
        loginId: String = "user@example.com",
        password: String = "password",
        maxRetry: Int = 3,
        retryBaseDelayMillis: Long = 0L,
    ): ExtNavAuthGateway = ExtNavAuthGateway(
        backend = fakeBackend,
        appConfig = buildAppConfig(loginId, password),
        maxRetry = maxRetry,
        retryBaseDelayMillis = retryBaseDelayMillis,
    )

    private fun buildAppConfig(loginId: String, password: String): AppConfig = AppConfig(
        versionName = "0.0.1",
        versionCode = 1,
        developerPin = "",
        googleApiKey = "",
        googleCloudTtsApiKey = "",
        serverRouteBaseUrl = "",
        serverRouteCfAccessClientIdHeader = "",
        serverRouteCfAccessClientSecretHeader = "",
        adMobAppId = "",
        adMobInterstitialAdUnitId = "",
        adMobBannerAdUnitId = "",
        adMobRewardedAdUnitId = "",
        purchaseAndroidApiKey = null,
        purchaseIosApiKey = null,
        extNavLoginId = loginId,
        extNavPassword = password,
    )
}

/**
 * [ExtNavAuthBackend] の fake。
 *
 * テストから signIn / signOut 呼び出し回数の計測・結果の差し替えが可能。
 */
private class FakeExtNavAuthBackend(
    private val initialState: AuthState,
    private val signInResult: ApiResult<AuthState.SignedIn> = ApiResult.Success(
        buildSignedInState(),
    ),
) : ExtNavAuthBackend {

    var signInCallCount: Int = 0
        private set

    var signOutCallCount: Int = 0
        private set

    override suspend fun currentState(): AuthState = initialState

    override suspend fun signInWithCredentials(
        loginId: String,
        password: String,
    ): ApiResult<AuthState.SignedIn> {
        signInCallCount += 1
        return signInResult
    }

    override suspend fun signOut(): ApiResult<Unit> {
        signOutCallCount += 1
        return ApiResult.Success(Unit)
    }
}

private fun buildSignedInState(
    session: AuthSession = buildCompleteSession(),
    externalUserId: String = "user@example.com",
    courseType: String = "premium",
): AuthState.SignedIn = AuthState.SignedIn(
    session,
    externalUserId,
    courseType,
)

private fun buildCompleteSession(): AuthSession = AuthSession(
    authToken = "header.payload.signature",
    expires = Long.MAX_VALUE,
    jti = "jti-value",
    sid = "session-id",
    nid = "user-nid",
    courseType = "premium",
)
