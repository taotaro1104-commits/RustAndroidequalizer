package dev.rusteq

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.PluralsRes

/** Keeps process state independent of the language used to display it. */
sealed interface UiText {
    fun resolve(context: Context): String

    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context): String = context.getString(
            id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray(),
        )
    }

    data class Literal(val value: String) : UiText {
        override fun resolve(context: Context): String = value
    }

    data class Quantity(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context): String = context.resources.getQuantityString(
            id, count, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray(),
        )
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Resource(id, args.toList())

fun uiQuantity(@PluralsRes id: Int, count: Int, vararg args: Any): UiText = UiText.Quantity(id, count, args.toList())

/** Validation failures retain a resource reference until the UI renders them. */
class UiTextException(val text: UiText) : IllegalArgumentException()

fun Throwable.asUiText(@StringRes fallback: Int): UiText = when (this) {
    is UiTextException -> text
    else -> message?.takeIf { it.isNotBlank() }?.let {
        uiText(R.string.player_error_details, uiText(fallback), UiText.Literal(it))
    } ?: uiText(fallback)
}
