package com.patmanak.contako.data.android.provider

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidProjectionPlan
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidRowOperation
import com.patmanak.contako.data.android.mapping.AndroidValueIdentity
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidPhotoProviderWriteJournalEntity
import com.patmanak.contako.data.local.AndroidProjectionAccountEntity
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContactEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAndroidPhotoProviderWriteCoordinatorDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var accountManager: AccountManager
    private lateinit var androidAccount: Account
    private var rawContactId: Long? = null
    private var ownsAndroidAccount = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CONTACTS)
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        accountManager = AccountManager.get(context)
        if (InstrumentationRegistry.getArguments().getString("useExistingContakoAccount") == "true") {
            androidAccount = accountManager.getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE).single()
        } else {
            androidAccount = Account("contako-photo-${System.nanoTime().toString(36)}",
                ContakoAndroidAccountContract.ACCOUNT_TYPE)
            check(accountManager.addAccountExplicitly(androidAccount, null, null))
            ownsAndroidAccount = true
        }
    }

    @After
    fun tearDown() {
        rawContactId?.let { locator ->
            context.contentResolver.delete(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(locator.toString()),
            )
        }
        if (ownsAndroidAccount) accountManager.removeAccountExplicitly(androidAccount)
        if (::database.isInitialized) database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun stalePreparedCommandIsReplacedAndCommittedAgainstCurrentDurableClaims() = runBlocking {
        val locator = insertOwnedRawContact()
        rawContactId = locator
        val reader = AndroidContactsProviderReader(context.contentResolver)
        val accountName = AndroidProviderAccountName(androidAccount.name)
        val currentBytes = ByteArrayOutputStream().use { output ->
            val dimension = 256
            val bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
            try {
                val pixels = IntArray(dimension * dimension) { index ->
                    val x = index % dimension
                    val y = index / dimension
                    Color.rgb(x, y, x xor y)
                }
                bitmap.setPixels(pixels, 0, dimension, 0, 0, dimension, dimension)
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            } finally {
                bitmap.recycle()
            }
            output.toByteArray()
        }
        val desiredPhoto = AndroidContactRow(
            AndroidValueIdentity(CURRENT_PHOTO_VALUE_ID),
            AndroidRowKind.PHOTO,
            isPrimary = true,
            isSuperPrimary = true,
            binaryReference = CURRENT_PHOTO_REFERENCE,
        )
        val beforeInline = (reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable)
            .page.observations.single().rawContact.version
        val inlineSnapshot = AndroidContactSnapshot(CONTACT_ID, listOf(desiredPhoto))
        val inlineResult = AndroidContactsProviderWriter(
            context.contentResolver,
            AndroidProjectionBinaryLoader { currentBytes },
        ).applyProjectionPlan(
            accountName,
            locator,
            beforeInline,
            AndroidExpectedSourceIdentity.Present(SOURCE_ID),
            null,
            AndroidProjectionPlan(
                inlineSnapshot,
                CanonicalAndroidContactMapper().fingerprint(inlineSnapshot),
                listOf(AndroidRowOperation.Insert(desiredPhoto)),
            ),
        )
        assertTrue("INLINE_PHOTO_WRITE_FAILED_$inlineResult", inlineResult is AndroidProviderProjectionResult.Applied)
        assertEquals(
            1,
            context.contentResolver.update(
                syncAdapterUri(ContactsContract.Data.CONTENT_URI),
                ContentValues().apply { putNull(ContactsContract.CommonDataKinds.Photo.PHOTO) },
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(locator.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            ),
        )
        val emptyPhoto = reader.readDataRows(accountName, setOf(locator)).single {
            it.mimeType == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        }
        assertNull(emptyPhoto.binarySlot)
        val initialVersion = (reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable)
            .page.observations.single().rawContact.version
        seedCurrentDurableClaims(locator)
        database.androidGroupProjectionDao().upsertPhotoProviderWriteJournal(
            AndroidPhotoProviderWriteJournalEntity(
                ACCOUNT.value,
                CONTACT_ID,
                androidAccount.name,
                PROVIDER_EPOCH,
                locator,
                initialVersion,
                SOURCE_ID,
                CURRENT_CANONICAL_REVISION - 1,
                LEDGER_REVISION,
                "stale-photo-value",
                "stale-photo-reference",
                1,
                ByteArray(1) { 1 }.sha256(),
                "PREPARED",
                null,
            ),
        )
        val coordinator = RoomAndroidPhotoProviderWriteCoordinator(
            database,
            context.contentResolver,
            reader,
            AndroidProjectionBinaryLoader { reference ->
                assertEquals(CURRENT_PHOTO_REFERENCE, reference)
                currentBytes
            },
        )

        val beforeWrite = (reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable)
            .page.observations.single()
        val result = coordinator.write(
            AndroidInteroperabilityContext(ACCOUNT, androidAccount.name, 1, PROVIDER_EPOCH),
            CONTACT_ID,
            CURRENT_CANONICAL_REVISION,
            LEDGER_REVISION,
            accountName,
            locator,
            initialVersion,
            SOURCE_ID,
            desiredPhoto,
        )

        val journal = database.androidGroupProjectionDao()
            .getPhotoProviderWriteJournal(ACCOUNT.value, CONTACT_ID)!!
        val diagnosticRows = reader.readDataRows(accountName, setOf(locator))
        val diagnosticPhotoRows = diagnosticRows.filter {
            it.mimeType == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        }
        val diagnosticPhoto = diagnosticPhotoRows.singleOrNull()
        val afterWrite = (reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable)
            .page.observations.single()
        assertTrue(
            "PHOTO_WRITE_NOT_COMMITTED_${result}_" +
                "JOURNAL_${journal.state}_CURRENT_${journal.expectedCanonicalRevision == CURRENT_CANONICAL_REVISION}_" +
                "PHOTO_ROWS_${diagnosticPhotoRows.size}_" +
                "BINARY_${diagnosticPhoto?.binarySlot?.isNotEmpty() == true}_" +
                "IDENTITY_${diagnosticPhoto?.canonicalValueId == CURRENT_PHOTO_VALUE_ID}_" +
                "VERSION_DELTA_${afterWrite.rawContact.version - initialVersion}_" +
                "DIRTY_${afterWrite.rawContact.dirty}_" +
                "NON_PHOTO_EQUAL_${beforeWrite.dataRows.filterNot { it.isStandardPhoto } == afterWrite.dataRows.filterNot { it.isStandardPhoto }}",
            result is AndroidPhotoProviderWriteResult.Committed,
        )
        assertEquals("COMMITTED", journal.state)
        assertEquals(CURRENT_CANONICAL_REVISION, journal.expectedCanonicalRevision)
        assertEquals(CURRENT_PHOTO_VALUE_ID, journal.canonicalValueId)
        assertEquals(CURRENT_PHOTO_REFERENCE, journal.binaryReference)
        assertEquals(currentBytes.sha256(), journal.contentSha256)
        val photoRows = diagnosticPhotoRows
        assertEquals(1, photoRows.size)
        assertEquals(CURRENT_PHOTO_VALUE_ID, photoRows.single().canonicalValueId)
        assertTrue("DISPLAY_PHOTO_BINARY_MISSING", displayPhotoHasBytes(locator))
        val finalRawContact = (reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable)
            .page.observations.single().rawContact
        assertFalse("DISPLAY_PHOTO_MARKED_RAW_CONTACT_DIRTY", finalRawContact.dirty)
        val stable = reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable
        val observedPhoto = stable.page.observations.single().dataRows.single { it.isStandardPhoto }
        val displayUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, locator)
            .buildUpon().appendPath(ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build()
        val actualDisplay = context.contentResolver.openAssetFileDescriptor(displayUri, "r")!!.use { descriptor ->
            descriptor.createInputStream().use { it.readBytes() }
        }
        assertTrue("STABLE_READER_MUST_PREFER_DISPLAY_BYTES", actualDisplay.contentEquals(observedPhoto.binarySlot))
        val verifier = AndroidPhotoReadbackVerifier(context.contentResolver)
        assertTrue("ACTUAL_PROVIDER_PHOTO_NOT_VERIFIED", verifier.matches(accountName, stable.page.observations.single(), currentBytes))
        val differentPhoto = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.MAGENTA)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            } finally { bitmap.recycle() }
        }
        assertFalse("DIFFERENT_PHOTO_ACCEPTED", verifier.matches(accountName, stable.page.observations.single(), differentPhoto))
        val receipt = database.androidGroupProjectionDao().getPhotoProjectionReceipt(ACCOUNT.value, CONTACT_ID)!!
        val scope = AndroidInteroperabilityContext(ACCOUNT, androidAccount.name, 1, PROVIDER_EPOCH)
        assertEquals(currentBytes.sha256(), receipt.sourceSha256)
        assertEquals(observedPhoto.binarySlot!!.sha256(), receipt.readbackSha256)
        assertTrue(receipt.matchesPhoto(scope, CONTACT_ID, stable.page.observations.single()))
        coordinator.cleanupCommitted(ACCOUNT.value, CONTACT_ID, currentBytes.sha256())
        assertNull(database.androidGroupProjectionDao().getPhotoProviderWriteJournal(ACCOUNT.value, CONTACT_ID))
        assertEquals(receipt, database.androidGroupProjectionDao().getPhotoProjectionReceipt(ACCOUNT.value, CONTACT_ID))
        val unchanged = coordinator.write(scope, CONTACT_ID, CURRENT_CANONICAL_REVISION, LEDGER_REVISION,
            accountName, locator, stable.page.observations.single().rawContact.version, SOURCE_ID, desiredPhoto)
        assertTrue(unchanged is AndroidPhotoProviderWriteResult.Committed)
        val unchangedObservation = (reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable)
            .page.observations.single()
        assertEquals(stable.page.observations.single(), unchangedObservation)
        assertNull(database.androidGroupProjectionDao().getPhotoProviderWriteJournal(ACCOUNT.value, CONTACT_ID))
        // Native note editing marks the raw contact dirty, but does not replace the original photo.
        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, locator)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Note.NOTE, "Synthetic native note")
        })
        val noted = (reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable)
            .page.observations.single()
        assertTrue(noted.rawContact.dirty)
        assertTrue(receipt.matchesPhoto(scope, CONTACT_ID, noted))
        context.contentResolver.update(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.CommonDataKinds.Photo.PHOTO, differentPhoto)
        }, "${ContactsContract.Data._ID} = ?", arrayOf(receipt.dataRowLocator.toString()))
        val changed = (reader.readStableRawContact(accountName, locator) as AndroidStableRawContactPageResult.Stable)
            .page.observations.single()
        assertFalse(receipt.matchesPhoto(scope, CONTACT_ID, changed))
    }

    private suspend fun seedCurrentDurableClaims(locator: Long) {
        database.contactDao().upsert(
            ContactEntity(
                ACCOUNT.value,
                CONTACT_ID,
                "${ACCOUNT.value}:$CONTACT_ID",
                "Synthetic",
                "Photo",
                "Synthetic Photo",
                "Synthetic Photo",
                CURRENT_CANONICAL_REVISION,
                1,
                SOURCE_ID,
                null,
                "1",
                "",
                null,
                null,
                false,
            ),
        )
        assertTrue(
            "ACCOUNT_LEDGER_INSERT_FAILED",
            database.androidProjectionLedgerDao().insertAccount(
                AndroidProjectionAccountEntity(ACCOUNT.value, 1, PROVIDER_EPOCH, androidAccount.name),
            ) > 0,
        )
        assertTrue(
            "CONTACT_LEDGER_INSERT_FAILED",
            database.androidProjectionLedgerDao().insert(
                AndroidProjectionLedgerEntity(
                    ACCOUNT.value,
                    CONTACT_ID,
                    LEDGER_REVISION,
                    PROVIDER_EPOCH,
                    locator,
                    SOURCE_ID,
                    FINGERPRINT,
                    FINGERPRINT,
                    FINGERPRINT,
                    null,
                    "WRITE_PENDING",
                    "BASELINED",
                    "NONE",
                    "ADOPTED",
                ),
            ) > 0,
        )
    }

    private fun insertOwnedRawContact(): Long {
        val uri = context.contentResolver.insert(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
            ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_NAME, androidAccount.name)
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, androidAccount.type)
                put(ContactsContract.RawContacts.SOURCE_ID, SOURCE_ID)
                put(ContactsContract.RawContacts.SYNC1, CONTACT_ID)
            },
        ) ?: error("Synthetic raw-contact insert failed")
        val locator = ContentUris.parseId(uri)
        check(
            context.contentResolver.insert(
                syncAdapterUri(ContactsContract.Data.CONTENT_URI),
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, locator)
                    put(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    )
                    put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, "Synthetic")
                    put(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, "Photo")
                    put(ContactsContract.Data.SYNC1, "synthetic-name-value")
                },
            ) != null,
        )
        return locator
    }

    private fun syncAdapterUri(base: android.net.Uri): android.net.Uri = base.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, androidAccount.name)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, androidAccount.type)
        .build()

    private fun displayPhotoHasBytes(locator: Long): Boolean {
        val contactId = context.contentResolver.query(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(locator.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        } ?: return false
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            .buildUpon()
            .appendPath(ContactsContract.Contacts.Photo.DISPLAY_PHOTO)
            .build()
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.createInputStream().use { input -> input.read() != -1 }
            } ?: false
        } catch (_: FileNotFoundException) {
            false
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DATABASE_NAME = "photo-provider-write-coordinator.db"
        const val CONTACT_ID = "synthetic-photo-contact"
        const val SOURCE_ID = "synthetic-photo-source"
        const val CURRENT_PHOTO_VALUE_ID = "current-photo-value"
        const val CURRENT_PHOTO_REFERENCE = "current-photo-reference"
        const val CURRENT_CANONICAL_REVISION = 7L
        const val LEDGER_REVISION = 3L
        const val PROVIDER_EPOCH = 0L
        const val FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val ACCOUNT = AccountScope("synthetic-photo-account")
    }
}
