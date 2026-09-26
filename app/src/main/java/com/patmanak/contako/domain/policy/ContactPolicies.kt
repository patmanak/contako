package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.repository.SaveValidationIssue
import java.text.Normalizer
import java.util.Locale

object ContactValidation {
    fun canCreate(contact: CanonicalContact): Boolean =
        sequenceOf(contact.firstName, contact.lastName, contact.displayName)
            .any { it.isNotBlank() }

    fun actionRequiredReason(contact: CanonicalContact, isNewContact: Boolean): String? {
        val reasons = actionRequiredReasons(contact, isNewContact)
        return if ("MISSING_NAME" in reasons) "MISSING_NAME" else reasons.sorted().firstOrNull()
    }

    fun actionRequiredReasons(contact: CanonicalContact, requiresCreateValidation: Boolean): Set<String> =
        buildSet {
            if (requiresCreateValidation && !canCreate(contact)) add("MISSING_NAME")
            if (contact.values.any { it.kind == com.patmanak.contako.domain.model.ContactValueKind.EMAIL && !isValidEmail(it.value) }) {
                add("INVALID_EMAIL")
            }
        }

    fun canonicalValueIdentityIssues(contact: CanonicalContact): Set<SaveValidationIssue> = buildSet {
        if (contact.accountId.isBlank()) add(SaveValidationIssue.BLANK_ACCOUNT_ID)
        if (contact.values.any { it.id.isBlank() }) add(SaveValidationIssue.BLANK_VALUE_ID)
        if (contact.values.map { it.id }.distinct().size != contact.values.size) {
            add(SaveValidationIssue.DUPLICATE_VALUE_ID)
        }
        if (contact.values.any { it.order < 0 }) add(SaveValidationIssue.NEGATIVE_VALUE_ORDER)
        if (contact.values.groupBy { it.kind }.values.any { family ->
                family.map { it.order }.distinct().size != family.size
            }
        ) {
            add(SaveValidationIssue.DUPLICATE_VALUE_ORDER)
        }
    }

    private fun isValidEmail(value: String): Boolean {
        val trimmed = value.trim()
        val at = trimmed.indexOf('@')
        return at > 0 && at == trimmed.lastIndexOf('@') && at < trimmed.lastIndex
    }
}

object ContactSearch {
    fun matches(contact: CanonicalContact, query: String): Boolean {
        val needle = normalize(query)
        if (needle.isEmpty()) return true
        val searchableValues = buildList {
            add(contact.firstName)
            add(contact.lastName)
            add(contact.displayName)
            contact.values
                .filter { it.kind in SEARCHED_FAMILIES }
                .forEach { add(it.value) }
        }
        return searchableValues.any { normalize(it).contains(needle) }
    }

    fun matchesGroupName(name: String, query: String): Boolean =
        normalize(name).contains(normalize(query))

    internal fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace('ı', 'i')
            .trim()

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val SEARCHED_FAMILIES = setOf(
        com.patmanak.contako.domain.model.ContactValueKind.NICKNAME,
        com.patmanak.contako.domain.model.ContactValueKind.EMAIL,
        com.patmanak.contako.domain.model.ContactValueKind.PHONE,
        com.patmanak.contako.domain.model.ContactValueKind.ORGANIZATION,
    )
}
