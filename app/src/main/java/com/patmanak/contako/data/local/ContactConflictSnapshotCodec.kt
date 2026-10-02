package com.patmanak.contako.data.local

import com.patmanak.contako.domain.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Private versioned persistence only. No Java object serialization or remote input execution. */
internal object ContactConflictSnapshotCodec {
    private const val MAX_BYTES = 48 * 1024 * 1024
    private const val MAX_ITEMS = 100_000

    fun encode(contact: CanonicalContact): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String?) {
                if (value == null) out.writeInt(-1) else {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    require(encoded.size <= MAX_BYTES && bytes.size().toLong() + encoded.size + 4 <= MAX_BYTES)
                    out.writeInt(encoded.size); out.write(encoded)
                }
            }
            fun map(value: Map<String, String>) {
                require(value.size <= MAX_ITEMS)
                out.writeInt(value.size); value.toSortedMap().forEach { (k, v) -> text(k); text(v) }
            }
            out.writeInt(1)
            text(contact.accountId); text(contact.id); text(contact.firstName); text(contact.lastName); text(contact.displayName)
            out.writeLong(contact.revision); out.writeLong(contact.updatedAtEpochMillis)
            text(contact.remoteContactId); text(contact.remoteVCardUid); text(contact.remoteVersion)
            out.writeBoolean(contact.preservationEnvelope != null)
            contact.preservationEnvelope?.let { map(it.rawProperties); text(it.remoteBaseline) }
            map(contact.actionRequiredReasons.associateWith { "" })
            text(contact.pendingMutationRevision?.toString()); text(contact.conflictState); out.writeBoolean(contact.isDeleted)
            require(contact.values.size <= MAX_ITEMS)
            out.writeInt(contact.values.size)
            contact.values.forEach { value ->
                text(value.id); text(value.kind.name); text(value.value); text(value.label)
                out.writeInt(value.order); out.writeBoolean(value.isPrimary)
                map(value.components); map(value.metadata); text(value.binaryReference); text(value.preservationKey)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): CanonicalContact {
        require(bytes.size <= MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        fun text(): String? {
            val length = input.readInt()
            if (length == -1) return null
            require(length in 0..input.available())
            return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
        }
        fun required() = requireNotNull(text())
        fun count() = input.readInt().also { require(it in 0..MAX_ITEMS) }
        fun map(): Map<String, String> = buildMap {
            repeat(count()) { val key = required(); require(!containsKey(key)); put(key, required()) }
        }
        require(input.readInt() == 1)
        val account = required(); val id = required(); val first = required(); val last = required(); val display = required()
        val revision = input.readLong(); val updated = input.readLong()
        val remoteId = text(); val uid = text(); val version = text()
        val envelope = if (input.readBoolean()) PreservationEnvelope(map(), text()) else null
        val reasons = map().keys
        val pending = text()?.toLong(); val conflict = text(); val deleted = input.readBoolean()
        val values = List(count()) {
            ContactValue(required(), ContactValueKind.valueOf(required()), required(), text(), input.readInt(),
                input.readBoolean(), map(), map(), text(), text())
        }
        require(input.available() == 0 && values.map { it.id }.distinct().size == values.size)
        return CanonicalContact(account, id, first, last, display, values, revision, updated,
            remoteId, uid, version, envelope, reasons, pending, conflict, deleted)
    }
}
