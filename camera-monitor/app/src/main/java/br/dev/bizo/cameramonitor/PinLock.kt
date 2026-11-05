package br.dev.bizo.cameramonitor

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN local que protege as ações de desligar a vigilância (parar a câmera, sair da conta).
 * Guarda só o hash do PIN. Depois de várias tentativas erradas, bloqueia por um tempo.
 */
object PinLock {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    private const val PREFS = "pin_lock"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"
    private const val KEY_FAILURES = "failures"
    private const val KEY_LOCKED_UNTIL = "lockedUntil"
    private const val MAX_FAILURES = 5
    private const val LOCKOUT_MS = 60_000L
    private const val ITERATIONS = 20_000

    enum class Result { OK, WRONG, LOCKED }

    fun isSet(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    fun isValidFormat(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    fun set(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash(pin, salt)))
            .remove(KEY_FAILURES)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Confere o PIN. Depois de [MAX_FAILURES] erros, recusa tudo por [LOCKOUT_MS]. */
    fun check(context: Context, pin: String): Result {
        val prefs = prefs(context)
        if (lockedForMs(context) > 0) return Result.LOCKED
        val salt = decode(prefs.getString(KEY_SALT, null) ?: return Result.OK)
        val expected = decode(prefs.getString(KEY_HASH, null) ?: return Result.OK)
        if (MessageDigest.isEqual(hash(pin, salt), expected)) {
            prefs.edit().remove(KEY_FAILURES).remove(KEY_LOCKED_UNTIL).apply()
            return Result.OK
        }
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        if (failures >= MAX_FAILURES) {
            prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCKED_UNTIL, now() + LOCKOUT_MS).apply()
            return Result.LOCKED
        }
        prefs.edit().putInt(KEY_FAILURES, failures).apply()
        return Result.WRONG
    }

    /** Tempo restante de bloqueio. Usa o relógio desde o boot, que não muda com a hora do sistema. */
    fun lockedForMs(context: Context): Long {
        val until = prefs(context).getLong(KEY_LOCKED_UNTIL, 0L)
        val remaining = until - now()
        // Depois de reiniciar, o relógio volta a zero; limita para o bloqueio não ficar eterno.
        return if (remaining in 1..LOCKOUT_MS) remaining else 0L
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
