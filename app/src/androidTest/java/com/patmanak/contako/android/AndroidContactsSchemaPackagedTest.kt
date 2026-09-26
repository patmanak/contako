package com.patmanak.contako.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import com.patmanak.contako.android.account.ContakoAccountAuthenticatorService
import com.patmanak.contako.android.sync.ContakoContactsSyncAdapterService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AndroidContactsSchemaPackagedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun packagedServicesAreExportedOnlyThroughTheirSystemBinderPermissions() {
        val packageManager = context.packageManager
        val authenticator = packageManager.serviceInfo(ContakoAccountAuthenticatorService::class.java)
        assertTrue(authenticator.exported)
        assertEquals(BIND_ACCOUNT_AUTHENTICATOR, authenticator.permission)
        assertEquals(R.xml.account_authenticator, authenticator.metaData.getInt("android.accounts.AccountAuthenticator"))
        assertFalse(authenticator.metaData.containsKey("android.provider.CONTACTS_STRUCTURE"))

        val syncAdapter = packageManager.serviceInfo(ContakoContactsSyncAdapterService::class.java)
        assertTrue(syncAdapter.exported)
        assertEquals(BIND_SYNC_ADAPTER, syncAdapter.permission)
        assertEquals(R.xml.contacts_sync_adapter, syncAdapter.metaData.getInt("android.content.SyncAdapter"))
        assertEquals(R.xml.contacts, syncAdapter.metaData.getInt("android.provider.CONTACTS_STRUCTURE"))

        @Suppress("DEPRECATION")
        val requested = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
        assertTrue(
            requested.containsAll(
                setOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS,
                    Manifest.permission.READ_SYNC_SETTINGS,
                    Manifest.permission.WRITE_SYNC_SETTINGS,
                ),
            ),
        )
        assertFalse(requested.contains("android.permission.GET_ACCOUNTS"))
        assertFalse(requested.contains("android.permission.AUTHENTICATE_ACCOUNTS"))
        assertFalse(requested.contains(BIND_ACCOUNT_AUTHENTICATOR))
        assertFalse(requested.contains(BIND_SYNC_ADAPTER))
    }

    @Test
    fun packagedMetadataUsesThePermanentAccountIdentityAndContactsAuthority() {
        val authenticator = context.resources.getXml(R.xml.account_authenticator).rootAttributes()
        assertEquals("com.patmanak.contako", authenticator.android("accountType"))
        assertTrue(authenticator.android("icon").isNotBlank())
        assertTrue(authenticator.android("smallIcon").isNotBlank())
        assertTrue(authenticator.android("label").isNotBlank())

        val syncAdapter = context.resources.getXml(R.xml.contacts_sync_adapter).rootAttributes()
        assertEquals("com.patmanak.contako", syncAdapter.android("accountType"))
        assertEquals("com.android.contacts", syncAdapter.android("contentAuthority"))
        assertEquals("true", syncAdapter.android("supportsUploading"))
        assertEquals("true", syncAdapter.android("userVisible"))
        assertEquals("true", syncAdapter.android("isAlwaysSyncable"))
        assertEquals("false", syncAdapter.android("allowParallelSyncs"))
    }

    @Test
    fun packagedContactsSchemaContainsEveryApprovedWritableKind() {
        val kinds = context.resources.getXml(R.xml.contacts).parseKinds()
        assertEquals(
            setOf(
                "name",
                "phone",
                "email",
                "photo",
                "organization",
                "nickname",
                "note",
                "group_membership",
                "postal",
                "website",
                "event",
                "relationship",
            ),
            kinds.keys,
        )
        setOf("name", "photo", "organization", "nickname", "note", "group_membership")
            .forEach { kind -> assertEquals("1", kinds.getValue(kind).attributes["maxOccurs"]) }
        setOf(
            "supportsDisplayName",
            "supportsFamilyName",
            "supportsPrefix",
            "supportsMiddleName",
            "supportsSuffix",
            "supportsPhoneticFamilyName",
            "supportsPhoneticMiddleName",
            "supportsPhoneticGivenName",
        ).forEach { attribute ->
            assertEquals("true", kinds.getValue("name").attributes[attribute])
        }
        assertEquals(
            setOf("birthday", "anniversary", "other", "custom"),
            kinds.getValue("event").types.map { it.getValue("type") }.toSet(),
        )
        kinds.getValue("event").types.forEach { type -> assertEquals("true", type["yearOptional"]) }
        assertTrue(kinds.getValue("relationship").types.any { it["type"] == "custom" })
        assertTrue(kinds.getValue("phone").types.any { it["type"] == "custom" })
        assertTrue(kinds.getValue("email").types.any { it["type"] == "custom" })
        assertTrue(kinds.getValue("postal").types.any { it["type"] == "custom" })
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.serviceInfo(serviceClass: Class<*>) = getServiceInfo(
        ComponentName(context, serviceClass),
        PackageManager.GET_META_DATA,
    )

    private fun XmlPullParser.rootAttributes(): Attributes {
        while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) next()
        check(eventType == XmlPullParser.START_TAG)
        return Attributes(
            (0 until attributeCount).associate {
                AttributeKey(getAttributeNamespace(it), getAttributeName(it)) to getAttributeValue(it)
            },
        )
    }

    private fun XmlPullParser.parseKinds(): Map<String, ParsedKind> {
        val result = linkedMapOf<String, ParsedKind>()
        var currentKind: ParsedKind? = null
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && name == "DataKind") {
                val attributes = attributesWithoutNamespace()
                currentKind = ParsedKind(attributes, mutableListOf())
                result[attributes.getValue("kind")] = currentKind
            } else if (eventType == XmlPullParser.START_TAG && name == "Type") {
                currentKind?.types?.add(attributesWithoutNamespace())
            } else if (eventType == XmlPullParser.END_TAG && name == "DataKind") {
                currentKind = null
            }
            next()
        }
        return result
    }

    private fun XmlPullParser.attributesWithoutNamespace(): Map<String, String> =
        (0 until attributeCount).associate { getAttributeName(it) to getAttributeValue(it) }

    private data class ParsedKind(
        val attributes: Map<String, String>,
        val types: MutableList<Map<String, String>>,
    )

    private data class AttributeKey(val namespace: String?, val name: String)

    private data class Attributes(val values: Map<AttributeKey, String>) {
        fun android(name: String): String = values.getValue(AttributeKey(ANDROID_NAMESPACE, name))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val BIND_ACCOUNT_AUTHENTICATOR = "android.permission.BIND_ACCOUNT_AUTHENTICATOR"
        const val BIND_SYNC_ADAPTER = "android.permission.BIND_SYNC_ADAPTER"
    }
}
