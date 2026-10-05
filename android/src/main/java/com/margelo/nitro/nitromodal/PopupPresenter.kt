package com.margelo.nitro.nitromodal

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import com.facebook.react.uimanager.ThemedReactContext

/**
 * `popup` mode: a centered card over the backdrop. Enter/exit are
 * [android.view.ViewPropertyAnimator]s on hardware layers, so they run on the
 * UI/render threads without touching JS.
 */
internal class PopupPresenter(
  activity: Activity,
  reactContext: ThemedReactContext,
  contentRoot: ModalContentRoot,
  config: ModalConfig,
  listener: Listener,
) : ModalPresenter(activity, reactContext, contentRoot, config, listener) {

  private val root = FrameLayout(activity)
  private val card = FrameLayout(activity)
  private val cardBackground = GradientDrawable()

  override fun createLayout(): ViewGroup {
    root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
    card.background = cardBackground
    card.outlineProvider = ViewOutlineProvider.BACKGROUND
    card.clipToOutline = true
    card.addView(contentRoot, FrameLayout.LayoutParams(MATCH, MATCH))
    root.addView(card, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
    root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
      if (r - l != oldR - oldL || b - t != oldB - oldT) reportArea()
    }
    applyAppearance()
    updateCardSize()
    return root
  }

  override fun animateIn() {
    card.alpha = 0f
    root.post {
      if (isDismissing) return@post
      if (config.popupAnimation == PopupAnimation.NONE) {
        backdrop.alpha = 1f
        card.alpha = 1f
        notifyPresented()
        return@post
      }
      val scale = if (config.popupAnimation == PopupAnimation.SCALE) 0.9f else 1f
      card.scaleX = scale
      card.scaleY = scale
      backdrop.animate().alpha(1f).setDuration(ENTER_DURATION).setInterpolator(EMPHASIZED_DECELERATE).start()
      card.animate()
        .alpha(1f).scaleX(1f).scaleY(1f)
        .setDuration(ENTER_DURATION)
        .setInterpolator(EMPHASIZED_DECELERATE)
        .withLayer()
        .withEndAction { notifyPresented() }
        .start()
    }
  }

  override fun animateOut(onEnd: () -> Unit) {
    if (config.popupAnimation == PopupAnimation.NONE) {
      onEnd()
      return
    }
    val scale = if (config.popupAnimation == PopupAnimation.SCALE) 0.95f else 1f
    backdrop.animate().alpha(0f).setDuration(EXIT_DURATION).setInterpolator(ACCELERATE).start()
    card.animate()
      .alpha(0f).scaleX(scale).scaleY(scale)
      .setDuration(EXIT_DURATION)
      .setInterpolator(ACCELERATE)
      .withLayer()
      .withEndAction { onEnd() }
      .start()
  }

  override fun onConfigChanged(previous: ModalConfig) {
    applyAppearance()
    onInsetsChanged()
  }

  override fun onInsetsChanged() {
    val margin = dp(MARGIN_DP)
    root.setPadding(
      systemInsets.left + margin,
      systemInsets.top + margin,
      systemInsets.right + margin,
      systemInsets.bottom + margin,
    )
    updateCardSize()
    applyKeyboardOffset()
    reportArea()
  }

  override fun onKeyboardProgress() = applyKeyboardOffset()

  override fun onContentSizeChanged() = updateCardSize()

  private fun reportArea() {
    val width = root.width - root.paddingLeft - root.paddingRight
    val height = root.height - root.paddingTop - root.paddingBottom -
      if (config.keyboardBehavior == KeyboardBehavior.RESIZE) keyboardOverlap else 0
    reportContentArea(width, height)
  }

  private fun applyAppearance() {
    cardBackground.cornerRadius = activity.dpToPx(config.cornerRadius ?: DEFAULT_CORNER_RADIUS_DP)
    cardBackground.setColor(config.surfaceColor(activity))
  }

  private fun updateCardSize() {
    val params = card.layoutParams ?: return
    val width = contentRoot.contentWidth
    val height = contentRoot.contentHeight
    if (params.width != width || params.height != height) {
      params.width = width
      params.height = height
      card.layoutParams = params
    }
  }

  /** Keeps the card centered in the space above the keyboard. */
  private fun applyKeyboardOffset() {
    if (config.keyboardBehavior == KeyboardBehavior.NONE) {
      card.translationY = 0f
      return
    }
    val headroom = (card.top - root.paddingTop).coerceAtLeast(0)
    card.translationY = -(keyboardOverlap / 2).coerceAtMost(headroom).toFloat()
  }

  companion object {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val MARGIN_DP = 16f
    private const val DEFAULT_CORNER_RADIUS_DP = 16f
    private const val ENTER_DURATION = 280L
    private const val EXIT_DURATION = 180L
    private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val ACCELERATE = AccelerateInterpolator()

    /** Content area before the popup is on screen, in px. */
    fun estimateContentArea(activity: Activity, width: Int, height: Int, insets: Insets): Pair<Int, Int> {
      val margin = activity.dpToPx(MARGIN_DP).toInt() * 2
      return Pair(width - insets.left - insets.right - margin, height - insets.top - insets.bottom - margin)
    }
  }
}
