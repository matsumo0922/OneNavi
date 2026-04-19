package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.content.pm.PackageManager
import io.github.aakira.napier.Napier
import java.security.MessageDigest

/**
 * 実行中 APK の署名 SHA-1 フィンガープリントを取得する。
 *
 * Google Cloud Text-to-Speech の `X-Android-Cert` ヘッダはコロン区切りを受け付けず、40 文字連結の
 * 大文字 hex のみで認証が通る (Maps Platform は `AA:BB:...` 形式でも通るが、TTS は違う挙動)。
 * 取得できない場合は空文字。
 */
internal fun Context.fetchSigningCertSha1(): String {
    return runCatching {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = info.signingInfo ?: return ""
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        val signature = signatures.firstOrNull() ?: return ""
        val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
        digest.joinToString(separator = "") { byte ->
            "%02X".format(byte)
        }
    }.getOrElse { error ->
        Napier.w(tag = "AndroidSigningCert", throwable = error) {
            "Failed to compute signing cert SHA-1"
        }
        ""
    }
}
