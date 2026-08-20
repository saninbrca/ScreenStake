package com.finite.focus.presentation.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.finite.focus.BuildConfig
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import timber.log.Timber

/** `ApiException` status code Google returns when the user dismisses the account chooser. */
const val GOOGLE_SIGN_IN_CANCELLED = 12501

/**
 * One place that knows how to obtain a Google ID token.
 *
 * Two callers need this: [com.finite.focus.presentation.screens.auth.AuthScreen] to sign in,
 * and the Settings delete-account dialog to RE-authenticate before deletion (Firebase requires
 * a recent credential for account deletion, and a Google-only account has no password to
 * re-authenticate with).
 *
 * It has to be a Composable because `rememberLauncherForActivityResult` is — the Google flow
 * is an Activity result, so a ViewModel cannot start it. Returns a lambda the caller invokes
 * on button press.
 *
 * ## HUAWEI
 * This path needs Google Play Services, which the app otherwise assumes absent. On a device
 * without them the chooser never resolves and [onFailed] fires. That is self-consistent for
 * sign-in — an account that cannot be created on the device cannot need re-auth there either —
 * but see the note at the delete-account branch in `SettingsScreen` for the one case it leaves
 * open.
 *
 * @param onIdToken a token came back — hand it to Firebase.
 * @param onNullToken the account resolved but carried no ID token (misconfigured web client id).
 * @param onCancelled the user dismissed the chooser ([GOOGLE_SIGN_IN_CANCELLED]). Callers MUST
 *   keep this silent — it is a normal user action, not an error worth reporting.
 * @param onFailed any other `ApiException`, carrying its status code.
 */
@Composable
fun rememberGoogleIdTokenLauncher(
    onIdToken: (String) -> Unit,
    onNullToken: () -> Unit,
    onCancelled: () -> Unit,
    onFailed: (statusCode: Int) -> Unit,
): () -> Unit {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account.idToken
            // Neither the address nor the token itself is logged — only whether a token came
            // back, plus its length, which is all the diagnosis ever needed. The release Timber
            // tree forwards WARN/ERROR to Sentry, and an ID token in a breadcrumb would be worse
            // than an email address.
            Timber.d("Google Sign-In: account resolved, idToken=%s",
                if (idToken != null) "present (${idToken.length} chars)" else "NULL")
            if (idToken == null) {
                onNullToken()
            } else {
                onIdToken(idToken)
            }
        } catch (e: ApiException) {
            Timber.w("Google Sign-In: ApiException statusCode=%d", e.statusCode)
            // A BREADCRUMB, not a second event. The caller's error handler is the only place
            // that captures the tagged event — and it is the half that knows 12501 means
            // "user cancelled" and must stay silent. Reporting the ApiException here as well
            // would duplicate every real failure and turn every cancelled sign-in into an
            // issue. The status code still travels with the event: here as the trail leading
            // up to it, there as the searchable tag.
            Sentry.addBreadcrumb(Breadcrumb().apply {
                category = "auth"
                message = "Google Sign-In returned ApiException"
                level = SentryLevel.WARNING
                setData("statusCode", e.statusCode)
            })
            if (e.statusCode == GOOGLE_SIGN_IN_CANCELLED) onCancelled() else onFailed(e.statusCode)
        }
    }

    val signInIntent = remember(context) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso).signInIntent
    }

    return { launcher.launch(signInIntent) }
}
