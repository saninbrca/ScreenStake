package com.finite.focus.util

/**
 * Scrubs personal data and credentials out of a log message before it is forwarded to Sentry.
 *
 * Pure Kotlin on purpose (no Android, no Sentry types) so it can be unit-tested directly —
 * [com.finite.focus.util.LogRedactionTest] — instead of only being exercisable through a real
 * `SentryAndroid.init`. Same reasoning as [SentryEventFilter], which it sits next to in
 * `DetoxApplication.onCreate`. Wired up in [com.finite.focus.util.SentryTimberTree].
 *
 * ## Why this exists at all
 *
 * Until the release Timber tree was planted, Timber had zero trees in the Play build, so every
 * `Timber.e`/`Timber.w` in the app wrote nowhere. Forwarding them to Sentry makes production
 * failures visible — and simultaneously turns every interpolated value in a log message into
 * something that leaves the device to a third-party processor. Sentry is an external processor
 * under GDPR and the onboarding copy promises addresses are not retained, so an email address
 * or an ID token in a breadcrumb is a data-protection problem regardless of log level.
 *
 * ## Belt AND braces — this is the braces
 *
 * The call sites in the auth path were fixed at source (they log `uid` instead of `email`), which
 * is the real fix: it removes the cause, and it also cleans up Logcat in debug builds where no
 * scrubber runs. This object is the safety net for the log lines that do not exist yet — code
 * written next month by someone who does not know the release tree is listening. Neither half
 * makes the other redundant:
 *
 *  - a regex can miss an unusual-but-valid address format, and it cannot clean a debug Logcat;
 *  - a source fix cannot cover a future call site.
 *
 * ## Scope: the formatted message only
 *
 * [redact] operates on the message string Timber hands the tree. It deliberately does NOT try to
 * rewrite a [Throwable]'s own message or stack trace — those are captured by Sentry's exception
 * pipeline, not by this one, and mutating exceptions to sanitise them is worse than the problem.
 * In practice Firebase Auth exception messages describe the failure class ("The email address is
 * already in use by another account.") without echoing the address back.
 */
object LogRedaction {

    private const val EMAIL_MASK = "<email>"
    private const val TOKEN_MASK = "<token>"
    private const val VALUE_MASK = "<redacted>"

    /**
     * Keys whose *value* is a credential, in any `key=value` / `key: value` shape.
     *
     * Matched case-insensitively, so `idToken`, `id_token` and `ID_TOKEN` are all covered by the
     * two spellings listed. The value is consumed up to the next whitespace or structural
     * character, or as a whole quoted string, so `password="a b c"` redacts fully.
     *
     * The `Bearer|Basic|Token` alternative must stay FIRST among the unquoted forms and must not
     * be dropped. Without it `Authorization: Bearer eyJhbG...` matched the key, consumed only the
     * word `Bearer` as its value, and left the credential itself sitting in the message — a
     * message that *looks* redacted while still carrying the token. Alternation is first-match,
     * so the scheme-aware branch has to be tried before the bare run-of-characters branch.
     *
     * It is not redundant with [BEARER]: that rule runs later and anchors on the word `Bearer`,
     * which this rule would already have consumed. Pinned by the deliberately loud
     * `DO NOT DELETE - scheme branch must stay first in the alternation` test in
     * `LogRedactionTest` — read it before touching the order of these alternatives.
     */
    private val CREDENTIAL_KEY_VALUE = Regex(
        """\b(idToken|id_token|accessToken|access_token|refreshToken|refresh_token|""" +
            """customToken|custom_token|authToken|auth_token|sessionToken|session_token|""" +
            """clientSecret|client_secret|apiKey|api_key|authorization|password|passwd|pwd|secret)""" +
            """(\s*[=:]\s*)""" +
            """(?:"[^"]*"|'[^']*'|(?:Bearer|Basic|Token)\s+[^\s,;)\]}]+|[^\s,;)\]}]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * JSON Web Tokens — Firebase ID tokens, Google ID tokens, and anything else JWT-shaped.
     *
     * A JWT header is always base64url-encoded JSON starting `{"`, which encodes to the literal
     * prefix `eyJ`. Anchoring on that keeps the pattern from matching ordinary dotted identifiers
     * (package names, class names, hostnames) while still catching the two- and three-segment
     * forms. The signature segment is allowed to be empty (`alg: none` tokens).
     */
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]*)?""")

    /**
     * Google OAuth 2.0 credentials by their documented prefixes: `ya29.` access tokens, `1//`
     * refresh tokens, and `AIza` API keys. These appear in GMS / Firebase failure paths, which
     * is exactly the code the auth screen exercises.
     */
    private val GOOGLE_OAUTH = Regex("""\bya29\.[A-Za-z0-9._-]+|(?<![A-Za-z0-9])1//[A-Za-z0-9._-]{10,}|\bAIza[A-Za-z0-9_-]{20,}""")

    /** `Authorization: Bearer <token>` headers, whatever the token format behind them is. */
    private val BEARER = Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)

    /**
     * Email addresses. Intentionally permissive on the local part (RFC 5322 allows far more than
     * the common subset) and requires a dotted domain with a 2+ character TLD, which is what keeps
     * it from matching Kotlin annotations or `user@host` style fragments without a TLD.
     */
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")

    /**
     * Returns [message] with every recognised identifier or credential replaced by a placeholder.
     *
     * Order matters. `key=value` runs first so that `idToken=eyJhbG...` becomes
     * `idToken=<redacted>` (the key survives, which is the diagnostically useful half) rather than
     * being half-eaten by the JWT rule. Email runs last because a token can contain an
     * `@`-free run that the email rule would never touch anyway, and running it early would
     * fragment a longer credential match.
     *
     * Returns the input unchanged when nothing matches, so the common case allocates nothing new.
     */
    fun redact(message: String): String {
        if (message.isEmpty()) return message
        var out = message
        out = CREDENTIAL_KEY_VALUE.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}$VALUE_MASK" }
        out = JWT.replace(out, TOKEN_MASK)
        out = GOOGLE_OAUTH.replace(out, TOKEN_MASK)
        out = BEARER.replace(out, "Bearer $TOKEN_MASK")
        out = EMAIL.replace(out, EMAIL_MASK)
        return out
    }
}
