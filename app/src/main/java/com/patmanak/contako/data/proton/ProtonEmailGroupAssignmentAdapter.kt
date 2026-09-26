package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.EmailLabelMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact legacy-evidence operations, kept out of the stable gateway contract and live composition. */
internal enum class EmailGroupAssignmentWireOperation(val relativeRoute: String) {
    ASSIGN("contacts/v4/contacts/emails/label"),
    REMOVE("contacts/v4/contacts/emails/unlabel"),
}

/** Replaceable seam around the authenticated legacy-evidence implementation. */
internal fun interface ProtonEmailGroupAssignmentWireTransport {
    suspend fun put(
        account: AccountScope,
        operation: EmailGroupAssignmentWireOperation,
        utf8JsonBody: String,
    ): String
}

/** Authoritative membership read used to make legacy mutations reconcilable without assuming idempotence. */
internal fun interface ProtonEmailGroupMembershipReader {
    suspend fun members(
        account: AccountScope,
        groupId: RemoteGroupId,
    ): GatewayOutcome<AuthoritativeEmailGroupMembership>
}

internal enum class ProtonEmailGroupAssignmentStage {
    HTTP,
    PARSE,
    PROOF,
}

internal fun interface ProtonEmailGroupAssignmentStageObserver {
    fun onStage(stage: ProtonEmailGroupAssignmentStage)
}

internal class ProtonEmailGroupAssignmentStageMonitor : ProtonEmailGroupAssignmentStageObserver {
    @Volatile
    private var observer = ProtonEmailGroupAssignmentStageObserver { }

    override fun onStage(stage: ProtonEmailGroupAssignmentStage) = observer.onStage(stage)

    fun observe(observer: ProtonEmailGroupAssignmentStageObserver) {
        this.observer = observer
    }
}

internal class AuthoritativeEmailGroupMembership(
    val account: AccountScope,
    val groupId: RemoteGroupId,
    emailIds: List<RemoteEmailId>,
) {
    val emailIds: List<RemoteEmailId> = emailIds.toList()

    init {
        require(this.emailIds.size <= ContactInventoryMetadata.MAX_REFERENCES_PER_CONTACT)
        require(this.emailIds.distinct().size == this.emailIds.size)
    }
}

/**
 * Mandatory read-before-write wrapper. Ambiguous delivery failures are read back before a retry can
 * be scheduled, so the undocumented endpoints are never presumed idempotent.
 */
internal class ReconciledProtonEmailGroupAssignmentGateway(
    private val membershipReader: ProtonEmailGroupMembershipReader,
    private val mutationGateway: ProtonContactEmailLabelGateway,
    private val stageObserver: ProtonEmailGroupAssignmentStageObserver = ProtonEmailGroupAssignmentStageObserver { },
    private val proofRetryDelayMillis: Long = 250L,
) : ProtonContactEmailLabelGateway {
    init {
        require(proofRetryDelayMillis >= 0)
    }

    override suspend fun apply(
        account: AccountScope,
        mutation: EmailLabelMutation,
    ): GatewayOutcome<Unit> {
        val groupId = mutation.groupId()
        val before = when (val outcome = membershipReader.members(account, groupId)) {
            is GatewayOutcome.Success -> outcome.value
            is GatewayOutcome.Failure -> return outcome
        }
        if (before.account != account || before.groupId != groupId) {
            return GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
        }
        val reconciled = mutation.remainingAfter(before.emailIds.toSet()) ?: return GatewayOutcome.Success(Unit)
        val write = mutationGateway.apply(account, reconciled)
        if (write is GatewayOutcome.Failure && write.category !in AMBIGUOUS_DELIVERY_FAILURES) return write

        stageObserver.onStage(ProtonEmailGroupAssignmentStage.PROOF)
        var after = membershipReader.members(account, groupId)
        if (after is GatewayOutcome.Failure && after.category in TRANSIENT_READ_FAILURES) {
            delay(proofRetryDelayMillis)
            after = membershipReader.members(account, groupId)
        }
        return if (
            after is GatewayOutcome.Success &&
            after.value.account == account &&
            after.value.groupId == groupId &&
            mutation.isSatisfiedBy(after.value.emailIds.toSet())
        ) {
            GatewayOutcome.Success(Unit)
        } else {
            when {
                write is GatewayOutcome.Failure -> write
                after is GatewayOutcome.Failure -> after
                else -> GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
            }
        }
    }

    private fun EmailLabelMutation.groupId(): RemoteGroupId = when (this) {
        is EmailLabelMutation.Assign -> groupId
        is EmailLabelMutation.Remove -> groupId
    }

    private fun EmailLabelMutation.remainingAfter(current: Set<RemoteEmailId>): EmailLabelMutation? = when (this) {
        is EmailLabelMutation.Assign -> emailIds.filterNot(current::contains)
            .takeIf(List<RemoteEmailId>::isNotEmpty)?.let { EmailLabelMutation.Assign(groupId, it) }
        is EmailLabelMutation.Remove -> emailIds.filter(current::contains)
            .takeIf(List<RemoteEmailId>::isNotEmpty)?.let { EmailLabelMutation.Remove(groupId, it) }
    }

    private fun EmailLabelMutation.isSatisfiedBy(current: Set<RemoteEmailId>): Boolean = when (this) {
        is EmailLabelMutation.Assign -> emailIds.all(current::contains)
        is EmailLabelMutation.Remove -> emailIds.none(current::contains)
    }

    private companion object {
        val AMBIGUOUS_DELIVERY_FAILURES = setOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            GatewayFailureCategory.MALFORMED_RESPONSE,
            GatewayFailureCategory.UNKNOWN,
        )
        val TRANSIENT_READ_FAILURES = setOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
        )
    }
}

internal class ProtonEmailGroupAssignmentAdapter(
    private val expectedAccount: AccountScope,
    private val transport: ProtonEmailGroupAssignmentWireTransport,
    private val stageObserver: ProtonEmailGroupAssignmentStageObserver = ProtonEmailGroupAssignmentStageObserver { },
) : ProtonContactEmailLabelGateway {
    override suspend fun apply(
        account: AccountScope,
        mutation: EmailLabelMutation,
    ): GatewayOutcome<Unit> = assignmentGatewayCall {
        if (account != expectedAccount) throw ProtonAuthenticationRequired()
        val (operation, groupId, emailIds) = when (mutation) {
            is EmailLabelMutation.Assign -> Triple(
                EmailGroupAssignmentWireOperation.ASSIGN,
                mutation.groupId,
                mutation.emailIds,
            )
            is EmailLabelMutation.Remove -> Triple(
                EmailGroupAssignmentWireOperation.REMOVE,
                mutation.groupId,
                mutation.emailIds,
            )
        }
        validateMutation(groupId, emailIds)
        val body = JsonObject(
            linkedMapOf(
                "LabelID" to JsonPrimitive(groupId.value),
                "ContactEmailIDs" to JsonArray(emailIds.map { JsonPrimitive(it.value) }),
            ),
        ).toString()
        require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_REQUEST_BYTES)
        stageObserver.onStage(ProtonEmailGroupAssignmentStage.HTTP)
        val response = transport.put(account, operation, body)
        stageObserver.onStage(ProtonEmailGroupAssignmentStage.PARSE)
        try {
            require(response.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES)
            val root = WIRE_JSON.parseToJsonElement(response).jsonObject
            val code = root["Code"]?.jsonPrimitive ?: throw ProtonMalformedContactResponse()
            if (code.isString || code.intOrNull != 1_000) throw ProtonMalformedContactResponse()
        } catch (malformed: ProtonMalformedContactResponse) {
            throw malformed
        } catch (_: IllegalArgumentException) {
            throw ProtonMalformedContactResponse()
        }
    }

    private fun validateMutation(groupId: RemoteGroupId, emailIds: List<RemoteEmailId>) {
        require(groupId.value.length <= MAX_IDENTIFIER_CHARS)
        require(emailIds.isNotEmpty() && emailIds.size <= EmailLabelMutation.MAX_EMAIL_IDS_PER_MUTATION)
        require(emailIds.distinct().size == emailIds.size)
        require(emailIds.all { it.value.length <= MAX_IDENTIFIER_CHARS })
    }

    private companion object {
        const val MAX_IDENTIFIER_CHARS = 4_096
        const val MAX_REQUEST_BYTES = 2 * 1_024 * 1_024
        const val MAX_RESPONSE_BYTES = 16 * 1_024
        val WIRE_JSON = Json { isLenient = false; ignoreUnknownKeys = true }
    }
}

private suspend inline fun <T> assignmentGatewayCall(
    crossinline block: suspend () -> T,
): GatewayOutcome<T> = try {
    GatewayOutcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    GatewayOutcome.Failure(
        when (error) {
            is ProtonMalformedContactResponse -> GatewayFailureCategory.MALFORMED_RESPONSE
            else -> error.toContactGatewayFailure()
        },
    )
}
