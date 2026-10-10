package com.lifeline.mesh.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A profile stored on this phone only (CLAUDE.md Phase 10: offline profile, no server login).
 * The password is never stored, only a salted PBKDF2 hash. The origin ID stays per install, not per profile.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String = "",
    val phone: String = "",
    val role: String = Role.NEEDS_HELP.name,
    val travel: String? = null,
    val salt: String,
    val hash: String,
) {
    val info: ProfileInfo get() = ProfileInfo(id, name, runCatching { Role.valueOf(role) }.getOrDefault(Role.NEEDS_HELP))
}

/** What the UI may see of a profile: no salt, no hash. */
data class ProfileInfo(val id: String, val name: String, val role: Role)

object Profiles {
    private val json = Json { ignoreUnknownKeys = true }

    /** Unreadable or missing data is an empty list, never a crash. */
    fun decode(text: String?): List<Profile> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<Profile>>(text) }.getOrDefault(emptyList())

    fun encode(list: List<Profile>): String = json.encodeToString(list)
}

object Passwords {
    const val MIN_LENGTH = 4
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time compare of the stored hash with the hash of [password]. */
    fun matches(password: String, profile: Profile): Boolean = runCatching {
        val salt = Base64.getDecoder().decode(profile.salt)
        MessageDigest.isEqual(hash(password, salt), Base64.getDecoder().decode(profile.hash))
    }.getOrDefault(false)

    /** Salt and hash, Base64, ready for a [Profile]. */
    fun protect(password: String): Pair<String, String> {
        val salt = newSalt()
        val enc = Base64.getEncoder()
        return enc.encodeToString(salt) to enc.encodeToString(hash(password, salt))
    }
}
