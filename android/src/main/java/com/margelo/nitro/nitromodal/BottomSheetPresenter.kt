package com.margelo.nitro.nitromodal

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.BackEventCompat
import androidx.core.animation.doOnEnd
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.facebook.react.uimanager.PointerEvents
import com.facebook.react.uimanager.ReactPointerEventsView
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.NativeGestureUtil
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlin.math.abs
import kotlin.math.ceil

/**
 * `bottomSheet` mode, driven by [BottomSheetBehavior]. Dragging, flinging,
 * snapping and the backdrop fade all run on the UI thread with no JS.
 *
 * `BottomSheetBehavior` supports up to three resting states, so detents map
 * onto collapsed / half-expanded / expanded, smallest first. Predictive back
 * uses the behavior's own Material animation, like `BottomSheetDialog`.
 *
 * Inline, the sheet fills the host view instead of a window, with no
 * backdrop, and touches that miss the sheet reach the views behind it.
 */
internal class BottomSheetPresenter(
  activity: Activity,
  reactContext: ThemedReactContext,
  contentRoot: ModalContentRoot,
  config: ModalConfig,
  listener: Listener,
  inlineHost: NitroModalHostView? = null,
) : ModalPresenter(activity, reactContext, contentRoot, config, listener, inlineHost) {

  private class RestingHeight(val index: Int, val height: Int)

  private val coordinator = SheetLayout(activity)
  private val sheet = FrameLayout(activity)
  private val grabber = View(activity)
  private val refreshIndicator = ProgressBar(activity)
  private val behavior = SheetBehavior()
  private val sheetBackground = GradientDrawable()
  private var cornerRadiusPx = 0f

  /** Resting detents sorted by height; position i maps to [states][i]. */
  private var sortedDetents: List<RestingHeight> = emptyList()
  private var maxContentHeight = 0
  private var presented = false
  private var lastDetentIndex = -1
  private var reportedRestingTop = Int.MIN_VALUE

  /** The behavior is dragging the sheet (as opposed to a nested scroll moving it). */
  private var sheetDragging = false

  /** How far the sheet is pulled below its lowest detent, in px. */
  private var pullOffset = 0f
  private var pullAnimator: ValueAnimator? = null
  private val refreshThreshold = activity.dpToPx(REFRESH_THRESHOLD_DP)

  /** The sheet rests below its lowest detent, the indicator spinning above it. */
  private var refreshing = config.refreshing ?: false

  /** A predictive back gesture was committed; the behavior is sliding the sheet out. */
  private var committingBack = false
  private var backProgress = 0f

  private val callback = object : BottomSheetBehavior.BottomSheetCallback() {
    override fun onStateChanged(bottomSheet: View, newState: Int) {
      if (newState == BottomSheetBehavior.STATE_HIDDEN) {
        finishUserDismissal(DismissReason.SWIPE)
        return
      }
      if (newState == BottomSheetBehavior.STATE_DRAGGING) {
        sheetDragging = true
        coordinator.notifyNativeGesture(started = true)
        // A refreshing sheet follows the finger from its lowest detent.
        animatePullOffset(0f)
      } else if (sheetDragging) {
        sheetDragging = false
        coordinator.notifyNativeGesture(started = false)
      }
      val position = states().indexOf(newState)
      if (position < 0 || isDismissing) return
      presented = true
      // Hideable while entering/exiting; at rest, swipe-to-dismiss follows the prop.
      behavior.isHideable = this@BottomSheetPresenter.config.dismissOnSwipe
      notifyPresented()
      reportRestingTop()
      animatePullOffset(restingPullOffset())
      val index = sortedDetents.getOrNull(position)?.index ?: return
      if (index != lastDetentIndex) {
        lastDetentIndex = index
        listener.onDetentChange(index)
      }
    }

    override fun onSlide(bottomSheet: View, slideOffset: Float) {
      // slideOffset is -1 when hidden and 0 at the lowest detent.
      backdrop.alpha = (1f + slideOffset).coerceIn(0f, 1f)
      layoutRefreshIndicator()
    }
  }

  override fun createLayout(): ViewGroup {
    if (!isInline) coordinator.addView(backdrop, CoordinatorLayout.LayoutParams(MATCH, MATCH))
    coordinator.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
      // Rotation / window resize. Posted: detents change the sheet's layout params.
      if (r - l != oldR - oldL || b - t != oldB - oldT) coordinator.post { updateDetents() }
      // Detent heights can move the resting sheet without a state change.
      reportRestingTop()
      layoutRefreshIndicator()
    }

    // Behind the sheet, which uncovers it as it's pulled down.
    refreshIndicator.isIndeterminate = true
    refreshIndicator.visibility = View.INVISIBLE
    refreshIndicator.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    coordinator.addView(
      refreshIndicator,
      CoordinatorLayout.LayoutParams(dp(REFRESH_INDICATOR_SIZE_DP), dp(REFRESH_INDICATOR_SIZE_DP)).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      },
    )

    sheet.background = sheetBackground
    sheet.outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        // Extends past the bottom edge so only the top corners round. A round
        // rect clips cheaply on every API level; a path outline only clips on
        // API 33+, and slowly.
        val overhang = ceil(cornerRadiusPx).toInt()
        outline.setRoundRect(0, 0, view.width, view.height + overhang, cornerRadiusPx)
      }
    }
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
    // Inline, the host view decides the width.
    if (!isInline) behavior.maxWidth = dp(MAX_WIDTH_DP)
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
      if (isDismissing) return@doOnLayout
      updateDetents()
      // Settles with the behavior's own animation, from HIDDEN on show or from
      // wherever a reversed exit got to (back to the detent it left).
      val index = if (lastDetentIndex >= 0) lastDetentIndex else config.clampedInitialDetentIndex
      behavior.state = stateFor(index)
    }
  }

  override fun animateOut(fromBackGesture: Boolean, onEnd: () -> Unit) {
    coordinator.cancelPull()
    if (behavior.state == BottomSheetBehavior.STATE_HIDDEN || !coordinator.isLaidOut) {
      onEnd()
      return
    }
    behavior.isHideable = true
    if (fromBackGesture) {
      // Slides out from the gesture's preview. onSlide doesn't run for this
      // animation, so fade the backdrop alongside it, timed like Material's
      // slide (shorter the further the gesture got).
      committingBack = true
      val duration = BACK_HIDE_MAX_DURATION -
        ((BACK_HIDE_MAX_DURATION - BACK_HIDE_MIN_DURATION) * backProgress).toLong()
      backdrop.animate().alpha(0f).setDuration(duration).setInterpolator(FAST_OUT_SLOW_IN).start()
      behavior.handleBackInvoked()
    } else {
      behavior.state = BottomSheetBehavior.STATE_HIDDEN
    }
    // STATE_HIDDEN reaches finishUserDismissal(), which keeps our reason.
  }

  override val canReverseDismiss: Boolean
    get() = !committingBack

  override fun onBackGestureStarted(event: BackEventCompat) {
    backProgress = event.progress
    behavior.startBackProgress(event)
  }

  override fun onBackGestureProgressed(event: BackEventCompat) {
    backProgress = event.progress
    behavior.updateBackProgress(event)
  }

  override fun onBackGestureCancelled() = behavior.cancelBackProgress()

  override fun onConfigChanged(previous: ModalConfig) {
    applyAppearance()
    if (previous.refreshing != config.refreshing) setRefreshing(config.refreshing ?: false)
    layoutRefreshIndicator()
    if (presented && !isDismissing) behavior.isHideable = config.dismissOnSwipe
    if (previous.detents != config.detents || previous.keyboardBehavior != config.keyboardBehavior) {
      updateDetents()
    }
  }

  override fun onInsetsChanged() {
    sheet.setPadding(systemInsets.left, 0, systemInsets.right, systemInsets.bottom)
    updateDetents()
    applyTranslation()
  }

  override fun onKeyboardProgress() = applyTranslation()

  override fun onContentSizeChanged() {
    if (config.detents.contains(Detent.FitContent)) updateDetents()
  }

  override fun snapToDetent(index: Int) {
    if (!presented || isDismissing) return
    behavior.state = stateFor(index)
  }

  override fun onTeardown() = coordinator.release()

  private fun applyAppearance() {
    val radius = activity.dpToPx(config.cornerRadius ?: DEFAULT_CORNER_RADIUS_DP)
    if (radius != cornerRadiusPx) {
      cornerRadiusPx = radius
      sheetBackground.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
      sheet.invalidateOutline()
    }
    sheetBackground.setColor(config.surfaceColor(activity))
    grabber.visibility = if (config.grabberVisible) View.VISIBLE else View.GONE
    grabber.background = GradientDrawable().apply {
      cornerRadius = activity.dpToPx(2f)
      setColor(Color.argb(0x66, 0x80, 0x80, 0x80))
    }
    refreshIndicator.indeterminateTintList = config.refreshIndicatorColor?.let(ColorStateList::valueOf)
  }

  /** Recomputes detent heights and maps them onto the behavior's states. */
  private fun updateDetents() {
    val parentHeight = coordinator.height
    if (parentHeight == 0) return
    val bottomInset = systemInsets.bottom
    maxContentHeight = (parentHeight - systemInsets.top - bottomInset).coerceAtLeast(1)

    val requested = config.detents.ifEmpty { listOf(Detent.FitContent) }
    val detents = requested
      .mapIndexed { index, detent -> RestingHeight(index, contentHeightFor(detent) + bottomInset) }
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
      if (Detent.FitContent in requested) maxContentHeight else sheetHeight - bottomInset
    val areaHeight = availableHeight -
      if (!isInline && config.keyboardBehavior == KeyboardBehavior.RESIZE) keyboardOverlap else 0
    val sheetWidth = minOf(coordinator.width, behavior.maxWidth.takeIf { it > 0 } ?: Int.MAX_VALUE)
    reportContentArea(sheetWidth - systemInsets.left - systemInsets.right, areaHeight)
  }

  private fun contentHeightFor(detent: Detent): Int = when (detent) {
    Detent.Small -> (maxContentHeight * 0.25f).toInt()
    Detent.Medium -> (maxContentHeight * 0.5f).toInt()
    Detent.Large -> maxContentHeight
    Detent.FitContent -> contentRoot.contentHeight.coerceIn(1, maxContentHeight)
    is Detent.Height -> activity.dpToPx(detent.dp).toInt().coerceIn(1, maxContentHeight)
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
      sortedDetents.indices.minBy { abs(sortedDetents[it].height - target) }
    }
    return states[position]
  }

  /**
   * Inline: React lays the content out at the sheet's resting top, so what
   * `measure()` reports (and presses rely on) matches the screen.
   */
  private fun reportRestingTop() {
    if (!isInline || isDismissing || behavior.state !in states()) return
    val top = sheet.top
    if (top == reportedRestingTop) return
    reportedRestingTop = top
    listener.onRestingTopChange(top)
  }

  /**
   * Lifts the sheet above the keyboard without relayout (`pan` and `resize`,
   * modal only), and moves it down while it's pulled to refresh.
   */
  private fun applyTranslation() {
    // The back commit animation owns translationY until the window goes.
    if (committingBack) return
    val lift = if (isInline || config.keyboardBehavior == KeyboardBehavior.NONE) {
      0
    } else {
      val headroom = (sheet.top - systemInsets.top).coerceAtLeast(0)
      keyboardOverlap.coerceAtMost(headroom)
    }
    sheet.translationY = pullOffset - lift
  }

  private fun setPullOffset(offset: Float) {
    pullOffset = offset
    applyTranslation()
    layoutRefreshIndicator()
  }

  private fun animatePullOffset(target: Float) {
    if (coordinator.isPulling) return
    if (pullAnimator == null && pullOffset == target) return
    pullAnimator?.cancel()
    pullAnimator = ValueAnimator.ofFloat(pullOffset, target).apply {
      duration = PULL_RELEASE_DURATION
      interpolator = DecelerateInterpolator()
      addUpdateListener { setPullOffset(it.animatedValue as Float) }
      doOnEnd { if (pullAnimator === it) pullAnimator = null }
      start()
    }
  }

  /** Where the sheet rests below its lowest detent: held there while refreshing. */
  private fun restingPullOffset(): Float =
    if (refreshing && behavior.state == states().firstOrNull()) refreshThreshold else 0f

  private fun setRefreshing(value: Boolean) {
    if (value == refreshing) return
    refreshing = value
    if (presented && !isDismissing) animatePullOffset(restingPullOffset())
  }

  /**
   * Centered in the gap a pull uncovers below the lowest detent, fading in
   * as the pull nears the threshold.
   */
  private fun layoutRefreshIndicator() {
    val size = refreshIndicator.layoutParams?.height ?: return
    refreshIndicator.translationY = sheet.top + sheet.translationY - (refreshThreshold + size) / 2
    val progress = if (config.refreshing == null || isDismissing) 0f else (pullOffset / refreshThreshold).coerceIn(0f, 1f)
    refreshIndicator.alpha = progress
    // Hidden, the indeterminate animation stops.
    refreshIndicator.visibility = if (progress > 0f) View.VISIBLE else View.INVISIBLE
  }

  /**
   * Gives up dragging the sheet down on nested scrollable content once that
   * content scrolled during the gesture: it scrolls back to its top, and only
   * a new gesture collapses the sheet.
   */
  private inner class SheetBehavior : BottomSheetBehavior<FrameLayout>() {
    private var contentScrolled = false

    override fun onInterceptTouchEvent(parent: CoordinatorLayout, child: FrameLayout, event: MotionEvent): Boolean {
      if (event.actionMasked == MotionEvent.ACTION_DOWN && state in states()) {
        // React may have mounted the scrollable content after the last layout
        // pass; laying out again re-finds it. The resting sheet doesn't move.
        onLayoutChild(parent, child, parent.layoutDirection)
      }
      return super.onInterceptTouchEvent(parent, child, event)
    }

    override fun onStartNestedScroll(
      coordinatorLayout: CoordinatorLayout,
      child: FrameLayout,
      directTargetChild: View,
      target: View,
      axes: Int,
      type: Int,
    ): Boolean {
      if (type == ViewCompat.TYPE_TOUCH) contentScrolled = false
      return super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target, axes, type)
    }

    override fun onNestedPreScroll(
      coordinatorLayout: CoordinatorLayout,
      child: FrameLayout,
      target: View,
      dx: Int,
      dy: Int,
      consumed: IntArray,
      type: Int,
    ) {
      if (dy < 0 && contentScrolled) return
      super.onNestedPreScroll(coordinatorLayout, child, target, dx, dy, consumed, type)
    }

    override fun onNestedScroll(
      coordinatorLayout: CoordinatorLayout,
      child: FrameLayout,
      target: View,
      dxConsumed: Int,
      dyConsumed: Int,
      dxUnconsumed: Int,
      dyUnconsumed: Int,
      type: Int,
      consumed: IntArray,
    ) {
      if (type == ViewCompat.TYPE_TOUCH && dyConsumed != 0) contentScrolled = true
      super.onNestedScroll(
        coordinatorLayout, child, target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed,
      )
    }
  }

  /**
   * The sheet's parent. Touches that miss the sheet fall through to the views
   * behind it (inline), JS touches are cancelled when the sheet takes over a
   * gesture, and pulling the sheet down past its lowest detent refreshes:
   * `BottomSheetBehavior` stops there, so that pull is handled here.
   */
  @SuppressLint("ViewConstructor")
  private inner class SheetLayout(context: Context) : CoordinatorLayout(context), ReactPointerEventsView {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastEvent: MotionEvent? = null
    private var downX = 0f
    private var downY = 0f
    private var canPull = false
    private var pulling = false
    private var armed = false

    val isPulling: Boolean
      get() = pulling

    override val pointerEvents: PointerEvents
      get() = PointerEvents.BOX_NONE

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
      lastEvent?.recycle()
      lastEvent = MotionEvent.obtain(ev)
      return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
      if (trackPull(ev)) return true
      return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
      if (!pulling && trackPull(ev)) {
        // The behavior had the gesture: let it go of it.
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.onTouchEvent(cancel)
        cancel.recycle()
        return true
      }
      if (!pulling) return super.onTouchEvent(ev)
      when (ev.actionMasked) {
        MotionEvent.ACTION_MOVE -> {
          val offset = rubberBand((ev.y - downY - touchSlop).coerceAtLeast(0f), height.toFloat())
          setPullOffset(offset)
          val nowArmed = offset >= refreshThreshold
          if (nowArmed && !armed) performHapticFeedback(THRESHOLD_FEEDBACK)
          armed = nowArmed
        }
        MotionEvent.ACTION_UP -> endPull(ev, refresh = armed)
        MotionEvent.ACTION_CANCEL -> endPull(ev, refresh = false)
      }
      return true
    }

    /** Follows a gesture until it turns out to be a pull. Returns true once it is. */
    private fun trackPull(ev: MotionEvent): Boolean {
      when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = ev.x
          downY = ev.y
          canPull = canStartPull(ev.x, ev.y)
        }
        MotionEvent.ACTION_POINTER_DOWN -> canPull = false
        MotionEvent.ACTION_MOVE -> if (canPull && !pulling) {
          val dx = ev.x - downX
          val dy = ev.y - downY
          if (dy > touchSlop && dy > abs(dx)) {
            pulling = true
            armed = false
            parent?.requestDisallowInterceptTouchEvent(true)
            notifyNativeGesture(started = true)
            return true
          }
          if (abs(dx) > touchSlop || dy < -touchSlop) canPull = false
        }
      }
      return false
    }

    /**
     * Only from the lowest detent at rest, on the sheet, and where nothing
     * under the finger can scroll up instead.
     */
    private fun canStartPull(x: Float, y: Float): Boolean {
      if (!config.pullToRefreshEnabled || config.dismissOnSwipe || !presented || isDismissing) return false
      if (refreshing || pullOffset != 0f || behavior.state != states().firstOrNull()) return false
      val top = sheet.top + sheet.translationY
      if (y < top || x < sheet.left || x >= sheet.right) return false
      return !canScrollUpAt(sheet, x - sheet.left, y - top)
    }

    private fun endPull(ev: MotionEvent, refresh: Boolean) {
      pulling = false
      armed = false
      canPull = false
      notifyNativeGesture(started = false, event = ev)
      // Settles on the refreshing position; React confirms or ends it.
      if (refresh && config.refreshing != null) refreshing = true
      animatePullOffset(restingPullOffset())
      if (refresh) listener.onPullToRefresh()
    }

    fun cancelPull() {
      pulling = false
      canPull = false
      pullAnimator?.cancel()
      pullAnimator = null
      setPullOffset(0f)
    }

    /**
     * Inline, the sheet lives inside the React root view, which forwards
     * touches to JS. Tell it when the sheet takes over (or lets go of) a
     * gesture, so a press under the finger is cancelled. A modal's content
     * root hears about it from the touch cancellation instead.
     */
    fun notifyNativeGesture(started: Boolean, event: MotionEvent? = lastEvent) {
      if (!isInline) return
      val ev = event ?: return
      if (started) {
        NativeGestureUtil.notifyNativeGestureStarted(this, ev)
      } else {
        NativeGestureUtil.notifyNativeGestureEnded(this, ev)
      }
    }

    fun release() {
      pullAnimator?.cancel()
      pullAnimator = null
      lastEvent?.recycle()
      lastEvent = null
    }
  }

  companion object {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val DEFAULT_CORNER_RADIUS_DP = 28f
    private const val MAX_WIDTH_DP = 640f

    /** How far below its lowest detent the sheet must be pulled to refresh. */
    private const val REFRESH_THRESHOLD_DP = 56f
    private const val REFRESH_INDICATOR_SIZE_DP = 32f
    private const val PULL_RELEASE_DURATION = 300L
    private val THRESHOLD_FEEDBACK =
      if (Build.VERSION.SDK_INT >= 34) {
        HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
      } else {
        HapticFeedbackConstants.CONTEXT_CLICK
      }

    /** `MaterialBottomContainerBackHelper`'s default hide durations. */
    private const val BACK_HIDE_MAX_DURATION = 300L
    private const val BACK_HIDE_MIN_DURATION = 150L
    private val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private fun rubberBand(offset: Float, dimension: Float): Float {
      if (dimension <= 0f) return 0f
      return (1f - 1f / (offset * 0.55f / dimension + 1f)) * dimension
    }

    /** Whether a view under (x, y), in [view]'s coordinates, can scroll up. */
    private fun canScrollUpAt(view: View, x: Float, y: Float): Boolean {
      if (view.canScrollVertically(-1)) return true
      if (view !is ViewGroup) return false
      for (i in view.childCount - 1 downTo 0) {
        val child = view.getChildAt(i)
        if (child.visibility != View.VISIBLE) continue
        val childX = x + view.scrollX - child.left - child.translationX
        val childY = y + view.scrollY - child.top - child.translationY
        if (childX >= 0 && childY >= 0 && childX < child.width && childY < child.height &&
          canScrollUpAt(child, childX, childY)
        ) {
          return true
        }
      }
      return false
    }

    /** Content area before the sheet is on screen, in px. */
    fun estimateContentArea(
      activity: Activity,
      detents: List<Detent>,
      width: Int,
      height: Int,
      insets: Insets,
      isInline: Boolean,
    ): Pair<Int, Int> {
      val maxContent = height - insets.top - insets.bottom
      val contentHeight = if (detents.isEmpty()) {
        maxContent
      } else {
        detents.maxOf {
          when (it) {
            Detent.Small -> (maxContent * 0.25f).toInt()
            Detent.Medium -> (maxContent * 0.5f).toInt()
            // fitContent content can grow up to the maximum.
            Detent.Large, Detent.FitContent -> maxContent
            is Detent.Height -> activity.dpToPx(it.dp).toInt().coerceIn(1, maxContent.coerceAtLeast(1))
          }
        }
      }
      val sheetWidth = if (isInline) width else minOf(width, activity.dpToPx(MAX_WIDTH_DP).toInt())
      return Pair(sheetWidth - insets.left - insets.right, contentHeight)
    }
  }
}
