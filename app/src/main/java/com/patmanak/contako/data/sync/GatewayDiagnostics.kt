package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.GatewayOutcome

/** Closed failure boundary, independent from retry eligibility and free of peer identifiers. */
enum class GatewayDiagnosticBoundary {
    SESSION_RESTORE, ANDROID_PREFLIGHT, EVENT_READ, INVENTORY_READ, CARD_READ,
    DELETION_CHECK, MUTATION_PREPARATION, MUTATION_UPLOAD, GROUP_READ,
}

fun interface GatewayFailureObserver {
    fun onFailure(boundary: GatewayDiagnosticBoundary, failure: GatewayOutcome.Failure,
        operation: RemoteMutationOperation?)
}
