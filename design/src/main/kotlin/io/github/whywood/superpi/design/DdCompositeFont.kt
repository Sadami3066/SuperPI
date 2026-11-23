package io.github.whywood.superpi.design

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle
import android.graphics.fonts.FontVariationAxis
import androidx.annotation.FontRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTextApi::class)
@Immutable
class DdCompositeFont(
    @FontRes internal val latinRes: Int,
    override val weight: FontWeight,
    internal val variable: Boolean,
) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = DdCompositeTypefaceLoader,
    variationSettings = FontVariation.Settings(),
) {
    override val style: FontStyle get() = FontStyle.Normal
}

internal const val SYSTEM_LATIN_RES = 0

internal object DdCompositeTypefaceLoader : AndroidFont.TypefaceLoader {
    private val cache = ConcurrentHashMap<Int, Typeface>()
    private val lock = Any()

    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
        val composite = font as? DdCompositeFont ?: return null
        return typeface(context.applicationContext, composite)
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? {
        val composite = font as? DdCompositeFont ?: return null
        cache[cacheKey(composite)]?.let { return it }
        val application = context.applicationContext
        return withContext(Dispatchers.IO) { typeface(application, composite) }
    }

    fun preload(context: Context, fonts: List<DdCompositeFont>) {
        val application = context.applicationContext
        fonts.forEach { font -> runCatching { typeface(application, font) } }
    }

    private fun cacheKey(font: DdCompositeFont): Int = font.latinRes * 1000 + font.weight.weight

    private fun typeface(context: Context, font: DdCompositeFont): Typeface {
        val key = cacheKey(font)
        cache[key]?.let { return it }
        synchronized(lock) {
            cache[key]?.let { return it }
            val typeface = build(context, font)
            cache[key] = typeface
            return typeface
        }
    }

    private fun build(context: Context, font: DdCompositeFont): Typeface = runCatching {
        if (font.latinRes == SYSTEM_LATIN_RES) {
            return@runCatching Typeface.create(Typeface.SANS_SERIF, font.weight.weight, false)
        }
        val platformFont = PlatformFont.Builder(context.resources, font.latinRes)
            .setWeight(font.weight.weight)
            .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
            .apply {
                if (font.variable) setFontVariationSettings(variation(font.weight.weight))
            }
            .build()
        compositeTypeface(PlatformFontFamily.Builder(platformFont).build(), font.weight.weight)
    }.getOrElse {
        Typeface.create(Typeface.SANS_SERIF, font.weight.weight, false)
    }

    private fun compositeTypeface(latin: PlatformFontFamily, weight: Int): Typeface =
        Typeface.CustomFallbackBuilder(latin)
            .setStyle(PlatformFontStyle(weight, PlatformFontStyle.FONT_SLANT_UPRIGHT))
            .setSystemFallback("sans-serif")
            .build()

    private fun variation(weight: Int): Array<FontVariationAxis> =
        arrayOf(FontVariationAxis("wght", weight.toFloat()))
}
