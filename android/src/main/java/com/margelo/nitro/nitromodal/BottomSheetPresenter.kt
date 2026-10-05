package com.margelo.nitro.nitromodal

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.doOnLayout
import com.facebook.react.uimanager.ThemedReactContext
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * `bottomSheet` mode, driven by [BottomSheetBehavior]. Dragging, flinging,
 * snapping and the backdrop fade all run on the UI thread with no JS.
 *
 * `BottomSheetBehavior` supports up to three resting states, so detents map
 * onto collapsed / half-expanded / expanded, smallest first.
 */
internal class BottomSheetPresenter(
  activity: Activity,
  reactContext: ThemedReactContext,
  contentRoot: ModalContentRoot,
  config: ModalConfig,
  listener: Listener,
) : ModalPresenter(activity, reactContext, contentRoot, config, listener) {

  private class Detent(val index: Int, val height: Int)

  private val coordinator = CoordinatorLayout(activity)
  private val sheet = FrameLayout(activity)
  private val grabber = View(activity)
  private val behavior = BottomSheetBehavior<FrameLayout>()
  private val sheetBackground = MaterialShapeDrawable()

  /** Resting detents sorted by height; position i maps to [states][i]. */
  private var sortedDetents: List<Detent> = emptyList()
  private var maxContentHeight = 0
  private var presented = false
  private var lastDetentIndex = -1

  private val callback = object : BottomSheetBehavior.BottomSheetCallback() {
    override fun onStateChanged(bottomSheet: View, newState: Int) {
      if (newState == BottomSheetBehavior.STATE_HIDDEN) {
        finishUserDismissal(DismissReason.SWIPE)
        return
      }
      val position = states().indexOf(newState)
      if (position < 0 || isDismissing) return
      if (!presented) {
        presented = true
        behavior.isHideable = this@BottomSheetPresenter.config.dismissOnSwipe
        notifyPresented()
      }
      val index = sortedDetents.getOrNull(position)?.index ?: return
      if (index != lastDetentIndex) {
        lastDetentIndex = index
        listener.onDetentChange(index)
      }
    }

    override fun onSlide(bottomSheet: View, slideOffset: Float) {
      // slideOffset is -1 when hidden and 0 at the lowest detent.
      backdrop.alpha = (1f + slideOffset).coerceIn(0f, 1f)
    }
  }

  override fun createLayout(): ViewGroup {
    coordinator.addView(backdrop, CoordinatorLayout.LayoutParams(MATCH, MATCH))
    coordinator.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
      // Rotation / window resize. Posted: detents change the sheet's layout params.
      if (r - l != oldR - oldL || b - t != oldB - oldT) coordinator.post { updateDetents() }
    }

    sheet.background = sheetBackground
    sheet.outlineProvider = ViewOutlineProvider.BACKGROUND
    sheet.clipToOutline = true
    sheet.addView(contentRoot, FrameLayout.LayoutParams(MATCH, MATCH))
    sheet.addView(
      grabber,
      FrameLayout.LayoutParams(dp(32f), dp(4f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
        topMargin = dp(8f)
      },
    )
    applyAppearance()

    behavior.isGestureInsetBottomIgnored = true
    behavior.maxWidth = dp(MAX_WIDTH_DP)
    behavior.isHideable = true
    behavior.state = BottomSheetBehavior.STATE_HIDDEN
    // Registered after the initial HIDDEN state so it doesn't read as a dismissal.
    behavior.addBottomSheetCallback(callback)

    coordinator.addView(
      sheet,
      CoordinatorLayout.LayoutParams(MATCH, 0).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        behavior = this@BottomSheetPresenter.behavior
      },
    )
    return coordinator
  }

  override fun animateIn() {
    coordinator.doOnLayout {
      updateDetents()
      // Settles from HIDDEN with the behavior's own animation.
      behavior.state = stateFor(config.clampedInitialDetentIndex)
    }
  }

  override fun animateOut(onEnd: () -> Unit) {
    if (behavior.state == BottomSheetBehavior.STATE_HIDDEN || !coordinator.isLaidOut) {
      onEnd()
      return
    }
    behavior.isHideable = true
    behavior.state = BottomSheetBehavior.STATE_HIDDEN
    // STATE_HIDDEN reaches finishUserDismissal(), which keeps our reason.
  }

  override fun onConfigChanged(previous: ModalConfig) {
    applyAppearance()
    if (presented) behavior.isHideable = config.dismissOnSwipe
    if (previous.detents != config.detents || previous.keyboardBehavior != config.keyboardBehavior) {
      updateDetents()
    }
  }

  override fun onInsetsChanged() {
    sheet.setPadding(systemInsets.left, 0, systemInsets.right, systemInsets.bottom)
    updateDetents()
    applyKeyboardOffset()
  }

  override fun onKeyboardProgress() = applyKeyboardOffset()

  override fun onContentSizeChanged() {
    if (config.detents.contains(SheetDetent.FITCONTENT)) updateDetents()
  }

  override fun snapToDetent(index: Int) {
    if (!presented || isDismissing) return
    behavior.state = stateFor(index)
  }

  private fun applyAppearance() {
    val radius = activity.dpToPx(config.cornerRadius ?: DEFAULT_CORNER_RADIUS_DP)
    sheetBackground.shapeAppearanceModel = ShapeAppearanceModel.builder()
      .setTopLeftCornerSize(radius)
      .setTopRightCornerSize(radius)
      .build()
    sheetBackground.fillColor = ColorStateList.valueOf(config.surfaceColor(activity))
    grabber.visibility = if (config.grabberVisible) View.VISIBLE else View.GONE
    grabber.background = GradientDrawable().apply {
      cornerRadius = activity.dpToPx(2f)
      setColor(Color.argb(0x66, 0x80, 0x80, 0x80))
    }
  }

  /** Recomputes detent heights and maps them onto the behavior's states. */
  private fun updateDetents() {
    val parentHeight = coordinator.height
    if (parentHeight == 0) return
    val bottomInset = systemInsets.bottom
    maxContentHeight = (parentHeight - systemInsets.top - bottomInset).coerceAtLeast(1)

    val requested = config.detents.ifEmpty { listOf(SheetDetent.FITCONTENT) }
    val detents = requested
      .mapIndexed { index, detent -> Detent(index, contentHeightFor(detent) + bottomInset) }
      .distinctBy { it.height }
      .sortedBy { it.height }
    sortedDetents = when {
      detents.size <= 3 -> detents
      // BottomSheetBehavior only has three resting states.
      else -> listOf(detents.first(), detents[detents.size / 2], detents.last())
    }

    val sheetHeight = sortedDetents.last().height
    val params = sheet.layoutParams
    if (params.height != sheetHeight) {
      params.height = sheetHeight
      sheet.layoutParams = params
    }

    val heights = sortedDetents.map { it.height }
    when (heights.size) {
      1 -> {
        behavior.isFitToContents = true
        behavior.skipCollapsed = true
        behavior.peekHeight = sheetHeight
      }
      2 -> {
        behavior.isFitToContents = true
        behavior.skipCollapsed = false
        behavior.peekHeight = heights[0]
      }
      else -> {
        behavior.isFitToContents = false
        behavior.skipCollapsed = false
        behavior.peekHeight = heights[0]
        behavior.halfExpandedRatio = (heights[1].toFloat() / parentHeight).coerceIn(0.01f, 0.99f)
        behavior.expandedOffset = parentHeight - heights[2]
      }
    }

    val availableHeight =
      if (SheetDetent.FITCONTENT in requested) maxContentHeight else sheetHeight - bottomInset
    val areaHeight = availableHeight -
      if (config.keyboardBehavior == KeyboardBehavior.RESIZE) keyboardOverlap else 0
    val sheetWidth = minOf(coordinator.width, behavior.maxWidth.takeIf { it > 0 } ?: Int.MAX_VALUE)
    reportContentArea(sheetWidth - systemInsets.left - systemInsets.right, areaHeight)
  }

  private fun contentHeightFor(detent: SheetDetent): Int = when (detent) {
    SheetDetent.SMALL -> (maxContentHeight * 0.25f).toInt()
    SheetDetent.MEDIUM -> (maxContentHeight * 0.5f).toInt()
    SheetDetent.LARGE -> maxContentHeight
    SheetDetent.FITCONTENT -> contentRoot.contentHeight.coerceIn(1, maxContentHeight)
  }

  private fun states(): List<Int> = when (sortedDetents.size) {
    0 -> emptyList()
    1 -> listOf(BottomSheetBehavior.STATE_EXPANDED)
    2 -> listOf(BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.STATE_EXPANDED)
    else -> listOf(
      BottomSheetBehavior.STATE_COLLAPSED,
      BottomSheetBehavior.STATE_HALF_EXPANDED,
      BottomSheetBehavior.STATE_EXPANDED,
    )
  }

  /** Resting state for `detents[index]`, falling back to the nearest height. */
  private fun stateFor(index: Int): Int {
    val states = states()
    if (states.isEmpty()) return BottomSheetBehavior.STATE_EXPANDED
    val target = config.detents.getOrNull(index)?.let { contentHeightFor(it) + systemInsets.bottom }
    val position = if (target == null) {
      states.lastIndex
    } else {
      sortedDetents.indices.minBy { kotlin.math.abs(sortedDetents[it].height - target) }
    }
    return states[position]
  }

  /** Lifts the sheet above the keyboard without relayout (`pan` and `resize`). */
  private fun applyKeyboardOffset() {
    if (config.keyboardBehavior == KeyboardBehavior.NONE) {
      sheet.translationY = 0f
      return
    }
    val headroom = (sheet.top - systemInsets.top).coerceAtLeast(0)
    sheet.translationY = -keyboardOverlap.coerceAtMost(headroom).toFloat()
  }

  companion object {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val DEFAULT_CORNER_RADIUS_DP = 28f
    private const val MAX_WIDTH_DP = 640f

    /** Content area before the sheet is on screen, in px. */
    fun estimateContentArea(
      activity: Activity,
      detents: List<SheetDetent>,
      width: Int,
      height: Int,
      insets: Insets,
    ): Pair<Int, Int> {
      val maxContent = height - insets.top - insets.bottom
      val fraction = when {
        detents.isEmpty() || SheetDetent.LARGE in detents || SheetDetent.FITCONTENT in detents -> 1f
        SheetDetent.MEDIUM in detents -> 0.5f
        else -> 0.25f
      }
      val sheetWidth = minOf(width, activity.dpToPx(MAX_WIDTH_DP).toInt())
      return Pair(sheetWidth - insets.left - insets.right, (maxContent * fraction).toInt())
    }
  }
}
