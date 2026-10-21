package com.berserk.reader

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.berserk.reader.data.MangaRepository
import com.berserk.reader.data.PreferencesManager
import com.berserk.reader.navigation.AppNavigation
import com.berserk.reader.ui.theme.BerserkReaderTheme
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        private var repositoryInstance: MangaRepository? = null

        @Volatile
        private var prefsInstance: PreferencesManager? = null

        @Volatile
        private var isImageLoaderInitialized = false

        fun getRepository(context: Context): MangaRepository {
            return repositoryInstance ?: synchronized(this) {
                repositoryInstance ?: MangaRepository(context.applicationContext).also {
                    repositoryInstance = it
                }
            }
        }

        fun getPreferences(context: Context): PreferencesManager {
            return prefsInstance ?: synchronized(this) {
                prefsInstance ?: PreferencesManager(context.applicationContext).also {
                    prefsInstance = it
                }
            }
        }

        fun initImageLoader(context: Context) {
            if (!isImageLoaderInitialized) {
                synchronized(this) {
                    if (!isImageLoaderInitialized) {
                        val appContext = context.applicationContext
                        val okHttpClient = OkHttpClient.Builder()
                            .addInterceptor { chain ->
                                val request = chain.request().newBuilder()
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                    .header("Referer", "https://onepiecepower.com/manga8/berserk/lista-capitoli")
                                    .build()
                                chain.proceed(request)
                            }
                            .build()

                        val imageLoader = ImageLoader.Builder(appContext)
                            .okHttpClient(okHttpClient)
                            .memoryCache {
                                MemoryCache.Builder(appContext)
                                    .maxSizePercent(0.25)
                                    .build()
                            }
                            .diskCache {
                                DiskCache.Builder()
                                    .directory(appContext.cacheDir.resolve("manga_image_cache"))
                                    .maxSizeBytes(512L * 1024 * 1024) // 512 MB disk cache
                                    .build()
                            }
                            .respectCacheHeaders(false)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build()

                        Coil.setImageLoader(imageLoader)
                        isImageLoaderInitialized = true
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = getRepository(this)
        val prefs = getPreferences(this)
        initImageLoader(this)

        setContent {
            BerserkReaderTheme {
                AppNavigation(
                    repository = repository,
                    prefs = prefs
                )
            }
        }
    }
}
