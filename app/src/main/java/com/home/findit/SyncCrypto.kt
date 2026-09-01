package com.home.findit

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 同步传输加密（应用层，家庭局域网级别）：
 * - 密钥 = PBKDF2-SHA256(PIN, 固定盐, 65536 轮) → AES-256
 * - JSON 接口：AES-256-GCM 信封 {v:1, c:base64(iv||密文||tag)}，防篡改 + 认证
 * - 照片：AES-256-CTR 流加密（文件名派生确定性 IV，密文长度 = 明文长度）
 *
 * 安全边界：4 位 PIN 熵有限，本方案防"旁路窃听明文"，不防攻击者持有 PIN 或
 * 对设备物理访问；暴力破解需在线逐次尝试且每次要过 65536 轮 PBKDF2。
 */
object SyncCrypto {

    private const val PBKDF2_ITERATIONS = 65536
    private val SALT = "FindIt::sync::v1".toByteArray(Charsets.UTF_8)
    private val rnd = SecureRandom()

    /** 按 PIN 缓存派生密钥（内存中，不落盘） */
    private val keyCache = HashMap<String, SecretKey>()

    @Synchronized
    fun keyFor(pin: String): SecretKey {
        keyCache[pin]?.let { return it }
        val spec = PBEKeySpec(pin.toCharArray(), SALT, PBKDF2_ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        val sk = SecretKeySpec(key.encoded, "AES")
        if (keyCache.size >= 8) keyCache.clear()
        keyCache[pin] = sk
        return sk
    }

    /** JSON 文本 → 加密信封对象 */
    fun encryptEnvelope(key: SecretKey, plain: String): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("v", 1)
            .put("c", Base64.encodeToString(iv + ct, Base64.NO_WRAP))
    }

    /** 加密信封 → 明文 JSON 文本（失败抛异常，调用方按"PIN 错误/数据无效"处理） */
    fun decryptEnvelope(key: SecretKey, env: JSONObject): String {
        if (env.optInt("v", 0) != 1) throw IllegalArgumentException("不支持的加密信封版本")
        val raw = Base64.decode(env.getString("c"), Base64.NO_WRAP)
        if (raw.size <= 12 + 16) throw IllegalArgumentException("密文长度不足")
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /**
     * 照片流密码：同一文件名 → 同一确定性 IV（文件名唯一，天然避免 IV 复用），
     * CTR 模式密文与明文等长，服务端可先回 Content-Length 再流式传输。
     */
    fun photoCipher(key: SecretKey, mode: Int, filename: String): Cipher {
        val iv = MessageDigest.getInstance("SHA-256")
            .digest(filename.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 16)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(mode, key, IvParameterSpec(iv))
        return c
    }
}
