package com.patmanak.contako.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class FoundationI18nContractTest {
    @Test
    fun contakoAppAndContactsViewModelContainNoHardcodedUserCopy() {
        val allowedLiterals = mapOf(
            "ContakoApp.kt" to setOf(
                "INVALID_EMAIL",
                "MISSING_NAME",
                "Unsupported editable contact kind: \$kind",
                "tel",
                "smsto",
                "mailto",
                "geo:0,0?q=\${Uri.encode(value)}",
                "Unsupported postal address component",
                // Stable navigation/test keys, vCard types, locale pattern and visual flags;
                // none is untranslated prose presented as a user instruction.
                "blocked:\${it.id}", "pending:\${it.id}", "android:\${it.id}", "local-diagnostics", "contact-editor-save-error",
                "__custom__", "", "home", "work", "cell", "other", "mobile",
                "contact_editor_top_save", "contact_editor_bottom_save", "group_editor_top_save", "group_editor_bottom_save",
                "MMMMd", "\$label, \$selectedLabel", "🌐", "🇬🇧", "🇫🇷", "🇩🇪", "🇪🇸", "🇮🇹", "🇳🇱", "🇵🇱", "🇵🇹", "  ",
            ),
            "ContactsViewModel.kt" to setOf(
                // Imported vCard N component keys and separator, not user-facing copy.
                "given", "family", "additional", "prefix", "suffix", ";",
                "",
                "UNCHECKED_CAST",
                "local-v0.1",
                "syncDisposition",
                "LOCAL_ONLY",
                "vcardPref",
            ),
            "DiagnosticSettings.kt" to setOf(
                "text/plain",
                "w",
                "Document provider returned no output stream",
                "contako-diagnostic.txt",
            ),
        )

        allowedLiterals.forEach { (name, allowed) ->
            val source = projectFile("src/main/java/com/patmanak/contako/ui/$name").readText()
            val literals = KOTLIN_STRING.findAll(source)
                .map { it.groupValues[1].replace("\\\"", "\"").replace("\\'", "'") }
                .toSet()
            assertEquals(
                "$name gained a string literal. User-facing copy MUST be an Android resource; " +
                    "only add technical literals to the explicit allowlist.",
                allowed,
                literals,
            )
        }
    }

    @Test
    fun defaultResourcesUseValidNamedStringsAndPlurals() {
        val document = parseXml(projectFile("src/main/res/values/strings.xml"))
        val resources = document.documentElement
        assertEquals("resources", resources.tagName)

        val names = mutableSetOf<String>()
        val children = resources.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            assertTrue("Only string and plurals belong in strings.xml", element.tagName in setOf("string", "plurals"))
            val name = element.getAttribute("name")
            assertTrue("Every resource needs a semantic name", name.matches(RESOURCE_NAME))
            assertTrue("Duplicate resource: $name", names.add(name))
            if (element.tagName == "plurals") {
                val quantities = (0 until element.childNodes.length)
                    .mapNotNull { element.childNodes.item(it) as? Element }
                    .map { it.getAttribute("quantity") }
                    .toSet()
                assertTrue("$name must define one", "one" in quantities)
                assertTrue("$name must define other", "other" in quantities)
                assertTrue("$name must format its count positionally", element.textContent.contains("%1\$d"))
            }
        }

        assertTrue("Foundation extraction must contain a substantial resource catalog", names.size >= 130)
        setOf("groups_member_count", "sync_local_change_count", "groups_contacts_without_email", "dialog_delete_group_body")
            .forEach { assertTrue("Missing plural resource $it", it in names) }
        setOf("field_photo", "field_logo", "field_preferred", "field_make_preferred")
            .forEach { assertTrue("Missing image-family resource $it", it in names) }
    }

    @Test
    fun localeConfigAndDebugPseudolocalesAreExplicit() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val build = projectFile("build.gradle.kts").readText()
        val localeDocument = parseXml(projectFile("src/main/res/xml/locales_config.xml"))
        val locales = localeDocument.getElementsByTagName("locale")

        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
        assertTrue(build.contains("isPseudoLocalesEnabled = true"))
        assertEquals(
            setOf("en", "fr", "de", "es", "it", "nl", "pl", "pt"),
            (0 until locales.length).map { (locales.item(it) as Element).getAttribute("android:name") }.toSet(),
        )
        assertNotNull(localeDocument.documentElement.getAttributeNode("xmlns:android"))
    }

    @Test
    fun runtimeAssetManifestUsesChecksumsOrExplicitNotAvailableFallbacks() {
        val manifest = projectFile("src/main/res/raw/asset_manifest.json").readText()
        assertTrue(manifest.contains("\"id\": \"AST-003\""))
        val mascot = projectFile("src/main/res/drawable-nodpi/contako_mascot_v2.png")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(mascot.readBytes())
            .joinToString("") { "%02X".format(it) }
        assertTrue(manifest.contains(digest))
        setOf("AST-001", "AST-002", "AST-005", "AST-007").forEach { id ->
            val start = manifest.indexOf("\"id\": \"$id\"")
            assertTrue("Missing $id", start >= 0)
            val record = manifest.substring(start, minOf(manifest.length, start + 520))
            // Rasters have an integrity anchor. Missing artwork is explicitly unavailable.
            val shipped = record.contains("\"runtime\"") && record.contains("\"sha256\"")
            assertTrue("$id needs a checksummed asset or NOT AVAILABLE", shipped || record.contains("NOT AVAILABLE"))
            // The text fallback is retained even when artwork ships: D-020 requires the Proton
            // mark to remain removable, and D-071 still expects redrawn masters before V1.
            assertTrue("$id needs a text fallback", record.contains("fallback"))
        }
    }

    @Test
    fun everyTranslationHasExactKeyPlaceholderAndPluralParity() {
        val source = catalog(projectFile("src/main/res/values/strings.xml"))
        setOf("fr", "de", "es", "it", "nl", "pl", "pt").forEach { locale ->
            val translated = catalog(projectFile("src/main/res/values-$locale/strings.xml"))
            assertEquals("$locale keys", source.keys, translated.keys)
            source.forEach { (name, value) ->
                val target = translated.getValue(name)
                assertEquals("$locale/$name placeholders", placeholders(value.text), placeholders(target.text))
                assertEquals("$locale/$name resource type", value.tag, target.tag)
                if (value.tag == "plurals") {
                    assertTrue("$locale/$name needs other", "other" in target.quantities)
                    value.quantityPlaceholders.forEach { (quantity, formats) ->
                        assertEquals("$locale/$name/$quantity placeholders", formats, target.quantityPlaceholders[quantity])
                    }
                    target.quantityPlaceholders.forEach { (quantity, formats) ->
                        assertEquals("$locale/$name/$quantity count placeholder", setOf("%1\$d"), formats)
                    }
                }
            }
        }
    }

    @Test
    fun languageSelectorUsesReviewedLocalizedLabels() {
        val expected = mapOf(
            "fr" to listOf("Système", "Anglais", "Français", "Allemand", "Espagnol", "Italien", "Néerlandais", "Polonais", "Portugais"),
            "de" to listOf("Systemstandard", "Englisch", "Französisch", "Deutsch", "Spanisch", "Italienisch", "Niederländisch", "Polnisch", "Portugiesisch"),
            "es" to listOf("Por defecto del sistema", "Inglés", "Francés", "Alemán", "Español", "Italiano", "Neerlandés", "Polaco", "Portugués"),
            "it" to listOf("Predefinita di sistema", "Inglese", "Francese", "Tedesco", "Spagnolo", "Italiano", "Olandese", "Polacco", "Portoghese"),
            "nl" to listOf("Systeemstandaard", "Engels", "Frans", "Duits", "Spaans", "Italiaans", "Nederlands", "Pools", "Portugees"),
            "pl" to listOf("Domyślny język systemu", "Angielski", "Francuski", "Niemiecki", "Hiszpański", "Włoski", "Niderlandzki", "Polski", "Portugalski"),
            "pt" to listOf("Predefinição do sistema", "Inglês", "Francês", "Alemão", "Espanhol", "Italiano", "Holandês", "Polaco", "Português"),
        )
        val keys = listOf(
            "settings_language_system",
            "language_english",
            "language_french",
            "language_german",
            "language_spanish",
            "language_italian",
            "language_dutch",
            "language_polish",
            "language_portuguese",
        )

        expected.forEach { (locale, labels) ->
            val translated = catalog(projectFile("src/main/res/values-$locale/strings.xml"))
            assertEquals("$locale language selector labels", labels, keys.map { translated.getValue(it).text })
        }
    }

    @Test
    fun externalContactActionsDoNotDependOnPackageVisibilityQueries() {
        val source = projectFile("src/main/java/com/patmanak/contako/ui/ContakoApp.kt").readText()
        val launchSafe = source.substringAfter("private fun android.content.Context.launchSafe(intent: Intent)")
            .substringBefore("private val detailSections")

        assertFalse(launchSafe.contains("resolveActivity"))
        assertTrue(launchSafe.contains("startActivity(intent)"))
        assertTrue(launchSafe.contains("ActivityNotFoundException"))
        assertTrue(launchSafe.contains("SecurityException"))
    }

    @Test
    fun translationsDoNotSilentlyReuseEnglishCopy() {
        val source = catalog(projectFile("src/main/res/values/strings.xml"))
        val identicalEnglishAllowlist = setOf(
            "app_name",
            "auth_welcome_title",
            "about_version",
            "logo_badge",
            "navigation_chevron",
            "field_local_only_badge",
            "auth_password",
            "nav_account",
            "contact_email_section",
            "sync_state_offline",
            "section_logos",
            "field_email",
            "field_role",
            "field_logo_uri",
            "field_logo",
            // "Contacts" is the correct French term and matches what the system contacts app uses.
            // The previous "Personnes-ressources" satisfied this check while being wrong for users.
            "nav_contacts",
        )

        setOf("fr", "de", "es", "it", "nl", "pl", "pt").forEach { locale ->
            val translated = catalog(projectFile("src/main/res/values-$locale/strings.xml"))
            val untranslated = source.keys.filter { name ->
                val validSharedFrench = locale == "fr" && name in setOf("contact_action_message", "contact_type_mobile")
                val validSharedDutch = locale == "nl" && name == "nav_sync_compact"
                // Day/month initials coincide in these languages; this is a format, not English prose.
                val validSharedDateHint = locale in setOf("es", "nl", "pl", "pt") &&
                    name == "date_input_yearless_hint" && translated.getValue(name).text == "DD/MM"
                name !in identicalEnglishAllowlist && !validSharedFrench && !validSharedDutch && !validSharedDateHint &&
                    normalizedText(source.getValue(name)) == normalizedText(translated.getValue(name))
            }
            assertTrue(
                "$locale contains values identical to English outside the proper-name/technical allowlist: $untranslated",
                untranslated.isEmpty(),
            )
        }
    }

    private data class ResourceValue(
        val tag: String,
        val text: String,
        val quantities: Set<String>,
        val quantityPlaceholders: Map<String, Set<String>>,
    )

    private fun catalog(file: File): Map<String, ResourceValue> {
        val root = parseXml(file).documentElement
        return (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }.associate { element ->
            val items = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            element.getAttribute("name") to ResourceValue(
                element.tagName,
                element.textContent,
                items.map { it.getAttribute("quantity") }.toSet(),
                items.associate { it.getAttribute("quantity") to placeholders(it.textContent) },
            )
        }
    }

    private fun placeholders(value: String): Set<String> = FORMAT.findAll(value).map { it.value }.toSet()

    private fun normalizedText(value: ResourceValue): String = value.text.replace(Regex("\\s+"), " ").trim()

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app", relativePath), File("..", relativePath))
        return candidates.firstOrNull(File::exists) ?: error("Missing project file: $relativePath")
    }

    private companion object {
        val KOTLIN_STRING = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")
        val FORMAT = Regex("%(?:\\d+\\$)?[dsf]")
    }
}
