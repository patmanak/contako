package com.patmanak.contako.data.proton

import android.content.Context
import android.os.SystemClock
import com.patmanak.contako.data.sync.ProductionServerClock
import com.patmanak.contako.data.sync.VerifiedServerClock
import com.patmanak.contako.BuildConfig
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import me.proton.core.network.data.ApiManagerFactory
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.NetworkManager
import me.proton.core.network.data.NetworkPrefs
import me.proton.core.network.data.ProtonCookieStore
import me.proton.core.network.data.client.ClientIdProviderImpl
import me.proton.core.network.data.client.ExtraHeaderProviderImpl
import me.proton.core.network.data.cookie.MemoryCookieStorage
import me.proton.core.network.domain.deviceverification.DeviceVerificationListener
import me.proton.core.network.domain.deviceverification.DeviceVerificationMethods
import me.proton.core.network.domain.deviceverification.DeviceVerificationProvider
import me.proton.core.network.domain.feature.FeatureDisabledListener
import me.proton.core.network.domain.client.ClientId
import me.proton.core.network.domain.client.ClientIdProvider
import me.proton.core.network.domain.client.CookieSessionId
import me.proton.core.network.domain.humanverification.HumanVerificationAvailableMethods
import me.proton.core.network.domain.humanverification.HumanVerificationDetails
import me.proton.core.network.domain.humanverification.HumanVerificationListener
import me.proton.core.network.domain.humanverification.HumanVerificationProvider
import me.proton.core.network.domain.scopes.MissingScopeListener
import me.proton.core.network.domain.scopes.MissingScopeResult
import me.proton.core.network.domain.scopes.MissingScopeState
import me.proton.core.network.domain.scopes.Scope
import me.proton.core.network.domain.server.ServerTimeListener
import me.proton.core.network.domain.session.SessionId
import me.proton.core.network.domain.interceptor.InterceptorInfo
import me.proton.core.util.kotlin.DefaultCoroutineScopeProvider
import me.proton.core.util.kotlin.DefaultDispatcherProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal data class GateCHumanVerificationHooks(
    val provider: HumanVerificationProvider,
    val listener: HumanVerificationListener,
    val clear: suspend () -> Unit = {},
) {
    companion object {
        val FailClosed = GateCHumanVerificationHooks(
            provider = EmptyHumanVerificationProvider,
            listener = RejectingHumanVerificationListener,
        )
    }
}

internal data class GateCNetworkGraph(
    val apiProvider: ApiProvider,
    val apiClient: ContakoApiClient,
    val clientVersionValidator: ContakoClientVersionValidator,
)

/**
 * Coarse request classes exposed only to the instrumented Gate C safety audit. The observer never
 * receives a URL, request body, header, token, or server response.
 */
internal enum class GateCRequestClass {
    CONTACT,
    GROUP,
    SESSION_REVOKE,
    OTHER,
}

/** Closed classification of each observed remote contact write attempt before transmission. */
internal enum class GateCDataMutationClass {
    CONTACT_CREATE,
    CONTACT_UPDATE,
    CONTACT_DELETE,
    GROUP_CREATE,
    GROUP_UPDATE,
    GROUP_DELETE,
    EMAIL_LABEL_ASSIGN,
    EMAIL_LABEL_REMOVE,
    UNKNOWN_CONTACT_WRITE,
}

private class GateCAuditedClientIdProvider(
    private val delegate: ClientIdProvider,
    private val requestAudit: GateCRequestAudit,
) : ClientIdProvider {
    override suspend fun getClientId(sessionId: SessionId?): ClientId? =
        delegate.getClientId(sessionId).also { clientId ->
            requestAudit.onHumanVerificationClientIdState(
                if (clientId == null) GateCHumanVerificationClientIdState.MISSING
                else GateCHumanVerificationClientIdState.AVAILABLE,
            )
        }
}

/**
 * Keeps unauthenticated human verification addressable when Proton does not issue Session-Id.
 *
 * The generated value is a process-memory correlation key only. Proton Core uses it to associate
 * the solved verification headers with the suspended/retried unauthenticated request; it is never
 * emitted as a request header or URL parameter. A real authenticated session always wins.
 */
internal class GateCPreAuthClientIdProvider(
    private val delegate: ClientIdProvider,
    private val fallbackIdFactory: () -> String = { UUID.randomUUID().toString() },
) : ClientIdProvider {
    private val guard = Any()
    private var preAuthClientId: ClientId? = null

    override suspend fun getClientId(sessionId: SessionId?): ClientId? {
        if (sessionId != null) {
            return delegate.getClientId(sessionId) ?: ClientId.AccountSession(sessionId)
        }
        synchronized(guard) { preAuthClientId }?.let { return it }

        val delegated = delegate.getClientId(null)
        return synchronized(guard) {
            val selected = preAuthClientId ?: delegated ?: fallbackIdFactory().let { generated ->
                require(generated.isNotBlank() && generated.length <= MAX_LOCAL_CLIENT_ID_LENGTH) {
                    "PRE_AUTH_CLIENT_ID_INVALID"
                }
                ClientId.CookieSession(CookieSessionId(generated))
            }
            preAuthClientId = selected
            selected
        }
    }

    private companion object {
        const val MAX_LOCAL_CLIENT_ID_LENGTH = 128
    }
}

private class GateCAuditedHumanVerificationListener(
    private val delegate: HumanVerificationListener,
    private val requestAudit: GateCRequestAudit,
) : HumanVerificationListener {
    override suspend fun onHumanVerificationNeeded(
        clientId: ClientId,
        methods: HumanVerificationAvailableMethods,
    ): HumanVerificationListener.HumanVerificationResult {
        requestAudit.onHumanVerificationListenerInvoked()
        return delegate.onHumanVerificationNeeded(clientId, methods)
    }

    override suspend fun onHumanVerificationInvalid(clientId: ClientId) =
        delegate.onHumanVerificationInvalid(clientId)
}

internal enum class GateCHumanVerificationClientIdState {
    AVAILABLE,
    MISSING,
}

internal fun interface GateCRequestAudit {
    fun onRequest(requestClass: GateCRequestClass)

    fun onDataMutationAttempt(mutationClass: GateCDataMutationClass) = Unit

    fun onRequestTarget(host: String, method: String, pathSegments: List<String>) = Unit

    fun onRequestComplete(method: String, statusCode: Int?) = Unit

    fun onHumanVerificationClientIdState(state: GateCHumanVerificationClientIdState) = Unit

    fun onHumanVerificationListenerInvoked() = Unit

    companion object {
        val Disabled = GateCRequestAudit { }
    }
}

/** Local admission failure; it MUST never be reported as a network outage. */
internal class GateCLocalRequestBudgetExceeded : IOException("GATE_C_LOCAL_REQUEST_BUDGET_EXHAUSTED")

/** Builds the sole maintained-host ApiProvider shared by every Gate C operation. */
internal object ProtonGateCNetworkFactory {
    private const val PRIMARY_API_BASE_URL = "https://api.protonmail.ch/"

    fun build(
        context: Context,
        sessionCoordinator: ProtonCoreSessionCoordinator,
        humanVerification: GateCHumanVerificationHooks = GateCHumanVerificationHooks.FailClosed,
        requestAudit: GateCRequestAudit = GateCRequestAudit.Disabled,
    ): GateCNetworkGraph {
        val releaseVersion = BuildConfig.PROTON_RELEASE_VERSION
        val client = ContakoApiClient.forReleaseVersion(releaseVersion)
        val validator = ContakoClientVersionValidator.forReleaseVersion(releaseVersion)
        check(validator.validate(client.appVersionHeader))

        val appContext = context.applicationContext
        val dispatcherProvider = DefaultDispatcherProvider()
        val scopeProvider = DefaultCoroutineScopeProvider(dispatcherProvider)
        val baseUrl = PRIMARY_API_BASE_URL.toHttpUrl()
        val cookieStore = ProtonCookieStore(
            persistentStorage = MemoryCookieStorage(),
            sessionStorage = MemoryCookieStorage(),
        )
        val okHttpClient = buildGateCOkHttpClient(requestAudit)
        val clientIdProvider = GateCPreAuthClientIdProvider(
            ClientIdProviderImpl(baseUrl, cookieStore),
        ).let { delegate ->
            if (requestAudit === GateCRequestAudit.Disabled) delegate
            else GateCAuditedClientIdProvider(delegate, requestAudit)
        }
        val verificationListener = if (requestAudit === GateCRequestAudit.Disabled) {
            humanVerification.listener
        } else {
            GateCAuditedHumanVerificationListener(humanVerification.listener, requestAudit)
        }
        val managerFactory = ApiManagerFactory(
            baseUrl = baseUrl,
            apiClient = client,
            clientIdProvider = clientIdProvider,
            serverTimeListener = GateCServerTimeListener,
            networkManager = NetworkManager(appContext),
            prefs = NetworkPrefs(appContext),
            sessionProvider = sessionCoordinator,
            sessionListener = sessionCoordinator,
            humanVerificationProvider = humanVerification.provider,
            humanVerificationListener = verificationListener,
            deviceVerificationProvider = EmptyDeviceVerificationProvider,
            deviceVerificationListener = RejectingDeviceVerificationListener,
            missingScopeListener = RejectingMissingScopeListener(),
            featureDisabledListener = GateCFeatureDisabledListener,
            cookieStore = cookieStore,
            scope = scopeProvider.GlobalIOSupervisedScope,
            extraHeaderProvider = ExtraHeaderProviderImpl(),
            clientVersionValidator = validator,
            dohAlternativesListener = null,
            okHttpClient = okHttpClient,
            // Core installs custom APP interceptors after its error readers. Responses pass
            // through this cap first, after OkHttp's transparent gzip decompression.
            interceptors = setOf(InterceptorInfo() to GateDBoundedResponseInterceptor),
        )
        return GateCNetworkGraph(
            apiProvider = ApiProvider(managerFactory, sessionCoordinator, dispatcherProvider),
            apiClient = client,
            clientVersionValidator = validator,
        )
    }

}

internal object GateDRawResponseLimits {
    const val CONTACT_BYTES = 16 * 1_024 * 1_024
    const val MUTATION_BYTES = 16 * 1_024
    const val CREATE_BYTES = 64 * 1_024

    fun forRequest(method: String, path: List<String>): Int? = when {
        method == "POST" && path == listOf("contacts", "v4", "contacts") -> CREATE_BYTES
        method == "GET" && (path == listOf("contacts", "v4") ||
            path == listOf("contacts", "v4", "contacts", "emails")) -> CONTACT_BYTES
        method == "PUT" && (path == listOf("contacts", "v4", "contacts", "emails", "label") ||
            path == listOf("contacts", "v4", "contacts", "emails", "unlabel")) -> MUTATION_BYTES
        else -> null
    }
}

internal class ProtonResponseSizeExceeded : IOException("PROTON_RESPONSE_TOO_LARGE")

/** Caps both success and error bodies before Core/Retrofit may buffer them. */
internal object GateDBoundedResponseInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val limit = GateDRawResponseLimits.forRequest(request.method, request.url.pathSegments)
            ?: return response
        val body = response.body ?: return response
        if (body.contentLength() > limit) {
            body.close()
            throw ProtonResponseSizeExceeded()
        }
        val bounded = object : ResponseBody() {
            private val boundedSource = object : ForwardingSource(body.source()) {
                private var remaining = limit.toLong()
                override fun read(sink: Buffer, byteCount: Long): Long {
                    require(byteCount >= 0)
                    if (byteCount == 0L) return 0
                    return try {
                        val count = super.read(sink, minOf(byteCount, remaining + 1))
                        if (count > remaining) throw ProtonResponseSizeExceeded()
                        if (count > 0) remaining -= count
                        count
                    } catch (failure: Exception) {
                        body.close()
                        throw failure
                    }
                }
            }.buffer()
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = boundedSource
        }
        return response.newBuilder().body(bounded).build()
    }
}

/** Exact production client policy, exposed internally for network-isolated redirect qualification. */
internal fun buildGateCOkHttpClient(
    requestAudit: GateCRequestAudit,
    serverClock: VerifiedServerClock = ProductionServerClock.calibration,
): OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val requestClass = classifyGateCRequest(request.method, request.url.pathSegments)
            requestAudit.onRequestTarget(request.url.host, request.method, request.url.pathSegments)
            try {
                requestAudit.onRequest(requestClass)
                classifyGateCDataMutation(request.method, request.url.pathSegments, requestClass)
                    ?.let(requestAudit::onDataMutationAttempt)
                val calibrationTarget = request.url.isHttps && request.url.host == "api.protonmail.ch"
                val startWall = if (calibrationTarget) System.currentTimeMillis() else 0L
                val startElapsed = if (calibrationTarget) SystemClock.elapsedRealtime() else 0L
                chain.proceed(request).also { response ->
                    if (calibrationTarget) serverClock.observe(
                        verifiedHttps = request.url.isHttps && response.handshake != null,
                        host = request.url.host,
                        date = response.headers.values("Date").singleOrNull(),
                        ageSeconds = response.header("Age"),
                        startWallMillis = startWall,
                        startElapsedMillis = startElapsed,
                        endWallMillis = System.currentTimeMillis(),
                        endElapsedMillis = SystemClock.elapsedRealtime(),
                    )
                    requestAudit.onRequestComplete(request.method, response.code)
                }
            } catch (failure: Throwable) {
                requestAudit.onRequestComplete(request.method, null)
                throw failure
            }
        }
        .build()

/**
 * Proton Core 36.6.2 declares session revocation as exactly `DELETE auth/v4`. Only that request
 * may consume the cleanup reserve; similar auth paths and other HTTP methods remain ordinary.
 */
internal fun classifyGateCRequest(
    method: String,
    pathSegments: List<String>,
): GateCRequestClass {
    val normalized = pathSegments.map { it.lowercase() }
    return when {
        method == "DELETE" && pathSegments == listOf("auth", "v4") -> GateCRequestClass.SESSION_REVOKE
        method == "PUT" && normalized in setOf(
            listOf("contacts", "v4", "contacts", "emails", "label"),
            listOf("contacts", "v4", "contacts", "emails", "unlabel"),
        ) -> GateCRequestClass.GROUP
        normalized.any { it == "groups" || it == "contactgroups" || it == "labels" } ->
            GateCRequestClass.GROUP
        normalized.any { it == "contact" || it == "contacts" } -> GateCRequestClass.CONTACT
        else -> GateCRequestClass.OTHER
    }
}

internal fun classifyGateCDataMutation(
    method: String,
    pathSegments: List<String>,
    requestClass: GateCRequestClass = classifyGateCRequest(method, pathSegments),
): GateCDataMutationClass? {
    val normalizedMethod = method.uppercase()
    if (normalizedMethod in setOf("GET", "HEAD", "OPTIONS") ||
        requestClass == GateCRequestClass.SESSION_REVOKE ||
        requestClass == GateCRequestClass.OTHER
    ) return null
    val normalizedPath = pathSegments.map(String::lowercase)
    if (normalizedMethod == "PUT" && normalizedPath ==
        listOf("contacts", "v4", "contacts", "emails", "label")
    ) return GateCDataMutationClass.EMAIL_LABEL_ASSIGN
    if (normalizedMethod == "PUT" && normalizedPath ==
        listOf("contacts", "v4", "contacts", "emails", "unlabel")
    ) return GateCDataMutationClass.EMAIL_LABEL_REMOVE

    return when (requestClass) {
        GateCRequestClass.CONTACT -> when (normalizedMethod) {
            "POST" -> GateCDataMutationClass.CONTACT_CREATE
            "PUT", "PATCH" -> GateCDataMutationClass.CONTACT_UPDATE
            "DELETE" -> GateCDataMutationClass.CONTACT_DELETE
            else -> GateCDataMutationClass.UNKNOWN_CONTACT_WRITE
        }
        GateCRequestClass.GROUP -> when (normalizedMethod) {
            "POST" -> GateCDataMutationClass.GROUP_CREATE
            "PUT", "PATCH" -> GateCDataMutationClass.GROUP_UPDATE
            "DELETE" -> GateCDataMutationClass.GROUP_DELETE
            else -> GateCDataMutationClass.UNKNOWN_CONTACT_WRITE
        }
        GateCRequestClass.SESSION_REVOKE, GateCRequestClass.OTHER -> null
    }
}

private object EmptyHumanVerificationProvider : HumanVerificationProvider {
    override suspend fun getHumanVerificationDetails(
        clientId: me.proton.core.network.domain.client.ClientId,
    ): HumanVerificationDetails? = null
}

/** Closed signal emitted only by Contako's fail-closed human-verification listener. */
internal class GateCHumanVerificationRequired : IllegalStateException()

private object RejectingHumanVerificationListener : HumanVerificationListener {
    override suspend fun onHumanVerificationNeeded(
        clientId: me.proton.core.network.domain.client.ClientId,
        methods: HumanVerificationAvailableMethods,
    ): HumanVerificationListener.HumanVerificationResult = throw GateCHumanVerificationRequired()

    override suspend fun onHumanVerificationInvalid(
        clientId: me.proton.core.network.domain.client.ClientId,
    ) = throw GateCHumanVerificationRequired()
}

private object EmptyDeviceVerificationProvider : DeviceVerificationProvider {
    override suspend fun getSolvedChallenge(sessionId: SessionId?): String? = null
    override suspend fun getSolvedChallenge(challengePayload: String): String? = null
    override suspend fun setSolvedChallenge(sessionId: SessionId, challengePayload: String, solved: String) = Unit
}

private object RejectingDeviceVerificationListener : DeviceVerificationListener {
    override suspend fun onDeviceVerification(
        sessionId: SessionId,
        methods: DeviceVerificationMethods,
    ): DeviceVerificationListener.DeviceVerificationResult =
        DeviceVerificationListener.DeviceVerificationResult.Failure
}

private class RejectingMissingScopeListener : MissingScopeListener {
    override val state = MutableSharedFlow<MissingScopeState>(extraBufferCapacity = 1)

    override suspend fun onMissingScope(
        userId: me.proton.core.domain.entity.UserId,
        scopes: List<Scope>,
    ): MissingScopeResult = MissingScopeResult.Failure

    override suspend fun onMissingScopeSuccess() = Unit
    override suspend fun onMissingScopeFailure() = Unit
}

private object GateCFeatureDisabledListener : FeatureDisabledListener {
    override suspend fun onFeatureDisabled(sessionId: SessionId?) = Unit
}

private object GateCServerTimeListener : ServerTimeListener {
    // Core's bare timestamp has no request timing/uncertainty. The verified network
    // interceptor records bounded calibration instead; this callback is not evidence.
    override fun onServerTimeMillisUpdated(epochMillis: Long) = Unit
}
