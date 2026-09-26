package com.mcp.injector.apk

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date

/**
 * APK 重签名器（任务 C）。
 *
 * 对齐反编译产物 ApkSigner：首次使用时在 filesDir/signing 下生成并持久化
 * 2048 位 RSA 密钥对 + 自签 X.509 证书（CN=MCP Injector），之后复用；
 * 用 com.android.apksig 输出 v1+v2+v3 签名（minSdk 26，v3 可用）。
 *
 * 依赖：`com.android.tools.build:apksig` 与 `org.bouncycastle:*`（见偏差说明，
 * 需在 build.gradle.kts 补充依赖；本环境不编译）。
 */
class ApkSigner(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "signing").apply { mkdirs() }

    private fun keyFile(): File = File(dir, "signing.key")

    private fun certFile(): File = File(dir, "signing.der")

    /** 重签名输入 APK 到输出 APK；密钥缺失或不成对时自动生成并持久化。 */
    fun sign(input: File, output: File) {
        var key = loadPrivateKey()
        var cert = loadCertificate()
        // 自愈：私钥/证书缺失，或二者不匹配（例如上次写入时中途被杀，留下
        // 「有 key 无 cert」或不成对的残留）时，清理旧材料并重新生成密钥对，
        // 避免永久陷入 "签名证书缺失" 的不可恢复状态。
        if (key == null || cert == null || !matches(key, cert)) {
            deleteSigningMaterial()
            key = generateAndStore()
            cert = loadCertificate()
        }
        val signingKey = key ?: throw IllegalStateException("签名私钥缺失")
        val signingCert = cert ?: throw IllegalStateException("签名证书缺失")
        ApkSigner.Builder(
            listOf(
                ApkSigner.SignerConfig.Builder("mcp-injector", signingKey, listOf(signingCert)).build(),
            ),
        )
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .setInputApk(input)
            .setOutputApk(output)
            .build()
            .sign()
    }

    private fun loadPrivateKey(): PrivateKey? {
        val f = keyFile()
        if (!f.exists()) return null
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(f.readBytes()))
        } catch (t: Throwable) {
            null
        }
    }

    private fun loadCertificate(): X509Certificate? {
        val f = certFile()
        if (!f.exists()) return null
        return try {
            FileInputStream(f).use { input ->
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(input) as X509Certificate
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun generateAndStore(): PrivateKey {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), SecureRandom())
        val pair = generator.generateKeyPair()

        val notBefore = Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 30L * 365 * 24 * 60 * 60 * 1000L)
        val subject = X500Name("CN=MCP Injector,O=MCP Injector,C=CN")
        val cert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger(64, SecureRandom()),
                notBefore,
                notAfter,
                subject,
                pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)),
        )

        // 先写临时文件再原子 rename，保证 key/cert 以「要么都存在、要么都不存在」
        // 的方式落地；中途崩溃也不会留下半截文件。若仍出现不成对残留，sign()
        // 的自愈逻辑会在下次调用时清理并重建。
        writeAtomically(keyFile(), pair.private.encoded)
        writeAtomically(certFile(), cert.encoded)
        return pair.private
    }

    /** 判断私钥与证书是否配套（比较 RSA 模数）。 */
    private fun matches(key: PrivateKey, cert: X509Certificate): Boolean {
        return try {
            val priv = key as? RSAPrivateKey ?: return false
            val pub = cert.publicKey as? RSAPublicKey ?: return false
            priv.modulus == pub.modulus
        } catch (t: Throwable) {
            false
        }
    }

    /** 删除已持久化的签名材料（key 与 cert）。 */
    private fun deleteSigningMaterial() {
        keyFile().delete()
        certFile().delete()
    }

    /** 同目录内「先写临时文件、再 delete 目标、最后 rename」的原子写入。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // 极少数文件系统上 renameTo 可能失败，退化为复制以避免残留 tmp。
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
