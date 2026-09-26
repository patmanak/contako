package com.patmanak.contako.domain.model

data class CanonicalContact(
    val accountId: String,
    val id: String,
    val firstName: String = "",
    val lastName: String = "",
    val displayName: String = "",
    val values: List<ContactValue> = emptyList(),
    val revision: Long = 0,
    val updatedAtEpochMillis: Long = 0,
    val remoteContactId: String? = null,
    val remoteVCardUid: String? = null,
    val remoteVersion: String? = null,
    val preservationEnvelope: PreservationEnvelope? = null,
    val actionRequiredReasons: Set<String> = emptySet(),
    val pendingMutationRevision: Long? = null,
    val conflictState: String? = null,
    val isDeleted: Boolean = false,
) {
    val fullName: String
        get() = listOf(firstName.trim(), lastName.trim())
            .filter(String::isNotEmpty)
            .joinToString(" ")

    val resolvedDisplayName: String
        get() = displayName.trim().ifEmpty {
            fullName.ifEmpty { "Unnamed contact" }
        }

    fun valuesOf(kind: ContactValueKind): List<ContactValue> =
        values.filter { it.kind == kind }.sortedBy(ContactValue::order)

    val isUploadEligible: Boolean
        get() = actionRequiredReasons.isEmpty() && !isDeleted

    /** Compatibility convenience for surfaces that display only the highest-priority correction. */
    val actionRequiredReason: String?
        get() = when {
            "MISSING_NAME" in actionRequiredReasons -> "MISSING_NAME"
            else -> actionRequiredReasons.sorted().firstOrNull()
        }
}

data class ContactValue(
    val id: String,
    val kind: ContactValueKind,
    val value: String,
    val label: String? = null,
    val order: Int,
    val isPrimary: Boolean = false,
    val components: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val binaryReference: String? = null,
    val preservationKey: String? = null,
)

enum class ContactValueKind {
    STRUCTURED_NAME,
    PHONETIC_NAME,
    EMAIL,
    PHONE,
    NICKNAME,
    NOTE,
    URL,
    POSTAL_ADDRESS,
    ORGANIZATION,
    TITLE,
    ROLE,
    PHOTO,
    LOGO,
    BIRTHDAY,
    ANNIVERSARY,
    CUSTOM_DATE,
    RELATIONSHIP,
    LANGUAGE,
    TIME_ZONE,
    GENDER,
    MEMBER,
    CATEGORY,
    PUBLIC_KEY,
    UNKNOWN_VCARD_PROPERTY,
}

data class PreservationEnvelope(
    val rawProperties: Map<String, String> = emptyMap(),
    val remoteBaseline: String? = null,
)

data class ContactGroup(
    val accountId: String,
    val id: String,
    val name: String,
    val color: String = ContactGroupDefaults.CREATE_COLOR,
    val order: Int = 0,
    val isVisible: Boolean = true,
    val revision: Long = 0,
    val updatedAtEpochMillis: Long = 0,
    val remoteLabelId: String? = null,
    val remoteVersion: String? = null,
    val memberships: List<GroupMembership> = emptyList(),
    val pendingMutationRevision: Long? = null,
    val conflictState: String? = null,
    val isDeleted: Boolean = false,
) {
    /** User-visible members are contacts, not Proton email-assignment occurrences. */
    val memberCount: Int
        get() = memberships.asSequence().map(GroupMembership::contactId).distinct().count()
}

/** Proton contact-group wire color; independent from Contako's UI theme primary token. */
object ContactGroupDefaults {
    const val CREATE_COLOR: String = "#8080FF"
}

data class GroupMembership(
    val contactId: String,
    val emailValueId: String,
)
