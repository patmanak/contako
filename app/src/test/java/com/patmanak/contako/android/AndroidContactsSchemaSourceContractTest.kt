package com.patmanak.contako.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidContactsSchemaSourceContractTest {
    @Test
    fun manifestExposesOnlySystemBinderProtectedFrameworkServices() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val root = manifest.documentElement
        val permissions = root.children("uses-permission")
            .map { it.androidAttribute("name") }
            .toSet()

        assertTrue(
            permissions.containsAll(
                setOf(
                    "android.permission.READ_CONTACTS",
                    "android.permission.WRITE_CONTACTS",
                    "android.permission.READ_SYNC_SETTINGS",
                    "android.permission.WRITE_SYNC_SETTINGS",
                ),
            ),
        )
        assertFalse(permissions.contains("android.permission.GET_ACCOUNTS"))
        assertFalse(permissions.contains("android.permission.AUTHENTICATE_ACCOUNTS"))

        val services = root.children("application").single().children("service")
        val authenticator = services.single {
            it.androidAttribute("name") == ".android.account.ContakoAccountAuthenticatorService"
        }
        authenticator.assertBinderBoundary(
            permission = "android.permission.BIND_ACCOUNT_AUTHENTICATOR",
            action = "android.accounts.AccountAuthenticator",
            metadata = mapOf(
                "android.accounts.AccountAuthenticator" to "@xml/account_authenticator",
            ),
        )

        val syncAdapter = services.single {
            it.androidAttribute("name") == ".android.sync.ContakoContactsSyncAdapterService"
        }
        syncAdapter.assertBinderBoundary(
            permission = "android.permission.BIND_SYNC_ADAPTER",
            action = "android.content.SyncAdapter",
            metadata = mapOf(
                "android.content.SyncAdapter" to "@xml/contacts_sync_adapter",
                "android.provider.CONTACTS_STRUCTURE" to "@xml/contacts",
            ),
        )
    }

    @Test
    fun accountAndSyncMetadataShareTheBuildIsolatedAndroidIdentity() {
        val authenticator = xml("src/main/res/xml/account_authenticator.xml").documentElement
        assertEquals("account-authenticator", authenticator.tagName)
        assertEquals(ACCOUNT_TYPE_RESOURCE, authenticator.androidAttribute("accountType"))
        assertTrue(authenticator.androidAttribute("icon").isNotBlank())
        assertTrue(authenticator.androidAttribute("smallIcon").isNotBlank())
        assertTrue(authenticator.androidAttribute("label").isNotBlank())

        val sync = xml("src/main/res/xml/contacts_sync_adapter.xml").documentElement
        assertEquals("sync-adapter", sync.tagName)
        assertEquals(ACCOUNT_TYPE_RESOURCE, sync.androidAttribute("accountType"))
        assertEquals(CONTACTS_AUTHORITY, sync.androidAttribute("contentAuthority"))
        assertEquals("true", sync.androidAttribute("supportsUploading"))
        assertEquals("true", sync.androidAttribute("userVisible"))
        assertEquals("true", sync.androidAttribute("isAlwaysSyncable"))
        assertEquals("false", sync.androidAttribute("allowParallelSyncs"))

        val build = projectFile("build.gradle.kts").readText()
        assertTrue(build.contains("resValue(\"string\", \"contako_account_type\", contakoApplicationId)"))
        assertTrue(build.contains("buildConfigField(\"String\", \"ANDROID_ACCOUNT_TYPE\""))
    }

    @Test
    fun contactsEditorSchemaDeclaresTheApprovedWritableStandardKinds() {
        val kinds = xml("src/main/res/xml/contacts.xml")
            .getElementsByTagName("DataKind")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            .associateBy { it.getAttribute("kind") }

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
            .forEach { kind -> assertEquals("1", kinds.getValue(kind).getAttribute("maxOccurs")) }

        val name = kinds.getValue("name")
        setOf(
            "supportsDisplayName",
            "supportsFamilyName",
            "supportsPrefix",
            "supportsMiddleName",
            "supportsSuffix",
            "supportsPhoneticFamilyName",
            "supportsPhoneticMiddleName",
            "supportsPhoneticGivenName",
        ).forEach { attribute -> assertEquals("true", name.getAttribute(attribute)) }

        assertEquals(PHONE_TYPES, kinds.getValue("phone").types())
        assertEquals(setOf("home", "work", "other", "mobile", "custom"), kinds.getValue("email").types())
        assertEquals(setOf("home", "work", "other", "custom"), kinds.getValue("postal").types())
        assertEquals("true", kinds.getValue("postal").getAttribute("needsStructured"))
        assertEquals(RELATIONSHIP_TYPES, kinds.getValue("relationship").types())

        val event = kinds.getValue("event")
        assertEquals("false", event.getAttribute("dateWithTime"))
        assertEquals(setOf("birthday", "anniversary", "other", "custom"), event.types())
        event.children("Type").forEach { type ->
            assertEquals("true", type.getAttribute("yearOptional"))
            if (type.getAttribute("type") in setOf("birthday", "anniversary")) {
                assertEquals("1", type.getAttribute("maxOccurs"))
            }
        }
    }

    @Test
    fun frameworkSyncBinderDelegatesWithoutOwningProviderOrNetworkAlgorithms() {
        val source = projectFile(
            "src/main/java/com/patmanak/contako/android/sync/ContakoContactsSyncAdapterService.kt",
        ).readText()
        assertTrue(source.contains("runner.request("))
        assertTrue(source.contains("runner.awaitIdle()"))
        assertTrue(source.contains("SyncPassOutcome.RETRY_WAITING"))
        listOf(
            "com.patmanak.contako.data.local",
            "com.patmanak.contako.data.proton",
            "ContentResolver",
            "RoomDatabase",
            "Retrofit",
            "OkHttp",
        ).forEach { forbidden -> assertFalse(source.contains(forbidden)) }
    }

    private fun Element.assertBinderBoundary(
        permission: String,
        action: String,
        metadata: Map<String, String>,
    ) {
        assertEquals("true", androidAttribute("exported"))
        assertEquals(permission, androidAttribute("permission"))
        val actions = children("intent-filter")
            .flatMap { it.children("action") }
            .map { it.androidAttribute("name") }
            .toSet()
        assertEquals(setOf(action), actions)
        val actualMetadata = children("meta-data").associate {
            it.androidAttribute("name") to it.androidAttribute("resource")
        }
        assertEquals(metadata, actualMetadata)
    }

    private fun Element.types(): Set<String> = children("Type")
        .map { it.getAttribute("type") }
        .toSet()

    private fun Element.children(tagName: String): List<Element> {
        val result = mutableListOf<Element>()
        for (index in 0 until childNodes.length) {
            val child = childNodes.item(index)
            if (child is Element && child.tagName == tagName) result += child
        }
        return result
    }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private fun xml(relativePath: String) = DocumentBuilderFactory.newInstance().run {
        isNamespaceAware = true
        newDocumentBuilder().parse(projectFile(relativePath)).also { assertNotNull(it.documentElement) }
    }

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app", relativePath), File("..", relativePath))
        return candidates.firstOrNull(File::exists) ?: error("Missing project file: $relativePath")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val ACCOUNT_TYPE_RESOURCE = "@string/contako_account_type"
        const val CONTACTS_AUTHORITY = "com.android.contacts"

        val PHONE_TYPES = setOf(
            "mobile",
            "home",
            "work",
            "fax_work",
            "fax_home",
            "pager",
            "other",
            "custom",
            "callback",
            "car",
            "company_main",
            "isdn",
            "main",
            "other_fax",
            "radio",
            "telex",
            "tty_tdd",
            "work_mobile",
            "work_pager",
            "assistant",
            "mms",
        )
        val RELATIONSHIP_TYPES = setOf(
            "assistant",
            "brother",
            "child",
            "domestic_partner",
            "father",
            "friend",
            "manager",
            "mother",
            "parent",
            "partner",
            "referred_by",
            "relative",
            "sister",
            "spouse",
            "custom",
        )
    }
}
