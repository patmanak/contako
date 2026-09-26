package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.AndroidProjectionFingerprint
import com.patmanak.contako.domain.model.CanonicalContact

/** Stable canonical identity plus an explicitly replaceable provider-row locator. */
internal data class AndroidValueIdentity(
    val canonicalValueId: String,
    val providerRowId: Long? = null,
) {
    init {
        require(canonicalValueId.isNotBlank())
        require(providerRowId == null || providerRowId > 0)
    }

    fun withoutProviderLocator(): AndroidValueIdentity = copy(providerRowId = null)

    override fun toString(): String =
        "AndroidValueIdentity(REDACTED, hasProviderLocator=${providerRowId != null})"
}

internal enum class AndroidRowKind {
    STRUCTURED_NAME,
    EMAIL,
    PHONE,
    POSTAL_ADDRESS,
    ORGANIZATION,
    PHOTO,
    NICKNAME,
    NOTE,
    WEBSITE,
    BIRTHDAY,
    ANNIVERSARY,
    CUSTOM_DATE,
    RELATIONSHIP,
}

internal enum class AndroidSemanticType {
    UNSPECIFIED,
    HOME,
    WORK,
    OTHER,
    MOBILE,
    FAX_HOME,
    FAX_WORK,
    OTHER_FAX,
    PAGER,
    CALLBACK,
    CAR,
    COMPANY_MAIN,
    ISDN,
    MAIN,
    RADIO,
    TELEX,
    TTY_TDD,
    WORK_MOBILE,
    WORK_PAGER,
    ASSISTANT,
    MMS,
    BLOG,
    PROFILE,
    FTP,
    CHILD,
    BROTHER,
    DOMESTIC_PARTNER,
    FATHER,
    FRIEND,
    MANAGER,
    MOTHER,
    PARENT,
    PARTNER,
    REFERRED_BY,
    RELATIVE,
    SISTER,
    SPOUSE,
    CUSTOM,
}

internal enum class AndroidComponent {
    DISPLAY_NAME,
    GIVEN_NAME,
    MIDDLE_NAME,
    FAMILY_NAME,
    PREFIX,
    SUFFIX,
    PHONETIC_GIVEN_NAME,
    PHONETIC_MIDDLE_NAME,
    PHONETIC_FAMILY_NAME,
    PO_BOX,
    EXTENDED_ADDRESS,
    STREET,
    LOCALITY,
    REGION,
    POSTCODE,
    COUNTRY,
    FORMATTED_ADDRESS,
    COMPANY,
    DEPARTMENT,
    TITLE,
    ROLE,
}

/** Additional canonical values represented by one standard Android row. */
internal enum class AndroidLinkedValueRole { PHONETIC_NAME, TITLE, ROLE }

internal data class AndroidContactRow(
    val identity: AndroidValueIdentity,
    val kind: AndroidRowKind,
    val value: String = "",
    val semanticType: AndroidSemanticType = AndroidSemanticType.UNSPECIFIED,
    val customLabel: String? = null,
    val order: Int = 0,
    val isPrimary: Boolean = false,
    val isSuperPrimary: Boolean = false,
    val components: Map<AndroidComponent, String> = emptyMap(),
    val linkedCanonicalValueIds: Map<AndroidLinkedValueRole, String> = emptyMap(),
    val binaryReference: String? = null,
) {
    init {
        require(order >= 0)
        require(semanticType == AndroidSemanticType.CUSTOM || customLabel == null)
        require(customLabel == null || customLabel.isNotBlank())
        require(linkedCanonicalValueIds.values.none(String::isBlank))
        require(linkedCanonicalValueIds.values.distinct().size == linkedCanonicalValueIds.size)
        require(identity.canonicalValueId !in linkedCanonicalValueIds.values)
    }

    fun withoutProviderLocator(): AndroidContactRow = copy(identity = identity.withoutProviderLocator())

    override fun toString(): String =
        "AndroidContactRow(REDACTED, kind=$kind, order=$order, " +
            "primary=$isPrimary, superPrimary=$isSuperPrimary)"
}

internal data class AndroidContactSnapshot(
    val canonicalContactId: String,
    val rows: List<AndroidContactRow>,
) {
    init {
        require(canonicalContactId.isNotBlank())
        require(rows.map { it.identity.canonicalValueId }.distinct().size == rows.size)
        require(rows.flatMap { row -> row.linkedCanonicalValueIds.values }.distinct().size ==
            rows.sumOf { it.linkedCanonicalValueIds.size })
    }

    override fun toString(): String = "AndroidContactSnapshot(REDACTED, rowCount=${rows.size})"
}

internal sealed interface AndroidRowOperation {
    data class Insert(val desired: AndroidContactRow) : AndroidRowOperation {
        init {
            require(desired.identity.providerRowId == null)
        }
    }

    data class Update(
        val currentIdentity: AndroidValueIdentity,
        val desired: AndroidContactRow,
    ) : AndroidRowOperation {
        init {
            require(currentIdentity.providerRowId != null)
            require(currentIdentity.canonicalValueId == desired.identity.canonicalValueId)
            require(desired.identity.providerRowId == null)
        }
    }

    data class Delete(val currentIdentity: AndroidValueIdentity) : AndroidRowOperation {
        init {
            require(currentIdentity.providerRowId != null)
        }
    }
}

internal data class AndroidProjectionPlan(
    val desired: AndroidContactSnapshot,
    val fingerprint: AndroidProjectionFingerprint,
    val operations: List<AndroidRowOperation>,
) {
    override fun toString(): String =
        "AndroidProjectionPlan(REDACTED, operationCount=${operations.size})"
}

internal data class AndroidCanonicalDelta(
    val contact: CanonicalContact,
    val observedFingerprint: AndroidProjectionFingerprint,
    val resultingCanonicalFingerprint: AndroidProjectionFingerprint,
    val changedValueIds: Set<String>,
) {
    val hasChanges: Boolean get() = changedValueIds.isNotEmpty()

    override fun toString(): String =
        "AndroidCanonicalDelta(REDACTED, changedValueCount=${changedValueIds.size})"
}
