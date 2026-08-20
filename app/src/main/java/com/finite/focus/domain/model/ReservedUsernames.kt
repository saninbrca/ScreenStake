package com.finite.focus.domain.model

/**
 * Handles that must never become public @usernames.
 *
 * A username is permanent (`username_subtitle`) and is shown to other players in group
 * challenges, so a system-looking handle like `@admin` or `@support` is an impersonation
 * surface that cannot be undone after the fact.
 *
 * ## This list is duplicated in `firestore.rules`
 * The real enforcement lives in the `usernames/{username}` create rule, which is the same
 * place uniqueness is enforced (`!exists(...)`). This Kotlin copy exists ONLY as a
 * fast-fail UX layer so the picker can say "reserved" before a round trip — it is not a
 * security boundary. **Any change here must be mirrored in `firestore.rules`**, otherwise
 * the client rejects a name the server would still accept (or vice versa).
 */
object ReservedUsernames {

    /**
     * Lowercase-only. Comparison is case-insensitive via [isReserved]; the picker already
     * lowercases input, but a name is checked defensively regardless of how it arrives.
     */
    val ENTRIES: Set<String> = setOf(
        // Roles / authority
        "admin", "administrator", "root", "support", "help", "staff",
        "team", "mod", "moderator", "official", "system",
        // Brand
        "finite", "finiteapp",
        // Technical / parser footguns that read as a real handle in UI
        "api", "null", "undefined",
        // Pronouns & mentions that would be ambiguous in any future @-mention context
        "me", "you", "everyone", "here",
    )

    /** True if [name] is reserved, ignoring case and surrounding whitespace. */
    fun isReserved(name: String): Boolean = name.trim().lowercase() in ENTRIES
}
