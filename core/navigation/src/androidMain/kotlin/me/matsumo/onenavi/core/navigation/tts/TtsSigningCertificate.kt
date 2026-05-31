package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.security.MessageDigest

/**
 * 実行中 APK の署名 SHA-1 を算出するユーティリティ。
 *
 * Google Cloud TTS の Android アプリ制限を発動させるための `X-Android-Cert` ヘッダ値
 * (コロン無し大文字 16 進) を返す。算出に失敗した場合は空文字を返す。
 * Google エンドポイントは Console 登録値とコロンを除去した hex 文字列で照合するため、
 * `:` 区切りでは「Client application blocked」(403) になる点に注意。
 */
internal object TtsSigningCertificate {

    /** byte を符号なし int として扱うためのマスク。 */
    private const val BYTE_MASK = 0xFF

    /**
     * 署名 SHA-1 をコロン無し大文字 16 進で返す。取得不能なら空文字。
     */
    fun resolveSha1(context: Context): String {
        val signatures = runCatching { loadSignatures(context) }.getOrNull().orEmpty()
        val firstSignature = signatures.firstOrNull() ?: return ""

        val digest = MessageDigest.getInstance("SHA-1").digest(firstSignature.toByteArray())
        return digest.joinToString(separator = "") { byte ->
            "%02X".format(byte.toInt() and BYTE_MASK)
        }
    }

    @Suppress("DEPRECATION")
    private fun loadSignatures(context: Context): Array<Signature> {
        val packageManager = context.packageManager
        val packageName = context.packageName

        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = packageInfo.signingInfo ?: return emptyArray()

        return if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }.orEmpty()
    }

    private fun Array<Signature>?.orEmpty(): Array<Signature> = this ?: emptyArray()
}
