package io.github.pandaakira.apppanda.ui.components

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Pide confirmar identidad (huella o, como respaldo, PIN/patrón del
 * dispositivo) antes de una acción sensible. `onApproved` solo se invoca si la
 * autenticación tuvo éxito; cualquier cancelación o error llama a `onDenied`.
 *
 * Si el dispositivo no tiene ningún método de bloqueo configurado, no podemos
 * exigir biometría — aprobamos directo (el factor de seguridad real es ya
 * tener la app y el token).
 */
fun confirmIdentity(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    description: String,
    onApproved: () -> Unit,
    onDenied: () -> Unit,
) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowed) !=
        BiometricManager.BIOMETRIC_SUCCESS
    ) {
        onApproved()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) = onApproved()

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence,
            ) = onDenied()
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setDescription(description)
        .setAllowedAuthenticators(allowed)
        .build()
    prompt.authenticate(info)
}
