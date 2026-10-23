package com.lshai.cloudscreen

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字段的本地加密（访问密码、会话 Cookie）。
 *
 * 之前这些是明文写在 SharedPreferences 里的：`adb backup`、root、或者把电视盒子转手，
 * 里面的服务器密码就直接露出来了。
 *
 * 用 AndroidKeyStore 里的 AES-256-GCM 密钥：密钥由系统托管（不可导出，不上云），
 * 加密结果存成 `enc:<iv>:<密文>`。解密失败（例如恢复备份到另一台机器、密钥被系统清掉）
 * 一律当作"没有"处理，绝不抛异常 —— 大不了让用户重新填一次密码。
 *
 * 兼容：读到的值不是 `enc:` 开头时原样返回，老版本的明文配置照样能用，
 * 下次写入时自动升级成密文。
 */
object Secret {

    private const val TAG = "cloudscreen_local_key"
    private const val PREFIX = "enc:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private fun key(): SecretKey? = runCatching {
        (keyStore.getEntry(TAG, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    TAG,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    }.getOrNull()

    /** 加密失败时中止保存，绝不将敏感值以明文写入偏好设置。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val k = key() ?: throw IllegalStateException("Android Keystore 不可用，无法安全保存凭据")
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, k)
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** 解密；不是密文就原样返回，解不开就返回空串（当作没配过） */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored     // 老版本明文，直接兼容
        val k = key() ?: return ""
        return runCatching {
            val body = stored.removePrefix(PREFIX)
            val iv = Base64.decode(body.substringBefore(':'), Base64.NO_WRAP)
            val ct = Base64.decode(body.substringAfter(':'), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
