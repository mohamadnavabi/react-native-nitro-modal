package com.margelo.nitro.nitromodal

import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.uimanager.ThemedReactContext

/**
 * The Nitro Hybrid View. Props arrive on the UI thread (Fabric `updateState`);
 * methods arrive on the JS thread and hop to the UI thread.
 *
 * Presentation is a small state machine so that rapid `isOpen` toggles,
 * native dismissals and unmounts always settle on a consistent state, and
 * every accepted present request ends with exactly one `onDismiss`.
 * Transitions are interrupted rather than queued: closing mid-enter turns the
 * enter around, and opening mid-exit turns the exit around (that session
 * carries on, so it gets no `onDismiss` and at most one `onPresent`).
 */
@DoNotStrip
class HybridNitroModal(private val reactContext: ThemedReactContext) :
  HybridNitroModalSpec(), LifecycleEventListener, ModalPresenter.Listener {

  override val view = NitroModalHostView(reactContext)

  // region Props

  override var isOpen: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      if (value) requestPresent() else requestDismiss(DismissReason.PROGRAMMATIC)
    }

  override var mode: ModalMode = ModalMode.BOTTOMSHEET
  override var isInline: Boolean = false
  override var detents: Array<SheetDetent> = arrayOf(SheetDetent.create(NamedSheetDetent.FITCONTENT))
  override var initialDetentIndex: Double = 0.0
  override var backdropColor: Double = 0xFF000000.toDouble()
  override var backdropOpacity: Double = 0.4
  override var backdropBlurRadius: Double = 0.0
  override var dismissOnBackdropPress: Boolean = true
  override var dismissOnSwipe: Boolean = true
  override var dismissOnBackButton: Boolean = true
  override var grabberVisible: Boolean = false
  override var cornerRadius: Double = -1.0
  override var contentBackgroundColor: Double? = null
  override var keyboardBehavior: KeyboardBehavior = KeyboardBehavior.PAN
  override var popupAnimation: PopupAnimation = PopupAnimation.SCALE
  override var pullToRefreshEnabled: Boolean = false
  override var refreshing: Boolean? = null
  override var refreshIndicatorColor: Double? = null

  override var onPresent: (() -> Unit)? = null
  override var onDismiss: ((reason: DismissReason) -> Unit)? = null
  override var onDetentChange: ((index: Double) -> Unit)? = null
  override var onBackdropPress: (() -> Unit)? = null
  override var onBackButtonPress: (() -> Unit)? = null
  override var onContentAreaChange: ((area: ModalContentArea) -> Unit)? = null
  override var onPullToRefresh: (() -> Unit)? = null
  override var onRestingTopChange: ((top: Double) -> Unit)? = null

  // endregion

  private enum class Phase { IDLE, PRESENTING, PRESENTED, DISMISSING }

  private val mainHandler = Handler(Looper.getMainLooper())
  private var config = ModalConfig()
  private var phase = Phase.IDLE

  /** What React (or an imperative call) asked for. */
  private var wantsOpen = false

  /** A present request was accepted and its `onDismiss` is still owed. */
  private var sessionActive = false

  /** `onPresent` was sent for the current session. */
  private var presentSent = false
  private var dismissReason = DismissReason.PROGRAMMATIC
  private var reconcileScheduled = false
  private var isDropped = false
  private var presenter: ModalPresenter? = null
  private var lastContentArea: ModalContentArea? = null

  private val reconcileRunnable = Runnable {
    reconcileScheduled = false
    reconcile()
  }

  init {
    reactContext.addLifecycleEventListener(this)
    view.onAttachedChange = { attached ->
      if (attached) {
        if (presenter == null) reportEstimatedContentArea()
        scheduleReconcile()
      }
    }
    view.onLayoutChange = {
      presenter?.onHostLayoutChanged() ?: run {
        if (config.isInline) {
          reportEstimatedContentArea()
          if (wantsOpen) scheduleReconcile()
        }
      }
    }
    view.contentRoot.onContentSizeChange = { presenter?.onContentSizeChanged() }
  }

  // region HybridView

  override fun afterUpdate() {
    val next = makeConfig()
    if (next == config) return
    config = next
    view.isInline = next.isInline
    presenter?.update(next) ?: reportEstimatedContentArea()
  }

  override fun onDropView() {
    isDropped = true
    wantsOpen = false
    sessionActive = false
    mainHandler.removeCallbacksAndMessages(null)
    reactContext.removeLifecycleEventListener(this)
    teardownPresenter()
  }

  // endregion

  // region Methods (JS thread)

  override fun present() {
    mainHandler.post { requestPresent() }
  }

  override fun dismiss() {
    mainHandler.post { requestDismiss(DismissReason.PROGRAMMATIC) }
  }

  override fun snapToDetent(index: Double) {
    if (index.isNaN()) return
    mainHandler.post { presenter?.snapToDetent(index.toInt()) }
  }

  // endregion

  // region State machine

  private fun requestPresent() {
    if (isDropped) return
    wantsOpen = true
    sessionActive = true
    scheduleReconcile()
  }

  private fun requestDismiss(reason: DismissReason) {
    if (wantsOpen) dismissReason = reason
    wantsOpen = false
    scheduleReconcile()
  }

  /** Posted so children and layout from the same Fabric batch are mounted first. */
  private fun scheduleReconcile() {
    if (reconcileScheduled) return
    reconcileScheduled = true
    mainHandler.post(reconcileRunnable)
  }

  private fun reconcile() {
    when (phase) {
      Phase.PRESENTING, Phase.PRESENTED -> if (!wantsOpen) startDismiss()
      // Not reversible (e.g. a committed back gesture): finishDismiss reconciles.
      Phase.DISMISSING -> if (wantsOpen && presenter?.cancelDismiss() == true) {
        phase = Phase.PRESENTING
        dismissReason = DismissReason.PROGRAMMATIC
      }
      Phase.IDLE -> when {
        wantsOpen -> startPresent()
        sessionActive -> endSession() // Cancelled before anything appeared.
      }
    }
  }

  private fun startPresent() {
    // Without an attached host or activity there is nothing to present over;
    // attaching / resuming retries.
    if (!view.isAttachedToWindow) return
    val activity = reactContext.currentActivity ?: return
    if (activity.isFinishing || activity.isDestroyed) return
    // An inline sheet needs its laid-out area; the host's layout retries.
    if (config.isInline && (view.width == 0 || view.height == 0)) return

    val next = when {
      config.isInline ->
        BottomSheetPresenter(activity, reactContext, view.contentRoot, config, this, inlineHost = view)
      config.mode == ModalMode.POPUP -> PopupPresenter(activity, reactContext, view.contentRoot, config, this)
      else -> BottomSheetPresenter(activity, reactContext, view.contentRoot, config, this)
    }
    presenter = next
    phase = Phase.PRESENTING
    try {
      next.show()
    } catch (e: WindowManager.BadTokenException) {
      // The activity window isn't ready; retry on the next resume.
      next.dismissImmediately()
      presenter = null
      phase = Phase.IDLE
    }
  }

  private fun startDismiss() {
    val current = presenter ?: return finishDismiss()
    phase = Phase.DISMISSING
    current.dismiss(dismissReason)
  }

  private fun finishDismiss() {
    phase = Phase.IDLE
    presenter = null
    endSession()
    reconcile()
  }

  private fun endSession() {
    if (!sessionActive) return
    val reason = dismissReason
    dismissReason = DismissReason.PROGRAMMATIC
    presentSent = false
    // A present requested while we were dismissing starts a new session.
    sessionActive = wantsOpen
    if (!isDropped) onDismiss?.invoke(reason)
  }

  private fun teardownPresenter() {
    presenter?.dismissImmediately()
    presenter = null
    phase = Phase.IDLE
  }

  // endregion

  // region ModalPresenter.Listener

  override fun onPresented() {
    if (phase != Phase.PRESENTING) return
    phase = Phase.PRESENTED
    if (!presentSent) {
      presentSent = true
      if (!isDropped) onPresent?.invoke()
    }
    reconcile()
  }

  override fun onDismissed(reason: DismissReason) {
    if (phase != Phase.DISMISSING) {
      // Dismissed natively (swipe) rather than by us.
      wantsOpen = false
      dismissReason = reason
    }
    finishDismiss()
  }

  override fun onBackdropPress() {
    onBackdropPress?.invoke()
    if (!config.dismissOnBackdropPress) return
    requestDismiss(DismissReason.BACKDROP)
    reconcile()
  }

  override fun onBackPress() {
    onBackButtonPress?.invoke()
    if (!config.dismissOnBackButton) return
    requestDismiss(DismissReason.BACKBUTTON)
    reconcile()
  }

  override fun onDetentChange(index: Int) {
    onDetentChange?.invoke(index.toDouble())
  }

  override fun onPullToRefresh() {
    if (!isDropped) onPullToRefresh?.invoke()
  }

  override fun onRestingTopChange(top: Int) {
    if (!isDropped) onRestingTopChange?.invoke(kotlin.math.round(reactContext.pxToDp(top)))
  }

  override fun onContentAreaChange(width: Int, height: Int) {
    val area = ModalContentArea(
      kotlin.math.floor(reactContext.pxToDp(width)),
      kotlin.math.floor(reactContext.pxToDp(height)),
    )
    if (area == lastContentArea || area.width <= 0 || area.height <= 0) return
    lastContentArea = area
    onContentAreaChange?.invoke(area)
  }

  // endregion

  // region LifecycleEventListener

  override fun onHostResume() = scheduleReconcile()

  override fun onHostPause() = Unit

  override fun onHostDestroy() {
    // The activity is going away; its windows go with it.
    teardownPresenter()
    if (sessionActive) {
      wantsOpen = false
      endSession()
    }
  }

  // endregion

  private fun makeConfig(): ModalConfig {
    // An inline sheet is part of the screen; the user can't dismiss it.
    val inline = isInline && mode == ModalMode.BOTTOMSHEET
    return ModalConfig(
      mode = mode,
      inlinePresentation = isInline,
      detents = detents.map(Detent::from),
      initialDetentIndex = if (initialDetentIndex.isNaN()) 0 else initialDetentIndex.toInt(),
      backdropColor = backdropColor.toProcessedColor(),
      backdropOpacity = backdropOpacity.toFloat(),
      backdropBlurRadius = backdropBlurRadius.toFloat(),
      dismissOnBackdropPress = dismissOnBackdropPress && !inline,
      dismissOnSwipe = dismissOnSwipe && !inline,
      dismissOnBackButton = dismissOnBackButton && !inline,
      grabberVisible = grabberVisible,
      cornerRadius = cornerRadius.takeIf { it >= 0 }?.toFloat(),
      contentBackgroundColor = contentBackgroundColor?.toProcessedColor(),
      keyboardBehavior = keyboardBehavior,
      popupAnimation = popupAnimation,
      pullToRefreshEnabled = pullToRefreshEnabled,
      refreshing = refreshing,
      refreshIndicatorColor = refreshIndicatorColor?.toProcessedColor(),
    )
  }

  /** Lets React size the content before the first presentation. */
  private fun reportEstimatedContentArea() {
    val activity = reactContext.currentActivity ?: return
    if (config.isInline) {
      if (view.width == 0 || view.height == 0) return
      // Insets are refined once the sheet is shown.
      val (width, height) = BottomSheetPresenter.estimateContentArea(
        activity, config.detents, view.width, view.height, Insets.NONE, isInline = true,
      )
      onContentAreaChange(width, height)
      return
    }
    val decor = activity.window?.decorView ?: return
    if (decor.width == 0 || decor.height == 0) return
    val insets = ViewCompat.getRootWindowInsets(decor)
      ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      ?: Insets.NONE
    val (width, height) = when (config.mode) {
      ModalMode.POPUP -> PopupPresenter.estimateContentArea(activity, decor.width, decor.height, insets)
      ModalMode.BOTTOMSHEET ->
        BottomSheetPresenter.estimateContentArea(activity, config.detents, decor.width, decor.height, insets, isInline = false)
    }
    onContentAreaChange(width, height)
  }
}
