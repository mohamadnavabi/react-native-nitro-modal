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
  override var detents: Array<SheetDetent> = arrayOf(SheetDetent.FITCONTENT)
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

  override var onPresent: (() -> Unit)? = null
  override var onDismiss: ((reason: DismissReason) -> Unit)? = null
  override var onDetentChange: ((index: Double) -> Unit)? = null
  override var onBackdropPress: (() -> Unit)? = null
  override var onBackButtonPress: (() -> Unit)? = null
  override var onContentAreaChange: ((area: ModalContentArea) -> Unit)? = null

  // endregion

  private enum class Phase { IDLE, PRESENTING, PRESENTED, DISMISSING }

  private val mainHandler = Handler(Looper.getMainLooper())
  private var config = ModalConfig()
  private var phase = Phase.IDLE

  /** What React (or an imperative call) asked for. */
  private var wantsOpen = false

  /** A present request was accepted and its `onDismiss` is still owed. */
  private var sessionActive = false
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
    view.contentRoot.onContentSizeChange = { presenter?.onContentSizeChanged() }
  }

  // region HybridView

  override fun afterUpdate() {
    val next = makeConfig()
    if (next == config) return
    config = next
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
      Phase.PRESENTING, Phase.DISMISSING -> Unit // The transition reconciles when done.
      Phase.PRESENTED -> if (!wantsOpen) startDismiss()
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

    val next = when (config.mode) {
      ModalMode.POPUP -> PopupPresenter(activity, reactContext, view.contentRoot, config, this)
      ModalMode.BOTTOMSHEET -> BottomSheetPresenter(activity, reactContext, view.contentRoot, config, this)
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
    if (!isDropped) onPresent?.invoke()
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

  private fun makeConfig() = ModalConfig(
    mode = mode,
    detents = detents.toList(),
    initialDetentIndex = if (initialDetentIndex.isNaN()) 0 else initialDetentIndex.toInt(),
    backdropColor = backdropColor.toProcessedColor(),
    backdropOpacity = backdropOpacity.toFloat(),
    backdropBlurRadius = backdropBlurRadius.toFloat(),
    dismissOnBackdropPress = dismissOnBackdropPress,
    dismissOnSwipe = dismissOnSwipe,
    dismissOnBackButton = dismissOnBackButton,
    grabberVisible = grabberVisible,
    cornerRadius = cornerRadius.takeIf { it >= 0 }?.toFloat(),
    contentBackgroundColor = contentBackgroundColor?.toProcessedColor(),
    keyboardBehavior = keyboardBehavior,
    popupAnimation = popupAnimation,
  )

  /** Lets React size the content before the first presentation. */
  private fun reportEstimatedContentArea() {
    val activity = reactContext.currentActivity ?: return
    val decor = activity.window?.decorView ?: return
    if (decor.width == 0 || decor.height == 0) return
    val insets = ViewCompat.getRootWindowInsets(decor)
      ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      ?: Insets.NONE
    val (width, height) = when (config.mode) {
      ModalMode.POPUP -> PopupPresenter.estimateContentArea(activity, decor.width, decor.height, insets)
      ModalMode.BOTTOMSHEET -> BottomSheetPresenter.estimateContentArea(activity, config.detents, decor.width, decor.height, insets)
    }
    onContentAreaChange(width, height)
  }
}
