package com.patmanak.contako.data.android.provider

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidProjectionPlan
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidRowOperation
import com.patmanak.contako.data.android.mapping.AndroidValueIdentity
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-process provider only: no Android account, device contact or network operation. */
@RunWith(AndroidJUnit4::class)
class AndroidPostWriteVersionProofDeviceTest {
    private val account = AndroidProviderAccountName("synthetic-account")
    private val row = AndroidContactRow(AndroidValueIdentity("note"), AndroidRowKind.NOTE, value = "Synthetic note")
    private val desired = AndroidContactSnapshot("contact", listOf(row))
    private val plan = AndroidProjectionPlan(desired, CanonicalAndroidContactMapper().fingerprint(desired), listOf(AndroidRowOperation.Insert(row)))

    @Test fun writerCapturesTheRealPostVersionWithoutAssumingAnIncrement() = runBlocking {
        val provider = SyntheticProvider()
        val writer = AndroidContactsProviderWriter(ContentResolver.wrap(provider))
        val result = writer.applyProjectionPlan(account, 10, 17, AndroidExpectedSourceIdentity.Present("remote"), null, plan)
        assertTrue(result is AndroidProviderProjectionResult.Applied)
        // This provider intentionally advances by more than one; OEM increments are not guessed.
        assertEquals(23L, (result as AndroidProviderProjectionResult.Applied).rawContactVersion)
        assertTrue(writer.isCurrentPostWriteVersion(account, 10, "remote", 23))
        assertFalse(writer.isCurrentPostWriteVersion(account, 10, "remote", 17))
    }

    @Test fun anEditAfterTheBatchInvalidatesTheVersionReceiptEvenIfItClearsDirty() = runBlocking {
        val provider = SyntheticProvider()
        val writer = AndroidContactsProviderWriter(ContentResolver.wrap(provider), afterApplyBatch = {
            provider.version = 24
            provider.dirty = false
        })
        val result = writer.applyProjectionPlan(account, 10, 17, AndroidExpectedSourceIdentity.Present("remote"), null, plan)
            as AndroidProviderProjectionResult.Applied
        assertEquals(23L, result.rawContactVersion)
        assertFalse(writer.isCurrentPostWriteVersion(account, 10, "remote", requireNotNull(result.rawContactVersion)))
        assertFalse(writer.isCurrentPostWriteVersion(account, 10, "foreign", 24))
    }

    @Test fun aDirtyReadbackCannotAcquireTheCleanVersionReceipt() = runBlocking {
        val provider = SyntheticProvider(dirtyAfterWrite = true)
        val result = AndroidContactsProviderWriter(ContentResolver.wrap(provider)).applyProjectionPlan(
            account, 10, 17, AndroidExpectedSourceIdentity.Present("remote"), null, plan)
        assertEquals(AndroidProviderProjectionResult.ReplanRequired, result)
    }

    private class SyntheticProvider(private val dirtyAfterWrite: Boolean = false) : ContentProvider() {
        var version = 17L
        var dirty = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            val columns = projection ?: arrayOf(ContactsContract.RawContacts._ID)
            val result = MatrixCursor(columns)
            val sql = selection.orEmpty()
            val versionClause = "${ContactsContract.RawContacts.VERSION} = ?"
            if (sql.contains(versionClause)) {
                val index = sql.substringBefore(versionClause).count { it == '?' }
                if (args?.getOrNull(index) != version.toString()) return result
            }
            if (sql.contains("${ContactsContract.RawContacts.DIRTY} = 0") && dirty) return result
            if (sql.contains("${ContactsContract.RawContacts.SOURCE_ID} = ?") && args?.lastOrNull() != "remote") return result
            if (args?.firstOrNull() != "10" || args?.getOrNull(1) != "synthetic-account") return result
            result.addRow(columns.map { if (it == ContactsContract.RawContacts.VERSION) version else 10L }.toTypedArray())
            return result
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            version = 23
            dirty = dirtyAfterWrite
            return operations.map {
                if (it.isInsert) ContentProviderResult(Uri.parse("content://com.android.contacts/data/11")) else ContentProviderResult(1)
            }.toTypedArray()
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No real provider writes")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No real provider writes")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No real provider writes")
    }
}
