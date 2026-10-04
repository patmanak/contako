package com.patmanak.contako.data.sync

import java.util.concurrent.atomic.AtomicIntegerArray
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.provider.AndroidPhotoProviderRepairCategory
import com.patmanak.contako.data.android.provider.AndroidProviderIdentityFailure
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure

/**
 * Closed, payload-free reasons for a single-contact Android projection repair.
 *
 * The observer boundary deliberately accepts no account, contact, provider locator, value, error
 * text, or throwable. Categories MAY be aggregated locally; they MUST NOT be enriched with stable
 * identifiers or contact payloads.
 */
internal enum class AndroidProjectionRepairCategory {
    LOCAL_LEDGER_STATE,
    CANONICAL_CONTACT_STATE,
    RAW_CONTACT_ADOPTION,
    PROVIDER_OWNERSHIP,
    CONTACT_DECODING,
    MEMBERSHIP_STATE,
    MEMBERSHIP_BASELINE_MISSING,
    MEMBERSHIP_BASELINE_INTEGRITY,
    MEMBERSHIP_LEDGER_FINGERPRINT,
    MEMBERSHIP_LEDGER_BINDING,
    MEMBERSHIP_CANONICAL_CONTEXT,
    GROUP_STATE,
    GROUP_STATE_SCOPE,
    GROUP_STATE_STALE_CONTEXT,
    GROUP_STATE_UNTRUSTED_IDENTITY,
    GROUP_STATE_DUPLICATE_LOCATOR,
    GROUP_STATE_BOUND_EXCEEDED,
    WRITE_PREPARATION,
    PROVIDER_IDENTITY_REGISTRATION,
    PHOTO_PAYLOAD,
    PHOTO_WRITE,
    INTERRUPTED_PHOTO_RECOVERY_UNVERIFIED,
    POST_WRITE_IDENTITY,
    POST_WRITE_CONTACT_VERIFICATION,
    POST_WRITE_CONTACT_ROW_SET,
    POST_WRITE_CONTACT_FLAGS,
    POST_WRITE_CONTACT_FLAGS_NAME_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_NAME_SUPER_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_PHOTO_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_PHOTO_SUPER_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_OTHER_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_OTHER_SUPER_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_EMAIL_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_PHONE_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_POSTAL_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_WEBSITE_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_DATE_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_RELATIONSHIP_PRIMARY,
    POST_WRITE_CONTACT_FLAGS_ORGANIZATION_PRIMARY,
    POST_WRITE_CONTACT_VALUE,
    POST_WRITE_CONTACT_VALUE_NAME,
    POST_WRITE_CONTACT_VALUE_PHOTO,
    POST_WRITE_CONTACT_VALUE_DATE,
    POST_WRITE_CONTACT_VALUE_OTHER,
    POST_WRITE_CONTACT_TYPE,
    POST_WRITE_CONTACT_TYPE_EMAIL,
    POST_WRITE_CONTACT_TYPE_PHONE,
    POST_WRITE_CONTACT_TYPE_POSTAL,
    POST_WRITE_CONTACT_TYPE_ORGANIZATION,
    POST_WRITE_CONTACT_TYPE_WEBSITE,
    POST_WRITE_CONTACT_TYPE_DATE,
    POST_WRITE_CONTACT_TYPE_RELATIONSHIP,
    POST_WRITE_CONTACT_TYPE_OTHER,
    POST_WRITE_CONTACT_ORDER,
    POST_WRITE_CONTACT_COMPONENTS,
    POST_WRITE_CONTACT_LINKS,
    POST_WRITE_CONTACT_PHOTO_REFERENCE,
    POST_WRITE_MEMBERSHIP_VERIFICATION,
    POST_WRITE_MEMBERSHIP_DECODING,
    LEDGER_COMPLETION,
    PROVIDER_PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_MALFORMED_DATA,
    PROVIDER_BOUND_EXCEEDED,
    PROVIDER_ACCOUNT_SCOPE_MISMATCH,
    RAW_CONTACT_LIFECYCLE,
    MIME_ROUTING,
    ROW_DECODING,
    ROW_DECODING_IDENTITY,
    ROW_DECODING_CARDINALITY,
    ROW_DECODING_PHOTO,
    ROW_DECODING_PAYLOAD,
    ROW_DECODING_MALFORMED_ROW,
    ROW_DECODING_PHOTO_BINARY_MISSING,
    ROW_DECODING_UNEXPECTED_BINARY,
    ROW_DECODING_MALFORMED_TYPE,
    ROW_DECODING_MALFORMED_DATE,
    ROW_DECODING_MALFORMED_ORDER,
    ROW_DECODING_MALFORMED_LINKS,
    ROW_DECODING_BOUND_EXCEEDED,
    MEMBERSHIP_DECODING,
    CONTACT_MAPPING,
    CONTACT_PLANNING,
    GROUP_CATALOG,
    GROUP_MAPPING,
    MEMBERSHIP_PLANNING,
    MEMBERSHIP_SNAPSHOT,
    CANONICAL_DECODING,
    PROVIDER_OBSERVATION,
    CURRENT_CONTACT_DECODING,
    MEMBERSHIP_BASELINE_DECODING,
    CURRENT_MEMBERSHIP_DECODING,
    WRITE_LEDGER,
    PROVIDER_WRITE,
    POST_WRITE_OBSERVATION,
    POST_WRITE_CONTACT_DECODING,
    POST_WRITE_FINGERPRINT,
    LEDGER_COMPLETION_ARGUMENT,
    INVALID_ARGUMENT,
    INVARIANT_VIOLATION,
}

internal fun interface AndroidProjectionRepairObserver {
    fun onRepair(category: AndroidProjectionRepairCategory)
    fun onComponentMismatch(kind: AndroidRowKind, component: AndroidComponent, difference: AndroidComponentDifference) {}
    fun onPhotoFailure(category: AndroidPhotoProviderRepairCategory) {}
    fun onBindingRecoveryFailure(
        stage: AndroidProjectionDecodeStage,
        detail: com.patmanak.contako.data.android.provider.AndroidBindingRecoveryDiagnostic,
    ) {}
    fun onRowFailure(
        stage: AndroidProjectionDecodeStage,
        category: AndroidProviderRowCodecFailure,
        identity: AndroidProviderIdentityFailure?,
        kind: AndroidRowKind?,
    ) {}
}

internal enum class AndroidProjectionDecodeStage { CURRENT, POST_WRITE }

/** Describes an unequal component without its value, length, hash or identity. */
internal enum class AndroidComponentDifference { EXPECTED_EMPTY, OBSERVED_EMPTY, WHITESPACE_ONLY, DIFFERENT }

internal enum class AndroidProjectionReplanCategory {
    LEDGER_CONTEXT,
    ADOPTION_ACKNOWLEDGEMENT,
    CURRENT_OBSERVATION,
    CURRENT_OBSERVATION_DIRTY,
    STALE_PREPARATION_RESET,
    WRITE_PREPARATION,
    PROVIDER_WRITE,
    POST_WRITE_OBSERVATION,
    PHOTO_JOURNAL_PREPARATION,
    PHOTO_PRE_WRITE_OBSERVATION,
    PHOTO_PRE_WRITE_VERSION,
    PHOTO_PRE_WRITE_UPDATE,
    PHOTO_PRE_WRITE_INLINE_BLOB,
    PHOTO_PRE_WRITE_PAYLOAD,
    PHOTO_POST_STREAM_OBSERVATION,
    PHOTO_POST_STREAM_PHOTO_ROW,
    PHOTO_POST_STREAM_DISPLAY_PHOTO,
    PHOTO_POST_BIND_OBSERVATION,
    PHOTO_JOURNAL_COMMIT,
    POST_PHOTO_OBSERVATION,
    LEDGER_COMPLETION,
}

internal fun interface AndroidProjectionReplanObserver {
    fun onReplan(category: AndroidProjectionReplanCategory)
}

internal class AndroidProjectionReplanAggregate : AndroidProjectionReplanObserver {
    private val counts = AtomicIntegerArray(AndroidProjectionReplanCategory.entries.size)

    override fun onReplan(category: AndroidProjectionReplanCategory) {
        val index = category.ordinal
        while (true) {
            val current = counts.get(index)
            if (current == Int.MAX_VALUE) return
            if (counts.compareAndSet(index, current, current + 1)) return
        }
    }

    fun snapshotAndReset(): Map<AndroidProjectionReplanCategory, Int> = buildMap {
        AndroidProjectionReplanCategory.entries.forEach { category ->
            counts.getAndSet(category.ordinal, 0).takeIf { it > 0 }?.let { put(category, it) }
        }
    }
}

/** Thread-safe, bounded aggregate with saturating category counters. */
internal class AndroidProjectionRepairAggregate : AndroidProjectionRepairObserver {
    private val counts = AtomicIntegerArray(AndroidProjectionRepairCategory.entries.size)

    override fun onRepair(category: AndroidProjectionRepairCategory) {
        val index = category.ordinal
        while (true) {
            val current = counts.get(index)
            if (current == Int.MAX_VALUE) return
            if (counts.compareAndSet(index, current, current + 1)) return
        }
    }

    fun snapshotAndReset(): Map<AndroidProjectionRepairCategory, Int> = buildMap {
        AndroidProjectionRepairCategory.entries.forEach { category ->
            counts.getAndSet(category.ordinal, 0).takeIf { it > 0 }?.let { put(category, it) }
        }
    }
}
