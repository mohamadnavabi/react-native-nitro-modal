package com.margelo.nitro.nitromodal

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** A resting height of a `bottomSheet`. */
internal sealed interface Detent {
  data object Small : Detent
  data object Medium : Detent
  data object Large : Detent
  data object FitContent : Detent

  /** Content height in dp, above the bottom system inset. */
  data class Height(val dp: Float) : Detent

  companion object {
    fun from(detent: SheetDetent): Detent = when (detent) {
      is SheetDetent.First -> when (detent.value) {
        NamedSheetDetent.SMALL -> Small
        NamedSheetDetent.MEDIUM -> Medium
        NamedSheetDetent.LARGE -> Large
        NamedSheetDetent.FITCONTENT -> FitContent
      }
      is SheetDetent.Second -> if (detent.value.isFinite()) Height(detent.value.toFloat()) else Large
    }
  }
}

/** Snapshot of the React props that shape a presentation. */
internal data class ModalConfig(
  val mode: ModalMode = ModalMode.BOTTOMSHEET,
  /** An inline `bottomSheet` (see [isInline]), as opposed to a modal one. */
  val inlinePresentation: Boolean = false,
  val detents: List<Detent> = listOf(Detent.FitContent),
  val initialDetentIndex: Int = 0,
  val backdropColor: Int = Color.BLACK,
  val backdropOpacity: Float = 0.4f,
  /** dp, 0 disables the blur. */
  val backdropBlurRadius: Float = 0f,
  val dismissOnBackdropPress: Boolean = true,
  val dismissOnSwipe: Boolean = true,
  val dismissOnBackButton: Boolean = true,
  val grabberVisible: Boolean = false,
  /** dp, `null` uses the platform default. */
  val cornerRadius: Float? = null,
  /** `null` uses the system surface color. */
  val contentBackgroundColor: Int? = null,
  val keyboardBehavior: KeyboardBehavior = KeyboardBehavior.PAN,
  val popupAnimation: PopupAnimation = PopupAnimation.SCALE,
  val pullToRefreshEnabled: Boolean = false,
  /** A refresh is in progress; `null` shows no refresh indicator. */
  val refreshing: Boolean? = null,
  /** `null` uses the platform default. */
  val refreshIndicatorColor: Int? = null,
) {
  /** A `bottomSheet` living in the host view's bounds. Popups are always modal. */
  val isInline: Boolean
    get() = inlinePresentation && mode == ModalMode.BOTTOMSHEET

  val clampedInitialDetentIndex: Int
    get() = initialDetentIndex.coerceIn(0, (detents.size - 1).coerceAtLeast(0))

  /** Backdrop color with `backdropOpacity` folded into its alpha. */
  val effectiveBackdropColor: Int
    get() {
      val alpha = (Color.alpha(backdropColor) * backdropOpacity.coerceIn(0f, 1f)).toInt()
      return Color.argb(alpha, Color.red(backdropColor), Color.green(backdropColor), Color.blue(backdropColor))
    }

  fun surfaceColor(context: Context): Int {
    contentBackgroundColor?.let { return it }
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
      Configuration.UI_MODE_NIGHT_YES
    return if (night) Color.rgb(0x1C, 0x1B, 0x1F) else Color.WHITE
  }
}

/** Decodes a color produced by React Native's `processColor` (ARGB int). */
internal fun Double.toProcessedColor(): Int = toLong().toInt()

internal fun Context.dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

internal fun Context.pxToDp(px: Int): Double = (px / resources.displayMetrics.density).toDouble()
