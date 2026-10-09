package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.AndroidProjectionFingerprint
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.policy.PostalAddressPolicy
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Pure canonical/Android mapping. Provider queries and constants belong to the provider gateway. */
internal class CanonicalAndroidContactMapper(
    /**
     * Reports whether a photo reference can be resolved to bytes offline.
     *
     * Defaults to accepting every reference so existing callers and tests keep their behavior;
     * production passes the loader-backed check.
     */
    private val photoBytesAvailable: (String?) -> Boolean = { true },
) {
    fun adoptAndroidCreatedContact(
        accountId: String,
        canonicalContactId: String,
        observed: AndroidContactSnapshot,
    ): CanonicalContact {
        require(accountId.isNotBlank())
        require(observed.canonicalContactId == canonicalContactId)
        return applyControlledDelta(
            canonical = CanonicalContact(accountId = accountId, id = canonicalContactId),
            baseline = AndroidContactSnapshot(canonicalContactId, emptyList()),
            observed = observed,
        ).contact
    }

    fun project(contact: CanonicalContact): AndroidContactSnapshot {
        require(contact.id.isNotBlank())
        val rows = buildList {
            add(projectName(contact))
            addAll(contact.projectAll(ContactValueKind.EMAIL, AndroidRowKind.EMAIL))
            addAll(contact.projectAll(ContactValueKind.PHONE, AndroidRowKind.PHONE))
            addAll(contact.projectAll(ContactValueKind.POSTAL_ADDRESS, AndroidRowKind.POSTAL_ADDRESS))
            addAll(contact.projectAll(ContactValueKind.URL, AndroidRowKind.WEBSITE))
            addAll(contact.projectAll(ContactValueKind.CUSTOM_DATE, AndroidRowKind.CUSTOM_DATE))
            addAll(contact.projectAll(ContactValueKind.RELATIONSHIP, AndroidRowKind.RELATIONSHIP))
            projectOrganization(contact)?.let(::add)
            // A PHOTO row without resolvable bytes is rejected by the provider row codec as
            // MALFORMED_ROW, which failed the whole projection page. Proton stores remote photo
            // URIs that this offline boundary cannot fetch, so such a contact projects without a
            // photo rather than blocking every following contact.
            projectPrimary(contact, ContactValueKind.PHOTO, AndroidRowKind.PHOTO)
                ?.takeIf { photoBytesAvailable(it.binaryReference) }
                ?.let(::add)
            projectPrimary(contact, ContactValueKind.NICKNAME, AndroidRowKind.NICKNAME)?.let(::add)
            projectPrimary(contact, ContactValueKind.NOTE, AndroidRowKind.NOTE)?.let(::add)
            projectPrimary(contact, ContactValueKind.BIRTHDAY, AndroidRowKind.BIRTHDAY)?.let(::add)
            projectPrimary(contact, ContactValueKind.ANNIVERSARY, AndroidRowKind.ANNIVERSARY)?.let(::add)
        }
        return AndroidContactSnapshot(contact.id, rows)
    }

    fun fingerprint(snapshot: AndroidContactSnapshot): AndroidProjectionFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val singleEmailPrimaryIsImplicit = snapshot.rows.count { it.kind == AndroidRowKind.EMAIL } == 1
        digest.put(snapshot.canonicalContactId)
        snapshot.rows.sortedWith(compareBy<AndroidContactRow> { it.identity.canonicalValueId }.thenBy { it.kind.name })
            .forEach { row ->
                digest.put(row.identity.canonicalValueId)
                digest.put(row.kind.name)
                digest.put(row.value)
                digest.put(row.semanticType.name)
                digest.put(row.customLabel.orEmpty())
                digest.put(row.order.toString())
                digest.put(row.semanticPrimary(singleEmailPrimaryIsImplicit).toString())
                digest.put(row.semanticSuperPrimary().toString())
                AndroidComponent.entries.forEach { component ->
                    digest.put(component.name)
                    digest.put(row.components[component].orEmpty())
                }
                AndroidLinkedValueRole.entries.forEach { role ->
                    digest.put(role.name)
                    digest.put(row.linkedCanonicalValueIds[role].orEmpty())
                }
                digest.put(row.binaryReference.orEmpty())
            }
        return AndroidProjectionFingerprint(
            digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            },
        )
    }

    /** Comparison-only view matching fingerprint semantics; never persist as an ingestion baseline. */
    fun projectionComparisonSnapshot(snapshot: AndroidContactSnapshot): AndroidContactSnapshot {
        val singleEmailPrimaryIsImplicit = snapshot.rows.count { it.kind == AndroidRowKind.EMAIL } == 1
        return snapshot.copy(rows = snapshot.rows.map { row ->
            row.withoutProviderLocator().copy(
                isPrimary = row.semanticPrimary(singleEmailPrimaryIsImplicit),
                isSuperPrimary = if (row.kind == AndroidRowKind.PHOTO) false else row.semanticSuperPrimary(),
                components = row.components.filterValues(String::isNotEmpty),
                binaryReference = row.binaryReference?.takeIf(String::isNotEmpty),
            )
        })
    }

    fun planProjection(
        current: AndroidContactSnapshot,
        canonical: CanonicalContact,
    ): AndroidProjectionPlan {
        require(current.canonicalContactId == canonical.id)
        val desired = project(canonical)
        val singleEmailPrimaryIsImplicit =
            current.rows.count { it.kind == AndroidRowKind.EMAIL } == 1 &&
                desired.rows.count { it.kind == AndroidRowKind.EMAIL } == 1
        val currentById = normalizeGeneratedName(current, desired).rows.associateBy { it.identity.canonicalValueId }
        val desiredById = desired.rows.associateBy { it.identity.canonicalValueId }
        val operations = buildList {
            currentById.values.forEach { existing ->
                val replacement = desiredById[existing.identity.canonicalValueId]
                when {
                    replacement == null -> add(AndroidRowOperation.Delete(existing.identity.requireProviderLocator()))
                    !existing.samePayload(replacement, singleEmailPrimaryIsImplicit) -> add(
                        AndroidRowOperation.Update(
                            currentIdentity = existing.identity.requireProviderLocator(),
                            desired = replacement.withoutProviderLocator().let {
                                // PHOTO super-primary is the aggregate's native display choice,
                                // not a Proton preference. Preserve it on real payload updates.
                                if (it.kind == AndroidRowKind.PHOTO) it.copy(isSuperPrimary = existing.isSuperPrimary) else it
                            },
                        ),
                    )
                }
            }
            desired.rows.forEach { replacement ->
                if (replacement.identity.canonicalValueId !in currentById) {
                    add(AndroidRowOperation.Insert(replacement.withoutProviderLocator()))
                }
            }
        }
        return AndroidProjectionPlan(desired, fingerprint(desired), operations)
    }

    fun applyControlledDelta(
        canonical: CanonicalContact,
        baseline: AndroidContactSnapshot,
        observed: AndroidContactSnapshot,
    ): AndroidCanonicalDelta {
        require(baseline.canonicalContactId == canonical.id)
        require(observed.canonicalContactId == canonical.id)
        val observedFingerprint = fingerprint(observed)
        if (observedFingerprint == fingerprint(baseline)) {
            return AndroidCanonicalDelta(
                contact = canonical,
                observedFingerprint = observedFingerprint,
                resultingCanonicalFingerprint = fingerprint(project(canonical)),
                changedValueIds = emptySet(),
            )
        }

        val baselineById = baseline.rows.associateBy { it.identity.canonicalValueId }
        val observedById = observed.rows.associateBy { it.identity.canonicalValueId }
        val singleEmailPrimaryIsImplicit =
            baseline.rows.count { it.kind == AndroidRowKind.EMAIL } == 1 &&
                observed.rows.count { it.kind == AndroidRowKind.EMAIL } == 1
        var updated = canonical

        baseline.rows.forEach { before ->
            val after = observedById[before.identity.canonicalValueId]
            when {
                after == null -> {
                    updated = removeProjectedRow(updated, before)
                }
                !before.samePayload(after, singleEmailPrimaryIsImplicit) -> {
                    updated = applyObservedRow(updated, before, after)
                }
            }
        }
        observed.rows.forEach { added ->
            if (added.identity.canonicalValueId !in baselineById) {
                updated = applyObservedRow(updated, null, added)
            }
        }
        updated = applyUnambiguousPrimarySignals(updated, observed)
        updated = normalizeDuplicateValueOrders(updated)
        val resulting = project(updated)
        return AndroidCanonicalDelta(
            contact = updated,
            observedFingerprint = observedFingerprint,
            resultingCanonicalFingerprint = fingerprint(resulting),
            changedValueIds = changedCanonicalIds(canonical, updated, baseline),
        )
    }

    private fun projectName(contact: CanonicalContact): AndroidContactRow {
        val structured = contact.primary(ContactValueKind.STRUCTURED_NAME)
        val phonetic = contact.primary(ContactValueKind.PHONETIC_NAME)
        return AndroidContactRow(
            identity = AndroidValueIdentity(structured?.id ?: reservedId(contact, "name")),
            kind = AndroidRowKind.STRUCTURED_NAME,
            value = contact.displayName,
            components = buildMap {
                put(AndroidComponent.DISPLAY_NAME, contact.displayName)
                put(AndroidComponent.GIVEN_NAME, structured?.components?.get("given") ?: contact.firstName)
                put(AndroidComponent.MIDDLE_NAME, structured?.components?.get("additional").orEmpty())
                put(AndroidComponent.FAMILY_NAME, structured?.components?.get("family") ?: contact.lastName)
                put(AndroidComponent.PREFIX, structured?.components?.get("prefix").orEmpty())
                put(AndroidComponent.SUFFIX, structured?.components?.get("suffix").orEmpty())
                put(AndroidComponent.PHONETIC_GIVEN_NAME, phonetic?.components?.get("given") ?: phonetic?.value.orEmpty())
                put(AndroidComponent.PHONETIC_MIDDLE_NAME, phonetic?.components?.get("additional").orEmpty())
                put(AndroidComponent.PHONETIC_FAMILY_NAME, phonetic?.components?.get("family").orEmpty())
            },
            linkedCanonicalValueIds = phonetic?.let {
                mapOf(AndroidLinkedValueRole.PHONETIC_NAME to it.id)
            }.orEmpty(),
        )
    }

    private fun projectOrganization(contact: CanonicalContact): AndroidContactRow? {
        val organization = contact.primary(ContactValueKind.ORGANIZATION)
        val title = contact.primary(ContactValueKind.TITLE)
        val role = contact.primary(ContactValueKind.ROLE)
        if (organization == null && title == null && role == null) return null
        val company = organization?.components?.get("company")
            ?: organization?.components?.get("component_0") ?: organization?.value.orEmpty()
        return AndroidContactRow(
            identity = AndroidValueIdentity(organization?.id ?: reservedId(contact, "organization")),
            kind = AndroidRowKind.ORGANIZATION,
            value = company,
            order = organization?.order ?: minOf(title?.order ?: Int.MAX_VALUE, role?.order ?: Int.MAX_VALUE),
            isPrimary = organization?.isPrimary ?: true,
            components = mapOf(
                AndroidComponent.COMPANY to company,
                AndroidComponent.DEPARTMENT to (organization?.components?.get("department")
                    ?: organization?.components?.get("component_1").orEmpty()),
                AndroidComponent.TITLE to title?.value.orEmpty(),
                AndroidComponent.ROLE to role?.value.orEmpty(),
            ),
            linkedCanonicalValueIds = buildMap {
                title?.let { put(AndroidLinkedValueRole.TITLE, it.id) }
                role?.let { put(AndroidLinkedValueRole.ROLE, it.id) }
            },
        )
    }

    private fun CanonicalContact.projectAll(
        valueKind: ContactValueKind,
        rowKind: AndroidRowKind,
    ): List<AndroidContactRow> {
        if (valueKind !in PROJECTED_REPEATABLE_PRIMARY_KINDS) {
            return valuesOf(valueKind).map { it.toAndroidRow(rowKind) }
        }
        val primaryId = if (valueKind == ContactValueKind.EMAIL) {
            CanonicalPrimaryValuePolicy.preferredEmail(this)?.id
        } else {
            primary(valueKind)?.id
        }
        return valuesOf(valueKind).map { value ->
            value.toAndroidRow(rowKind).copy(isPrimary = value.id == primaryId)
        }
    }

    private fun projectPrimary(
        contact: CanonicalContact,
        valueKind: ContactValueKind,
        rowKind: AndroidRowKind,
    ): AndroidContactRow? = contact.primary(valueKind)?.toAndroidRow(rowKind)

    /**
     * Rewrites a vCard basic-format date into the extended form the provider row codec accepts.
     *
     * RFC 6350 allows `19850315` and `--0315` as well as `1985-03-15` and `--03-15`, and the vCard
     * decoder keeps whichever form Proton sent. The codec only matches the extended form, so a
     * contact with a compact date failed as MALFORMED_ROW and, because the executor aborts the
     * page, blocked every following contact from reaching the provider.
     *
     * Anything already extended, or not a recognized date at all, is returned untouched so the
     * codec still rejects genuinely malformed values.
     */
    private fun String.toExtendedIsoDate(): String = when {
        BASIC_FULL_DATE.matches(this) -> "${substring(0, 4)}-${substring(4, 6)}-${substring(6, 8)}"
        BASIC_YEARLESS_DATE.matches(this) -> "--${substring(2, 4)}-${substring(4, 6)}"
        else -> this
    }

    private fun ContactValue.toAndroidRow(kind: AndroidRowKind): AndroidContactRow {
        val type = androidType(kind)
        return AndroidContactRow(
            identity = AndroidValueIdentity(id),
            kind = kind,
            // PHOTO bytes are carried only through binaryReference. Keeping a data URI in the
            // generic text slot made the desired snapshot differ from every provider-decoded
            // photo row, whose text slot is correctly empty.
            value = when {
                kind == AndroidRowKind.PHOTO -> ""
                kind in DATE_ROW_KINDS -> value.toExtendedIsoDate()
                else -> value
            },
            semanticType = type.first,
            customLabel = type.second,
            order = order,
            isPrimary = isPrimary,
            isSuperPrimary = false,
            components = when (kind) {
                AndroidRowKind.POSTAL_ADDRESS -> mapOf(
                    AndroidComponent.PO_BOX to components[PostalAddressPolicy.PO_BOX].orEmpty(),
                    AndroidComponent.EXTENDED_ADDRESS to components[PostalAddressPolicy.EXTENDED].orEmpty(),
                    AndroidComponent.STREET to components[PostalAddressPolicy.STREET].orEmpty(),
                    AndroidComponent.LOCALITY to components[PostalAddressPolicy.LOCALITY].orEmpty(),
                    AndroidComponent.REGION to components[PostalAddressPolicy.REGION].orEmpty(),
                    AndroidComponent.POSTCODE to components[PostalAddressPolicy.POSTAL_CODE].orEmpty(),
                    AndroidComponent.COUNTRY to components[PostalAddressPolicy.COUNTRY].orEmpty(),
                    AndroidComponent.FORMATTED_ADDRESS to value,
                )
                else -> emptyMap()
            },
            binaryReference = if (kind == AndroidRowKind.PHOTO) {
                binaryReference ?: value.takeIf(String::isNotBlank)
            } else {
                binaryReference
            },
        )
    }

    private fun ContactValue.androidType(kind: AndroidRowKind): Pair<AndroidSemanticType, String?> {
        metadata[ANDROID_TYPE_METADATA]?.let { stored ->
            AndroidSemanticType.entries.firstOrNull { it.name == stored }?.let { semantic ->
                val label = metadata[ANDROID_CUSTOM_LABEL_METADATA]?.takeIf(String::isNotBlank)
                return if (semantic == AndroidSemanticType.CUSTOM && label != null) semantic to label
                else semantic to null
            }
        }
        val tokens = metadata[VCARD_TYPE_TOKENS_METADATA]
            ?.split(TYPE_TOKEN_SEPARATOR)
            ?.map { it.uppercase(Locale.ROOT) }
            .orEmpty()
            .toSet()
        val normalized = label?.trim()?.uppercase(Locale.ROOT)
        val semantic = when (kind) {
            AndroidRowKind.PHONE -> phoneType(tokens + listOfNotNull(normalized))
            AndroidRowKind.EMAIL -> simpleType(normalized, allowMobile = true)
            AndroidRowKind.POSTAL_ADDRESS -> simpleType(normalized, allowMobile = false)
            AndroidRowKind.WEBSITE -> websiteType(normalized)
            AndroidRowKind.RELATIONSHIP -> relationshipType(normalized)
            AndroidRowKind.CUSTOM_DATE -> if (label?.trim().isNullOrEmpty()) {
                AndroidSemanticType.OTHER
            } else {
                AndroidSemanticType.CUSTOM
            }
            else -> AndroidSemanticType.UNSPECIFIED
        }
        val custom = label?.trim()?.takeIf { semantic == AndroidSemanticType.CUSTOM && it.isNotBlank() }
        return (if (semantic == AndroidSemanticType.CUSTOM && custom == null) AndroidSemanticType.OTHER else semantic) to custom
    }

    private fun removeProjectedRow(contact: CanonicalContact, row: AndroidContactRow): CanonicalContact {
        val ids = row.representedCanonicalIds()
        val remaining = contact.values.filterNot { it.id in ids }
        return if (row.kind == AndroidRowKind.STRUCTURED_NAME) {
            contact.copy(firstName = "", lastName = "", displayName = "", values = remaining)
        } else {
            contact.copy(values = remaining)
        }
    }

    private fun applyObservedRow(
        contact: CanonicalContact,
        baseline: AndroidContactRow?,
        observed: AndroidContactRow,
    ): CanonicalContact = when (observed.kind) {
        AndroidRowKind.STRUCTURED_NAME -> applyName(contact, baseline, observed)
        AndroidRowKind.ORGANIZATION -> applyOrganization(contact, baseline, observed)
        else -> applyValue(contact, baseline, observed)
    }

    private fun applyName(contact: CanonicalContact, baseline: AndroidContactRow?, row: AndroidContactRow): CanonicalContact {
        val id = row.identity.canonicalValueId
        val existingStructured = contact.values.firstOrNull { it.id == id }
        val components = existingStructured?.components.orEmpty() + mapOf(
            "given" to row.components[AndroidComponent.GIVEN_NAME].orEmpty(),
            "additional" to row.components[AndroidComponent.MIDDLE_NAME].orEmpty(),
            "family" to row.components[AndroidComponent.FAMILY_NAME].orEmpty(),
            "prefix" to row.components[AndroidComponent.PREFIX].orEmpty(),
            "suffix" to row.components[AndroidComponent.SUFFIX].orEmpty(),
        )
        var values = contact.values.replaceOrAdd(
            ContactValue(
                id = id,
                kind = ContactValueKind.STRUCTURED_NAME,
                value = listOf("family", "given", "additional", "prefix", "suffix")
                    .joinToString(";") { component -> components[component].orEmpty() },
                order = existingStructured?.order ?: 0,
                isPrimary = true,
                components = components,
                metadata = existingStructured?.metadata.orEmpty(),
                preservationKey = existingStructured?.preservationKey,
            ),
        )
        val baselinePhoneticId = row.linkedCanonicalValueIds[AndroidLinkedValueRole.PHONETIC_NAME]
            ?: reservedId(contact, "phonetic-name")
        val existingPhonetic = contact.values.firstOrNull { it.id == baselinePhoneticId }
        val phoneticComponents = existingPhonetic?.components.orEmpty() + mapOf(
            "given" to row.components[AndroidComponent.PHONETIC_GIVEN_NAME].orEmpty(),
            "additional" to row.components[AndroidComponent.PHONETIC_MIDDLE_NAME].orEmpty(),
            "family" to row.components[AndroidComponent.PHONETIC_FAMILY_NAME].orEmpty(),
        )
        val phoneticId = baselinePhoneticId
        values = if (phoneticComponents.values.any(String::isNotBlank)) {
            values.replaceOrAdd(
                ContactValue(
                    id = phoneticId,
                    kind = ContactValueKind.PHONETIC_NAME,
                    value = phoneticComponents["given"].orEmpty(),
                    order = existingPhonetic?.order ?: 0,
                    isPrimary = true,
                    components = phoneticComponents,
                    metadata = existingPhonetic?.metadata.orEmpty(),
                    preservationKey = existingPhonetic?.preservationKey,
                ),
            )
        } else {
            values.filterNot { it.id == phoneticId }
        }
        return contact.copy(
            firstName = row.components[AndroidComponent.GIVEN_NAME].orEmpty(),
            lastName = row.components[AndroidComponent.FAMILY_NAME].orEmpty(),
            displayName = observedDisplayName(contact, baseline, row),
            values = values,
        )
    }

    private fun observedDisplayName(
        contact: CanonicalContact,
        baseline: AndroidContactRow?,
        observed: AndroidContactRow,
    ): String {
        val display = observed.components[AndroidComponent.DISPLAY_NAME] ?: observed.value
        if (baseline == null || contact.displayName.isBlank()) return display
        val parts = listOf(AndroidComponent.PREFIX, AndroidComponent.GIVEN_NAME, AndroidComponent.MIDDLE_NAME,
            AndroidComponent.FAMILY_NAME, AndroidComponent.SUFFIX)
        fun joinedName(row: AndroidContactRow) = parts.map { row.components[it].orEmpty().trim() }
            .filter(String::isNotEmpty).joinToString(" ")
        // Google Contacts recomputes DISPLAY_NAME when a structured part is edited.
        // Preserve a distinct canonical alias only for this exact derived replacement;
        // a display-only edit or a new non-derived display name remains authoritative.
        val baselineDisplay = baseline.components[AndroidComponent.DISPLAY_NAME] ?: baseline.value
        val derivedReplacement = baselineDisplay == contact.displayName &&
            contact.displayName != joinedName(baseline) &&
            parts.any { baseline.components[it].orEmpty() != observed.components[it].orEmpty() } &&
            display.isNotBlank() && display == joinedName(observed)
        return if (derivedReplacement) contact.displayName else display
    }

    private fun applyOrganization(
        contact: CanonicalContact,
        baseline: AndroidContactRow?,
        row: AndroidContactRow,
    ): CanonicalContact {
        var values = contact.values
        val organizationId = row.identity.canonicalValueId
        val company = row.components[AndroidComponent.COMPANY].orEmpty()
        val department = row.components[AndroidComponent.DEPARTMENT].orEmpty()
        val existingOrganization = values.firstOrNull { it.id == organizationId }
        values = if (company.isNotBlank() || department.isNotBlank()) {
            values.replaceOrAdd(
                ContactValue(
                    id = organizationId,
                    kind = ContactValueKind.ORGANIZATION,
                    value = company,
                    order = existingOrganization?.order ?: row.order,
                    isPrimary = existingOrganization?.isPrimary ?: true,
                    components = existingOrganization?.components.orEmpty() + mapOf(
                        "company" to company,
                        "department" to department,
                        "component_0" to company,
                        "component_1" to department,
                    ),
                    metadata = existingOrganization?.metadata.orEmpty(),
                    preservationKey = existingOrganization?.preservationKey,
                ),
            )
        } else values.filterNot { it.id == organizationId }

        values = applyLinkedText(
            contact,
            values,
            baseline,
            row,
            AndroidLinkedValueRole.TITLE,
            AndroidComponent.TITLE,
            ContactValueKind.TITLE,
        )
        values = applyLinkedText(
            contact,
            values,
            baseline,
            row,
            AndroidLinkedValueRole.ROLE,
            AndroidComponent.ROLE,
            ContactValueKind.ROLE,
        )
        return contact.copy(values = values)
    }

    private fun applyLinkedText(
        contact: CanonicalContact,
        current: List<ContactValue>,
        baseline: AndroidContactRow?,
        row: AndroidContactRow,
        role: AndroidLinkedValueRole,
        component: AndroidComponent,
        kind: ContactValueKind,
    ): List<ContactValue> {
        val existingId = row.linkedCanonicalValueIds[role] ?: baseline?.linkedCanonicalValueIds?.get(role)
        val id = existingId ?: reservedId(contact, role.name.lowercase(Locale.ROOT))
        val text = row.components[component].orEmpty()
        val existing = current.firstOrNull { it.id == id }
        return if (text.isBlank()) current.filterNot { it.id == id } else current.replaceOrAdd(
            ContactValue(
                id = id,
                kind = kind,
                value = text,
                order = existing?.order ?: row.order,
                isPrimary = existing?.isPrimary ?: true,
                metadata = existing?.metadata.orEmpty(),
                preservationKey = existing?.preservationKey,
            ),
        )
    }

    private fun applyValue(
        contact: CanonicalContact,
        baseline: AndroidContactRow?,
        row: AndroidContactRow,
    ): CanonicalContact {
        val kind = row.kind.canonicalKind()
        val existing = contact.values.firstOrNull { it.id == row.identity.canonicalValueId }
        val typeChanged = baseline == null || baseline.semanticType != row.semanticType ||
            baseline.customLabel != row.customLabel
        val type = canonicalType(existing, row, typeChanged)
        val components = if (row.kind == AndroidRowKind.POSTAL_ADDRESS) {
            val observed = mapOf(
                PostalAddressPolicy.PO_BOX to row.components[AndroidComponent.PO_BOX].orEmpty(),
                PostalAddressPolicy.EXTENDED to row.components[AndroidComponent.EXTENDED_ADDRESS].orEmpty(),
                PostalAddressPolicy.STREET to row.components[AndroidComponent.STREET].orEmpty(),
                PostalAddressPolicy.LOCALITY to row.components[AndroidComponent.LOCALITY].orEmpty(),
                PostalAddressPolicy.REGION to row.components[AndroidComponent.REGION].orEmpty(),
                PostalAddressPolicy.POSTAL_CODE to row.components[AndroidComponent.POSTCODE].orEmpty(),
                PostalAddressPolicy.COUNTRY to row.components[AndroidComponent.COUNTRY].orEmpty(),
            )
            val normalizedObserved = if (
                observed.values.none(String::isNotBlank) && row.value.isNotBlank()
            ) {
                observed + (PostalAddressPolicy.STREET to row.value)
            } else {
                observed
            }
            existing?.components.orEmpty() + normalizedObserved
        } else existing?.components.orEmpty()
        val replacement = ContactValue(
            id = row.identity.canonicalValueId,
            kind = kind,
            // Android photo rows carry bytes through binaryReference and deliberately expose an
            // empty generic text slot. Canonical/Proton PHOTO values are the data URI itself, so
            // copying row.value here produced an empty image that failed validation before upload.
            value = when (kind) {
                ContactValueKind.PHOTO -> row.binaryReference ?: existing?.value.orEmpty()
                ContactValueKind.POSTAL_ADDRESS -> PostalAddressPolicy.formattedValue(components)
                else -> row.value
            },
            label = type.first,
            order = existing?.order ?: row.order,
            isPrimary = existing?.isPrimary ?: row.isPrimary || row.isSuperPrimary,
            components = components,
            metadata = type.second,
            binaryReference = row.binaryReference ?: existing?.binaryReference,
            preservationKey = existing?.preservationKey,
        )
        return contact.copy(values = contact.values.replaceOrAdd(replacement))
    }

    private fun canonicalType(
        existing: ContactValue?,
        row: AndroidContactRow,
        changed: Boolean,
    ): Pair<String?, Map<String, String>> {
        if (!changed && existing != null) return existing.label to existing.metadata
        val canonicalTokens = row.semanticType.canonicalTokens(row.kind)
        val previousTokens = existing?.metadata?.get(VCARD_TYPE_TOKENS_METADATA)
            ?.split(TYPE_TOKEN_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()
        val retainedUnknown = previousTokens.filterNot { it.uppercase(Locale.ROOT) in MANAGED_TYPE_TOKENS }
        val metadata = existing?.metadata.orEmpty().toMutableMap().apply {
            put(ANDROID_TYPE_METADATA, row.semanticType.name)
            if (row.semanticType == AndroidSemanticType.CUSTOM) {
                row.customLabel?.let { put(ANDROID_CUSTOM_LABEL_METADATA, it) }
            } else remove(ANDROID_CUSTOM_LABEL_METADATA)
            val tokens = retainedUnknown + canonicalTokens
            if (tokens.isEmpty()) remove(VCARD_TYPE_TOKENS_METADATA)
            else put(VCARD_TYPE_TOKENS_METADATA, tokens.joinToString(TYPE_TOKEN_SEPARATOR))
            if (row.kind == AndroidRowKind.CUSTOM_DATE) put(SYNC_DISPOSITION_METADATA, LOCAL_ONLY_DISPOSITION)
        }
        val label = when {
            row.semanticType == AndroidSemanticType.CUSTOM -> row.customLabel
            canonicalTokens.isNotEmpty() -> canonicalTokens.first()
            else -> null
        }
        return label to metadata
    }

    private fun applyUnambiguousPrimarySignals(
        contact: CanonicalContact,
        observed: AndroidContactSnapshot,
    ): CanonicalContact {
        val mutable = contact.values.toMutableList()
        observed.rows.groupBy { it.kind }.forEach { (rowKind, rows) ->
            val canonicalKind = runCatching { rowKind.canonicalKind() }.getOrNull() ?: return@forEach
            if (canonicalKind !in REPEATABLE_PRIMARY_KINDS) return@forEach
            val superPrimary = rows.filter(AndroidContactRow::isSuperPrimary)
            val primary = rows.filter(AndroidContactRow::isPrimary)
            val selected = when {
                superPrimary.size == 1 -> superPrimary.single()
                superPrimary.isEmpty() && primary.size == 1 -> primary.single()
                else -> null
            } ?: return@forEach
            val family = mutable.filter { it.kind == canonicalKind }
            val selectedId = selected.identity.canonicalValueId
            if (family.none { it.id == selectedId }) return@forEach
            if (canonicalKind == ContactValueKind.EMAIL &&
                family.single { it.id == selectedId }.value.isBlank()
            ) {
                mutable.indices.forEach { index ->
                    val value = mutable[index]
                    if (value.kind == canonicalKind && value.id == selectedId && value.isPrimary) {
                        mutable[index] = value.copy(isPrimary = false)
                    }
                }
                return@forEach
            }
            val currentContact = contact.copy(values = mutable)
            val currentPrimaryId = if (canonicalKind == ContactValueKind.EMAIL) {
                CanonicalPrimaryValuePolicy.preferredEmail(currentContact)?.id
            } else {
                CanonicalPrimaryValuePolicy.select(currentContact, canonicalKind)?.id
            }
            if (currentPrimaryId == selectedId) return@forEach
            val preferenceFamily = if (canonicalKind == ContactValueKind.EMAIL) {
                family.filter { it.value.isNotBlank() }
            } else {
                family
            }
            val reordered = listOf(requireNotNull(preferenceFamily.singleOrNull { it.id == selectedId })) +
                preferenceFamily.filterNot { it.id == selectedId }.sortedWith(
                    compareBy<ContactValue>(ContactValue::order).thenBy(ContactValue::id),
                )
            val preferenceById = reordered.mapIndexed { index, value -> value.id to index + 1 }.toMap()
            mutable.indices.forEach { index ->
                val value = mutable[index]
                if (value.kind == canonicalKind && value.id in preferenceById) {
                    mutable[index] = value.copy(
                        isPrimary = value.id == selectedId,
                        metadata = value.metadata +
                            (CanonicalPrimaryValuePolicy.VCARD_PREF_METADATA to preferenceById.getValue(value.id).toString()),
                    )
                }
            }
        }
        return contact.copy(values = mutable)
    }

    private fun AndroidRowKind.canonicalKind(): ContactValueKind = when (this) {
        AndroidRowKind.EMAIL -> ContactValueKind.EMAIL
        AndroidRowKind.PHONE -> ContactValueKind.PHONE
        AndroidRowKind.POSTAL_ADDRESS -> ContactValueKind.POSTAL_ADDRESS
        AndroidRowKind.PHOTO -> ContactValueKind.PHOTO
        AndroidRowKind.NICKNAME -> ContactValueKind.NICKNAME
        AndroidRowKind.NOTE -> ContactValueKind.NOTE
        AndroidRowKind.WEBSITE -> ContactValueKind.URL
        AndroidRowKind.BIRTHDAY -> ContactValueKind.BIRTHDAY
        AndroidRowKind.ANNIVERSARY -> ContactValueKind.ANNIVERSARY
        AndroidRowKind.CUSTOM_DATE -> ContactValueKind.CUSTOM_DATE
        AndroidRowKind.RELATIONSHIP -> ContactValueKind.RELATIONSHIP
        AndroidRowKind.STRUCTURED_NAME, AndroidRowKind.ORGANIZATION -> error("Composite row")
    }

    private fun AndroidSemanticType.canonicalTokens(kind: AndroidRowKind): List<String> = when (kind) {
        AndroidRowKind.PHONE -> when (this) {
            AndroidSemanticType.HOME -> listOf("HOME")
            AndroidSemanticType.MOBILE -> listOf("CELL")
            AndroidSemanticType.WORK -> listOf("WORK")
            AndroidSemanticType.FAX_HOME -> listOf("HOME", "FAX")
            AndroidSemanticType.FAX_WORK -> listOf("WORK", "FAX")
            AndroidSemanticType.OTHER_FAX -> listOf("FAX")
            AndroidSemanticType.PAGER -> listOf("PAGER")
            AndroidSemanticType.WORK_MOBILE -> listOf("WORK", "CELL")
            AndroidSemanticType.WORK_PAGER -> listOf("WORK", "PAGER")
            AndroidSemanticType.MAIN -> listOf("MAIN")
            AndroidSemanticType.OTHER -> listOf("OTHER")
            else -> emptyList()
        }
        AndroidRowKind.EMAIL, AndroidRowKind.POSTAL_ADDRESS -> when (this) {
            AndroidSemanticType.HOME -> listOf("HOME")
            AndroidSemanticType.WORK -> listOf("WORK")
            AndroidSemanticType.OTHER -> listOf("OTHER")
            else -> emptyList()
        }
        AndroidRowKind.WEBSITE -> when (this) {
            AndroidSemanticType.HOME -> listOf("HOME")
            AndroidSemanticType.WORK -> listOf("WORK")
            else -> emptyList()
        }
        AndroidRowKind.RELATIONSHIP -> when (this) {
            AndroidSemanticType.CHILD -> listOf("child")
            AndroidSemanticType.FRIEND -> listOf("friend")
            AndroidSemanticType.PARENT, AndroidSemanticType.FATHER, AndroidSemanticType.MOTHER -> listOf("parent")
            AndroidSemanticType.SPOUSE -> listOf("spouse")
            AndroidSemanticType.BROTHER, AndroidSemanticType.SISTER -> listOf("sibling")
            AndroidSemanticType.RELATIVE -> listOf("kin")
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun phoneType(tokens: Collection<String>): AndroidSemanticType {
        val upper = tokens.map { it.uppercase(Locale.ROOT) }.toSet()
        return when {
            "WORK" in upper && "CELL" in upper -> AndroidSemanticType.WORK_MOBILE
            "WORK" in upper && "PAGER" in upper -> AndroidSemanticType.WORK_PAGER
            "WORK" in upper && "FAX" in upper -> AndroidSemanticType.FAX_WORK
            "HOME" in upper && "FAX" in upper -> AndroidSemanticType.FAX_HOME
            "FAX" in upper -> AndroidSemanticType.OTHER_FAX
            "CELL" in upper || "MOBILE" in upper -> AndroidSemanticType.MOBILE
            "HOME" in upper -> AndroidSemanticType.HOME
            "WORK" in upper -> AndroidSemanticType.WORK
            "PAGER" in upper -> AndroidSemanticType.PAGER
            "MAIN" in upper -> AndroidSemanticType.MAIN
            "OTHER" in upper -> AndroidSemanticType.OTHER
            upper.isEmpty() -> AndroidSemanticType.UNSPECIFIED
            else -> AndroidSemanticType.CUSTOM
        }
    }

    private fun simpleType(label: String?, allowMobile: Boolean): AndroidSemanticType = when (label) {
        null, "" -> AndroidSemanticType.UNSPECIFIED
        "HOME" -> AndroidSemanticType.HOME
        "WORK" -> AndroidSemanticType.WORK
        "OTHER" -> AndroidSemanticType.OTHER
        "MOBILE", "CELL" -> if (allowMobile) AndroidSemanticType.MOBILE else AndroidSemanticType.CUSTOM
        else -> AndroidSemanticType.CUSTOM
    }

    private fun websiteType(label: String?): AndroidSemanticType = when (label) {
        null, "" -> AndroidSemanticType.UNSPECIFIED
        "HOME" -> AndroidSemanticType.HOME
        "WORK" -> AndroidSemanticType.WORK
        "BLOG" -> AndroidSemanticType.BLOG
        "PROFILE" -> AndroidSemanticType.PROFILE
        "FTP" -> AndroidSemanticType.FTP
        "OTHER" -> AndroidSemanticType.OTHER
        else -> AndroidSemanticType.CUSTOM
    }

    private fun relationshipType(label: String?): AndroidSemanticType = when (label?.lowercase(Locale.ROOT)) {
        null, "" -> AndroidSemanticType.UNSPECIFIED
        "child" -> AndroidSemanticType.CHILD
        "brother" -> AndroidSemanticType.BROTHER
        "domestic_partner", "domestic partner" -> AndroidSemanticType.DOMESTIC_PARTNER
        "father" -> AndroidSemanticType.FATHER
        "friend" -> AndroidSemanticType.FRIEND
        "manager" -> AndroidSemanticType.MANAGER
        "mother" -> AndroidSemanticType.MOTHER
        "parent" -> AndroidSemanticType.PARENT
        "partner" -> AndroidSemanticType.PARTNER
        "referred_by", "referred by" -> AndroidSemanticType.REFERRED_BY
        "kin", "relative" -> AndroidSemanticType.RELATIVE
        "sibling" -> AndroidSemanticType.CUSTOM
        "sister" -> AndroidSemanticType.SISTER
        "spouse" -> AndroidSemanticType.SPOUSE
        else -> AndroidSemanticType.CUSTOM
    }

    private fun CanonicalContact.primary(kind: ContactValueKind): ContactValue? {
        return CanonicalPrimaryValuePolicy.select(this, kind)
    }

    private fun reservedId(contact: CanonicalContact, role: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${contact.accountId}\u0000${contact.id}\u0000$role".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "android-$role-${digest.take(24)}"
    }

    private fun changedCanonicalIds(
        before: CanonicalContact,
        after: CanonicalContact,
        baseline: AndroidContactSnapshot,
    ): Set<String> = buildSet {
        val beforeValues = before.values.associateBy(ContactValue::id)
        val afterValues = after.values.associateBy(ContactValue::id)
        (beforeValues.keys + afterValues.keys).forEach { id ->
            if (beforeValues[id] != afterValues[id]) add(id)
        }
        if (before.firstName != after.firstName || before.lastName != after.lastName ||
            before.displayName != after.displayName
        ) {
            baseline.rows.firstOrNull { it.kind == AndroidRowKind.STRUCTURED_NAME }
                ?.identity?.canonicalValueId?.let(::add)
        }
    }

    private fun List<ContactValue>.replaceOrAdd(replacement: ContactValue): List<ContactValue> {
        val index = indexOfFirst { it.id == replacement.id }
        if (index < 0) return this + replacement
        return toMutableList().also { it[index] = replacement }
    }

    private fun AndroidContactRow.representedCanonicalIds(): Set<String> =
        setOf(identity.canonicalValueId) + linkedCanonicalValueIds.values

    private fun AndroidContactRow.samePayload(
        other: AndroidContactRow,
        singleEmailPrimaryIsImplicit: Boolean = false,
    ): Boolean = normalizedProviderPayload(singleEmailPrimaryIsImplicit) ==
        other.normalizedProviderPayload(singleEmailPrimaryIsImplicit)

    /** ContactsProvider chooses one primary row across the shared Event MIME family. */
    private fun AndroidContactRow.normalizedProviderPayload(
        singleEmailPrimaryIsImplicit: Boolean = false,
    ): AndroidContactRow = withoutProviderLocator().let { row ->
        when {
            row.kind in DATE_ROW_KINDS -> row.copy(isPrimary = false, isSuperPrimary = false)
            row.kind == AndroidRowKind.PHOTO -> row.copy(isSuperPrimary = false)
            singleEmailPrimaryIsImplicit && row.kind == AndroidRowKind.EMAIL -> row.copy(isPrimary = false)
            else -> row
        }
    }

    /**
     * Projection comparison only: Android may split a display-only name on write.
     * Keep the real provider snapshot as the ingestion baseline, so a subsequent native edit
     * is still compared with what Android actually held, not with invented canonical names.
     */
    fun normalizeGeneratedName(observed: AndroidContactSnapshot, desired: AndroidContactSnapshot): AndroidContactSnapshot {
        val expected = desired.rows.singleOrNull { it.kind == AndroidRowKind.STRUCTURED_NAME } ?: return observed
        val actual = observed.rows.singleOrNull { it.kind == AndroidRowKind.STRUCTURED_NAME } ?: return observed
        val parts = listOf(AndroidComponent.PREFIX, AndroidComponent.GIVEN_NAME, AndroidComponent.MIDDLE_NAME,
            AndroidComponent.FAMILY_NAME, AndroidComponent.SUFFIX)
        if (observed.canonicalContactId != desired.canonicalContactId ||
            actual.identity.canonicalValueId != expected.identity.canonicalValueId ||
            expected.value.isBlank() || actual.value != expected.value ||
            actual.components[AndroidComponent.DISPLAY_NAME] != expected.components[AndroidComponent.DISPLAY_NAME] ||
            parts.any { !expected.components[it].isNullOrEmpty() }
        ) return observed
        val reconstructed = parts.map { actual.components[it].orEmpty().trim() }
            .filter(String::isNotEmpty).joinToString(" ")
        // ContactsProvider splits on spaces, dots and commas, and can move the surname
        // before the given name. Compare the complete token multiset, not that formatting.
        // Keep case, accents, hyphens, apostrophes and repetitions significant. This is
        // only a projection equivalence; ingestion still compares real provider baselines.
        fun tokens(value: String) = value.split(Regex("[\\s.,]+"))
            .filter(String::isNotEmpty).groupingBy { it }.eachCount()
        val expectedTokens = tokens(expected.value)
        if (expectedTokens.isEmpty() || tokens(reconstructed) != expectedTokens) return observed
        return observed.copy(rows = observed.rows.map { row ->
            if (row === actual) row.copy(components = row.components + parts.associateWith { expected.components[it].orEmpty() })
            else row
        })
    }

    /**
     * Remote cards can retain equal order hints inside one repeatable family. A controlled Android
     * edit must remain saveable without deleting hidden values, so only duplicate families are
     * compacted. Current order wins first and original canonical list order is the stable tie-break.
     */
    private fun normalizeDuplicateValueOrders(contact: CanonicalContact): CanonicalContact {
        val values = contact.values.toMutableList()
        contact.values.indices.groupBy { contact.values[it].kind }.values.forEach { familyIndices ->
            if (familyIndices.map { contact.values[it].order }.distinct().size == familyIndices.size) {
                return@forEach
            }
            familyIndices.sortedWith(compareBy<Int> { contact.values[it].order }.thenBy { it })
                .forEachIndexed { normalizedOrder, valueIndex ->
                    values[valueIndex] = values[valueIndex].copy(order = normalizedOrder)
                }
        }
        return if (values == contact.values) contact else contact.copy(values = values)
    }

    private fun AndroidContactRow.semanticPrimary(singleEmailPrimaryIsImplicit: Boolean): Boolean =
        kind !in DATE_ROW_KINDS && !(singleEmailPrimaryIsImplicit && kind == AndroidRowKind.EMAIL) && isPrimary

    private fun AndroidContactRow.semanticSuperPrimary(): Boolean = kind !in DATE_ROW_KINDS && isSuperPrimary

    private fun AndroidValueIdentity.requireProviderLocator(): AndroidValueIdentity =
        apply { require(providerRowId != null) }

    private fun MessageDigest.put(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    private companion object {
        /** vCard basic format, which the provider row codec does not accept. */
        val BASIC_FULL_DATE = Regex("[0-9]{8}")
        val BASIC_YEARLESS_DATE = Regex("--[0-9]{4}")
        val DATE_ROW_KINDS = setOf(
            AndroidRowKind.BIRTHDAY,
            AndroidRowKind.ANNIVERSARY,
            AndroidRowKind.CUSTOM_DATE,
        )

        const val ANDROID_TYPE_METADATA = "androidSemanticType"
        const val ANDROID_CUSTOM_LABEL_METADATA = "androidCustomLabel"
        const val VCARD_TYPE_TOKENS_METADATA = "vcardTypeTokens"
        const val SYNC_DISPOSITION_METADATA = "syncDisposition"
        const val LOCAL_ONLY_DISPOSITION = "LOCAL_ONLY"
        const val TYPE_TOKEN_SEPARATOR = "\u001f"
        val MANAGED_TYPE_TOKENS = setOf("HOME", "WORK", "OTHER", "CELL", "MAIN", "FAX", "PAGER")
        val PROJECTED_REPEATABLE_PRIMARY_KINDS = setOf(
            ContactValueKind.EMAIL,
            ContactValueKind.PHONE,
            ContactValueKind.POSTAL_ADDRESS,
        )
        val REPEATABLE_PRIMARY_KINDS = PROJECTED_REPEATABLE_PRIMARY_KINDS
    }
}
