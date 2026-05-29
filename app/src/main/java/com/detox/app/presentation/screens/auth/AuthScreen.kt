package com.detox.app.presentation.screens.auth

import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.BuildConfig
import com.detox.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import timber.log.Timber

private val ErrorRed = Color(0xFFFF3B30)
private val AccentGreen = Color(0xFF00C853)
private val WarnOrange = Color(0xFFFF9500)
private val TextSecondary = Color(0xFF8E8E93)

@Composable
fun AuthScreen(
    initialTab: AuthTab = AuthTab.LOGIN,
    onRegistered: () -> Unit,
    onLoggedIn: () -> Unit,
    onNeedsEmailVerification: (fromRegister: Boolean) -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    LaunchedEffect(initialTab) { viewModel.switchTab(initialTab) }

    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val infoMessage by viewModel.infoMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // React to auth state changes
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is AuthUiState.RegisterSuccess -> onRegistered()
            is AuthUiState.LoginSuccess -> onLoggedIn()
            is AuthUiState.NeedsEmailVerification ->
                onNeedsEmailVerification(state.fromRegister)
            else -> Unit
        }
    }

    // Google Sign-In launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account.idToken
            Timber.d("Google Sign-In: account=%s idToken=%s",
                account.email,
                if (idToken != null) "present (${idToken.length} chars)" else "NULL")
            if (idToken == null) {
                viewModel.onGoogleSignInNullToken()
            } else {
                viewModel.signInWithGoogle(idToken)
            }
        } catch (e: ApiException) {
            Timber.w("Google Sign-In: ApiException statusCode=%d", e.statusCode)
            viewModel.onGoogleSignInApiError(e.statusCode)
        }
    }

    val isLoading = uiState is AuthUiState.Loading
    val errorMessage = (uiState as? AuthUiState.Error)?.message

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.auth_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            TabRow(selectedTabIndex = if (tab == AuthTab.LOGIN) 0 else 1) {
                Tab(
                    selected = tab == AuthTab.LOGIN,
                    onClick = { viewModel.switchTab(AuthTab.LOGIN) },
                    text = { Text(stringResource(R.string.auth_tab_login)) }
                )
                Tab(
                    selected = tab == AuthTab.REGISTER,
                    onClick = { viewModel.switchTab(AuthTab.REGISTER) },
                    text = { Text(stringResource(R.string.auth_tab_register)) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (tab == AuthTab.LOGIN) {
                LoginForm(
                    isLoading = isLoading,
                    error = errorMessage,
                    infoMessage = infoMessage,
                    onLogin = { email, password -> viewModel.login(email, password) },
                    onForgotPassword = { email -> viewModel.sendPasswordReset(email) }
                )
            } else {
                RegisterForm(
                    isLoading = isLoading,
                    error = errorMessage,
                    onRegister = { email, password, confirm, agb, datenschutz, age18 ->
                        viewModel.register(email, password, confirm, agb, datenschutz, age18)
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.auth_or),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                        .requestEmail()
                        .build()
                    googleSignInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.auth_google_button))
            }
        }
    }
}

// ── Inline error text ────────────────────────────────────────────────────────

@Composable
private fun InlineError(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Text(
            text = message ?: "",
            fontSize = 12.sp,
            color = ErrorRed,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun isValidEmail(email: String): Boolean =
    email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

// ── Login form ─────────────────────────────────────────────────────────────────

@Composable
private fun LoginForm(
    isLoading: Boolean,
    error: String?,
    infoMessage: String?,
    onLogin: (email: String, password: String) -> Unit,
    onForgotPassword: (email: String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var emailError by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<String?>(null) }
    val passwordFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val emailEmptyMsg = stringResource(R.string.auth_error_email_empty)
    val emailInvalidMsg = stringResource(R.string.auth_error_email_invalid)
    val passwordEmptyMsg = stringResource(R.string.auth_error_password_empty)

    fun validateAndSubmit() {
        emailError = when {
            email.isBlank() -> emailEmptyMsg
            !isValidEmail(email) -> emailInvalidMsg
            else -> null
        }
        passwordError = if (password.isBlank()) passwordEmptyMsg else null
        if (emailError == null && passwordError == null) {
            keyboard?.hide()
            onLogin(email, password)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; emailError = null },
            label = { Text(stringResource(R.string.auth_email_label)) },
            placeholder = { Text(stringResource(R.string.auth_email_placeholder)) },
            singleLine = true,
            isError = emailError != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        InlineError(emailError)

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; passwordError = null },
            label = { Text(stringResource(R.string.auth_password_label)) },
            singleLine = true,
            isError = passwordError != null,
            visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = { PasswordToggle(passwordVisible) { passwordVisible = !passwordVisible } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { validateAndSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus),
            enabled = !isLoading
        )
        InlineError(passwordError)
        InlineError(error)

        infoMessage?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = AccentGreen,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = stringResource(R.string.auth_forgot_password),
            fontSize = 13.sp,
            color = AccentGreen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clickableNoRipple { onForgotPassword(email) },
            textAlign = TextAlign.End
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = { validateAndSubmit() },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(stringResource(R.string.auth_login_button))
            }
        }
    }
}

// ── Register form ──────────────────────────────────────────────────────────────

@Composable
private fun RegisterForm(
    isLoading: Boolean,
    error: String?,
    onRegister: (
        email: String,
        password: String,
        confirmPassword: String,
        consentAGB: Boolean,
        consentDatenschutz: Boolean,
        consentAge18: Boolean
    ) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var emailError by rememberSaveable { mutableStateOf<String?>(null) }
    var consentAGB by rememberSaveable { mutableStateOf(false) }
    var consentDatenschutz by rememberSaveable { mutableStateOf(false) }
    var consentAge18 by rememberSaveable { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val emailEmptyMsg = stringResource(R.string.auth_error_email_empty)
    val emailInvalidMsg = stringResource(R.string.auth_error_email_invalid)

    val strength = remember(password) { passwordStrength(password) }
    val passwordsMatch = confirmPassword.isEmpty() || password == confirmPassword

    val canSubmit = !isLoading &&
            isValidEmail(email) &&
            password.length >= 8 &&
            confirmPassword.isNotBlank() &&
            password == confirmPassword &&
            consentAGB && consentDatenschutz && consentAge18

    fun submit() {
        emailError = when {
            email.isBlank() -> emailEmptyMsg
            !isValidEmail(email) -> emailInvalidMsg
            else -> null
        }
        if (emailError == null) {
            keyboard?.hide()
            onRegister(email, password, confirmPassword, consentAGB, consentDatenschutz, consentAge18)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; emailError = null },
            label = { Text(stringResource(R.string.auth_email_label)) },
            placeholder = { Text(stringResource(R.string.auth_email_placeholder)) },
            singleLine = true,
            isError = emailError != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        InlineError(emailError)

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.auth_password_label)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = { PasswordToggle(passwordVisible) { passwordVisible = !passwordVisible } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { confirmFocus.requestFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus),
            enabled = !isLoading
        )

        if (password.isNotEmpty()) {
            PasswordStrengthBar(strength)
        } else {
            Text(
                text = stringResource(R.string.auth_password_hint),
                fontSize = 12.sp,
                color = TextSecondary
            )
        }

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text(stringResource(R.string.auth_confirm_password_label)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(confirmFocus),
            enabled = !isLoading,
            isError = !passwordsMatch
        )
        InlineError(if (!passwordsMatch) stringResource(R.string.auth_passwords_mismatch) else null)

        Spacer(modifier = Modifier.height(4.dp))

        ConsentRow(
            checked = consentAGB,
            onCheckedChange = { consentAGB = it },
            label = stringResource(R.string.auth_consent_agb),
            enabled = !isLoading
        )
        ConsentRow(
            checked = consentDatenschutz,
            onCheckedChange = { consentDatenschutz = it },
            label = stringResource(R.string.auth_consent_datenschutz),
            enabled = !isLoading
        )
        ConsentRow(
            checked = consentAge18,
            onCheckedChange = { consentAge18 = it },
            label = stringResource(R.string.auth_consent_age18),
            enabled = !isLoading
        )

        InlineError(error)

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = { submit() },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = canSubmit
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(stringResource(R.string.auth_register_button))
            }
        }
    }
}

// ── Shared composables ───────────────────────────────────────────────────────

@Composable
private fun PasswordToggle(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(
                if (visible) R.string.auth_hide_password else R.string.auth_show_password
            ),
            tint = TextSecondary
        )
    }
}

@Composable
private fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple { if (enabled) onCheckedChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = AccentGreen)
        )
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PasswordStrengthBar(strength: Int) {
    val (label, color) = when (strength) {
        2 -> stringResource(R.string.auth_password_strength_strong) to AccentGreen
        1 -> stringResource(R.string.auth_password_strength_medium) to WarnOrange
        else -> stringResource(R.string.auth_password_strength_weak) to ErrorRed
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        color = if (index <= strength) color else Color(0xFFE0E0E5),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

/** 0 = weak, 1 = medium, 2 = strong. Anything shorter than 8 chars is always weak. */
private fun passwordStrength(password: String): Int {
    if (password.length < 8) return 0
    var score = 0
    if (password.any { it.isDigit() }) score++
    if (password.any { it.isLetter() }) score++
    if (password.any { !it.isLetterOrDigit() }) score++
    if (password.length >= 12) score++
    return if (score >= 3) 2 else 1
}

@Composable
internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
    )
}
