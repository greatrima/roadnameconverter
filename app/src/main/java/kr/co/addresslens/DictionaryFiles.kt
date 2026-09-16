package kr.co.addresslens

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal object DictionaryFiles {
    const val COMPRESSED_ASSET = "address_dictionary.tsv.gz"
    const val PLAIN_ASSET = "address_dictionary.tsv"

    fun openBundled(openAsset: (String) -> InputStream): InputStream {
        // Android packaging may unpack a .gz asset and remove its suffix.
        var lastError: IOException? = null
        for (name in listOf(PLAIN_ASSET, COMPRESSED_ASSET)) {
            try {
                return openAsset(name)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw IOException("APK에서 내장 주소 사전을 찾을 수 없습니다.", lastError)
    }

    fun decoded(input: InputStream): InputStream {
        val buffered = input.buffered()
        try {
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            return if (gzip) GZIPInputStream(buffered) else buffered
        } catch (error: Exception) {
            buffered.close()
            throw error
        }
    }

    fun contentDigest(input: InputStream): ByteArray = decoded(input).use { stream ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16_384)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest()
    }
}
