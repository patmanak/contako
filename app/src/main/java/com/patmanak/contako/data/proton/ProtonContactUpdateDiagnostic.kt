package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.domain.model.ContactValueKind

enum class ProtonContactUpdateStage { VALIDATE, ENCODE, PROTECT, VERIFY_PREPARED, REMOTE_UPDATE, READBACK }
enum class ProtonContactEncodingStage { SOURCE_BASELINE, FIELDS, PRIVATE_CARD, SIGNED_CARD, CLEAR_CARD, SERIALIZED_BOUNDS }

/** Closed metadata only. No contact, field value, exception or server message crosses this boundary. */
fun interface ProtonContactUpdateFailureObserver {
    fun onFailure(stage: ProtonContactUpdateStage, category: GatewayFailureCategory,
        encodingStage: ProtonContactEncodingStage?, field: ContactValueKind?)
}

internal class ProtonContactValueValidationException(val field: ContactValueKind) : IllegalArgumentException()
internal class ProtonContactEncodingException(val stage: ProtonContactEncodingStage,
    val field: ContactValueKind?) : IllegalArgumentException()

internal inline fun <T> contactEncodingStep(stage: ProtonContactEncodingStage, block: () -> T): T =
    try { block() } catch (failure: ProtonContactEncodingException) { throw failure }
    catch (failure: IllegalArgumentException) {
        throw ProtonContactEncodingException(stage, (failure as? ProtonContactValueValidationException)?.field)
    }
