package com.patmanak.contako.data.proton

import com.patmanak.contako.diagnostics.DiagnosticCardType
import com.patmanak.contako.diagnostics.SanitizedDiagnosticEvent
import com.patmanak.contako.diagnostics.SanitizedDiagnosticLog

import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.policy.PostalAddressPolicy
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import java.util.Locale
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCard
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.signContactCard
import me.proton.core.crypto.common.pgp.exception.CryptoException
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.encryptText
import me.proton.core.key.domain.signText
import me.proton.core.key.domain.entity.key.PrivateKeyRing
import me.proton.core.key.domain.entity.key.PublicKeyRing
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext
import me.proton.core.key.domain.publicKey
import me.proton.core.key.domain.verifyText
import me.proton.core.key.domain.verifyData
import me.proton.core.user.domain.UserManager

/** Proton contact-email identifier retained on the canonical email value. */
internal const val PROTON_EMAIL_ID_KEY = "protonEmailId"

/** Closed parser rejection only: never retain the rejected line, value or exception cause. */
internal class ProtonVCardParseFailure(val category: GatewayContactHydrationCategory) : IllegalArgumentException()

private fun requireVCardParse(condition: Boolean, category: GatewayContactHydrationCategory) {
    if (!condition) throw ProtonVCardParseFailure(category)
}

/**
 * Comma-separated Proton label identifiers for one email, retained on the canonical email value.
 *
 * Proton assigns contact groups per email address (`D-007`), and this is the only place that
 * relation survives decoding, so canonical membership reconciliation reads it back from here.
 */
internal const val PROTON_GROUP_IDS_KEY = "protonGroupIds"

internal data class ProtonPreparedVCard(
    val encryptedPrivate: String,
    val signed: String,
    val clear: String,
) {
    override fun toString(): String = "ProtonPreparedVCard(REDACTED)"
}

/** A fresh context is transferred to the caller and always closed after one operation. */
internal fun interface ProtonKeyHolderContextProvider {
    suspend fun acquire(userId: UserId): KeyHolderContext?
}

/**
 * Creates an operation-scoped key context from Proton Core's already-unlocked local user state.
 * `refresh = false` is deliberate: acquiring contact keys MUST NOT introduce a hidden network call.
 */
internal class ProtonCoreUnlockedKeyHolderContextProvider(
    private val userManager: UserManager,
    private val cryptoContext: CryptoContext,
) : ProtonKeyHolderContextProvider {
    override suspend fun acquire(userId: UserId): KeyHolderContext? {
        val user = userManager.getUser(userId, refresh = false)
        val addresses = userManager.getAddresses(userId, refresh = false)
        val allKeys = buildList {
            addAll(user.keys.map { it.privateKey })
            addresses.forEach { address -> addAll(address.keys.map { it.privateKey }) }
        }.distinctBy { it.key }
        val privateKeys = allKeys.filter { it.isActive && it.passphrase != null }
        if (privateKeys.none { it.isPrimary }) return null

        val privateKeyRing = PrivateKeyRing(cryptoContext, privateKeys)
        return try {
            KeyHolderContext(
                context = cryptoContext,
                privateKeyRing = privateKeyRing,
                // Verification needs the public portion even when a private key is locked.
                // Core still enforces each public key's isActive/canVerify flags.
                publicKeyRing = PublicKeyRing(allKeys.map { it.publicKey(cryptoContext) }),
            )
        } catch (error: Throwable) {
            privateKeyRing.close()
            throw error
        }
    }
}

internal interface ProtonContactCardCrypto {
    suspend fun decryptAndVerify(userId: UserId, cards: List<ContactCard>): List<ProtonPlainContactCard>
    suspend fun protect(userId: UserId, vCard: ProtonPreparedVCard): List<ContactCard>
}

internal data class ProtonPlainContactCard(
    val type: ContactCardType,
    val vCard: String,
) {
    init {
        require(vCard.toByteArray(Charsets.UTF_8).size <= MAX_PLAIN_CARD_BYTES)
    }

    override fun toString(): String = "ProtonPlainContactCard(type=$type, REDACTED)"

    private companion object {
        const val MAX_PLAIN_CARD_BYTES = 10 * 1_024 * 1_024
    }
}

/** Uses maintained Proton key/contact primitives; no PGP protocol is implemented here. */
internal class ProtonCoreContactCardCrypto(
    private val keyContextProvider: ProtonKeyHolderContextProvider,
) : ProtonContactCardCrypto {
    override suspend fun decryptAndVerify(userId: UserId, cards: List<ContactCard>): List<ProtonPlainContactCard> {
        try {
            requireBoundedCards(cards)
        } catch (_: ProtonContactVerificationFailure) {
            throw ProtonHydrationVerificationFailure(
                GatewayContactHydrationCategory.AUTHENTICITY_REQUIREMENT,
            )
        } catch (_: ProtonMalformedContactResponse) {
            throw ProtonHydrationMalformedResponse(
                GatewayContactHydrationCategory.WIRE_CARD_VALIDATION,
            )
        } catch (_: ArithmeticException) {
            throw ProtonHydrationMalformedResponse(
                GatewayContactHydrationCategory.WIRE_CARD_VALIDATION,
            )
        }
        val context = keyContextProvider.acquire(userId) ?: throw ProtonAuthenticationRequired()
        return context.use { keyHolder ->
            var aggregateBytes = 0L
            cards.map { card ->
                val plain = when (card) {
                    is ContactCard.ClearText -> card.data
                    is ContactCard.Signed -> {
                        val verified = try {
                            keyHolder.verifyText(card.data, card.signature)
                        } catch (_: CryptoException) {
                            throw ProtonHydrationVerificationFailure(
                                GatewayContactHydrationCategory.SIGNATURE_VERIFICATION,
                            )
                        }
                        if (!verified) {
                            reportSignatureFailure(keyHolder, DiagnosticCardType.SIGNED, card.data, card.signature)
                            throw ProtonHydrationVerificationFailure(GatewayContactHydrationCategory.SIGNATURE_VERIFICATION)
                        }
                        card.data
                    }
                    is ContactCard.Encrypted -> {
                        // Core keeps wire type 1 unsigned; type 3 requires a signature in its mapper.
                        val signature = card.signature
                        val decrypted = try {
                            decryptBoundedContactText(keyHolder, card.data,
                                (MAX_PLAIN_CONTACT_BYTES - aggregateBytes).toInt())
                        } catch (_: ProtonPlaintextBoundsExceeded) {
                            throw ProtonHydrationMalformedResponse(
                                GatewayContactHydrationCategory.PLAINTEXT_BOUNDS,
                            )
                        } catch (_: CryptoException) {
                            throw ProtonHydrationVerificationFailure(
                                GatewayContactHydrationCategory.DECRYPT_OPERATION,
                            )
                        }
                        val verified = signature == null || try {
                            // Imported encrypted cards can sign the exact UTF-8 content. The
                            // text verifier trims trailing whitespace and can reject that valid
                            // signature. Both paths retain Core's key and time verification.
                            keyHolder.verifyData(decrypted.toByteArray(Charsets.UTF_8), signature) ||
                                keyHolder.verifyText(decrypted, signature)
                        } catch (_: CryptoException) {
                            throw ProtonHydrationVerificationFailure(
                                GatewayContactHydrationCategory.SIGNATURE_VERIFICATION,
                            )
                        }
                        if (!verified) {
                            reportSignatureFailure(keyHolder, DiagnosticCardType.ENCRYPTED_AND_SIGNED, decrypted, signature.orEmpty())
                            throw ProtonHydrationVerificationFailure(GatewayContactHydrationCategory.SIGNATURE_VERIFICATION)
                        }
                        decrypted
                    }
                }
                val validatedPlaintext = try {
                    validateSingleCompleteHydratedVCard(plain)
                } catch (_: ProtonPlaintextVCardStructureFailure) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_VCARD_STRUCTURE,
                    )
                } catch (_: ProtonPlaintextVCardVersionFailure) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_VCARD_VERSION,
                    )
                } catch (_: ProtonPlaintextVCardParserFailure) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_VCARD_PARSER,
                    )
                } catch (_: ProtonPlaintextVCardSerializationFailure) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_VCARD_SERIALIZATION,
                    )
                } catch (_: ProtonPlaintextVCardBoundsFailure) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_BOUNDS,
                    )
                } catch (_: ProtonMalformedContactResponse) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_VCARD_NORMALIZATION,
                    )
                } catch (_: IllegalArgumentException) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_VCARD_NORMALIZATION,
                    )
                } catch (_: RuntimeException) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_VCARD_SERIALIZATION,
                    )
                }
                val bytes = validatedPlaintext.toByteArray(Charsets.UTF_8).size
                aggregateBytes = try {
                    Math.addExact(aggregateBytes, bytes.toLong())
                } catch (_: ArithmeticException) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_BOUNDS,
                    )
                }
                if (aggregateBytes > MAX_PLAIN_CONTACT_BYTES) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_BOUNDS,
                    )
                }
                try {
                    ProtonPlainContactCard(card.cardType(), validatedPlaintext)
                } catch (_: IllegalArgumentException) {
                    throw ProtonHydrationMalformedResponse(
                        GatewayContactHydrationCategory.PLAINTEXT_BOUNDS,
                    )
                }
            }
        }
    }

    override suspend fun protect(userId: UserId, vCard: ProtonPreparedVCard): List<ContactCard> {
        val context = keyContextProvider.acquire(userId) ?: throw ProtonAuthenticationRequired()
        return context.use { keyHolder ->
            val private = parseSingle(vCard.encryptedPrivate)
            val signed = parseSingle(vCard.signed)
            buildList {
                // Web verifies encrypted cards without stripping trailing spaces. A folded
                // rich value can end a physical line with a significant space; Core's
                // contact helper signs with trimming enabled and changes that content.
                val privateText = private.write()
                add(ContactCard.Encrypted(
                    keyHolder.encryptText(privateText),
                    keyHolder.signText(privateText, trimTrailingSpaces = false),
                ))
                add(keyHolder.signContactCard(signed))
                if (hasContactCardPayloadProperties(vCard.clear)) {
                    add(ContactCard.ClearText(parseSingle(vCard.clear).write()))
                }
            }
        }
    }

    private fun parseSingle(value: String): VCard = parseSingleCompleteVCard(value)

    private fun reportSignatureFailure(holder: KeyHolderContext, type: DiagnosticCardType, data: String, signature: String) {
        if (com.patmanak.contako.BuildConfig.SANITIZED_DIAGNOSTICS) {
            // Diagnostic comparisons only; none of these results permit canonical import.
            val withoutTime = runCatching { holder.verifyText(data, signature,
                time = me.proton.core.crypto.common.pgp.VerificationTime.Ignore) }.getOrDefault(false)
            val exactBytes = runCatching { holder.verifyData(data.toByteArray(Charsets.UTF_8), signature) }.getOrDefault(false)
            SanitizedDiagnosticLog.write(SanitizedDiagnosticEvent.SignatureCheck(
                type, signature.isBlank(), holder.publicKeyRing.keys.size,
                holder.publicKeyRing.keys.count { it.isActive && it.canVerify }, withoutTime, exactBytes,
            ))
        }
    }

    private fun requireBoundedWireValue(value: String) {
        if (value.toByteArray(Charsets.UTF_8).size > MAX_WIRE_CARD_BYTES) {
            throw ProtonMalformedContactResponse()
        }
    }

    private fun requireBoundedSignature(value: String) {
        if (value.toByteArray(Charsets.UTF_8).size > MAX_SIGNATURE_BYTES) {
            throw ProtonMalformedContactResponse()
        }
    }

    private fun requireBoundedCards(cards: List<ContactCard>) {
        if (cards.isEmpty() || cards.size > MAX_CARDS_PER_CONTACT) throw ProtonMalformedContactResponse()
        if (cards.distinct().size != cards.size) throw ProtonMalformedContactResponse()
        var aggregateBytes = 0L
        cards.forEach { card ->
            val data = when (card) {
                is ContactCard.ClearText -> card.data
                is ContactCard.Signed -> card.data
                is ContactCard.Encrypted -> card.data
            }
            requireBoundedWireValue(data)
            val signature = when (card) {
                is ContactCard.ClearText -> null
                is ContactCard.Signed -> card.signature
                is ContactCard.Encrypted -> card.signature
            }
            signature?.let(::requireBoundedSignature)
            aggregateBytes = Math.addExact(aggregateBytes, data.toByteArray(Charsets.UTF_8).size.toLong())
            aggregateBytes = Math.addExact(
                aggregateBytes,
                signature?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L,
            )
            if (aggregateBytes > MAX_WIRE_CONTACT_BYTES) throw ProtonMalformedContactResponse()
        }
    }

    private fun ContactCard.cardType(): ContactCardType = when (this) {
        is ContactCard.ClearText -> ContactCardType.ClearText
        is ContactCard.Signed -> ContactCardType.Signed
        is ContactCard.Encrypted -> if (signature == null) {
            ContactCardType.Encrypted
        } else {
            ContactCardType.EncryptedAndSigned
        }
    }

    private companion object {
        const val MAX_CARDS_PER_CONTACT = 16
        const val MAX_WIRE_CARD_BYTES = 10 * 1_024 * 1_024
        const val MAX_WIRE_CONTACT_BYTES = 10L * 1_024 * 1_024
        const val MAX_PLAIN_CONTACT_BYTES = 10L * 1_024 * 1_024
        const val MAX_SIGNATURE_BYTES = 1 * 1_024 * 1_024
    }
}

/**
 * Bounds clear text before ez-vcard or the canonical mapper can observe it and accepts exactly one
 * complete vCard 4.0 document. The configurable bound exists for deterministic boundary tests;
 * production callers always use the fixed 10 MiB ceiling.
 */
internal fun parseSingleCompleteVCard(
    value: String,
    maxPlainCardBytes: Int = MAX_PLAIN_CARD_BYTES,
): VCard {
    require(maxPlainCardBytes > 0)
    validatePlaintextVCardEnvelope(value, maxPlainCardBytes)
    val parsed = try {
        Ezvcard.parse(value).all().singleOrNull()
    } catch (_: RuntimeException) {
        null
    } ?: throw ProtonPlaintextVCardParserFailure()
    if (parsed.version != VCardVersion.V4_0) throw ProtonPlaintextVCardVersionFailure()
    // Parsing may normalize line endings or add serializer metadata. Keep that normalized result
    // under the production ceiling without changing the caller-provided input-boundary oracle.
    val normalized = try {
        parsed.write()
    } catch (_: RuntimeException) {
        throw ProtonPlaintextVCardSerializationFailure()
    }

    try {
        requireBoundedPlainValue(normalized, MAX_PLAIN_CARD_BYTES)
    } catch (_: ProtonMalformedContactResponse) {
        throw ProtonPlaintextVCardBoundsFailure()
    }
    return parsed
}

/**
 * Validates the untrusted decrypted envelope without rewriting Proton extensions. The bounded
 * canonical codec remains the authoritative parser and preservation boundary for hydrated cards.
 */
internal fun validateSingleCompleteHydratedVCard(
    value: String,
    maxPlainCardBytes: Int = MAX_PLAIN_CARD_BYTES,
): String {
    require(maxPlainCardBytes > 0)
    validatePlaintextVCardEnvelope(value, maxPlainCardBytes)
    return value
}

private fun validatePlaintextVCardEnvelope(value: String, maxPlainCardBytes: Int) {
    try {
        requireBoundedPlainValue(value, maxPlainCardBytes)
    } catch (_: ProtonMalformedContactResponse) {
        throw ProtonPlaintextVCardBoundsFailure()
    }
    val structuralLines = value.replace("\r\n", "\n").replace('\r', '\n')
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
    if (
        structuralLines.firstOrNull()?.equals("BEGIN:VCARD", ignoreCase = true) != true ||
        structuralLines.lastOrNull()?.equals("END:VCARD", ignoreCase = true) != true ||
        structuralLines.count { it.equals("BEGIN:VCARD", ignoreCase = true) } != 1 ||
        structuralLines.count { it.equals("END:VCARD", ignoreCase = true) } != 1
    ) {
        throw ProtonPlaintextVCardStructureFailure()
    }
    if (structuralLines.count { it.equals("VERSION:4.0", ignoreCase = true) } != 1) {
        throw ProtonPlaintextVCardVersionFailure()
    }
}

internal class ProtonPlaintextVCardStructureFailure : ProtonMalformedContactResponse()
internal class ProtonPlaintextVCardVersionFailure : ProtonMalformedContactResponse()
internal class ProtonPlaintextVCardParserFailure : ProtonMalformedContactResponse()
internal class ProtonPlaintextVCardSerializationFailure : ProtonMalformedContactResponse()
internal class ProtonPlaintextVCardBoundsFailure : ProtonMalformedContactResponse()

private fun requireBoundedPlainValue(value: String, maxPlainCardBytes: Int = MAX_PLAIN_CARD_BYTES) {
    if (value.toByteArray(Charsets.UTF_8).size > maxPlainCardBytes) {
        throw ProtonMalformedContactResponse()
    }
}

private const val MAX_PLAIN_CARD_BYTES = 10 * 1_024 * 1_024

/**
 * Bounded canonical vCard mapping. Managed fields are replaced on edit while every unmanaged raw
 * property from the hydrated baseline is retained byte-for-text after line unfolding.
 */
internal class ProtonContactVCardCodec(
    private val fieldValidator: ProtonContactFieldValidator = ProtonContactFieldValidator(),
) {
    fun decode(
        accountId: String,
        remote: Contact,
        plainCards: List<ProtonPlainContactCard>,
    ): CanonicalContact {
        require(accountId.isNotBlank())
        requireVCardParse(plainCards.isNotEmpty() && plainCards.size <= MAX_CARDS,
            GatewayContactHydrationCategory.VCARD_PARSE_BOUNDS)
        val keyedCards = plainCards.mapIndexed { index, plain ->
            val key = "proton-card-${plain.type.value}-$index"
            Triple(key, plain, parse(plain.vCard, key))
        }
        requireVCardParse(plainCards.distinctBy { it.type to it.vCard }.size == plainCards.size,
            GatewayContactHydrationCategory.VCARD_PARSE_DUPLICATE_CARD)
        val parsed = keyedCards.map { it.third }
        parsed.flatMap(ParsedCard::values)
            .filter { it.kind == ContactValueKind.PUBLIC_KEY }
            .forEach { value ->
                if (fieldValidator.editableValueError(value) != null) {
                    throw ProtonHydrationMalformedResponse(GatewayContactHydrationCategory.VCARD_PARSE_PUBLIC_KEY)
                }
            }
        val remoteUids = parsed.map(ParsedCard::uid).filter(String::isNotBlank).distinct()
        if (remoteUids.size > 1) throw ProtonInconsistentContactIdentity()
        val preferred = parsed.firstOrNull { card ->
            card.firstName.isNotBlank() || card.lastName.isNotBlank()
        } ?: parsed.first()
        val values = parsed.flatMap(ParsedCard::values).toMutableList()
        setOf(ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY).forEach { kind ->
            values.withIndex().filter { it.value.kind == kind }.drop(1).forEach { indexed ->
                values[indexed.index] = indexed.value.copy(
                    kind = ContactValueKind.UNKNOWN_VCARD_PROPERTY,
                    label = kind.standardDateProperty(),
                    metadata = indexed.value.metadata + (DUPLICATE_STANDARD_DATE_METADATA to "true"),
                )
            }
        }
        val emailIndexes = mutableMapOf<String, Int>()
        values.forEachIndexed { index, value ->
            if (value.kind == ContactValueKind.EMAIL) {
                emailIndexes.putIfAbsent(value.value.normalizedEmail(), index)
            }
        }
        var nextEmailOrder = values.count { it.kind == ContactValueKind.EMAIL }

        remote.contactEmails.forEach { email ->
            val normalizedEmail = email.email.normalizedEmail()
            val existingIndex = emailIndexes[normalizedEmail]
            if (existingIndex == null) {
                values += ContactValue(
                    id = "remote-email-${email.id.id}",
                    kind = ContactValueKind.EMAIL,
                    value = email.email,
                    label = null,
                    order = nextEmailOrder++,
                    isPrimary = email.order == 0,
                    metadata = mapOf(
                        PROTON_EMAIL_ID_KEY to email.id.id,
                        PROTON_GROUP_IDS_KEY to email.labelIds.joinToString(","),
                    ),
                )
                emailIndexes[normalizedEmail] = values.lastIndex
            } else {
                val current = values[existingIndex]
                values[existingIndex] = current.copy(
                    metadata = current.metadata + mapOf(
                        PROTON_EMAIL_ID_KEY to email.id.id,
                        PROTON_GROUP_IDS_KEY to email.labelIds.joinToString(","),
                    ),
                )
            }
        }

        val normalizedValues = values.groupBy(ContactValue::kind).flatMap { (_, family) ->
            val primaryId = if (family.first().kind == ContactValueKind.EMAIL) {
                CanonicalPrimaryValuePolicy.preferredEmail(
                    CanonicalContact(accountId = accountId, id = remote.id.id, values = family),
                )?.id
            } else {
                CanonicalPrimaryValuePolicy.select(family)?.id
            }
            family.mapIndexed { index, value ->
                value.copy(order = index, isPrimary = value.id == primaryId)
            }
        }
        val displayName = parsed.firstNotNullOfOrNull { it.displayName.takeIf(String::isNotBlank) }
            ?: remote.name
        return CanonicalContact(
            accountId = accountId,
            id = remote.id.id,
            firstName = preferred.firstName,
            lastName = preferred.lastName,
            displayName = displayName,
            values = normalizedValues,
            remoteContactId = remote.id.id,
            remoteVCardUid = remoteUids.singleOrNull(),
            preservationEnvelope = PreservationEnvelope(
                rawProperties = keyedCards.associate { (key, plain, _) -> key to plain.vCard },
                remoteBaseline = null,
            ),
        )
    }

    fun encode(contact: CanonicalContact): ProtonPreparedVCard {
        require(contact.accountId.isNotBlank())
        require(contact.values.size <= MAX_VALUES)
        val normalizedContact = normalizePreferenceEdits(contact)
        val preservationKeys = normalizedContact.values.mapNotNull(ContactValue::preservationKey)
        require(preservationKeys.distinct().size == preservationKeys.size)
        val preservedCards = normalizedContact.preservationEnvelope?.rawProperties.orEmpty()
            .filterKeys { it.startsWith("proton-card-") }
        val sourceCards = normalizedContact.preservationEnvelope?.valueSourceCards().orEmpty()
        val unchangedImportedKeys = contactEncodingStep(ProtonContactEncodingStage.SOURCE_BASELINE) {
            unchangedImportedPreservationKeys(normalizedContact, sourceCards)
        }
        contactEncodingStep(ProtonContactEncodingStage.FIELDS) {
            fieldValidator.validate(normalizedContact, unchangedImportedKeys)
        }
        val legacyBaseline = normalizedContact.preservationEnvelope?.remoteBaseline.orEmpty()
        if (preservedCards.isEmpty() && legacyBaseline.isBlank() &&
            normalizedContact.values.any { it.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY }
        ) {
            throw IllegalArgumentException()
        }
        val uid = normalizedContact.remoteVCardUid?.takeIf(String::isNotBlank)
            ?: normalizedContact.id.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException()
        fun retainedUnmanaged(types: Set<Int>) = mergePreservedPropertyOccurrences(
            unmanagedFor(preservedCards, types), unmanagedFor(sourceCards, types),
        )
        val encryptedUnmanaged = retainedUnmanaged(setOf(1, 3)) +
            unmanaged(legacyBaseline) + preservedDuplicateStandardDates(normalizedContact, sourceCards)
        val signedUnmanaged = retainedUnmanaged(setOf(2))
        val clearUnmanaged = retainedUnmanaged(setOf(0))
        return ProtonPreparedVCard(
            encryptedPrivate = contactEncodingStep(ProtonContactEncodingStage.PRIVATE_CARD) {
                render(buildPrivateProperties(normalizedContact, uid) + encryptedUnmanaged)
            },
            signed = contactEncodingStep(ProtonContactEncodingStage.SIGNED_CARD) {
                render(buildSignedProperties(normalizedContact, uid) + signedUnmanaged)
            },
            clear = contactEncodingStep(ProtonContactEncodingStage.CLEAR_CARD) {
                render(buildClearProperties(normalizedContact) + clearUnmanaged)
            },
        ).also { prepared -> contactEncodingStep(ProtonContactEncodingStage.SERIALIZED_BOUNDS) {
            fieldValidator.validateSerialized(prepared)
        } }
    }

    private fun normalizePreferenceEdits(contact: CanonicalContact): CanonicalContact {
        val normalized = contact.values.groupBy(ContactValue::kind).flatMap { (kind, family) ->
            val stable = family.sortedWith(compareBy(ContactValue::order).thenBy(ContactValue::id))
            if (kind !in PROTON_PREFERENCE_KINDS) return@flatMap stable
            val primaryId = if (kind == ContactValueKind.EMAIL) {
                CanonicalPrimaryValuePolicy.preferredEmail(contact.copy(values = stable))?.id
            } else {
                CanonicalPrimaryValuePolicy.select(stable)?.id
            }
            val hasExplicitPreference = stable.any { it.vCardPreference() != null }
            stable.map { value ->
                value.copy(isPrimary = hasExplicitPreference && value.id == primaryId)
            }
        }
        return contact.copy(values = normalized)
    }

    /** Imported out-of-limit values remain read-only only while their hydrated payload is exact. */
    private fun unchangedImportedPreservationKeys(
        contact: CanonicalContact,
        preservedCards: Map<String, String>,
    ): Set<String> {
        val baselines = preservedCards.flatMap { (cardKey, rawCard) ->
            parse(rawCard, cardKey).values.mapNotNull { value ->
                value.preservationKey?.let { key -> key to value }
            }
        }.toMap()
        return contact.values.mapNotNull { current ->
            val key = current.preservationKey ?: return@mapNotNull null
            val baseline = baselines[key] ?: return@mapNotNull null
            key.takeIf {
                current.id == baseline.id &&
                    current.kind == baseline.kind &&
                    current.value == baseline.value &&
                    current.label == baseline.label &&
                    current.components == baseline.components &&
                    current.metadata == baseline.metadata
            }
        }.toSet()
    }

    private fun parse(raw: String, preservationNamespace: String): ParsedCard {
        requireVCardParse(raw.toByteArray(Charsets.UTF_8).size <= MAX_CARD_BYTES,
            GatewayContactHydrationCategory.VCARD_PARSE_BOUNDS)
        requireVCardParse(raw.none { it in BIDI_CONTROL_CHARACTERS || it == '\uFFFD' },
            GatewayContactHydrationCategory.VCARD_PARSE_CHARACTERS)
        val lines = unfold(raw)
        requireVCardParse(lines.size <= MAX_LINES, GatewayContactHydrationCategory.VCARD_PARSE_BOUNDS)
        requireVCardParse(
            lines.count { it.equals("BEGIN:VCARD", ignoreCase = true) } == 1 &&
                lines.count { it.equals("END:VCARD", ignoreCase = true) } == 1 &&
                lines.count { it.equals("VERSION:4.0", ignoreCase = true) } == 1,
            GatewayContactHydrationCategory.VCARD_PARSE_ENVELOPE,
        )
        val groupedLabelPairs = lines.mapNotNull { line ->
            val content = contentLine(line) ?: return@mapNotNull null
            if (content.property != "X-ABLABEL" || content.group == null) return@mapNotNull null
            content.group.uppercase(Locale.ROOT) to unescape(content.value)
        }
        requireVCardParse(groupedLabelPairs.map(Pair<String, String>::first).distinct().size == groupedLabelPairs.size,
            GatewayContactHydrationCategory.VCARD_PARSE_DUPLICATE_LABEL)
        val groupedLabels = groupedLabelPairs.toMap()
        val managedLabelGroups = lines.mapNotNull { line ->
            val content = contentLine(line) ?: return@mapNotNull null
            content.group?.uppercase(Locale.ROOT)
                ?.takeIf { content.property in CUSTOM_LABEL_PROPERTIES }
        }.toSet()
        var displayName = ""
        var firstName = ""
        var lastName = ""
        var uid = ""
        val values = mutableListOf<ContactValue>()
        val unknown = mutableListOf<String>()
        val nextFamilyOrder = mutableMapOf<ContactValueKind, Int>()

        fun addParsedValue(
            kind: ContactValueKind,
            value: String,
            content: ContentLine,
            label: String? = null,
            components: Map<String, String> = emptyMap(),
            preservationKey: String? = content.preservationKey,
        ) {
            val familyOrder = nextFamilyOrder.getOrDefault(kind, 0)
            nextFamilyOrder[kind] = familyOrder + 1
            addValue(
                target = values,
                kind = kind,
                value = value,
                content = content,
                familyOrder = familyOrder,
                label = label,
                components = components,
                preservationKey = preservationKey,
            )
        }

        lines.forEachIndexed { lineIndex, line ->
            val content = contentLine(line, "$preservationNamespace$PRESERVATION_LINE_SEPARATOR$lineIndex")
                ?: return@forEachIndexed
            val value = unescape(content.value)
            when (content.property) {
                "BEGIN", "END", "VERSION", "PRODID" -> Unit
                "UID" -> uid = value
                "FN" -> displayName = value
                "N" -> {
                    val parts = splitEscaped(content.value, ';').map(::unescape)
                    lastName = parts.getOrElse(0) { "" }
                    firstName = parts.getOrElse(1) { "" }
                    addParsedValue(
                        ContactValueKind.STRUCTURED_NAME,
                        value,
                        content,
                        components = mapOf(
                            "family" to lastName,
                            "given" to firstName,
                            "additional" to parts.getOrElse(2) { "" },
                            "prefix" to parts.getOrElse(3) { "" },
                            "suffix" to parts.getOrElse(4) { "" },
                        ),
                    )
                }
                "EMAIL" -> addParsedValue(ContactValueKind.EMAIL, value, content, groupedLabels[content.group?.uppercase(Locale.ROOT)])
                "TEL" -> addParsedValue(ContactValueKind.PHONE, value, content, groupedLabels[content.group?.uppercase(Locale.ROOT)])
                "NICKNAME" -> splitEscaped(content.value, ',').map(::unescape).forEach {
                    addParsedValue(ContactValueKind.NICKNAME, it, content)
                }
                "NOTE" -> addParsedValue(ContactValueKind.NOTE, value, content)
                "URL" -> addParsedValue(ContactValueKind.URL, value, content, groupedLabels[content.group?.uppercase(Locale.ROOT)])
                "ADR" -> {
                    val parts = splitEscaped(content.value, ';').map(::unescape)
                    val components = mapOf(
                        PostalAddressPolicy.PO_BOX to parts.getOrElse(0) { "" },
                        PostalAddressPolicy.EXTENDED to parts.getOrElse(1) { "" },
                        PostalAddressPolicy.STREET to parts.getOrElse(2) { "" },
                        PostalAddressPolicy.LOCALITY to parts.getOrElse(3) { "" },
                        PostalAddressPolicy.REGION to parts.getOrElse(4) { "" },
                        PostalAddressPolicy.POSTAL_CODE to parts.getOrElse(5) { "" },
                        PostalAddressPolicy.COUNTRY to parts.getOrElse(6) { "" },
                    )
                    addParsedValue(
                        ContactValueKind.POSTAL_ADDRESS,
                        PostalAddressPolicy.formattedValue(components),
                        content,
                        groupedLabels[content.group?.uppercase(Locale.ROOT)],
                        components,
                    )
                }
                "ORG" -> {
                    val parts = splitEscaped(content.value, ';').map(::unescape)
                    val structured = buildMap {
                        parts.forEachIndexed { index, component -> put("component_$index", component) }
                        put("company", parts.getOrElse(0) { "" })
                        put("department", parts.getOrElse(1) { "" })
                    }
                    addParsedValue(
                        ContactValueKind.ORGANIZATION,
                        parts.filter(String::isNotBlank).joinToString(" / "),
                        content,
                        components = structured,
                    )
                }
                "TITLE" -> addParsedValue(ContactValueKind.TITLE, value, content)
                "ROLE" -> addParsedValue(ContactValueKind.ROLE, value, content)
                "BDAY" -> addParsedValue(ContactValueKind.BIRTHDAY, value, content)
                "ANNIVERSARY" -> addParsedValue(ContactValueKind.ANNIVERSARY, value, content)
                "LANG" -> addParsedValue(ContactValueKind.LANGUAGE, value, content)
                "TZ" -> addParsedValue(ContactValueKind.TIME_ZONE, value, content)
                "GENDER" -> {
                    val parts = splitEscaped(content.value, ';').map(::unescape)
                    addParsedValue(
                        ContactValueKind.GENDER,
                        value,
                        content,
                        components = mapOf(
                            "sex" to parts.getOrElse(0) { "" },
                            "identity" to parts.getOrElse(1) { "" },
                        ),
                    )
                }
                "MEMBER" -> addParsedValue(ContactValueKind.MEMBER, value, content)
                "RELATED", "X-ABRELATEDNAMES" ->
                    addParsedValue(ContactValueKind.RELATIONSHIP, value, content, groupedLabels[content.group?.uppercase(Locale.ROOT)])
                "X-ABLABEL" -> if (content.group?.uppercase(Locale.ROOT) !in managedLabelGroups) {
                    unknown += line
                    addParsedValue(
                        ContactValueKind.UNKNOWN_VCARD_PROPERTY,
                        value,
                        content,
                        label = content.property,
                        preservationKey = content.preservationKey,
                    )
                }
                "CATEGORIES" -> splitEscaped(content.value, ',').map(::unescape).forEach {
                    addParsedValue(ContactValueKind.CATEGORY, it, content)
                }
                "KEY" -> addParsedValue(ContactValueKind.PUBLIC_KEY, value, content)
                "PHOTO" -> addParsedValue(ContactValueKind.PHOTO, legacyInlineImage(content) ?: value, content)
                "LOGO" -> addParsedValue(ContactValueKind.LOGO, legacyInlineImage(content) ?: value, content)
                else -> {
                    unknown += line
                    addParsedValue(
                        ContactValueKind.UNKNOWN_VCARD_PROPERTY,
                        value,
                        content,
                        label = content.property,
                        preservationKey = content.preservationKey,
                    )
                }
            }
        }
        return ParsedCard(uid, firstName, lastName, displayName, values, unknown)
    }

    private fun buildSignedProperties(contact: CanonicalContact, uid: String): List<String> = buildList {
        add("FN;PREF=1:${escape(contact.remoteDisplayName())}")
        var groupIndex = 0
        contact.valuesOf(ContactValueKind.EMAIL).forEachIndexed { index, value ->
            addDecorated(
                "EMAIL",
                value,
                groupIndex++,
                EMAIL_TYPES,
                preservedDecoration = preservedDecoration(contact, value, "EMAIL", PUBLIC_SOURCE_CARD_TYPES),
                generatedPreference = index + 1,
            )
        }
        contact.valuesOf(ContactValueKind.PUBLIC_KEY).forEachIndexed { index, value ->
            addDecorated(
                "KEY", value, groupIndex++, EMPTY_TYPES,
                preservedDecoration = preservedDecoration(contact, value, "KEY", PUBLIC_SOURCE_CARD_TYPES),
                generatedPreference = index + 1,
            )
        }
        add("UID:${escape(uid)}")
    }

    private fun buildPrivateProperties(contact: CanonicalContact, uid: String): List<String> = buildList {
        val structured = contact.valuesOf(ContactValueKind.STRUCTURED_NAME).firstOrNull()
        val structuredValue = listOf(
                structured?.components?.get("family") ?: contact.lastName,
                structured?.components?.get("given") ?: contact.firstName,
                structured?.components?.get("additional").orEmpty(),
                structured?.components?.get("prefix").orEmpty(),
                structured?.components?.get("suffix").orEmpty(),
            ).joinToString(";") { escape(it) }
        var groupIndex = 0
        if (structured == null) {
            add("N:$structuredValue")
        } else {
            addDecorated(
                "N", structured.copy(value = structuredValue), groupIndex++, EMPTY_TYPES,
                alreadyEscaped = true,
                preservedDecoration = preservedDecoration(contact, structured, "N", setOf(1, 3)),
            )
        }
        contact.values.forEach { value ->
            when (value.kind) {
                ContactValueKind.STRUCTURED_NAME, ContactValueKind.EMAIL, ContactValueKind.PUBLIC_KEY,
                ContactValueKind.CATEGORY, ContactValueKind.CUSTOM_DATE,
                ContactValueKind.UNKNOWN_VCARD_PROPERTY -> Unit
                ContactValueKind.PHONE -> addDecorated(
                    "TEL", value, groupIndex++, PHONE_TYPES,
                    preservedDecoration = preservedDecoration(contact, value, "TEL", setOf(1, 3)),
                    generatedPreference = generatedPreference(contact, value),
                )
                ContactValueKind.NICKNAME -> addDecoratedProperty(contact, "NICKNAME", value, groupIndex++)
                ContactValueKind.NOTE -> addDecoratedProperty(contact, "NOTE", value, groupIndex++)
                ContactValueKind.URL -> addDecorated(
                    "URL", value, groupIndex++, URL_TYPES,
                    preservedDecoration = preservedDecoration(contact, value, "URL", setOf(1, 3)),
                )
                ContactValueKind.POSTAL_ADDRESS -> addDecorated(
                    "ADR",
                    PostalAddressPolicy.normalize(value).let { normalized ->
                        normalized.copy(value = PostalAddressPolicy.componentKeys
                            .joinToString(";") { escape(normalized.components[it].orEmpty()) })
                    },
                    groupIndex++,
                    ADDRESS_TYPES,
                    alreadyEscaped = true,
                    preservedDecoration = preservedDecoration(contact, value, "ADR", setOf(1, 3)),
                    generatedPreference = generatedPreference(contact, value),
                )
                ContactValueKind.ORGANIZATION -> addDecorated(
                    "ORG",
                    value.copy(value = organizationComponents(value)
                        .joinToString(";") { escape(it) }),
                    groupIndex++,
                    EMPTY_TYPES,
                    alreadyEscaped = true,
                    preservedDecoration = preservedDecoration(contact, value, "ORG", setOf(1, 3)),
                )
                ContactValueKind.TITLE -> addDecoratedProperty(contact, "TITLE", value, groupIndex++)
                ContactValueKind.ROLE -> addDecoratedProperty(contact, "ROLE", value, groupIndex++)
                ContactValueKind.BIRTHDAY -> addDateProperty(contact, "BDAY", value, groupIndex++)
                ContactValueKind.ANNIVERSARY -> addDateProperty(contact, "ANNIVERSARY", value, groupIndex++)
                ContactValueKind.RELATIONSHIP -> addDecorated(
                    "RELATED", value, groupIndex++, RELATIONSHIP_TYPES,
                    preservedDecoration = preservedDecoration(contact, value, "RELATED", setOf(1, 3)),
                )
                ContactValueKind.LANGUAGE -> addDecoratedProperty(contact, "LANG", value, groupIndex++)
                ContactValueKind.TIME_ZONE -> addDecoratedProperty(contact, "TZ", value, groupIndex++)
                ContactValueKind.GENDER -> addDecorated(
                    "GENDER",
                    value.copy(value = listOf(
                        value.components["sex"] ?: value.value.substringBefore(';'),
                        value.components["identity"] ?: value.value.substringAfter(';', ""),
                    ).joinToString(";") { escape(it) }),
                    groupIndex++,
                    EMPTY_TYPES,
                    alreadyEscaped = true,
                    preservedDecoration = preservedDecoration(contact, value, "GENDER", setOf(1, 3)),
                )
                ContactValueKind.MEMBER -> addDecoratedProperty(contact, "MEMBER", value, groupIndex++)
                ContactValueKind.PHOTO -> addDecorated(
                    "PHOTO", value, groupIndex++, EMPTY_TYPES,
                    alreadyEscaped = true,
                    preservedDecoration = preservedDecoration(contact, value, "PHOTO", setOf(1, 3)),
                    generatedPreference = generatedPreference(contact, value),
                )
                ContactValueKind.LOGO -> addDecorated(
                    "LOGO", value, groupIndex++, EMPTY_TYPES,
                    alreadyEscaped = true,
                    preservedDecoration = preservedDecoration(contact, value, "LOGO", setOf(1, 3)),
                )
                ContactValueKind.PHONETIC_NAME ->
                    addDecoratedProperty(contact, "X-PHONETIC-FIRST-NAME", value, groupIndex++)
            }
        }
    }

    private fun buildClearProperties(contact: CanonicalContact): List<String> = buildList {
        // Proton's clear ITEMn.CATEGORIES refers to the matching signed ITEMn.EMAIL.
        // Preserve that link when signed email groups are regenerated/reordered.
        val emailGroups = contact.valuesOf(ContactValueKind.EMAIL).mapIndexedNotNull { index, email ->
            preservedDecoration(contact, email, "EMAIL", PUBLIC_SOURCE_CARD_TYPES)?.sourceGroup
                ?.uppercase(Locale.ROOT)?.let { it to "ITEM${index + 1}" }
        }.toMap()
        contact.valuesOf(ContactValueKind.CATEGORY).forEachIndexed { index, value ->
            val decoration = preservedDecoration(contact, value, "CATEGORIES", setOf(0))
            addDecorated(
                "CATEGORIES", value, index, EMPTY_TYPES,
                preservedDecoration = decoration,
                explicitGroup = decoration?.sourceGroup?.let { emailGroups[it.uppercase(Locale.ROOT)] ?: it },
            )
        }
    }

    private fun MutableList<String>.addDateProperty(
        contact: CanonicalContact,
        property: String,
        value: ContactValue,
        index: Int,
    ) {
        val decoration = preservedDecoration(contact, value, property, setOf(1, 3))
        // Validation has restricted new/edited dates to calendar dates. RFC 6350
        // requires their basic form; untouched imports retain their original wire value.
        val wireValue = if (decoration?.sourceValue == value.value) {
            value.value
        } else if (value.value.startsWith("--")) {
            "--" + value.value.removePrefix("--").replace("-", "")
        } else {
            value.value.replace("-", "")
        }
        addDecorated(property, value.copy(value = wireValue), index, EMPTY_TYPES,
            preservedDecoration = decoration)
    }

    private fun MutableList<String>.addDecoratedProperty(
        contact: CanonicalContact,
        property: String,
        value: ContactValue,
        index: Int,
    ) = addDecorated(
        property, value, index, EMPTY_TYPES,
        preservedDecoration = preservedDecoration(contact, value, property, setOf(1, 3)),
    )

    private fun MutableList<String>.addDecorated(
        property: String,
        value: ContactValue,
        index: Int,
        allowedTypes: Set<String>,
        alreadyEscaped: Boolean = false,
        preservedDecoration: PreservedDecoration? = null,
        generatedPreference: Int? = null,
        explicitGroup: String? = null,
    ) {
        val customLabel = value.label?.trim()?.takeIf(String::isNotBlank)
            ?.takeIf { it.uppercase(Locale.ROOT) !in allowedTypes }
            ?.takeIf { property in CUSTOM_LABEL_PROPERTIES }
        val group = explicitGroup ?: if (
            property == "EMAIL" || preservedDecoration?.groupedLabelSuffix != null || customLabel != null
        ) {
            "ITEM${index + 1}"
        } else {
            null
        }
        val params = buildList {
            if (preservedDecoration != null) {
                addAll(preservedDecoration.rawParameters)
            }
            val preservedParameterNames = preservedDecoration?.rawParameters.orEmpty()
                .map { it.substringBefore('=').uppercase(Locale.ROOT) }
                .toSet()
            if (property == "PHOTO" || property == "LOGO") {
                if ("VALUE" !in preservedParameterNames) add("VALUE=uri")
                generatedImageMediaType(value.value)
                    ?.takeIf { "MEDIATYPE" !in preservedParameterNames }
                    ?.let { add("MEDIATYPE=$it") }
            }
            if (preservedDecoration?.hasTypeParameter != true) {
                value.label?.trim()?.takeIf(String::isNotBlank)?.let { label ->
                    val normalized = label.uppercase(Locale.ROOT)
                    when {
                        normalized in allowedTypes -> add("TYPE=${label.lowercase(Locale.ROOT)}")
                        property in CUSTOM_LABEL_PROPERTIES -> add("TYPE=${customTypeToken(label)}")
                    }
                }
            }
            val importedPreference = value.vCardPreference()
            when {
                importedPreference != null -> add("PREF=$importedPreference")
                generatedPreference != null -> add("PREF=$generatedPreference")
                value.isPrimary && value.preservationKey == null &&
                    property in GENERATED_PREFERENCE_PROPERTIES -> add("PREF=1")
            }
        }.joinToString("") { ";$it" }
        val groupedProperty = group?.let { "$it.$property" } ?: property
        add("$groupedProperty$params:${if (alreadyEscaped) value.value else escape(value.value)}")
        when {
            preservedDecoration?.groupedLabelSuffix != null ->
                add("${requireNotNull(group)}.${preservedDecoration.groupedLabelSuffix}")
            customLabel != null -> Unit
        }
    }

    /** Produces an RFC 6350 x-name without requiring quoting or parameter escaping. */
    private fun customTypeToken(label: String): String {
        val value = label.lowercase(Locale.ROOT).removePrefix("x-")
        val token = buildString(value.length) {
            var separatorPending = false
            value.forEach { character ->
                when {
                    character in 'a'..'z' || character in '0'..'9' -> {
                        if (separatorPending && isNotEmpty()) append('-')
                        append(character)
                        separatorPending = false
                    }
                    character == '-' -> separatorPending = isNotEmpty()
                    else -> separatorPending = isNotEmpty()
                }
            }
        }
        return "x-${token.ifEmpty { "custom" }}"
    }

    private fun generatedPreference(contact: CanonicalContact, value: ContactValue): Int =
        contact.valuesOf(value.kind).indexOfFirst { it.id == value.id } + 1

    private fun unmanagedFor(cards: Map<String, String>, types: Set<Int>): List<String> = cards.entries
        .filter { entry -> types.any { type -> entry.key.startsWith("proton-card-$type-") } }
        .flatMap { entry -> unmanaged(entry.value) }

    private fun preservedDuplicateStandardDates(
        contact: CanonicalContact,
        cards: Map<String, String>,
    ): List<String> = contact.values.asSequence()
        .filter { value ->
            value.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY &&
                value.metadata[DUPLICATE_STANDARD_DATE_METADATA] == "true" &&
                value.label in STANDARD_DATE_PROPERTIES
        }
        .map { value ->
            val reference = requireNotNull(value.preservationKey?.toPreservationReference())
            val raw = requireNotNull(cards[reference.cardKey])
            val line = requireNotNull(unfold(raw).getOrNull(reference.lineIndex))
            require(contentLine(line)?.property == value.label)
            line
        }
        .toList()

    /** Reuses only decorations attached to this exact hydrated value, never a positional neighbour. */
    private fun preservedDecoration(
        contact: CanonicalContact,
        value: ContactValue,
        property: String,
        cardTypes: Set<Int>,
    ): PreservedDecoration? {
        val preservationKey = value.preservationKey ?: return null
        val reference = preservationKey.toPreservationReference()
            ?: throw IllegalArgumentException("Invalid preservation provenance")
        require(value.id == "${value.kind.name.lowercase(Locale.ROOT)}@${value.preservationKey}")
        require(cardTypes.any { type -> reference.cardKey.startsWith("proton-card-$type-") })
        val rawCard = requireNotNull(contact.preservationEnvelope?.valueSourceCards()?.get(reference.cardKey))
        val lines = unfold(rawCard)
        val baseline = requireNotNull(lines.getOrNull(reference.lineIndex)?.let(::contentLine))
        require(baseline.property == property)

        val groupedLabelLine = baseline.group?.let { group ->
            val labels = lines.filter { line ->
                contentLine(line)?.let { content ->
                    content.property == "X-ABLABEL" && content.group.equals(group, ignoreCase = true)
                } == true
            }
            require(labels.size <= 1)
            labels.singleOrNull()
        }
        val allowedTypes = managedTypesFor(property)
        val baselineLabel = groupedLabelLine?.let { unescape(requireNotNull(contentLine(it)).value) }
            ?: baseline.typeTokens().firstOrNull()
        val currentLabel = value.label?.trim()?.takeIf(String::isNotBlank)
        val unchangedLabel = if (groupedLabelLine == null && baselineLabel?.uppercase(Locale.ROOT) in allowedTypes) {
            currentLabel.equals(baselineLabel, ignoreCase = true)
        } else {
            currentLabel == baselineLabel
        }
        // Preserve unknown type decoration on compatible edits, but an explicit type edit
        // must replace the previous custom TYPE/X-ABLABEL just as it replaces HOME or WORK.
        val retainManagedType = allowedTypes.isEmpty() || unchangedLabel
        val migratedBinaryImage = property in setOf("PHOTO", "LOGO") &&
            legacyInlineImage(baseline) != null && generatedImageMediaType(value.value) != null
        val currentImageMediaType = if (property in setOf("PHOTO", "LOGO")) generatedImageMediaType(value.value) else null
        val emittedValueParameters = mutableSetOf<String>()
        val rawParameters = baseline.rawParameterSegments.mapNotNull { parameter ->
            require(parameter.length <= MAX_PARAMETER_CHARS)
            require(parameter.all { character -> character >= ' ' && character != '\u007f' })
            val separator = parameter.indexOf('=')
            require(separator in 1 until parameter.lastIndex)
            val name = parameter.substring(0, separator)
            require(PARAMETER_NAME.matches(name))
            when {
                name.equals("PREF", ignoreCase = true) -> null
                migratedBinaryImage && name.equals("ENCODING", ignoreCase = true) -> null
                currentImageMediaType != null && name.equals("MEDIATYPE", ignoreCase = true) &&
                    !parameter.substringAfter('=').removeSurrounding("\"").equals(currentImageMediaType, ignoreCase = true) -> null
                migratedBinaryImage && name.equals("TYPE", ignoreCase = true) &&
                    parameter.substringAfter('=').removeSurrounding("\"").split(',')
                        .all { it.trim().uppercase(Locale.ROOT) in LEGACY_IMAGE_TYPES } -> null
                name.equals("TYPE", ignoreCase = true) && !retainManagedType -> null
                name.equals("VALUE", ignoreCase = true) &&
                    !valueParameterRemainsCompatible(property, parameter, unescape(baseline.value), value.value) -> null
                else -> parameter
            }
        }.filter { parameter ->
            // Equivalent imported VALUE duplicates stay in the raw baseline, but a
            // regenerated managed property must contain only one declaration.
            !parameter.substringBefore('=').equals("VALUE", ignoreCase = true) ||
                emittedValueParameters.add("VALUE")
        }
        val groupedLabelSuffix = groupedLabelLine
            ?.takeIf { property !in CUSTOM_LABEL_PROPERTIES || unchangedLabel }
            ?.substringAfter('.', "")
            ?.takeIf { suffix -> suffix.isNotBlank() && '\r' !in suffix && '\n' !in suffix }
        return PreservedDecoration(
            rawParameters = rawParameters,
            hasTypeParameter = rawParameters.any { it.substringBefore('=').equals("TYPE", ignoreCase = true) },
            groupedLabelSuffix = groupedLabelSuffix,
            sourceGroup = baseline.group,
            sourceValue = unescape(baseline.value),
        )
    }

    private fun managedTypesFor(property: String): Set<String> = when (property) {
        "EMAIL" -> EMAIL_TYPES
        "TEL" -> PHONE_TYPES
        "ADR" -> ADDRESS_TYPES
        else -> EMPTY_TYPES
    }

    /** A representation declaration is retained only when an edit stays in the same value form. */
    private fun valueParameterRemainsCompatible(
        property: String,
        parameter: String,
        baselineValue: String,
        currentValue: String,
    ): Boolean {
        if (baselineValue == currentValue) return true
        val declared = parameter.substringAfter('=').trim().removeSurrounding("\"").lowercase(Locale.ROOT)
        val baselineForm = valueForm(property, baselineValue)
        val currentForm = valueForm(property, currentValue)
        if (baselineForm != currentForm) return false
        return when (declared) {
            "uri" -> currentForm == PreservedValueForm.URI
            "text" -> currentForm == PreservedValueForm.TEXT
            "date" -> currentForm == PreservedValueForm.DATE
            "date-time" -> currentForm == PreservedValueForm.DATE_TIME
            "time" -> currentForm == PreservedValueForm.TIME
            "utc-offset" -> currentForm == PreservedValueForm.UTC_OFFSET
            "language-tag" -> currentForm == PreservedValueForm.LANGUAGE_TAG
            else -> false
        }
    }

    private fun valueForm(property: String, value: String): PreservedValueForm = when (property) {
        "BDAY", "ANNIVERSARY" -> when {
            DATE_TIME_VALUE.matches(value) -> PreservedValueForm.DATE_TIME
            DATE_VALUE.matches(value) -> PreservedValueForm.DATE
            else -> PreservedValueForm.TEXT
        }
        "LANG" -> if (LANGUAGE_TAG_VALUE.matches(value)) PreservedValueForm.LANGUAGE_TAG else PreservedValueForm.TEXT
        "TZ" -> when {
            URI_VALUE.matches(value) -> PreservedValueForm.URI
            UTC_OFFSET_VALUE.matches(value) -> PreservedValueForm.UTC_OFFSET
            else -> PreservedValueForm.TEXT
        }
        "URL", "MEMBER", "KEY", "PHOTO", "LOGO" ->
            if (URI_VALUE.matches(value)) PreservedValueForm.URI else PreservedValueForm.TEXT
        else -> PreservedValueForm.TEXT
    }

    private fun String.toPreservationReference(): PreservationReference? {
        val separator = lastIndexOf(PRESERVATION_LINE_SEPARATOR)
        if (separator <= 0) return null
        val lineIndex = substring(separator + PRESERVATION_LINE_SEPARATOR.length)
            .substringBefore(PRESERVATION_VALUE_SEPARATOR)
            .toIntOrNull() ?: return null
        if (lineIndex < 0) return null
        return PreservationReference(substring(0, separator), lineIndex)
    }

    private fun unmanaged(baseline: String): List<String> {
        if (baseline.isBlank()) return emptyList()
        val lines = unfold(baseline)
        val groupsWithUnmanagedProperties = lines.mapNotNull { line ->
            val content = contentLine(line) ?: return@mapNotNull null
            content.group?.uppercase(Locale.ROOT)
                ?.takeIf { content.property !in MANAGED_AND_STRUCTURAL_PROPERTIES }
        }.toSet()
        return lines.filter { line ->
            val content = contentLine(line) ?: return@filter false
            content.property !in MANAGED_AND_STRUCTURAL_PROPERTIES ||
                (content.property == "X-ABLABEL" &&
                    content.group?.uppercase(Locale.ROOT) in groupsWithUnmanagedProperties)
        }
    }

    private fun addValue(
        target: MutableList<ContactValue>,
        kind: ContactValueKind,
        value: String,
        content: ContentLine,
        familyOrder: Int,
        label: String? = null,
        components: Map<String, String> = emptyMap(),
        preservationKey: String? = content.preservationKey,
    ) {
        if (value.isBlank() && components.values.all(String::isBlank)) return
        val typeTokens = content.typeTokens()
        val type = label ?: typeTokens.firstOrNull()
        val preference = content.parameters["PREF"]?.toIntOrNull()?.takeIf { it > 0 }
        val resolvedPreservationKey = preservationKey?.let { "$it$PRESERVATION_VALUE_SEPARATOR$familyOrder" }
        target += ContactValue(
            id = resolvedPreservationKey?.let { "${kind.name.lowercase(Locale.ROOT)}@$it" }
                ?: "${kind.name.lowercase(Locale.ROOT)}-${familyOrder + 1}",
            kind = kind,
            value = value,
            label = type,
            order = familyOrder,
            isPrimary = false,
            components = components,
            metadata = buildMap {
                preference?.let { put(VCARD_PREF_METADATA, it.toString()) }
                if (typeTokens.isNotEmpty()) put(VCARD_TYPE_TOKENS_METADATA, typeTokens.joinToString("\u001f"))
            },
            preservationKey = resolvedPreservationKey,
        )
    }

    private fun ContentLine.typeTokens(): List<String> = rawParameterSegments.asSequence()
        .filter { it.substringBefore('=').equals("TYPE", ignoreCase = true) }
        .flatMap { parameter -> parameter.substringAfter('=').removeSurrounding("\"").split(',').asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    private fun ContactValue.vCardPreference(): Int? = metadata[VCARD_PREF_METADATA]
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    private fun ContactValueKind.standardDateProperty(): String = when (this) {
        ContactValueKind.BIRTHDAY -> "BDAY"
        ContactValueKind.ANNIVERSARY -> "ANNIVERSARY"
        else -> error("Not a standard date kind")
    }

    private fun organizationComponents(value: ContactValue): List<String> {
        val indexed = value.components.entries.mapNotNull { (key, component) ->
            key.removePrefix("component_").toIntOrNull()?.let { it to component }
        }.sortedBy(Pair<Int, String>::first).map(Pair<Int, String>::second)
        return indexed.ifEmpty {
            val company = value.components["company"]
            val department = value.components["department"]
            if (company == null && department == null) {
                listOf(value.value)
            } else {
                listOf(company.orEmpty(), department.orEmpty())
            }
        }
    }

    /** Proton imports can retain vCard 3 binary images inside their vCard 4 envelope.
     * Normalize only an explicit, bounded base64 image declaration; preserve unknown forms.
     * The original wire card remains in the preservation envelope.
     */
    private fun legacyInlineImage(content: ContentLine): String? {
        fun declarations(name: String) = content.rawParameterSegments
            .filter { it.substringBefore('=').equals(name, ignoreCase = true) }
            .map { it.substringAfter('=').removeSurrounding("\"").lowercase(Locale.ROOT) }.distinct()
        val encoding = declarations("ENCODING")
        val valueType = declarations("VALUE")
        val media = declarations("MEDIATYPE")
        if (encoding.singleOrNull() !in setOf("b", "base64")) return null
        if (valueType.size > 1 || valueType.singleOrNull() !in setOf(null, "binary") || media.size > 1) return null
        val mediaTypes = buildList {
            media.singleOrNull()?.let { add(it) }
            content.typeTokens().forEach { token ->
                LEGACY_IMAGE_TYPES[token.uppercase(Locale.ROOT)]?.let { add(it) }
            }
        }.distinct()
        val mediaType = mediaTypes.singleOrNull()?.takeIf { it in IMAGE_MEDIA_TYPES } ?: return null
        val bytes = try {
            java.util.Base64.getDecoder().decode(content.value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        return "data:$mediaType;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun generatedImageMediaType(value: String): String? {
        if (!value.startsWith("data:", ignoreCase = true)) return null
        val header = value.substringBefore(',', missingDelimiterValue = "")
        if (!header.endsWith(";base64", ignoreCase = true)) return null
        return header.substringAfter(':').dropLast(";base64".length).lowercase(Locale.ROOT)
            .takeIf { it in IMAGE_MEDIA_TYPES }
    }

    private fun render(properties: List<String>): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:4.0")
        properties.forEach { property ->
            require(property.length <= MAX_LINE_CHARS)
            append("\r\n").append(property)
        }
        append("\r\nEND:VCARD")
    }

    private fun unfold(raw: String): List<String> {
        val result = mutableListOf<String>()
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if ((line.startsWith(' ') || line.startsWith('\t')) && result.isNotEmpty()) {
                result[result.lastIndex] += line.drop(1)
            } else if (line.isNotEmpty()) {
                result += line
            }
        }
        return result
    }

    private fun contentLine(line: String, preservationKey: String? = null): ContentLine? {
        val colon = line.indexOfOutsideQuotes(':')
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val segments = head.splitOutsideQuotes(';')
        val groupedName = segments.first()
        val group = groupedName.substringBeforeLast('.', "").ifBlank { null }
        val property = groupedName.substringAfterLast('.').uppercase(Locale.ROOT)
        val parameterPairs = segments.drop(1).mapNotNull { parameter ->
            val equals = parameter.indexOf('=')
            if (equals <= 0) null else parameter.substring(0, equals).uppercase(Locale.ROOT) to parameter.substring(equals + 1)
        }
        val duplicateManagedParameters = parameterPairs.groupBy(Pair<String, String>::first)
            .filter { (name, values) -> name in setOf("PREF", "VALUE") && values.size > 1 }
        val parameters = parameterPairs.toMap().toMutableMap()
        duplicateManagedParameters.forEach { (name, repeated) ->
            val normalized = repeated.map { (_, value) -> singletonParameterValue(name, value) }
            requireVCardParse(normalized.all { it != null } && normalized.distinct().size == 1,
                GatewayContactHydrationCategory.VCARD_PARSE_DUPLICATE_PARAMETER)
            parameters[name] = requireNotNull(normalized.first())
        }
        return ContentLine(
            group = group,
            property = property,
            parameters = parameters,
            rawParameterSegments = segments.drop(1),
            value = line.substring(colon + 1),
            preservationKey = preservationKey,
        )
    }

    private fun singletonParameterValue(name: String, rawValue: String): String? {
        val value = rawValue.removeSurrounding("\"")
        return when (name) {
            "PREF" -> value.takeIf { it.matches(Regex("[0-9]{1,3}")) }
                ?.toIntOrNull()?.takeIf { it in 1..100 }?.toString()
            "VALUE" -> value.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }?.lowercase(Locale.ROOT)
            else -> null
        }
    }

    private fun String.indexOfOutsideQuotes(delimiter: Char): Int {
        var quoted = false
        var escaped = false
        forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                character == delimiter && !quoted -> return index
            }
        }
        requireVCardParse(!quoted && !escaped, GatewayContactHydrationCategory.VCARD_PARSE_PARAMETER_SYNTAX)
        return -1
    }

    private fun String.splitOutsideQuotes(delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start <= length) {
            val relative = substring(start).indexOfOutsideQuotes(delimiter)
            if (relative < 0) {
                result += substring(start)
                break
            }
            val index = start + relative
            result += substring(start, index)
            start = index + 1
        }
        return result
    }

    private fun propertyName(line: String): String = contentLine(line)?.property.orEmpty()

    private fun splitEscaped(value: String, delimiter: Char): List<String> {
        val output = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        value.forEach { char ->
            when {
                escaped -> {
                    current.append('\\').append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == delimiter -> {
                    output += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        if (escaped) current.append('\\')
        output += current.toString()
        return output
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")
        .replace(";", "\\;")
        .replace(",", "\\,")

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n', 'N' -> append('\n')
                    '\\', ';', ',' -> append(next)
                    else -> append(next)
                }
                index += 2
            } else {
                append(value[index++])
            }
        }
    }

    private fun String.normalizedEmail(): String = trim().lowercase(Locale.ROOT)

    private fun CanonicalContact.remoteDisplayName(): String = displayName.trim().ifEmpty {
        listOf(firstName.trim(), lastName.trim()).filter(String::isNotEmpty).joinToString(" ")
    }

    private data class ContentLine(
        val group: String?,
        val property: String,
        val parameters: Map<String, String>,
        val rawParameterSegments: List<String>,
        val value: String,
        val preservationKey: String?,
    )

    private data class PreservationReference(
        val cardKey: String,
        val lineIndex: Int,
    )

    private data class PreservedDecoration(
        val rawParameters: List<String>,
        val hasTypeParameter: Boolean,
        val groupedLabelSuffix: String?,
        val sourceGroup: String?,
        val sourceValue: String,
    )

    private enum class PreservedValueForm { URI, DATE_TIME, DATE, TIME, UTC_OFFSET, LANGUAGE_TAG, TEXT }

    private data class ParsedCard(
        val uid: String,
        val firstName: String,
        val lastName: String,
        val displayName: String,
        val values: List<ContactValue>,
        val unknownLines: List<String>,
    )

    private companion object {
        const val MAX_CARDS = 16
        const val MAX_CARD_BYTES = 10 * 1_024 * 1_024
        const val MAX_LINES = 50_000
        const val MAX_LINE_CHARS = 10 * 1_024 * 1_024
        const val MAX_VALUES = 10_000
        // Legacy unencrypted EMAIL/KEY provenance may be clear; new output is
        // still signed. Private-card values MUST NOT gain public output here.
        val PUBLIC_SOURCE_CARD_TYPES = setOf(0, 2)
        val EMAIL_TYPES = setOf("HOME", "WORK", "OTHER")
        val PHONE_TYPES = setOf("HOME", "WORK", "OTHER", "CELL", "MAIN", "FAX", "PAGER")
        val ADDRESS_TYPES = setOf("HOME", "WORK", "OTHER")
        val URL_TYPES = emptySet<String>()
        val RELATIONSHIP_TYPES = emptySet<String>()
        val CUSTOM_LABEL_PROPERTIES = setOf("EMAIL", "TEL")
        val GENERATED_PREFERENCE_PROPERTIES = setOf("EMAIL", "TEL", "ADR", "KEY", "PHOTO")
        val EMPTY_TYPES = emptySet<String>()
        const val PRESERVATION_LINE_SEPARATOR = "#line="
        const val PRESERVATION_VALUE_SEPARATOR = "#value="
        const val MAX_PARAMETER_CHARS = 16 * 1_024
        const val VCARD_PREF_METADATA = "vcardPref"
        const val VCARD_TYPE_TOKENS_METADATA = "vcardTypeTokens"
        const val DUPLICATE_STANDARD_DATE_METADATA = "duplicateStandardDate"
        val STANDARD_DATE_PROPERTIES = setOf("BDAY", "ANNIVERSARY")
        val PARAMETER_NAME = Regex("[A-Za-z0-9-]{1,64}")
        val URI_VALUE = Regex("[A-Za-z][A-Za-z0-9+.-]{1,31}:.*")
        val DATE_TIME_VALUE = Regex("(?:--\\d{2}-\\d{2}|\\d{4}-?\\d{2}-?\\d{2})T.*", RegexOption.IGNORE_CASE)
        val DATE_VALUE = Regex("(?:--\\d{2}-?\\d{2}|\\d{4}-?\\d{2}-?\\d{2})")
        val TIME_VALUE = Regex("\\d{2}:?\\d{2}(?::?\\d{2})?(?:Z|[+-]\\d{2}:?\\d{2})?", RegexOption.IGNORE_CASE)
        val UTC_OFFSET_VALUE = Regex("[+-]\\d{2}:?\\d{2}")
        val LANGUAGE_TAG_VALUE = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
        val MANAGED_AND_STRUCTURAL_PROPERTIES = setOf(
            "BEGIN", "END", "VERSION", "PRODID", "UID", "FN", "N", "EMAIL", "TEL",
            "NICKNAME", "NOTE", "URL", "ADR", "ORG", "TITLE", "ROLE", "BDAY",
            "ANNIVERSARY", "LANG", "TZ", "GENDER", "MEMBER", "RELATED",
            "X-ABRELATEDNAMES", "X-ABLABEL", "CATEGORIES", "KEY", "PHOTO", "LOGO",
            "X-PHONETIC-FIRST-NAME",
        )
        val PROTON_PREFERENCE_KINDS = setOf(
            ContactValueKind.STRUCTURED_NAME,
            ContactValueKind.EMAIL,
            ContactValueKind.PHONE,
            ContactValueKind.POSTAL_ADDRESS,
            ContactValueKind.PUBLIC_KEY,
            ContactValueKind.PHOTO,
        )
        val IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        val LEGACY_IMAGE_TYPES = mapOf("PNG" to "image/png", "JPEG" to "image/jpeg",
            "JPG" to "image/jpeg", "GIF" to "image/gif", "WEBP" to "image/webp")
        val BIDI_CONTROL_CHARACTERS = setOf(
            '\u061C', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069',
        )
    }
}

internal class ProtonInconsistentContactIdentity : ProtonMalformedContactResponse()

internal fun hasContactCardPayloadProperties(card: String): Boolean = card
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .any { line -> line.substringBefore(':').substringBefore(';').uppercase(Locale.ROOT) !in
        setOf("BEGIN", "VERSION", "PRODID", "END") }
