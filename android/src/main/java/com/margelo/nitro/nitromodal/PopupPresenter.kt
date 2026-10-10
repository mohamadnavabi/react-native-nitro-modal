package com.margelo.nitro.nitromodal

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.core.graphics.Insets
import com.facebook.react.uimanager.ThemedReactContext
import kotlin.math.ceil

/**
 * `popup` mode: a centered card over the backdrop. Enter/exit are
 * [android.view.ViewPropertyAnimator]s on hardware layers, so they run on the
 * UI/render threads without touching JS. Each one starts from the current
 * values, so a transition can be turned around mid-flight.
 */
internal class PopupPresenter(
  activity: Activity,
  reactContext: ThemedReactContext,
  contentRoot: ModalContentRoot,
  config: ModalConfig,
  listener: Listener,
) : ModalPresenter(activity, reactContext, contentRoot, config, listener) {

  private val root = FrameLayout(activity)

  /** Insets + margin; the backdrop sits outside it so it covers the whole window. */
  private val cardFrame = FrameLayout(activity)
  private val card = FrameLayout(activity)
  private val cardBackground = GradientDrawable()
  /** The border, as the card's foreground so the content can't cover it. */
  private val cardBorder = GradientDrawable()
  private var started = false
  private var backPreview = false

  override fun createLayout(): ViewGroup {
    root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
    card.background = cardBackground
    card.foreground = cardBorder
    card.outlineProvider = ViewOutlineProvider.BACKGROUND
    card.clipToOutline = true
    card.addView(contentRoot, FrameLayout.LayoutParams(MATCH, MATCH))
    cardFrame.addView(card, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
    cardFrame.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
      if (r - l != oldR - oldL || b - t != oldB - oldT) reportArea()
    }
    root.addView(cardFrame, FrameLayout.LayoutParams(MATCH, MATCH))
    applyAppearance()
    updateCardSize()
    return root
  }

  /** One frame after show, so the card has its size before it scales in. */
  private val firstEnter = Runnable {
    if (config.popupAnimation == PopupAnimation.SCALE) {
      card.scaleX = ENTER_SCALE
      card.scaleY = ENTER_SCALE
    }
    runEnter()
  }

  override fun animateIn() {
    if (started) return runEnter()
    started = true
    card.alpha = 0f
    root.post(firstEnter)
  }

  override fun animateOut(fromBackGesture: Boolean, onEnd: () -> Unit) {
    root.removeCallbacks(firstEnter)
    backPreview = false
    if (config.popupAnimation == PopupAnimation.NONE) {
      onEnd()
      return
    }
    // From wherever the enter or a back preview left the card.
    val scale = card.scaleX * if (config.popupAnimation == PopupAnimation.SCALE) EXIT_SCALE else 1f
    val duration = if (fromBackGesture) BACK_COMMIT_DURATION else EXIT_DURATION
    backdrop.animate().alpha(0f).setDuration(duration).setInterpolator(ACCELERATE).start()
    card.animate()
      .alpha(0f).scaleX(scale).scaleY(scale)
      .setDuration(duration)
      .setInterpolator(ACCELERATE)
      .withLayer()
      .withEndAction { onEnd() }
      .start()
  }

  override fun onBackGestureStarted(event: BackEventCompat) {
    if (config.popupAnimation == PopupAnimation.NONE) return
    backPreview = true
    card.animate().cancel()
    card.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    onBackGestureProgressed(event)
  }

  override fun onBackGestureProgressed(event: BackEventCompat) {
    if (!backPreview) return
    val scale = 1f - (1f - BACK_MIN_SCALE) * BACK_PROGRESS.getInterpolation(event.progress)
    card.scaleX = scale
    card.scaleY = scale
  }

  override fun onBackGestureCancelled() {
    if (!backPreview) return
    backPreview = false
    card.animate()
      .scaleX(1f).scaleY(1f)
      .setDuration(BACK_CANCEL_DURATION)
      .setInterpolator(EMPHASIZED_DECELERATE)
      .withEndAction { card.setLayerType(View.LAYER_TYPE_NONE, null) }
      .start()
  }

  override fun onConfigChanged(previous: ModalConfig) {
    applyAppearance()
    onInsetsChanged()
  }

  override fun onInsetsChanged() {
    val margin = dp(MARGIN_DP)
    cardFrame.setPadding(
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

  private fun runEnter() {
    if (config.popupAnimation == PopupAnimation.NONE) {
      backdrop.alpha = 1f
      card.alpha = 1f
      notifyPresented()
      return
    }
    // Cancelling first restores the layer type a previous animation saved.
    card.animate().cancel()
    card.setLayerType(View.LAYER_TYPE_NONE, null)
    backdrop.animate().alpha(1f).setDuration(ENTER_DURATION).setInterpolator(EMPHASIZED_DECELERATE).start()
    card.animate()
      .alpha(1f).scaleX(1f).scaleY(1f)
      .setDuration(ENTER_DURATION)
      .setInterpolator(EMPHASIZED_DECELERATE)
      .withLayer()
      .withEndAction { notifyPresented() }
      .start()
  }

  private fun reportArea() {
    val width = cardFrame.width - cardFrame.paddingLeft - cardFrame.paddingRight
    val height = cardFrame.height - cardFrame.paddingTop - cardFrame.paddingBottom -
      if (config.keyboardBehavior == KeyboardBehavior.RESIZE) keyboardOverlap else 0
    reportContentArea(width, height)
  }

  private fun applyAppearance() {
    cardBackground.cornerRadius = activity.dpToPx(config.cornerRadius ?: DEFAULT_CORNER_RADIUS_DP)
    cardBackground.setColor(config.surfaceColor(activity))
    cardBorder.cornerRadius = cardBackground.cornerRadius
    cardBorder.setStroke(ceil(activity.dpToPx(config.borderWidth)).toInt(), config.borderColor)
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
    val headroom = (card.top - cardFrame.paddingTop).coerceAtLeast(0)
    card.translationY = -(keyboardOverlap / 2).coerceAtMost(headroom).toFloat()
  }

  companion object {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val MARGIN_DP = 16f
    private const val DEFAULT_CORNER_RADIUS_DP = 16f
    private const val ENTER_SCALE = 0.9f
    private const val EXIT_SCALE = 0.95f
    private const val ENTER_DURATION = 280L
    private const val EXIT_DURATION = 180L

    /** Predictive back: how far the card shrinks while the gesture runs. */
    private const val BACK_MIN_SCALE = 0.9f
    private const val BACK_COMMIT_DURATION = 150L
    private const val BACK_CANCEL_DURATION = 200L
    private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val ACCELERATE = AccelerateInterpolator()

    /** Material's predictive back progress curve. */
    private val BACK_PROGRESS = PathInterpolator(0.1f, 0.1f, 0f, 1f)

    /** Content area before the popup is on screen, in px. */
    fun estimateContentArea(activity: Activity, width: Int, height: Int, insets: Insets): Pair<Int, Int> {
      val margin = activity.dpToPx(MARGIN_DP).toInt() * 2
      return Pair(width - insets.left - insets.right - margin, height - insets.top - insets.bottom - margin)
    }
  }
}
