package com.ctfly.app

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.biometric.BiometricManager
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

data class StaffBiometricRequest(
    val owner: String,
    val enabling: Boolean,
    val deviceId: String,
    val signature: Signature,
    val payload: String,
    val publicKey: String = "",
    val staffName: String = "",
    val challengeId: String = "",
    val generation: Int = 0,
    var started: Boolean = false,
)

/** No fingerprint or account password is stored. Android authorizes each cryptographic use. */
class StaffBiometrics(private val context: Context) {
    private val prefs = context.getSharedPreferences("ctfly_staff_biometrics", Context.MODE_PRIVATE)
    private val alias = "ctfly_staff_device_signing_key"

    val owner
        get() = prefs.getString("owner", "").orEmpty()

    val deviceId
        get() = prefs.getString("device_id", "").orEmpty()

    val email
        get() = prefs.getString("email", "").orEmpty()

    val staffName
        get() = prefs.getString("staff_name", "").orEmpty()

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun phone(): Boolean {
        val pm = context.packageManager
        val minimumDp =
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds =
                    context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                (minOf(bounds.width(), bounds.height()) / context.resources.displayMetrics.density)
                    .toInt()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                context
                    .getSystemService(DisplayManager::class.java)
                    .getDisplay(0)
                    ?.getRealMetrics(metrics)
                (minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density).toInt()
            }
        return SessionRules.fingerprintPhone(
            minimumDp,
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            pm.hasSystemFeature("android.hardware.sensor.hinge_angle"),
        )
    }

    fun available() =
        BuildConfig.STAFF_APP &&
            phone() &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) &&
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS

    fun offered(owner: String) = prefs.getBoolean("offered.$owner", false)

    fun decline(owner: String) {
        prefs.edit().putBoolean("offered.$owner", true).apply()
    }

    fun enabled(owner: String) =
        owner.isNotBlank() &&
            this.owner == owner &&
            deviceId.isNotBlank() &&
            runCatching { store().containsAlias(alias) }.getOrDefault(false)

    fun begin(
        owner: String,
        enabling: Boolean,
        staffName: String = "",
        challenge: org.json.JSONObject = org.json.JSONObject(),
        generation: Int = 0,
    ): StaffBiometricRequest {
        check(available()) { "Fingerprint is unavailable. Use your staff account password." }
        var publicKey = ""
        var id = deviceId
        if (enabling) {
            if (prefs.getString("registration_owner", "") != owner || id.isBlank())
                id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).putString("registration_owner", owner).commit()
            val builder =
                KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30)
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            else {
                @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            val pair =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                    .apply { initialize(builder.build()) }
                    .generateKeyPair()
            publicKey = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        } else {
            check(enabled(owner)) { "Sign in with your staff account password." }
        }
        val signature =
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(store().getKey(alias, null) as PrivateKey)
            }
        val payload =
            if (enabling) "ctfly-enroll:$owner:$id"
            else "ctfly-login:$id:${challenge.text("challenge_id")}:${challenge.text("nonce")}"
        return StaffBiometricRequest(
            owner,
            enabling,
            id,
            signature,
            payload,
            publicKey,
            staffName,
            challenge.text("challenge_id"),
            generation,
        )
    }

    fun sign(request: StaffBiometricRequest, signature: Signature): String {
        check(signature === request.signature)
        signature.update(request.payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun remember(request: StaffBiometricRequest, email: String) {
        check(request.enabling)
        prefs
            .edit()
            .putString("owner", request.owner)
            .putString("email", email)
            .putString("device_id", request.deviceId)
            .putString("staff_name", request.staffName)
            .putBoolean("offered.${request.owner}", true)
            .commit()
    }

    fun disable() {
        prefs
            .edit()
            .remove("owner")
            .remove("email")
            .remove("staff_name")
            .remove("proof")
            .remove("iv")
            .commit()
        runCatching { store().deleteEntry(alias) }
        runCatching { store().deleteEntry("ctfly_staff_biometric_key") }
    }
}
