package com.patmanak.contako.data.android.provider

import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidLinkedValueRole
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidSemanticType
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidProviderRowCodecDeviceTest {
    @Test
    fun importedProviderEventTextIsDecodedVerbatim() {
        listOf("20000229", "--0229", "2000-02", "2000-02-29T12:30:00Z", "circa spring 2000")
            .forEach { raw ->
                val snapshot = codec().decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(
                    row(1, EVENT, "birthday", slots(DATA1 to raw, DATA2 to EVENT_BIRTHDAY)),
                ))
                assertEquals(raw, snapshot.rows.single().value)
            }
    }

    @Test
    fun decodesEveryMappedKindComponentCustomLabelDateLinkedIdentityAndPhoto() {
        val originalPhoto = byteArrayOf(1, 2, 3)
        var capturedPhoto: ByteArray? = null
        val codec = codec(
            photoCapture = AndroidDurablePhotoCapture { _, _, _, bytes ->
                capturedPhoto = bytes.copyOf()
                bytes[0] = 99
                "sha256:immutable-photo"
            },
        )
        val rows = listOf(
            row(1, NAME, "name", slots(
                DATA1 to "Ada Lovelace",
                DATA2 to "Ada",
                DATA3 to "Lovelace",
                DATA4 to "Countess",
                DATA5 to "Byron",
                DATA6 to "I",
                DATA7 to "Ay-da",
                DATA8 to "Bye-ron",
                DATA9 to "Love-lace",
            ), linked = linked(AndroidLinkedValueRole.PHONETIC_NAME to "phonetic-id")),
            row(2, EMAIL, null, slots(DATA1 to "ada@example.test", DATA2 to EMAIL_CUSTOM, DATA3 to "Personal inbox")),
            row(3, PHONE, "phone", slots(DATA1 to "+33123", DATA2 to PHONE_WORK_MOBILE)),
            row(4, POSTAL, "postal", slots(
                DATA1 to "1 Byte Street",
                DATA2 to POSTAL_HOME,
                DATA4 to "1 Byte Street",
                DATA5 to "PO 42",
                DATA6 to "District",
                DATA7 to "London",
                DATA8 to "Region",
                DATA9 to "N1",
                DATA10 to "UK",
            )),
            row(5, ORGANIZATION, "org", slots(
                DATA1 to "Analytical Engines",
                DATA2 to ORGANIZATION_WORK,
                DATA4 to "Programmer",
                DATA5 to "Research",
                DATA6 to "Computing",
            ), linked = linked(
                AndroidLinkedValueRole.TITLE to "title-id",
                AndroidLinkedValueRole.ROLE to "role-id",
            )),
            row(6, PHOTO, "photo", binary = originalPhoto),
            row(7, NICKNAME, "nickname", slots(DATA1 to "Enchantress")),
            row(8, NOTE, "note", slots(DATA1 to "Keep this note")),
            row(9, WEBSITE, "website", slots(DATA1 to "https://example.test", DATA2 to WEBSITE_BLOG)),
            row(10, EVENT, "birthday", slots(DATA1 to "1843-12-10", DATA2 to EVENT_BIRTHDAY)),
            row(11, EVENT, "anniversary", slots(DATA1 to "--04-15", DATA2 to EVENT_ANNIVERSARY)),
            row(12, EVENT, "custom-date", slots(DATA1 to "--02-29", DATA2 to EVENT_CUSTOM, DATA3 to "Leap day")),
            row(13, RELATION, "relation", slots(DATA1 to "Charles", DATA2 to RELATION_FATHER)),
        )

        val snapshot = codec.decode(ACCOUNT, CONTACT_ID, RAW_ID, rows)

        assertEquals(AndroidRowKind.entries.toSet(), snapshot.rows.map { it.kind }.toSet())
        val name = snapshot.row(AndroidRowKind.STRUCTURED_NAME)
        assertEquals("Ada", name.components[AndroidComponent.GIVEN_NAME])
        assertEquals("Byron", name.components[AndroidComponent.MIDDLE_NAME])
        assertEquals("Love-lace", name.components[AndroidComponent.PHONETIC_FAMILY_NAME])
        assertEquals("phonetic-id", name.linkedCanonicalValueIds[AndroidLinkedValueRole.PHONETIC_NAME])
        val email = snapshot.row(AndroidRowKind.EMAIL)
        assertEquals(AndroidSemanticType.CUSTOM, email.semanticType)
        assertEquals("Personal inbox", email.customLabel)
        assertFalse(email.identity.canonicalValueId == email.identity.providerRowId.toString())
        assertEquals(AndroidSemanticType.WORK_MOBILE, snapshot.row(AndroidRowKind.PHONE).semanticType)
        assertEquals("PO 42", snapshot.row(AndroidRowKind.POSTAL_ADDRESS).components[AndroidComponent.PO_BOX])
        val organization = snapshot.row(AndroidRowKind.ORGANIZATION)
        assertEquals("Programmer", organization.components[AndroidComponent.TITLE])
        assertEquals("Computing", organization.components[AndroidComponent.ROLE])
        assertEquals("title-id", organization.linkedCanonicalValueIds[AndroidLinkedValueRole.TITLE])
        assertEquals("role-id", organization.linkedCanonicalValueIds[AndroidLinkedValueRole.ROLE])
        assertEquals("sha256:immutable-photo", snapshot.row(AndroidRowKind.PHOTO).binaryReference)
        assertArrayEquals(byteArrayOf(1, 2, 3), originalPhoto)
        assertArrayEquals(byteArrayOf(1, 2, 3), capturedPhoto)
        assertEquals(AndroidSemanticType.BLOG, snapshot.row(AndroidRowKind.WEBSITE).semanticType)
        assertEquals("1843-12-10", snapshot.row(AndroidRowKind.BIRTHDAY).value)
        assertEquals("--04-15", snapshot.row(AndroidRowKind.ANNIVERSARY).value)
        assertEquals(AndroidSemanticType.CUSTOM, snapshot.row(AndroidRowKind.CUSTOM_DATE).semanticType)
        assertEquals("Leap day", snapshot.row(AndroidRowKind.CUSTOM_DATE).customLabel)
        assertEquals(AndroidSemanticType.FATHER, snapshot.row(AndroidRowKind.RELATIONSHIP).semanticType)
    }

    @Test
    fun decodesEveryDeclaredPhoneEmailPostalWebsiteAndRelationshipType() {
        val cases = buildList {
            PHONE_TYPES.forEach { (provider, semantic) -> add(TypeCase(PHONE, provider, semantic)) }
            EMAIL_TYPES.forEach { (provider, semantic) -> add(TypeCase(EMAIL, provider, semantic)) }
            POSTAL_TYPES.forEach { (provider, semantic) -> add(TypeCase(POSTAL, provider, semantic)) }
            ORGANIZATION_TYPES.forEach { (provider, semantic) -> add(TypeCase(ORGANIZATION, provider, semantic)) }
            WEBSITE_TYPES.forEach { (provider, semantic) -> add(TypeCase(WEBSITE, provider, semantic)) }
            RELATION_TYPES.forEach { (provider, semantic) -> add(TypeCase(RELATION, provider, semantic)) }
        }
        cases.forEachIndexed { index, case ->
            val custom = "Custom $index".takeIf { case.semantic == AndroidSemanticType.CUSTOM }
            val snapshot = codec().decode(
                ACCOUNT,
                "$CONTACT_ID-$index",
                RAW_ID,
                listOf(
                    row(1, NAME, "name", slots(DATA1 to "Name")),
                    row(2, case.mimeType, "typed", slots(
                        DATA1 to "value",
                        DATA2 to case.providerType.toString(),
                        DATA3 to custom,
                    )),
                ),
            )
            val decoded = snapshot.rows.single { it.kind != AndroidRowKind.STRUCTURED_NAME }
            assertEquals("${case.mimeType}/${case.providerType}", case.semantic, decoded.semanticType)
            assertEquals(custom, decoded.customLabel)
        }
    }

    @Test
    fun missingAndBlankCanonicalIdsUseInjectedDurableAllocatorAndFallbackOrder() {
        val resolver = InMemoryStrictIdentityResolver()
        val codec = codec(identityResolver = resolver)
        val input = listOf(
            row(11, NAME, " ", slots(DATA1 to "Name"), order = null),
            row(7, EMAIL, null, slots(DATA1 to "a@example.test"), order = null),
        )

        val first = codec.decode(ACCOUNT, CONTACT_ID, RAW_ID, input)
        val second = codec.decode(ACCOUNT, CONTACT_ID, RAW_ID, input)

        assertEquals(first.rows.map { it.identity.canonicalValueId }, second.rows.map { it.identity.canonicalValueId })
        assertEquals(listOf(0, 1), first.rows.map { it.order })
        assertTrue(first.rows.all { it.identity.canonicalValueId != it.identity.providerRowId.toString() })
    }

    @Test
    fun contactWithoutStructuredNameRemainsRepresentableForDraftOrExistingNamelessPolicy() {
        val snapshot = codec().decode(
            ACCOUNT,
            CONTACT_ID,
            RAW_ID,
            listOf(row(2, EMAIL, "email", slots(DATA1 to "draft@example.test"))),
        )

        assertEquals(1, snapshot.rows.size)
        assertEquals(AndroidRowKind.EMAIL, snapshot.rows.single().kind)
    }

    @Test
    fun legacyAndLengthPrefixedBase64LinkedIdentityGrammarsAreAcceptedStrictly() {
        val modernEncoding = linked(AndroidLinkedValueRole.PHONETIC_NAME to "id:with,delimiters")
        val legacyEncoding = modernEncoding.substringBefore(':', modernEncoding) + ":" + modernEncoding.substringAfterLast(':')
        val modern = codec().decode(
            ACCOUNT,
            CONTACT_ID,
            RAW_ID,
            listOf(row(
                1,
                NAME,
                "name",
                slots(DATA1 to "Name"),
                linked = modernEncoding,
            )),
        )
        val legacy = codec().decode(
            ACCOUNT,
            "$CONTACT_ID-legacy",
            RAW_ID,
            listOf(row(1, NAME, "name", slots(DATA1 to "Name"), linked = legacyEncoding)),
        )
        assertEquals(
            "id:with,delimiters",
            modern.row(AndroidRowKind.STRUCTURED_NAME)
                .linkedCanonicalValueIds[AndroidLinkedValueRole.PHONETIC_NAME],
        )
        assertEquals(
            "id:with,delimiters",
            legacy.row(AndroidRowKind.STRUCTURED_NAME)
                .linkedCanonicalValueIds[AndroidLinkedValueRole.PHONETIC_NAME],
        )

        listOf(
            "PHONETIC_NAME:not-a-length:YWJj",
            "PHONETIC_NAME:99:YWJj",
            "PHONETIC_NAME:4:***!",
            "TITLE:4:YWJj",
            "PHONETIC_NAME:4:YWJj,PHONETIC_NAME:4:ZGVm",
            "PHONETIC_NAME:",
            "PHONETIC_NAME:***!",
        ).forEach { malformed ->
            assertFailure(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES, "secret-canary") {
                codec().decode(
                    ACCOUNT,
                    CONTACT_ID,
                    RAW_ID,
                    listOf(row(1, NAME, "name", slots(DATA1 to "secret-canary"), linked = malformed)),
                )
            }
        }
    }

    @Test
    fun malformedOrAmbiguousBatchesFailClosedWithRedactedCategories() {
        val name = row(1, NAME, "name", slots(DATA1 to "secret-canary"))
        val cases = listOf(
            FailureCase(AndroidProviderRowCodecFailure.MIXED_RAW_CONTACTS) {
                codec().decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(name.copy(rawContactId = RAW_ID + 1)))
            },
            FailureCase(AndroidProviderRowCodecFailure.DUPLICATE_PROVIDER_ROW) {
                codec().decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(name, name.copy(canonicalValueId = "other")))
            },
            FailureCase(AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY) {
                codec().decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(name, row(2, EMAIL, "name")))
            },
            FailureCase(AndroidProviderRowCodecFailure.DUPLICATE_SINGLETON) {
                codec().decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(name, row(2, NAME, "name-2")))
            },
            FailureCase(AndroidProviderRowCodecFailure.UNSUPPORTED_ROW_KIND) {
                codec().decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(name, row(2, "vnd.invalid/secret-canary", "bad")))
            },
            FailureCase(AndroidProviderRowCodecFailure.GROUP_MEMBERSHIP_REQUIRES_SEPARATE_CODEC) {
                codec().decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(name, row(2, GROUP_MEMBERSHIP, "group")))
            },
            FailureCase(AndroidProviderRowCodecFailure.MALFORMED_ORDER) {
                codec().decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(name.copy(canonicalOrder = -1)))
            },
            FailureCase(AndroidProviderRowCodecFailure.MALFORMED_ORDER) {
                codec().decode(
                    ACCOUNT,
                    CONTACT_ID,
                    RAW_ID,
                    listOf(name.copy(canonicalOrder = null, canonicalOrderMalformed = true)),
                )
            },
            FailureCase(AndroidProviderRowCodecFailure.MALFORMED_DATE) {
                codec().decode(
                    ACCOUNT,
                    CONTACT_ID,
                    RAW_ID,
                    listOf(name, row(2, EVENT, "date", slots(DATA1 to "--02-30", DATA2 to EVENT_BIRTHDAY))),
                )
            },
            FailureCase(AndroidProviderRowCodecFailure.VALUE_ID_ALLOCATION_FAILED) {
                codec(identityResolver = AndroidProviderIdentityResolver { claims ->
                    AndroidProviderIdentityResolution.Bound(claims.associate { claim ->
                        claim.providerRowId to AndroidDurableValueBinding(
                            claim.providerRowId.toString(),
                            claim.claimedLinkedCanonicalValueIds,
                        )
                    })
                })
                    .decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(name.copy(canonicalValueId = null)))
            },
            FailureCase(AndroidProviderRowCodecFailure.PHOTO_CAPTURE_FAILED) {
                codec(photoCapture = AndroidDurablePhotoCapture { _, _, _, _ -> "" }).decode(
                    ACCOUNT,
                    CONTACT_ID,
                    RAW_ID,
                    listOf(name, row(2, PHOTO, "photo", binary = byteArrayOf(1))),
                )
            },
        )
        cases.forEach { case -> assertFailure(case.category, "secret-canary", case.block) }
    }

    @Test
    fun tamperedSyncIdentityClaimsDivergeFromDurableBindingAndFailClosed() {
        val resolver = InMemoryStrictIdentityResolver(
            rejectUnknownClaims = true,
            knownCanonicalIds = setOf("name", "phonetic-a"),
        )
        val original = row(
            1,
            NAME,
            "name",
            slots(DATA1 to "Name"),
            linked = linked(AndroidLinkedValueRole.PHONETIC_NAME to "phonetic-a"),
        )
        codec(identityResolver = resolver).decode(ACCOUNT, CONTACT_ID, RAW_ID, listOf(original))

        assertFailure(AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE, "tampered-sync1") {
            codec(identityResolver = resolver).decode(
                ACCOUNT,
                CONTACT_ID,
                RAW_ID,
                listOf(original.copy(canonicalValueId = "tampered-sync1")),
            )
        }
        assertFailure(AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE, "tampered-linked") {
            codec(identityResolver = resolver).decode(
                ACCOUNT,
                CONTACT_ID,
                RAW_ID,
                listOf(original.copy(
                    linkedValueIdsEncoding = linked(
                        AndroidLinkedValueRole.PHONETIC_NAME to "tampered-linked",
                    ),
                )),
            )
        }
    }

    @Test
    fun completePurePreflightPreventsAllocationAndPhotoCaptureForLaterInvalidRows() {
        fun assertNoEffects(
            expected: AndroidProviderRowCodecFailure,
            rows: List<AndroidOwnedDataRow>,
        ) {
            var resolutions = 0
            var captures = 0
            val resolver = AndroidProviderIdentityResolver { claims ->
                resolutions += claims.size
                AndroidProviderIdentityResolution.Bound(claims.associate { claim ->
                    claim.providerRowId to AndroidDurableValueBinding(
                        claim.claimedCanonicalValueId?.takeIf { it.isNotBlank() }
                            ?: "allocated-${claim.kind.name}",
                        claim.claimedLinkedCanonicalValueIds,
                    )
                })
            }
            val capture = AndroidDurablePhotoCapture { _, _, _, _ ->
                captures += 1
                "sha256:captured"
            }
            assertFailure(expected, "invalid-later") {
                codec(resolver, capture).decode(ACCOUNT, CONTACT_ID, RAW_ID, rows)
            }
            assertEquals(0, resolutions)
            assertEquals(0, captures)
        }

        val missingNameIdentity = row(1, NAME, null, slots(DATA1 to "Name"))
        val validPhoto = row(2, PHOTO, "photo", binary = byteArrayOf(1, 2, 3))
        assertNoEffects(
            AndroidProviderRowCodecFailure.MALFORMED_DATE,
            listOf(
                missingNameIdentity,
                validPhoto,
                row(3, EVENT, "invalid-later", slots(DATA1 to "--02-30", DATA2 to EVENT_BIRTHDAY)),
            ),
        )
        assertNoEffects(
            AndroidProviderRowCodecFailure.DUPLICATE_SINGLETON,
            listOf(missingNameIdentity, validPhoto, row(3, PHOTO, "invalid-later", binary = byteArrayOf(4))),
        )
        assertNoEffects(
            AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY,
            listOf(
                missingNameIdentity,
                validPhoto.copy(canonicalValueId = "duplicate"),
                row(3, EMAIL, "duplicate", slots(DATA1 to "invalid-later")),
            ),
        )
    }

    @Test
    fun customTypesWithBlankLabelsDegradeToOtherWithoutDroppingValues() {
        val snapshot = codec().decode(
            ACCOUNT,
            CONTACT_ID,
            RAW_ID,
            listOf(
                row(1, NAME, "name", slots(DATA1 to "Name")),
                row(2, PHONE, "phone", slots(DATA1 to "+33123", DATA2 to PHONE_CUSTOM, DATA3 to " ")),
                row(3, EVENT, "date", slots(DATA1 to "--12-31", DATA2 to EVENT_CUSTOM, DATA3 to null)),
            ),
        )

        assertEquals(AndroidSemanticType.OTHER, snapshot.row(AndroidRowKind.PHONE).semanticType)
        assertEquals("+33123", snapshot.row(AndroidRowKind.PHONE).value)
        assertEquals(AndroidSemanticType.OTHER, snapshot.row(AndroidRowKind.CUSTOM_DATE).semanticType)
        assertEquals("--12-31", snapshot.row(AndroidRowKind.CUSTOM_DATE).value)
    }

    @Test
    fun projectionVerificationMayRetainDurablePhotoReferenceWhenInlineThumbnailIsMissing() {
        var captures = 0
        val reference = "private://photo/already-committed"
        val snapshot = AndroidProviderRowCodec(
            identityResolver = InMemoryStrictIdentityResolver(),
            photoCapture = AndroidDurablePhotoCapture { _, _, _, _ ->
                captures += 1
                "private://photo/unexpected-capture"
            },
            missingPhotoReference = { reference },
        ).decode(
            ACCOUNT,
            CONTACT_ID,
            RAW_ID,
            listOf(row(1, PHOTO, "photo", binary = null)),
        )

        assertEquals(reference, snapshot.rows.single().binaryReference)
        assertEquals(0, captures)
    }

    @Test
    fun scalarAndImmutableReferenceBoundsAreMeasuredAsUtf8Bytes() {
        val oversizedEmojiText = "😀".repeat(5_000)
        assertFailure(AndroidProviderRowCodecFailure.BOUND_EXCEEDED, "not-present") {
            codec().decode(
                ACCOUNT,
                CONTACT_ID,
                RAW_ID,
                listOf(row(1, NAME, "name", slots(DATA1 to oversizedEmojiText))),
            )
        }

        assertFailure(AndroidProviderRowCodecFailure.PHOTO_CAPTURE_FAILED, "not-present") {
            codec(
                photoCapture = AndroidDurablePhotoCapture { _, _, _, _ ->
                    // One four-byte UTF-8 code point beyond the 15 MiB immutable-reference bound.
                    "😀".repeat(3_932_161)
                },
            ).decode(
                ACCOUNT,
                CONTACT_ID,
                RAW_ID,
                listOf(
                    row(1, NAME, "name", slots(DATA1 to "Name")),
                    row(2, PHOTO, "photo", binary = byteArrayOf(1)),
                ),
            )
        }
    }

    private fun codec(
        identityResolver: AndroidProviderIdentityResolver = InMemoryStrictIdentityResolver(),
        photoCapture: AndroidDurablePhotoCapture = AndroidDurablePhotoCapture { _, _, _, bytes ->
            "sha256:test-photo-${bytes.contentHashCode()}"
        },
    ) = AndroidProviderRowCodec(identityResolver, photoCapture)

    private fun row(
        id: Long,
        mimeType: String,
        canonicalId: String?,
        slots: List<String?> = slots(),
        order: Int? = id.toInt(),
        linked: String? = null,
        binary: ByteArray? = null,
        rawContactId: Long = RAW_ID,
    ) = AndroidOwnedDataRow(
        dataRowId = id,
        rawContactId = rawContactId,
        mimeType = mimeType,
        canonicalValueId = canonicalId,
        canonicalOrder = order,
        linkedValueIdsEncoding = linked,
        isPrimary = false,
        isSuperPrimary = false,
        stringSlots = slots,
        binarySlot = binary,
    )

    private fun slots(vararg values: Pair<Int, Any?>): List<String?> = MutableList<String?>(14) { null }.apply {
        values.forEach { (index, value) -> this[index] = value?.toString() }
    }

    private fun linked(vararg values: Pair<AndroidLinkedValueRole, String>): String = values.joinToString(",") {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(it.second.toByteArray(Charsets.UTF_8))
        "${it.first.name}:${payload.length}:$payload"
    }

    private fun assertFailure(
        expected: AndroidProviderRowCodecFailure,
        secret: String,
        block: () -> Unit,
    ) {
        val failure = runCatching(block).exceptionOrNull() as AndroidProviderRowCodecException
        assertEquals(expected, failure.category)
        assertFalse(failure.message.orEmpty().contains(secret))
        assertFalse(failure.message.orEmpty().contains(CONTACT_ID))
        assertFalse(failure.message.orEmpty().contains(RAW_ID.toString()))
    }

    private fun com.patmanak.contako.data.android.mapping.AndroidContactSnapshot.row(kind: AndroidRowKind) =
        rows.single { it.kind == kind }

    private data class TypeCase(val mimeType: String, val providerType: Int, val semantic: AndroidSemanticType)
    private data class FailureCase(val category: AndroidProviderRowCodecFailure, val block: () -> Unit)

    private class InMemoryStrictIdentityResolver(
        private val rejectUnknownClaims: Boolean = false,
        knownCanonicalIds: Set<String> = emptySet(),
    ) : AndroidProviderIdentityResolver {
        private data class Locator(
            val accountName: String,
            val contactId: String,
            val rawContactId: Long,
            val providerRowId: Long,
        )

        private data class Stored(
            val kind: AndroidRowKind,
            val binding: AndroidDurableValueBinding,
        )

        private val known = knownCanonicalIds.toMutableSet()
        private val bindings = linkedMapOf<Locator, Stored>()
        var calls: Int = 0
            private set

        override fun resolve(claims: List<AndroidProviderIdentityClaim>): AndroidProviderIdentityResolution {
            calls += claims.size
            val stagedBindings = LinkedHashMap(bindings)
            val stagedKnown = known.toMutableSet()
            val result = linkedMapOf<Long, AndroidDurableValueBinding>()
            claims.forEach { claim ->
                val locator = Locator(
                    claim.accountName.value,
                    claim.canonicalContactId,
                    claim.rawContactId,
                    claim.providerRowId,
                )
                val claimed = claim.claimedCanonicalValueId?.takeIf { it.isNotBlank() }
                val existing = stagedBindings[locator]
                val binding = if (existing != null) {
                    if (existing.kind != claim.kind ||
                        claimed != null && existing.binding.canonicalValueId != claimed ||
                        existing.binding.linkedCanonicalValueIds != claim.claimedLinkedCanonicalValueIds
                    ) {
                        return AndroidProviderIdentityResolution.Divergence
                    }
                    existing.binding
                } else {
                    if (rejectUnknownClaims &&
                        (claimed != null && claimed !in stagedKnown ||
                            claim.claimedLinkedCanonicalValueIds.values.any { it !in stagedKnown })
                    ) {
                        return AndroidProviderIdentityResolution.Divergence
                    }
                    val canonicalId = claimed ?: "allocated-${claim.kind.name.lowercase()}-${('a' + stagedBindings.size)}"
                    val created = AndroidDurableValueBinding(canonicalId, claim.claimedLinkedCanonicalValueIds)
                    stagedKnown += canonicalId
                    stagedKnown += claim.claimedLinkedCanonicalValueIds.values
                    stagedBindings[locator] = Stored(claim.kind, created)
                    created
                }
                result[claim.providerRowId] = binding
            }
            bindings.clear()
            bindings.putAll(stagedBindings)
            known.clear()
            known.addAll(stagedKnown)
            return AndroidProviderIdentityResolution.Bound(result)
        }
    }

    private companion object {
        val ACCOUNT = AndroidProviderAccountName("android-account@example.test")
        const val CONTACT_ID = "canonical-contact"
        const val RAW_ID = 42L
        const val NAME = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        const val EMAIL = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        const val PHONE = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        const val POSTAL = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE
        const val ORGANIZATION = ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
        const val PHOTO = ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        const val NICKNAME = ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
        const val NOTE = ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
        const val WEBSITE = ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE
        const val EVENT = ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
        const val RELATION = ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE
        const val GROUP_MEMBERSHIP = ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        const val EMAIL_CUSTOM = ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM
        const val PHONE_CUSTOM = ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
        const val PHONE_WORK_MOBILE = ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE
        const val POSTAL_HOME = ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
        const val ORGANIZATION_WORK = ContactsContract.CommonDataKinds.Organization.TYPE_WORK
        const val WEBSITE_BLOG = ContactsContract.CommonDataKinds.Website.TYPE_BLOG
        const val EVENT_BIRTHDAY = ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
        const val EVENT_ANNIVERSARY = ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY
        const val EVENT_CUSTOM = ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM
        const val RELATION_FATHER = ContactsContract.CommonDataKinds.Relation.TYPE_FATHER
        const val DATA1 = 0
        const val DATA2 = 1
        const val DATA3 = 2
        const val DATA4 = 3
        const val DATA5 = 4
        const val DATA6 = 5
        const val DATA7 = 6
        const val DATA8 = 7
        const val DATA9 = 8
        const val DATA10 = 9

        val EMAIL_TYPES = mapOf(
            ContactsContract.CommonDataKinds.Email.TYPE_HOME to AndroidSemanticType.HOME,
            ContactsContract.CommonDataKinds.Email.TYPE_WORK to AndroidSemanticType.WORK,
            ContactsContract.CommonDataKinds.Email.TYPE_OTHER to AndroidSemanticType.OTHER,
            ContactsContract.CommonDataKinds.Email.TYPE_MOBILE to AndroidSemanticType.MOBILE,
            ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM to AndroidSemanticType.CUSTOM,
        )
        val PHONE_TYPES = mapOf(
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME to AndroidSemanticType.HOME,
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE to AndroidSemanticType.MOBILE,
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK to AndroidSemanticType.WORK,
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK to AndroidSemanticType.FAX_WORK,
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME to AndroidSemanticType.FAX_HOME,
            ContactsContract.CommonDataKinds.Phone.TYPE_PAGER to AndroidSemanticType.PAGER,
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER to AndroidSemanticType.OTHER,
            ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK to AndroidSemanticType.CALLBACK,
            ContactsContract.CommonDataKinds.Phone.TYPE_CAR to AndroidSemanticType.CAR,
            ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN to AndroidSemanticType.COMPANY_MAIN,
            ContactsContract.CommonDataKinds.Phone.TYPE_ISDN to AndroidSemanticType.ISDN,
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN to AndroidSemanticType.MAIN,
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX to AndroidSemanticType.OTHER_FAX,
            ContactsContract.CommonDataKinds.Phone.TYPE_RADIO to AndroidSemanticType.RADIO,
            ContactsContract.CommonDataKinds.Phone.TYPE_TELEX to AndroidSemanticType.TELEX,
            ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD to AndroidSemanticType.TTY_TDD,
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE to AndroidSemanticType.WORK_MOBILE,
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER to AndroidSemanticType.WORK_PAGER,
            ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT to AndroidSemanticType.ASSISTANT,
            ContactsContract.CommonDataKinds.Phone.TYPE_MMS to AndroidSemanticType.MMS,
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM to AndroidSemanticType.CUSTOM,
        )
        val POSTAL_TYPES = mapOf(
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME to AndroidSemanticType.HOME,
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK to AndroidSemanticType.WORK,
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER to AndroidSemanticType.OTHER,
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM to AndroidSemanticType.CUSTOM,
        )
        val WEBSITE_TYPES = mapOf(
            ContactsContract.CommonDataKinds.Website.TYPE_HOME to AndroidSemanticType.HOME,
            ContactsContract.CommonDataKinds.Website.TYPE_WORK to AndroidSemanticType.WORK,
            ContactsContract.CommonDataKinds.Website.TYPE_OTHER to AndroidSemanticType.OTHER,
            ContactsContract.CommonDataKinds.Website.TYPE_BLOG to AndroidSemanticType.BLOG,
            ContactsContract.CommonDataKinds.Website.TYPE_PROFILE to AndroidSemanticType.PROFILE,
            ContactsContract.CommonDataKinds.Website.TYPE_FTP to AndroidSemanticType.FTP,
            ContactsContract.CommonDataKinds.Website.TYPE_CUSTOM to AndroidSemanticType.CUSTOM,
        )
        val ORGANIZATION_TYPES = mapOf(
            ContactsContract.CommonDataKinds.Organization.TYPE_WORK to AndroidSemanticType.WORK,
            ContactsContract.CommonDataKinds.Organization.TYPE_OTHER to AndroidSemanticType.OTHER,
            ContactsContract.CommonDataKinds.Organization.TYPE_CUSTOM to AndroidSemanticType.CUSTOM,
        )
        val RELATION_TYPES = mapOf(
            ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT to AndroidSemanticType.ASSISTANT,
            ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER to AndroidSemanticType.BROTHER,
            ContactsContract.CommonDataKinds.Relation.TYPE_CHILD to AndroidSemanticType.CHILD,
            ContactsContract.CommonDataKinds.Relation.TYPE_DOMESTIC_PARTNER to AndroidSemanticType.DOMESTIC_PARTNER,
            ContactsContract.CommonDataKinds.Relation.TYPE_FATHER to AndroidSemanticType.FATHER,
            ContactsContract.CommonDataKinds.Relation.TYPE_FRIEND to AndroidSemanticType.FRIEND,
            ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER to AndroidSemanticType.MANAGER,
            ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER to AndroidSemanticType.MOTHER,
            ContactsContract.CommonDataKinds.Relation.TYPE_PARENT to AndroidSemanticType.PARENT,
            ContactsContract.CommonDataKinds.Relation.TYPE_PARTNER to AndroidSemanticType.PARTNER,
            ContactsContract.CommonDataKinds.Relation.TYPE_REFERRED_BY to AndroidSemanticType.REFERRED_BY,
            ContactsContract.CommonDataKinds.Relation.TYPE_RELATIVE to AndroidSemanticType.RELATIVE,
            ContactsContract.CommonDataKinds.Relation.TYPE_SISTER to AndroidSemanticType.SISTER,
            ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE to AndroidSemanticType.SPOUSE,
            ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM to AndroidSemanticType.CUSTOM,
        )
    }
}
