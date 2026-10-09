package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalAndroidContactMapperTest {
    @Test fun `aggregate photo preference is not a canonical edit or projection obligation`() {
        val canonical = contact(values = listOf(value("photo", ContactValueKind.PHOTO, binaryReference = "owned-photo")))
        val baseline = mapper.project(canonical).withProviderIds()
        val selected = baseline.copy(rows = baseline.rows.map {
            if (it.kind == AndroidRowKind.PHOTO) it.copy(isSuperPrimary = true) else it
        })
        // Durable baselines keep the actual flag and their existing fingerprint format.
        assertNotEquals(mapper.fingerprint(baseline), mapper.fingerprint(selected))
        assertEquals(mapper.fingerprint(mapper.projectionComparisonSnapshot(baseline)),
            mapper.fingerprint(mapper.projectionComparisonSnapshot(selected)))
        assertTrue(mapper.planProjection(selected, canonical).operations.isEmpty())
        assertTrue(mapper.applyControlledDelta(canonical, baseline, selected).changedValueIds.isEmpty())
        val changed = selected.copy(rows = selected.rows.map {
            if (it.kind == AndroidRowKind.PHOTO) it.copy(binaryReference = "replacement-photo") else it
        })
        assertNotEquals(mapper.fingerprint(selected), mapper.fingerprint(changed))
        val update = mapper.planProjection(changed, canonical).operations.filterIsInstance<AndroidRowOperation.Update>().single()
        assertTrue(update.desired.isSuperPrimary)
        assertEquals("owned-photo", update.desired.binaryReference)
    }

    @Test
    fun `generated name decomposition converges but native edits remain observable`() {
        val canonical = contact(values = listOf(value("email", ContactValueKind.EMAIL, "owned@example.test")))
            .copy(firstName = "", lastName = "")
        val desired = mapper.project(canonical)
        val actual = desired.withProviderIds().copy(rows = desired.withProviderIds().rows.map { row ->
            if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(components = row.components + mapOf(
                AndroidComponent.GIVEN_NAME to "Ada", AndroidComponent.FAMILY_NAME to "Lovelace")) else row
        })
        assertEquals(mapper.fingerprint(desired), mapper.fingerprint(mapper.normalizeGeneratedName(actual, desired)))
        assertTrue(mapper.planProjection(actual, canonical).operations.isEmpty())
        val edited = actual.copy(rows = actual.rows.map { row ->
            if (row.kind == AndroidRowKind.EMAIL) row.copy(value = "edited@example.test") else row
        })
        val adopted = mapper.applyControlledDelta(canonical, actual, edited).contact
        assertEquals("", adopted.firstName)
        assertEquals("", adopted.lastName)
        assertEquals("edited@example.test", adopted.valuesOf(ContactValueKind.EMAIL).single().value)
        val renamed = actual.copy(rows = actual.rows.map { row ->
            if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(components = row.components +
                (AndroidComponent.GIVEN_NAME to "Grace")) else row
        })
        assertEquals(renamed, mapper.normalizeGeneratedName(renamed, desired))
        assertEquals("Grace", mapper.applyControlledDelta(canonical, actual, renamed).contact.firstName)
        val explicit = mapper.project(canonical.copy(firstName = "Explicit"))
        assertEquals(actual, mapper.normalizeGeneratedName(actual, explicit))
        assertTrue(mapper.fingerprint(desired) != mapper.fingerprint(mapper.normalizeGeneratedName(edited, desired)))
    }

    private val mapper = CanonicalAndroidContactMapper()

    @Test
    fun `native structured rename preserves distinct alias but accepts explicit display edits`() {
        data class Case(val initialDisplay: String, val given: String, val observedDisplay: String, val expected: String)
        listOf(
            Case("Research alias", "Grace", "Grace Lovelace", "Research alias"),
            Case("Ada Lovelace", "Grace", "Grace Lovelace", "Grace Lovelace"),
            Case("Research alias", "Grace", "New explicit alias", "New explicit alias"),
            Case("Research alias", "Ada", "Ada Lovelace", "Ada Lovelace"),
        ).forEach { case ->
            val canonical = contact(values = listOf(value("unknown", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-KEEP:yes")))
                .copy(displayName = case.initialDisplay)
            val baseline = mapper.project(canonical).withProviderIds()
            val name = baseline.row(AndroidRowKind.STRUCTURED_NAME).let { row ->
                row.copy(value = case.observedDisplay, components = row.components + mapOf(
                    AndroidComponent.GIVEN_NAME to case.given,
                    AndroidComponent.DISPLAY_NAME to case.observedDisplay,
                ))
            }
            val observed = baseline.copy(rows = baseline.rows.replace(name))
            val result = mapper.applyControlledDelta(canonical, baseline, observed).contact
            assertEquals(case.given, result.firstName)
            assertEquals("Lovelace", result.lastName)
            assertEquals(case.expected, result.displayName)
            assertEquals(canonical.values.single(), result.values.single { it.id == "unknown" })
            // Retrying an unchanged native baseline must not re-adopt its generated display.
            assertEquals(result, mapper.applyControlledDelta(result, observed, observed).contact)
        }
    }

    @Test
    fun `punctuated and surname-first generated names converge without hiding native edits`() {
        val canonical = contact(values = listOf(value("email", ContactValueKind.EMAIL, "owned@example.test")))
            .copy(displayName = "Dr. Lovelace, Ada B.", firstName = "", lastName = "")
        val desired = mapper.project(canonical)
        val actual = desired.withProviderIds().copy(rows = desired.withProviderIds().rows.map { row ->
            if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(components = row.components + mapOf(
                AndroidComponent.PREFIX to "Dr", AndroidComponent.GIVEN_NAME to "Ada",
                AndroidComponent.MIDDLE_NAME to "B", AndroidComponent.FAMILY_NAME to "Lovelace")) else row
        })
        assertEquals(mapper.fingerprint(desired), mapper.fingerprint(mapper.normalizeGeneratedName(actual, desired)))
        assertTrue(mapper.planProjection(actual, canonical).operations.isEmpty())
        val emailEdit = actual.copy(rows = actual.rows.map { row ->
            if (row.kind == AndroidRowKind.EMAIL) row.copy(value = "edited@example.test") else row
        })
        val adopted = mapper.applyControlledDelta(canonical, actual, emailEdit).contact
        assertEquals("", adopted.firstName)
        assertEquals("", adopted.lastName)
        assertEquals(canonical.displayName, adopted.displayName)
        // A native swap still changes the actual baseline even though the tokens are equal.
        val nativeEdit = actual.copy(rows = actual.rows.map { row ->
            if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(components = row.components + mapOf(
                AndroidComponent.GIVEN_NAME to "Lovelace", AndroidComponent.FAMILY_NAME to "Ada")) else row
        })
        val renamed = mapper.applyControlledDelta(canonical, actual, nativeEdit).contact
        assertEquals("Lovelace", renamed.firstName)
        assertEquals("Ada", renamed.lastName)
        listOf("Grace", "Ada Ada", "ada", "Áda", "Ada-B", "").forEach { replacement ->
            val changed = actual.copy(rows = actual.rows.map { row ->
                if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(components = row.components +
                    (AndroidComponent.GIVEN_NAME to replacement)) else row
            })
            assertEquals(changed, mapper.normalizeGeneratedName(changed, desired))
        }
        assertEquals(actual, mapper.normalizeGeneratedName(actual, mapper.project(canonical.copy(firstName = "Ada"))))
        assertEquals(actual, mapper.normalizeGeneratedName(actual, desired.copy(canonicalContactId = "other")))
    }

    @Test
    fun `opaque imported dates survive projection and unrelated native edits unchanged`() {
        listOf("2000-02-29T12:30:00Z", "2000-02", "circa spring 2000").forEach { raw ->
            val canonical = contact(values = listOf(
                value("birthday", ContactValueKind.BIRTHDAY, raw),
                value("email", ContactValueKind.EMAIL, "owned@example.test"),
            ))
            val baseline = mapper.project(canonical).withProviderIds()
            assertEquals(raw, baseline.row(AndroidRowKind.BIRTHDAY).value)
            assertTrue(mapper.planProjection(baseline, canonical).operations.isEmpty())
            val edited = baseline.copy(rows = baseline.rows.map { row ->
                if (row.kind == AndroidRowKind.EMAIL) row.copy(value = "edited@example.test") else row
            })
            assertEquals(canonical.valuesOf(ContactValueKind.BIRTHDAY),
                mapper.applyControlledDelta(canonical, baseline, edited).contact.valuesOf(ContactValueKind.BIRTHDAY))
        }
    }

    @Test
    fun `provider primary normalization across event rows is not a semantic edit`() {
        val canonical = contact(
            values = listOf(
                value("birthday", ContactValueKind.BIRTHDAY, "--12-10", primary = true),
                value("anniversary", ContactValueKind.ANNIVERSARY, "1843-12-10", primary = true),
            ),
        )
        val projected = mapper.project(canonical)
        val normalized = projected.copy(
            rows = projected.rows.map { row ->
                if (row.kind == AndroidRowKind.ANNIVERSARY) row.copy(isPrimary = false) else row
            },
        )

        assertEquals(mapper.fingerprint(projected), mapper.fingerprint(normalized))
        assertTrue(mapper.planProjection(normalized, canonical).operations.isEmpty())
    }

    @Test
    fun `provider owned primary bit on a single email is implicit and does not loop projection`() {
        val canonical = contact(
            values = listOf(value("email", ContactValueKind.EMAIL, "single@example.test", primary = true)),
        )
        val projected = mapper.project(canonical).withProviderIds()
        val providerNormalized = projected.copy(
            rows = projected.rows.map { row ->
                if (row.kind == AndroidRowKind.EMAIL) row.copy(isPrimary = false) else row
            },
        )

        assertEquals(mapper.fingerprint(projected), mapper.fingerprint(providerNormalized))
        assertTrue(mapper.planProjection(providerNormalized, canonical).operations.isEmpty())
        assertEquals(canonical, mapper.applyControlledDelta(canonical, projected, providerNormalized).contact)
    }

    @Test
    fun `controlled edit normalizes duplicate family order without dropping hidden values`() {
        val canonical = contact(
            values = listOf(
                value("email-a", ContactValueKind.EMAIL, "a@example.test", order = 0, primary = true),
                value("email-b", ContactValueKind.EMAIL, "b@example.test", order = 0),
                value("hidden-a", ContactValueKind.LANGUAGE, "fr", order = 4),
                value("hidden-b", ContactValueKind.LANGUAGE, "en", order = 4),
            ),
        )
        val baseline = mapper.project(canonical).withProviderIds()
        val observed = baseline.copy(rows = baseline.rows.map { row ->
            if (row.identity.canonicalValueId == "email-a") row.copy(value = "edited@example.test") else row
        })

        val result = mapper.applyControlledDelta(canonical, baseline, observed).contact

        assertEquals("edited@example.test", result.values.single { it.id == "email-a" }.value)
        assertEquals(listOf(0, 1), result.values.filter { it.kind == ContactValueKind.EMAIL }.map { it.order })
        assertEquals(listOf("hidden-a", "hidden-b"), result.values.filter {
            it.kind == ContactValueKind.LANGUAGE
        }.map { it.id })
        assertEquals(listOf(0, 1), result.values.filter { it.kind == ContactValueKind.LANGUAGE }.map { it.order })
    }

    @Test
    fun `formatted-only Android postal edit is retained as a legacy street`() {
        val canonical = contact(
            values = listOf(
                value(
                    "postal",
                    ContactValueKind.POSTAL_ADDRESS,
                    value = "1 Old Street, Paris",
                    components = mapOf(
                        "street" to "1 Old Street",
                        "locality" to "Paris",
                        "remote-extra" to "keep",
                    ),
                ),
            ),
        )
        val baseline = mapper.project(canonical).withProviderIds()
        val formattedOnly = baseline.row(AndroidRowKind.POSTAL_ADDRESS).copy(
            value = "2 New Street",
            components = AndroidComponent.entries.associateWith { "" } +
                (AndroidComponent.FORMATTED_ADDRESS to "2 New Street"),
        )

        val result = mapper.applyControlledDelta(
            canonical,
            baseline,
            baseline.copy(rows = baseline.rows.replace(formattedOnly)),
        ).contact.values.single()

        assertEquals("2 New Street", result.value)
        assertEquals("2 New Street", result.components["street"])
        assertEquals("keep", result.components["remote-extra"])
    }

    @Test
    fun `projection covers every approved representable family and omits canonical-only families`() {
        val contact = contact(
            values = listOf(
                value("name", ContactValueKind.STRUCTURED_NAME, components = mapOf("given" to "Ada", "family" to "Lovelace")),
                value("phonetic", ContactValueKind.PHONETIC_NAME, components = mapOf("given" to "Ay-da")),
                value("email", ContactValueKind.EMAIL, "ada@example.test", "HOME"),
                value("phone", ContactValueKind.PHONE, "+331234", "CELL"),
                value("postal", ContactValueKind.POSTAL_ADDRESS, components = mapOf("street" to "1 Byte St")),
                value("org", ContactValueKind.ORGANIZATION, components = mapOf("company" to "Analytical")),
                value("title", ContactValueKind.TITLE, "Programmer"),
                value("role", ContactValueKind.ROLE, "Research"),
                value("photo", ContactValueKind.PHOTO, "photo:primary", binaryReference = "photo-ref"),
                value("nickname", ContactValueKind.NICKNAME, "Enchantress"),
                value("note", ContactValueKind.NOTE, "Primary note"),
                value("url", ContactValueKind.URL, "https://example.test", "WORK"),
                value("birthday", ContactValueKind.BIRTHDAY, "--12-10"),
                value("anniversary", ContactValueKind.ANNIVERSARY, "1843-12-10"),
                value("custom-date", ContactValueKind.CUSTOM_DATE, "--04-15", "Launch"),
                value("relation", ContactValueKind.RELATIONSHIP, "Charles", "friend"),
                value("logo", ContactValueKind.LOGO, "logo:canonical-only"),
                value("language", ContactValueKind.LANGUAGE, "en"),
                value("timezone", ContactValueKind.TIME_ZONE, "+0100"),
                value("gender", ContactValueKind.GENDER, "F"),
                value("member", ContactValueKind.MEMBER, "mailto:list@example.test"),
                value("category", ContactValueKind.CATEGORY, "History"),
                value("key", ContactValueKind.PUBLIC_KEY, "data:application/pgp-keys,..."),
                value("unknown", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-CANARY:keep"),
            ),
        )

        val projection = mapper.project(contact)

        assertEquals(AndroidRowKind.entries.toSet(), projection.rows.map(AndroidContactRow::kind).toSet())
        assertEquals("phonetic", projection.row(AndroidRowKind.STRUCTURED_NAME)
            .linkedCanonicalValueIds[AndroidLinkedValueRole.PHONETIC_NAME])
        assertEquals("title", projection.row(AndroidRowKind.ORGANIZATION)
            .linkedCanonicalValueIds[AndroidLinkedValueRole.TITLE])
        assertEquals("role", projection.row(AndroidRowKind.ORGANIZATION)
            .linkedCanonicalValueIds[AndroidLinkedValueRole.ROLE])
        assertEquals("photo-ref", projection.row(AndroidRowKind.PHOTO).binaryReference)
        assertEquals("", projection.row(AndroidRowKind.PHOTO).value)
        assertFalse(projection.rows.any { it.identity.canonicalValueId in CANONICAL_ONLY_IDS })
    }

    @Test
    fun `primary-only families select lowest positive pref then preserve hidden occurrences`() {
        val first = value("note-first", ContactValueKind.NOTE, "first", order = 0, primary = true)
        val preferred = value(
            "note-pref",
            ContactValueKind.NOTE,
            "preferred",
            order = 2,
            metadata = mapOf("vcardPref" to "2"),
        )
        val lowerPreference = value(
            "note-low-pref",
            ContactValueKind.NOTE,
            "lowest",
            order = 3,
            metadata = mapOf("vcardPref" to "1"),
        )
        val contact = contact(values = listOf(first, preferred, lowerPreference))
        val baseline = mapper.project(contact).withProviderIds()
        val observed = baseline.copy(rows = baseline.rows.map { row ->
            if (row.kind == AndroidRowKind.NOTE) row.copy(value = "edited lowest") else row
        })

        assertEquals("note-low-pref", baseline.row(AndroidRowKind.NOTE).identity.canonicalValueId)
        val delta = mapper.applyControlledDelta(contact, baseline, observed)

        assertEquals("edited lowest", delta.contact.values.single { it.id == "note-low-pref" }.value)
        assertEquals(first, delta.contact.values.single { it.id == first.id })
        assertEquals(preferred, delta.contact.values.single { it.id == preferred.id })
        assertEquals(contact.preservationEnvelope, delta.contact.preservationEnvelope)
    }

    @Test
    fun `05-PRIMARY-NOTE FX-C-008 edit delete and second pass affect only selected note`() {
        val archive = value(
            "note-archive", ContactValueKind.NOTE, "archive", order = 0,
            metadata = mapOf("vcardPref" to "7", "unknownParam" to "archive"),
        )
        val primary = value(
            "note-primary", ContactValueKind.NOTE, "projected", order = 1,
            metadata = mapOf("vcardPref" to "2", "unknownParam" to "android"),
        )
        val plain = value("note-plain", ContactValueKind.NOTE, "plain", order = 2)
        val canary = value(
            "canary", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "preserve-opaque-note-marker",
        )
        val original = contact(values = listOf(archive, primary, plain, canary))
        val baseline = mapper.project(original).withProviderIds()
        assertEquals(primary.id, baseline.row(AndroidRowKind.NOTE).identity.canonicalValueId)

        val editedObservation = baseline.copy(rows = baseline.rows.map { row ->
            if (row.kind == AndroidRowKind.NOTE) row.copy(value = "edited") else row
        })
        val editedDelta = mapper.applyControlledDelta(original, baseline, editedObservation)
        val edited = editedDelta.contact
        assertEquals(setOf(primary.id), editedDelta.changedValueIds)
        assertEquals("edited", edited.values.single { it.id == primary.id }.value)
        assertEquals(primary.metadata, edited.values.single { it.id == primary.id }.metadata)
        assertEquals(archive, edited.values.single { it.id == archive.id })
        assertEquals(plain, edited.values.single { it.id == plain.id })
        assertEquals(canary, edited.values.single { it.id == canary.id })

        val secondBaseline = mapper.project(edited)
        val secondPass = mapper.applyControlledDelta(edited, secondBaseline, secondBaseline.withProviderIds())
        assertSame(edited, secondPass.contact)
        assertFalse(secondPass.hasChanges)
        assertEquals("AndroidCanonicalDelta(REDACTED, changedValueCount=0)", secondPass.toString())
        assertFalse(secondPass.toString().contains("edited"))

        val deletedObservation = baseline.copy(rows = baseline.rows.filterNot { it.kind == AndroidRowKind.NOTE })
        val deletedDelta = mapper.applyControlledDelta(original, baseline, deletedObservation)
        assertEquals(setOf(primary.id), deletedDelta.changedValueIds)
        assertEquals(listOf(archive, plain, canary), deletedDelta.contact.values)
        assertFalse(deletedDelta.toString().contains("preserve-opaque-note-marker"))
    }

    @Test
    fun `deleting projected singleton removes only its stable value identity`() {
        val primary = value("photo-a", ContactValueKind.PHOTO, "a", primary = true)
        val hidden = value("photo-b", ContactValueKind.PHOTO, "b", order = 1)
        val contact = contact(values = listOf(primary, hidden, value("canary", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "keep")))
        val baseline = mapper.project(contact).withProviderIds()
        val observed = baseline.copy(rows = baseline.rows.filterNot { it.kind == AndroidRowKind.PHOTO })

        val result = mapper.applyControlledDelta(contact, baseline, observed).contact

        assertFalse(result.values.any { it.id == primary.id })
        assertEquals(hidden, result.values.single { it.id == hidden.id })
        assertEquals("keep", result.values.single { it.id == "canary" }.value)
    }

    @Test
    fun `empty provider photo marker forces bounded photo reseed`() {
        val photo = value(
            "photo-a",
            ContactValueKind.PHOTO,
            primary = true,
            binaryReference = "data:image/png;base64,cHJvamVjdGlvbi10ZXN0",
        )
        val contact = contact(values = listOf(photo))
        val current = mapper.project(contact).withProviderIds().let { snapshot ->
            snapshot.copy(
                rows = snapshot.rows.map { row ->
                    if (row.kind == AndroidRowKind.PHOTO) {
                        row.copy(binaryReference = "provider://missing-photo")
                    } else {
                        row
                    }
                },
            )
        }

        val update = mapper.planProjection(current, contact).operations.single() as AndroidRowOperation.Update

        assertEquals(photo.binaryReference, update.desired.binaryReference)
    }

    @Test
    fun `organization edit and deletion preserve hidden organizations and their companions`() {
        val values = listOf(
            value("org-a", ContactValueKind.ORGANIZATION, components = mapOf("company" to "A"), primary = true),
            value("org-b", ContactValueKind.ORGANIZATION, components = mapOf("company" to "B"), order = 1),
            value("title-a", ContactValueKind.TITLE, "Title A", primary = true),
            value("title-b", ContactValueKind.TITLE, "Title B", order = 1),
            value("role-a", ContactValueKind.ROLE, "Role A", primary = true),
            value("role-b", ContactValueKind.ROLE, "Role B", order = 1),
        )
        val contact = contact(values = values)
        val baseline = mapper.project(contact).withProviderIds()
        val editedRow = baseline.row(AndroidRowKind.ORGANIZATION).copy(
            components = baseline.row(AndroidRowKind.ORGANIZATION).components +
                (AndroidComponent.COMPANY to "A edited") +
                (AndroidComponent.TITLE to "Title A edited"),
        )
        val edited = mapper.applyControlledDelta(
            contact,
            baseline,
            baseline.copy(rows = baseline.rows.replace(editedRow)),
        ).contact

        assertEquals("A edited", edited.values.single { it.id == "org-a" }.components["company"])
        assertEquals("Title A edited", edited.values.single { it.id == "title-a" }.value)
        assertEquals(values.single { it.id == "org-b" }, edited.values.single { it.id == "org-b" })
        assertEquals(values.single { it.id == "title-b" }, edited.values.single { it.id == "title-b" })
        assertEquals(values.single { it.id == "role-b" }, edited.values.single { it.id == "role-b" })

        val deleted = mapper.applyControlledDelta(
            contact,
            baseline,
            baseline.copy(rows = baseline.rows.filterNot { it.kind == AndroidRowKind.ORGANIZATION }),
        ).contact
        assertEquals(setOf("org-b", "title-b", "role-b"), deleted.values.map(ContactValue::id).toSet())
    }

    @Test
    fun `value-only edit retains exact custom label and unknown remote type tokens`() {
        val original = value(
            "phone",
            ContactValueKind.PHONE,
            "+330000",
            label = "Desk line",
            metadata = mapOf(
                "androidSemanticType" to "CUSTOM",
                "androidCustomLabel" to "Desk line",
                "vcardTypeTokens" to "X-REMOTE\u001fVOICE",
                "opaque" to "keep",
            ),
        )
        val contact = contact(values = listOf(original))
        val baseline = mapper.project(contact).withProviderIds()
        val editedRow = baseline.row(AndroidRowKind.PHONE).copy(value = "+331111")

        val edited = mapper.applyControlledDelta(
            contact,
            baseline,
            baseline.copy(rows = baseline.rows.replace(editedRow)),
        ).contact.values.single { it.id == "phone" }

        assertEquals("+331111", edited.value)
        assertEquals("Desk line", edited.label)
        assertEquals(original.metadata, edited.metadata)
    }

    @Test
    fun `explicit type edit replaces managed tokens but preserves unrelated unknown tokens`() {
        val original = value(
            "phone",
            ContactValueKind.PHONE,
            "+330000",
            label = "HOME",
            metadata = mapOf("vcardTypeTokens" to "HOME\u001fX-REMOTE"),
        )
        val contact = contact(values = listOf(original))
        val baseline = mapper.project(contact).withProviderIds()
        val changedType = baseline.row(AndroidRowKind.PHONE).copy(semanticType = AndroidSemanticType.FAX_WORK)

        val edited = mapper.applyControlledDelta(
            contact,
            baseline,
            baseline.copy(rows = baseline.rows.replace(changedType)),
        ).contact.values.single()

        assertEquals("WORK", edited.label)
        assertEquals("X-REMOTE\u001fWORK\u001fFAX", edited.metadata["vcardTypeTokens"])
        assertEquals("FAX_WORK", edited.metadata["androidSemanticType"])
    }

    @Test
    fun `unsupported and blank custom types have deterministic local fallbacks`() {
        val unsupported = value("phone", ContactValueKind.PHONE, "+330", "Satellite")
        val blank = value("email", ContactValueKind.EMAIL, "a@example.test", " ")
        val projection = mapper.project(contact(values = listOf(unsupported, blank)))

        assertEquals(AndroidSemanticType.CUSTOM, projection.rows.single { it.identity.canonicalValueId == "phone" }.semanticType)
        assertEquals("Satellite", projection.rows.single { it.identity.canonicalValueId == "phone" }.customLabel)
        assertEquals(AndroidSemanticType.UNSPECIFIED, projection.rows.single { it.identity.canonicalValueId == "email" }.semanticType)
        assertTrue(runCatching {
            AndroidContactRow(
                AndroidValueIdentity("invalid"),
                AndroidRowKind.PHONE,
                semanticType = AndroidSemanticType.CUSTOM,
                customLabel = " ",
            )
        }.isFailure)
    }

    @Test
    fun `all writable Android semantic types retain their provider-neutral identity`() {
        val phoneTypes = setOf(
            AndroidSemanticType.HOME,
            AndroidSemanticType.WORK,
            AndroidSemanticType.OTHER,
            AndroidSemanticType.MOBILE,
            AndroidSemanticType.FAX_HOME,
            AndroidSemanticType.FAX_WORK,
            AndroidSemanticType.OTHER_FAX,
            AndroidSemanticType.PAGER,
            AndroidSemanticType.CALLBACK,
            AndroidSemanticType.CAR,
            AndroidSemanticType.COMPANY_MAIN,
            AndroidSemanticType.ISDN,
            AndroidSemanticType.MAIN,
            AndroidSemanticType.RADIO,
            AndroidSemanticType.TELEX,
            AndroidSemanticType.TTY_TDD,
            AndroidSemanticType.WORK_MOBILE,
            AndroidSemanticType.WORK_PAGER,
            AndroidSemanticType.ASSISTANT,
            AndroidSemanticType.MMS,
            AndroidSemanticType.CUSTOM,
        )
        val relationshipTypes = setOf(
            AndroidSemanticType.ASSISTANT,
            AndroidSemanticType.BROTHER,
            AndroidSemanticType.CHILD,
            AndroidSemanticType.DOMESTIC_PARTNER,
            AndroidSemanticType.FATHER,
            AndroidSemanticType.FRIEND,
            AndroidSemanticType.MANAGER,
            AndroidSemanticType.MOTHER,
            AndroidSemanticType.PARENT,
            AndroidSemanticType.PARTNER,
            AndroidSemanticType.REFERRED_BY,
            AndroidSemanticType.RELATIVE,
            AndroidSemanticType.SISTER,
            AndroidSemanticType.SPOUSE,
            AndroidSemanticType.CUSTOM,
        )
        val cases = buildList {
            phoneTypes.forEach { add(Triple(ContactValueKind.PHONE, AndroidRowKind.PHONE, it)) }
            setOf(
                AndroidSemanticType.HOME,
                AndroidSemanticType.WORK,
                AndroidSemanticType.OTHER,
                AndroidSemanticType.MOBILE,
                AndroidSemanticType.CUSTOM,
            ).forEach { add(Triple(ContactValueKind.EMAIL, AndroidRowKind.EMAIL, it)) }
            setOf(
                AndroidSemanticType.HOME,
                AndroidSemanticType.WORK,
                AndroidSemanticType.OTHER,
                AndroidSemanticType.CUSTOM,
            ).forEach { add(Triple(ContactValueKind.POSTAL_ADDRESS, AndroidRowKind.POSTAL_ADDRESS, it)) }
            setOf(
                AndroidSemanticType.HOME,
                AndroidSemanticType.WORK,
                AndroidSemanticType.OTHER,
                AndroidSemanticType.BLOG,
                AndroidSemanticType.PROFILE,
                AndroidSemanticType.FTP,
                AndroidSemanticType.CUSTOM,
            ).forEach { add(Triple(ContactValueKind.URL, AndroidRowKind.WEBSITE, it)) }
            relationshipTypes.forEach { add(Triple(ContactValueKind.RELATIONSHIP, AndroidRowKind.RELATIONSHIP, it)) }
        }
        cases.forEachIndexed { index, (canonicalKind, rowKind, semanticType) ->
            val custom = "Custom $index".takeIf { semanticType == AndroidSemanticType.CUSTOM }
            val projected = mapper.project(
                contact(
                    values = listOf(value(
                        id = "value-$index",
                        kind = canonicalKind,
                        value = "payload-$index",
                        label = custom,
                        metadata = buildMap {
                            put("androidSemanticType", semanticType.name)
                            custom?.let { put("androidCustomLabel", it) }
                        },
                    )),
                ),
            ).row(rowKind)
            assertEquals("$rowKind/$semanticType", semanticType, projected.semanticType)
            assertEquals(custom, projected.customLabel)
        }
    }

    @Test
    fun `new provider row with assigned canonical identity becomes a durable canonical value`() {
        val contact = contact(values = emptyList())
        val baseline = mapper.project(contact).withProviderIds()
        val added = AndroidContactRow(
            identity = AndroidValueIdentity("android-created-email", providerRowId = 99),
            kind = AndroidRowKind.EMAIL,
            value = "new@example.test",
            semanticType = AndroidSemanticType.MOBILE,
            order = 0,
        )

        val result = mapper.applyControlledDelta(
            contact,
            baseline,
            baseline.copy(rows = baseline.rows + added),
        )

        val created = result.contact.values.single()
        assertEquals("android-created-email", created.id)
        assertEquals(ContactValueKind.EMAIL, created.kind)
        assertEquals("new@example.test", created.value)
        assertEquals("MOBILE", created.metadata["androidSemanticType"])
        assertEquals(null, created.label)
        assertTrue("android-created-email" in result.changedValueIds)
    }

    @Test
    fun `full and yearless standard dates and labeled custom dates round trip without fabrication`() {
        val contact = contact(
            values = listOf(
                value("birthday", ContactValueKind.BIRTHDAY, "--04-15"),
                value("anniversary", ContactValueKind.ANNIVERSARY, "2020-09-30"),
                value("custom", ContactValueKind.CUSTOM_DATE, "--02-29", "Leap reminder"),
            ),
        )
        val baseline = mapper.project(contact).withProviderIds()
        assertEquals("--04-15", baseline.row(AndroidRowKind.BIRTHDAY).value)
        assertEquals("2020-09-30", baseline.row(AndroidRowKind.ANNIVERSARY).value)
        assertEquals("--02-29", baseline.row(AndroidRowKind.CUSTOM_DATE).value)
        assertEquals(AndroidSemanticType.UNSPECIFIED, baseline.row(AndroidRowKind.BIRTHDAY).semanticType)
        assertEquals(AndroidSemanticType.UNSPECIFIED, baseline.row(AndroidRowKind.ANNIVERSARY).semanticType)
        assertEquals(AndroidSemanticType.CUSTOM, baseline.row(AndroidRowKind.CUSTOM_DATE).semanticType)
        assertEquals("Leap reminder", baseline.row(AndroidRowKind.CUSTOM_DATE).customLabel)

        val editedBirthday = baseline.row(AndroidRowKind.BIRTHDAY).copy(value = "--12-31")
        val result = mapper.applyControlledDelta(
            contact,
            baseline,
            baseline.copy(rows = baseline.rows.replace(editedBirthday)),
        ).contact

        assertEquals("--12-31", result.values.single { it.id == "birthday" }.value)
        assertEquals("2020-09-30", result.values.single { it.id == "anniversary" }.value)
        assertEquals("--02-29", result.values.single { it.id == "custom" }.value)
        assertEquals("Leap reminder", result.values.single { it.id == "custom" }.label)
    }

    @Test
    fun `05-IMAGE projects preferred photo and preserves gallery logos through Android edit delete and reorder`() {
        val first = value(
            "photo-first", ContactValueKind.PHOTO, "https://img.example.test/first.png", order = 0,
            metadata = mapOf("vcardPref" to "3"), binaryReference = "private://first",
        )
        val preferred = value(
            "photo-preferred", ContactValueKind.PHOTO, "https://img.example.test/preferred.png", order = 1,
            metadata = mapOf("vcardPref" to "1"), binaryReference = "private://preferred",
        )
        val logo = value(
            "logo-primary", ContactValueKind.LOGO, "https://img.example.test/logo.png",
            metadata = mapOf("vcardPref" to "1"),
        )
        val canary = value("image-canary", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "opaque-image-marker")
        val original = contact(values = listOf(first, preferred, logo, canary))
        val baseline = mapper.project(original).withProviderIds()
        assertEquals(preferred.id, baseline.row(AndroidRowKind.PHOTO).identity.canonicalValueId)
        assertEquals("private://preferred", baseline.row(AndroidRowKind.PHOTO).binaryReference)

        val capturedPhoto = "data:image/png;base64,ZWRpdGVk"
        val editedObservation = baseline.copy(rows = baseline.rows.map { row ->
            if (row.kind == AndroidRowKind.PHOTO) row.copy(
                value = "",
                binaryReference = capturedPhoto,
            ) else row
        })
        val edited = mapper.applyControlledDelta(original, baseline, editedObservation).contact
        assertEquals(capturedPhoto, edited.values.single { it.id == preferred.id }.value)
        assertEquals(capturedPhoto, edited.values.single { it.id == preferred.id }.binaryReference)
        assertEquals(first, edited.values.single { it.id == first.id })
        assertEquals(logo, edited.values.single { it.id == logo.id })
        assertEquals(canary, edited.values.single { it.id == canary.id })

        val deleted = mapper.applyControlledDelta(
            original,
            baseline,
            baseline.copy(rows = baseline.rows.filterNot { it.kind == AndroidRowKind.PHOTO }),
        ).contact
        assertEquals(first.id, mapper.project(deleted).row(AndroidRowKind.PHOTO).identity.canonicalValueId)
        assertEquals(logo, deleted.values.single { it.id == logo.id })

        val reordered = original.copy(values = original.values.map { value ->
            when (value.id) {
                first.id -> value.copy(metadata = value.metadata + ("vcardPref" to "1"), isPrimary = true)
                preferred.id -> value.copy(metadata = value.metadata + ("vcardPref" to "2"), isPrimary = false)
                else -> value
            }
        })
        assertEquals(first.id, mapper.project(reordered).row(AndroidRowKind.PHOTO).identity.canonicalValueId)
        assertFalse(mapper.project(reordered).toString().contains("img.example.test"))
    }

    @Test
    fun `05-DATE custom creation edit deletion relations and unknown canary remain scoped`() {
        val canary = value("canary", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "opaque")
        val relation = value(
            "relation", ContactValueKind.RELATIONSHIP, "Synthetic relation", "X-REMOTE-REL",
            metadata = mapOf("vcardTypeTokens" to "X-REMOTE-REL"),
        )
        val original = contact(values = listOf(
            value("birthday", ContactValueKind.BIRTHDAY, "--02-29"),
            relation,
            canary,
        ))
        val baseline = mapper.project(original).withProviderIds()
        val createdRow = AndroidContactRow(
            identity = AndroidValueIdentity("custom-created", 99),
            kind = AndroidRowKind.CUSTOM_DATE,
            value = "--07-14",
            semanticType = AndroidSemanticType.CUSTOM,
            customLabel = "Jour spécial",
            order = 0,
        )
        val created = mapper.applyControlledDelta(
            original,
            baseline,
            baseline.copy(rows = baseline.rows + createdRow),
        ).contact
        val custom = created.values.single { it.id == "custom-created" }
        assertEquals("--07-14", custom.value)
        assertEquals("Jour spécial", custom.label)
        assertEquals("LOCAL_ONLY", custom.metadata["syncDisposition"])
        assertEquals(relation, created.values.single { it.id == relation.id })
        assertEquals(canary, created.values.single { it.id == canary.id })

        val createdBaseline = mapper.project(created).withProviderIds()
        val editedRow = createdBaseline.rows.single { it.identity.canonicalValueId == custom.id }
            .copy(value = "--12-31")
        val edited = mapper.applyControlledDelta(
            created,
            createdBaseline,
            createdBaseline.copy(rows = createdBaseline.rows.replace(editedRow)),
        ).contact
        assertEquals("--12-31", edited.values.single { it.id == custom.id }.value)
        assertEquals("LOCAL_ONLY", edited.values.single { it.id == custom.id }.metadata["syncDisposition"])

        val editedBaseline = mapper.project(edited).withProviderIds()
        val deleted = mapper.applyControlledDelta(
            edited,
            editedBaseline,
            editedBaseline.copy(rows = editedBaseline.rows.filterNot { it.identity.canonicalValueId == custom.id }),
        ).contact
        assertFalse(deleted.values.any { it.id == custom.id })
        assertEquals(relation, deleted.values.single { it.id == relation.id })
        assertEquals(canary, deleted.values.single { it.id == canary.id })
        val second = mapper.applyControlledDelta(deleted, mapper.project(deleted), mapper.project(deleted))
        assertFalse(second.hasChanges)
    }

    @Test
    fun `fingerprint ignores provider locators and query order but detects visible changes`() {
        val projected = mapper.project(
            contact(values = listOf(
                value("email-a", ContactValueKind.EMAIL, "a@example.test", order = 0),
                value("email-b", ContactValueKind.EMAIL, "b@example.test", order = 1),
            )),
        )
        val withLocators = projected.withProviderIds()
        val reordered = withLocators.copy(rows = withLocators.rows.reversed())

        assertEquals(mapper.fingerprint(projected), mapper.fingerprint(withLocators))
        assertEquals(mapper.fingerprint(projected), mapper.fingerprint(reordered))
        val changed = reordered.copy(rows = reordered.rows.map { if (it.kind == AndroidRowKind.EMAIL) it.copy(value = "changed") else it })
        assertNotEquals(mapper.fingerprint(projected), mapper.fingerprint(changed))
    }

    @Test
    fun `no-change controlled delta returns original contact and empty change set`() {
        val contact = contact(values = listOf(value("email", ContactValueKind.EMAIL, "a@example.test")))
        val baseline = mapper.project(contact)
        val observed = baseline.withProviderIds().copy(rows = baseline.withProviderIds().rows.reversed())

        val result = mapper.applyControlledDelta(contact, baseline, observed)

        assertSame(contact, result.contact)
        assertFalse(result.hasChanges)
        assertEquals(mapper.fingerprint(baseline), result.observedFingerprint)
    }

    @Test
    fun `projection planner uses current provider locator for update and delete and none for insert`() {
        val initial = contact(
            values = listOf(
                value("update", ContactValueKind.EMAIL, "before@example.test"),
                value("delete", ContactValueKind.PHONE, "+330"),
            ),
        )
        val current = mapper.project(initial).withProviderIds()
        val desired = initial.copy(values = listOf(
            value("update", ContactValueKind.EMAIL, "after@example.test"),
            value("insert", ContactValueKind.NOTE, "new note"),
        ))

        val plan = mapper.planProjection(current, desired)

        val update = plan.operations.filterIsInstance<AndroidRowOperation.Update>().single()
        val delete = plan.operations.filterIsInstance<AndroidRowOperation.Delete>().single()
        val insert = plan.operations.filterIsInstance<AndroidRowOperation.Insert>().single()
        assertEquals(current.rows.single { it.identity.canonicalValueId == "update" }.identity, update.currentIdentity)
        assertEquals("delete", delete.currentIdentity.canonicalValueId)
        assertTrue(delete.currentIdentity.providerRowId != null)
        assertEquals("insert", insert.desired.identity.canonicalValueId)
        assertEquals(null, insert.desired.identity.providerRowId)
    }

    @Test
    fun `structured and phonetic name delta changes mapped values without touching envelope`() {
        val envelope = PreservationEnvelope(mapOf("proton-card-1-0" to "X-CANARY:keep"), "baseline")
        val contact = contact(
            values = listOf(
                value("name", ContactValueKind.STRUCTURED_NAME, components = mapOf(
                    "given" to "Ada",
                    "family" to "Lovelace",
                    "additional" to "Byron",
                    "prefix" to "Countess",
                    "suffix" to "I",
                    "remote-extra" to "keep",
                )),
                value("phonetic", ContactValueKind.PHONETIC_NAME, components = mapOf("given" to "Ay-da")),
                value("unknown", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-CANARY:keep"),
            ),
            envelope = envelope,
        )
        val baseline = mapper.project(contact).withProviderIds()
        val name = baseline.row(AndroidRowKind.STRUCTURED_NAME).copy(
            components = baseline.row(AndroidRowKind.STRUCTURED_NAME).components +
                (AndroidComponent.GIVEN_NAME to "Augusta") +
                (AndroidComponent.PHONETIC_GIVEN_NAME to "Aw-gus-ta"),
        )

        val result = mapper.applyControlledDelta(
            contact,
            baseline,
            baseline.copy(rows = baseline.rows.replace(name)),
        ).contact

        assertEquals("Augusta", result.firstName)
        assertEquals("Lovelace;Augusta;Byron;Countess;I", result.values.single { it.id == "name" }.value)
        assertEquals("keep", result.values.single { it.id == "name" }.components["remote-extra"])
        assertEquals("Aw-gus-ta", result.values.single { it.id == "phonetic" }.components["given"])
        assertEquals(contact.values.single { it.id == "unknown" }, result.values.single { it.id == "unknown" })
        assertEquals(envelope, result.preservationEnvelope)
    }

    @Test
    fun `unambiguous super-primary changes only repeatable family preference`() {
        val contact = contact(values = listOf(
            value("email-a", ContactValueKind.EMAIL, "a@example.test", primary = true),
            value("email-b", ContactValueKind.EMAIL, "b@example.test", order = 1),
            value("phone", ContactValueKind.PHONE, "+330", primary = true),
        ))
        val baseline = mapper.project(contact).withProviderIds()
        val observed = baseline.copy(rows = baseline.rows.map { row ->
            when (row.identity.canonicalValueId) {
                "email-a" -> row.copy(isPrimary = false, isSuperPrimary = false)
                "email-b" -> row.copy(isPrimary = true, isSuperPrimary = true)
                else -> row
            }
        })

        val result = mapper.applyControlledDelta(contact, baseline, observed).contact

        assertFalse(result.values.single { it.id == "email-a" }.isPrimary)
        assertTrue(result.values.single { it.id == "email-b" }.isPrimary)
        assertEquals("2", result.values.single { it.id == "email-a" }.metadata["vcardPref"])
        assertEquals("1", result.values.single { it.id == "email-b" }.metadata["vcardPref"])
        assertTrue(result.values.single { it.id == "phone" }.isPrimary)
    }

    @Test
    fun `blank email primary signal is ignored and does not receive preference metadata`() {
        val contact = contact(values = listOf(
            value("usable", ContactValueKind.EMAIL, "usable@example.test", primary = true),
            value("blank", ContactValueKind.EMAIL, "", order = 1),
        ))
        val baseline = mapper.project(contact).withProviderIds()
        val observed = baseline.copy(rows = baseline.rows.map { row ->
            when (row.identity.canonicalValueId) {
                "usable" -> row.copy(isPrimary = false, isSuperPrimary = false)
                "blank" -> row.copy(isPrimary = true, isSuperPrimary = true)
                else -> row
            }
        })

        val result = mapper.applyControlledDelta(contact, baseline, observed).contact

        assertTrue(result.values.single { it.id == "usable" }.isPrimary)
        assertFalse(result.values.single { it.id == "blank" }.isPrimary)
        assertFalse(result.values.single { it.id == "blank" }.metadata.containsKey("vcardPref"))
    }

    @Test
    fun `value identity rejects invalid provider locators and fingerprints never expose values`() {
        assertTrue(runCatching { AndroidValueIdentity("", null) }.isFailure)
        assertTrue(runCatching { AndroidValueIdentity("value", 0) }.isFailure)
        val fingerprint = mapper.fingerprint(mapper.project(contact(values = listOf(
            value("secret-id", ContactValueKind.NOTE, "secret payload"),
        ))))
        assertFalse(fingerprint.toString().contains("secret"))
    }

    @Test
    fun `whole projection proof ignores name locator but rejects changed name identity phonetics or other content`() {
        val baseline = mapper.project(contact(values = listOf(
            value("note", ContactValueKind.NOTE, "Keep this note"),
            value("photo", ContactValueKind.PHOTO, binaryReference = "owned-photo"),
        ))).withProviderIds()
        val name = baseline.row(AndroidRowKind.STRUCTURED_NAME)
        val moved = baseline.copy(rows = baseline.rows.replace(name.copy(identity = name.identity.copy(providerRowId = 700))))
        val expected = mapper.fingerprint(baseline)
        assertEquals(expected, mapper.fingerprint(mapper.normalizeGeneratedName(moved, baseline)))
        val changed = listOf(
            moved.copy(rows = moved.rows.replace(name.copy(components = name.components + (AndroidComponent.GIVEN_NAME to "Changed")))),
            moved.copy(rows = moved.rows.map { row ->
                if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(identity = row.identity.copy(canonicalValueId = "forged-name")) else row
            }),
            moved.copy(rows = moved.rows.replace(name.copy(linkedCanonicalValueIds = mapOf(AndroidLinkedValueRole.PHONETIC_NAME to "forged-phonetic")))),
            moved.copy(rows = moved.rows.replace(moved.row(AndroidRowKind.NOTE).copy(value = "Changed note"))),
            moved.copy(rows = moved.rows.replace(moved.row(AndroidRowKind.PHOTO).copy(binaryReference = "changed-photo"))),
        )
        changed.forEach { assertFalse(expected == mapper.fingerprint(mapper.normalizeGeneratedName(it, baseline))) }
    }

    private fun contact(
        values: List<ContactValue>,
        envelope: PreservationEnvelope? = PreservationEnvelope(
            rawProperties = mapOf("canary" to "X-CANARY:keep"),
            remoteBaseline = "remote-baseline",
        ),
    ) = CanonicalContact(
        accountId = "account",
        id = "contact",
        firstName = "Ada",
        lastName = "Lovelace",
        displayName = "Ada Lovelace",
        values = values,
        preservationEnvelope = envelope,
    )

    private fun value(
        id: String,
        kind: ContactValueKind,
        value: String = "",
        label: String? = null,
        order: Int = 0,
        primary: Boolean = false,
        components: Map<String, String> = emptyMap(),
        metadata: Map<String, String> = emptyMap(),
        binaryReference: String? = null,
    ) = ContactValue(
        id = id,
        kind = kind,
        value = value,
        label = label,
        order = order,
        isPrimary = primary,
        components = components,
        metadata = metadata,
        binaryReference = binaryReference,
    )

    private fun AndroidContactSnapshot.row(kind: AndroidRowKind): AndroidContactRow = rows.single { it.kind == kind }

    private fun AndroidContactSnapshot.withProviderIds(): AndroidContactSnapshot = copy(
        rows = rows.mapIndexed { index, row -> row.copy(identity = row.identity.copy(providerRowId = index + 1L)) },
    )

    private fun List<AndroidContactRow>.replace(replacement: AndroidContactRow): List<AndroidContactRow> =
        map { if (it.identity.canonicalValueId == replacement.identity.canonicalValueId) replacement else it }

    private companion object {
        val CANONICAL_ONLY_IDS = setOf("logo", "language", "timezone", "gender", "member", "category", "key", "unknown")
    }
}
