package app.visorgopro.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Carga miniaturas/fotos de la cámara con caché en memoria y pocas descargas simultáneas. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(96 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // La cámara es lenta: no más de 4 descargas a la vez.
    private val gate = Semaphore(4)

    suspend fun load(client: GoProClient, url: String, maxDim: Int): Bitmap? {
        val key = "$url#$maxDim"
        cache.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(url).build()
                    val bytes = client.http.newCall(req).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes() else null
                    }
                    if (bytes == null) {
                        null
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= maxDim ||
                            bounds.outHeight / (sample * 2) >= maxDim
                        ) {
                            sample *= 2
                        }
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bmp != null) cache.put(key, bmp)
                        bmp
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
}
