package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.domain.sync.TargetedRecoveryFailure

/** Durable storage keeps the exact gateway category; UI uses this closed, payload-free cause. */
internal fun GatewayFailureCategory.toTargetedRecoveryFailure(): TargetedRecoveryFailure = when (this) {
    GatewayFailureCategory.AUTHENTICATION_REQUIRED,
    GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED,
    GatewayFailureCategory.UNSUPPORTED_AUTHENTICATION -> TargetedRecoveryFailure.AUTHENTICATION
    GatewayFailureCategory.NETWORK_UNAVAILABLE,
    GatewayFailureCategory.TIMEOUT -> TargetedRecoveryFailure.NETWORK
    GatewayFailureCategory.RATE_LIMITED,
    GatewayFailureCategory.LOCAL_REQUEST_BUDGET_EXHAUSTED -> TargetedRecoveryFailure.RATE_LIMITED
    GatewayFailureCategory.VALIDATION_REJECTED -> TargetedRecoveryFailure.VALIDATION
    GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED -> TargetedRecoveryFailure.CRYPTOGRAPHY
    GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED -> TargetedRecoveryFailure.REMOTE_PERMISSION
    GatewayFailureCategory.CONFLICT -> TargetedRecoveryFailure.CONFLICT
    GatewayFailureCategory.REMOTE_SERVICE_FAILURE -> TargetedRecoveryFailure.REMOTE_SERVICE
    GatewayFailureCategory.MALFORMED_RESPONSE -> TargetedRecoveryFailure.MALFORMED_RESPONSE
    GatewayFailureCategory.ACCOUNT_ALREADY_CONNECTED,
    GatewayFailureCategory.CANCELLED,
    GatewayFailureCategory.NOT_FOUND,
    GatewayFailureCategory.CLIENT_IDENTITY_REJECTED,
    GatewayFailureCategory.UNKNOWN -> TargetedRecoveryFailure.UNKNOWN
}
