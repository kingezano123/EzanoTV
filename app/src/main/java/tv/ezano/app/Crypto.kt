package tv.ezano.app

import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rng = SecureRandom()
    fun salt() = ByteArray(16).also { rng.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun un64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun derive(password: String, salt: ByteArray): ByteArray { val spec=PBEKeySpec(password.toCharArray(),salt,210_000,256); return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() } }
    fun verify(password: String, salt: String, hash: String) = runCatching { MessageDigest.isEqual(derive(password,un64(salt)),un64(hash)) }.getOrDefault(false)
    fun backup(raw: String, passphrase: String): ByteArray {
        require(passphrase.length>=8) { "Use at least 8 characters" }
        val salt=salt(); val iv=ByteArray(12).also { rng.nextBytes(it) }; val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(derive(passphrase,salt),"AES"),GCMParameterSpec(128,iv)); cipher.updateAAD("EZANO1".toByteArray())
        return "EZANO1".toByteArray()+salt+iv+cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
    }
    fun restore(data: ByteArray, passphrase: String): String {
        require(data.size in 50..(128*1024*1024) && String(data.copyOfRange(0,6))=="EZANO1") { "Not an Ezano backup" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(derive(passphrase,data.copyOfRange(6,22)),"AES"),GCMParameterSpec(128,data.copyOfRange(22,34))); cipher.updateAAD("EZANO1".toByteArray())
        return String(cipher.doFinal(data.copyOfRange(34,data.size)),Charsets.UTF_8)
    }
}
