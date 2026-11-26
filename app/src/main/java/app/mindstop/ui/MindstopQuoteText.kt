package app.mindstop.ui

import android.content.Context
import app.mindstop.core.DailyQuotes

internal object MindstopQuoteText {

    private fun isChinese(context: Context): Boolean {
        val locales = context.resources.configuration.locales
        return !locales.isEmpty && locales[0].language == "zh"
    }

    fun primaryText(context: Context, quote: DailyQuotes.Quote): String =
        if (isChinese(context)) quote.zh else quote.en

    fun secondaryText(context: Context, quote: DailyQuotes.Quote): String =
        if (isChinese(context)) quote.en else quote.zh

    fun sourceText(context: Context, quote: DailyQuotes.Quote): String? =
        if (isChinese(context)) quote.zhSource else quote.enSource
}
