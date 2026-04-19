package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.content.pm.PackageManager
import io.github.aakira.napier.Napier
import java.security.MessageDigest

/**
 * 実行中 APK の署名 SHA-1 フィンガープリントを取得する。
 *
 * Google API の Android アプリ制限で `X-Android-Cert` ヘッダに要求される値 (`AA:BB:...` 形式大文字) を返す。
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
        digest.joinToString(separator = ":") { byte ->
            "%02X".format(byte)
        }
    }.getOrElse { error ->
        Napier.w(tag = "AndroidSigningCert", throwable = error) {
            "Failed to compute signing cert SHA-1"
        }
        ""
    }
}
