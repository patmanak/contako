package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.policy.ContactValueSupport

internal enum class ProtonFieldSupport { GENERATED, LOCAL_ONLY, PRESERVED_READ_ONLY, UNKNOWN }
internal enum class AndroidFieldScope { ALL_OCCURRENCES, PRIMARY_ONLY, NONE, UNKNOWN }

/** Format/projection facts only. Neither this description nor a row proves peer delivery. */
internal data class FieldCompatibilityDescription(
    val kind: ContactValueKind,
    val proton: ProtonFieldSupport,
    val android: AndroidFieldScope,
    val represented: Boolean?,
    val localLabelOnly: Boolean,
    val preferredEmail: Boolean? = null,
) {
    override fun toString() = "FieldCompatibilityDescription(kind=$kind, proton=$proton, android=$android, " +
        "represented=$represented, localLabelOnly=$localLabelOnly, preferredEmail=$preferredEmail)"
}

/**
 * Pure explanation of the existing mapper and generated-Proton policy, never a validator.
 * The default mapper models theoretical PHOTO representation without resolving its reference:
 * offline bytes, permissions, account capability, provider receipts and editor visibility are
 * independent facts. No supplied contact, media reference or identifier is cached.
 */
internal object ContactFieldCompatibility {
    private val mapper = CanonicalAndroidContactMapper()

    // Keep only enum facts. Synthetic valid occurrences exercise the real production projection,
    // including phonetic/title/role IDs linked into another row rather than a separate MIME row.
    private val androidScopes: Map<ContactValueKind, AndroidFieldScope> by lazy {
        ContactValueKind.entries.associateWith { kind ->
            val probe = probeContact(kind)
            val represented = representedIds(mapper.project(probe))
            when (probe.values.count { it.id in represented }) {
                2 -> AndroidFieldScope.ALL_OCCURRENCES
                1 -> AndroidFieldScope.PRIMARY_ONLY
                0 -> AndroidFieldScope.NONE
                else -> AndroidFieldScope.UNKNOWN
            }
        }
    }

    fun describeFamily(kind: ContactValueKind): FieldCompatibilityDescription = FieldCompatibilityDescription(
        kind = kind,
        proton = protonSupport(kind),
        android = androidScopes.getValue(kind),
        represented = null,
        localLabelOnly = false,
    )

    fun describe(contact: CanonicalContact, valueId: String): FieldCompatibilityDescription {
        val candidates = contact.values.filter { it.id == valueId }
        val value = candidates.singleOrNull()
        if (value == null || value.id.isBlank() || contact.values.map { it.id }.distinct().size != contact.values.size) {
            return unknown(candidates.map { it.kind }.distinct().singleOrNull() ?: ContactValueKind.UNKNOWN_VCARD_PROPERTY)
        }
        val represented = try {
            valueId in representedIds(mapper.project(contact))
        } catch (_: IllegalArgumentException) {
            // An unclassifiable input is retained. Help must not turn it into a positive claim.
            return unknown(value.kind)
        }
        return describeFamily(value.kind).copy(
            represented = represented,
            localLabelOnly = ContactValueSupport.hasLocalOnlyLabel(value),
            preferredEmail = if (value.kind == ContactValueKind.EMAIL) {
                CanonicalPrimaryValuePolicy.preferredEmail(contact)?.id == valueId
            } else null,
        )
    }

    /** Unsaved label intent is explanatory only; durable provenance is established at Save. */
    fun describeDraft(draft: CanonicalContact, original: CanonicalContact?, valueId: String): FieldCompatibilityDescription {
        val facts = describe(draft, valueId)
        if (facts.proton == ProtonFieldSupport.UNKNOWN) return facts
        if (original != null && (original.accountId != draft.accountId || original.id != draft.id)) {
            return unknown(facts.kind)
        }
        val value = draft.values.single { it.id == valueId }
        val previous = original?.values.orEmpty().filter { it.id == valueId }
        if (previous.size > 1) return unknown(facts.kind)
        val labelEdited = previous.singleOrNull()?.let { it.label != value.label } ?: true
        return facts.copy(localLabelOnly = facts.localLabelOnly ||
            (labelEdited && ContactValueSupport.hasUnsupportedGeneratedLabel(value)))
    }

    fun needsExplanation(kind: ContactValueKind): Boolean =
        protonSupport(kind) != ProtonFieldSupport.GENERATED ||
            androidScopes.getValue(kind) != AndroidFieldScope.ALL_OCCURRENCES ||
            kind in setOf(ContactValueKind.EMAIL, ContactValueKind.PHOTO, ContactValueKind.URL,
                ContactValueKind.RELATIONSHIP, ContactValueKind.POSTAL_ADDRESS)

    private fun protonSupport(kind: ContactValueKind): ProtonFieldSupport = when {
        ContactValueSupport.isLocalOnlyValue(kind) -> ProtonFieldSupport.LOCAL_ONLY
        kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY -> ProtonFieldSupport.PRESERVED_READ_ONLY
        else -> ProtonFieldSupport.GENERATED
    }

    private fun unknown(kind: ContactValueKind) = FieldCompatibilityDescription(
        kind, ProtonFieldSupport.UNKNOWN, AndroidFieldScope.UNKNOWN, represented = null, localLabelOnly = false,
    )

    private fun representedIds(snapshot: AndroidContactSnapshot): Set<String> = snapshot.rows.flatMap { row ->
        listOf(row.identity.canonicalValueId) + row.linkedCanonicalValueIds.values
    }.toSet()

    private fun probeContact(kind: ContactValueKind) = CanonicalContact(
        accountId = "compatibility-probe", id = "compatibility-probe", displayName = "Synthetic compatibility",
        values = (0..1).map { index ->
            val text = when (kind) {
                ContactValueKind.EMAIL -> "occurrence-$index@example.test"
                ContactValueKind.PHONE -> "+3360000000${index + 1}"
                ContactValueKind.URL -> "https://example.test/profile/$index"
                ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY, ContactValueKind.CUSTOM_DATE -> "2000-01-0${index + 1}"
                ContactValueKind.PHOTO, ContactValueKind.LOGO -> INLINE_PROBE_IMAGE
                ContactValueKind.LANGUAGE -> "en"
                ContactValueKind.TIME_ZONE -> "+01:00"
                ContactValueKind.GENDER -> "U"
                ContactValueKind.UNKNOWN_VCARD_PROPERTY -> "X-SYNTHETIC:occurrence-$index"
                else -> "Synthetic occurrence $index"
            }
            val components = when (kind) {
                ContactValueKind.STRUCTURED_NAME, ContactValueKind.PHONETIC_NAME ->
                    mapOf("given" to "Synthetic $index", "family" to "Example $index")
                ContactValueKind.POSTAL_ADDRESS -> mapOf("street" to "${index + 1} Synthetic Lane", "locality" to "Example")
                ContactValueKind.ORGANIZATION -> mapOf("company" to "Synthetic $index", "department" to "Examples")
                else -> emptyMap()
            }
            ContactValue(id = "compatibility-occurrence-$index", kind = kind, value = text, order = index,
                components = components, binaryReference = if (kind in setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO)) text else null)
        },
    )

    private const val INLINE_PROBE_IMAGE = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
}
