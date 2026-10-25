package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.domain.sync.TargetedRecoveryFailure
import org.junit.Assert.*
import org.junit.Test

class TargetedRecoveryFailureMappingTest {
    @Test fun knownReadFailuresKeepTheirActionableCauseWhileUnprovedAbsenceAndIdentityErrorsStayUnknown() {
        val expected = mapOf(
            GatewayFailureCategory.AUTHENTICATION_REQUIRED to TargetedRecoveryFailure.AUTHENTICATION,
            GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED to TargetedRecoveryFailure.REMOTE_PERMISSION,
            GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED to TargetedRecoveryFailure.CRYPTOGRAPHY,
            GatewayFailureCategory.MALFORMED_RESPONSE to TargetedRecoveryFailure.MALFORMED_RESPONSE,
            GatewayFailureCategory.RATE_LIMITED to TargetedRecoveryFailure.RATE_LIMITED,
            GatewayFailureCategory.TIMEOUT to TargetedRecoveryFailure.NETWORK,
            GatewayFailureCategory.NOT_FOUND to TargetedRecoveryFailure.UNKNOWN,
            GatewayFailureCategory.CLIENT_IDENTITY_REJECTED to TargetedRecoveryFailure.UNKNOWN,
            GatewayFailureCategory.UNKNOWN to TargetedRecoveryFailure.UNKNOWN,
        )
        expected.forEach { (category, cause) -> assertEquals(cause, category.toTargetedRecoveryFailure()) }
    }
}
