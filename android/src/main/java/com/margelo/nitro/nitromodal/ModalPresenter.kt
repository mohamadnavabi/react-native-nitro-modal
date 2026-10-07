package com.margelo.nitro.nitromodal

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.react.uimanager.ThemedReactContext

/**
 * Owns one modal window from show to dismiss. Subclasses build the layout and
 * drive the enter/exit animations; this class handles the window, edge-to-edge
 * insets, keyboard tracking, back handling and teardown.
 *
 * With an [inlineHost], there is no window: the layout fills that view, in
 * the screen's own hierarchy, and the keyboard and back button are left to
 * the screen.
 *
 * Transitions are interruptible like native ones: dismissing mid-enter turns
 * the enter around, and [cancelDismiss] turns an exit back around. Once an
 * exit starts the window stops taking input, as a dismissed dialog would.
 *
 * Everything here runs on the UI thread.
 */
internal abstract class ModalPresenter(
  protected val activity: Activity,
  private val reactContext: ThemedReactContext,
  protected val contentRoot: ModalContentRoot,
  config: ModalConfig,
  protected val listener: Listener,
  private val inlineHost: NitroModalHostView? = null,
) {
  interface Listener {
    /** An enter transition settled: the first one, or one that reversed an exit. */
    fun onPresented()
    /** The window is gone. Called exactly once per presenter. */
    fun onDismissed(reason: DismissReason)
    fun onBackdropPress()
    fun onBackPress()
    fun onDetentChange(index: Int)
    /** Area available to the content, in px. */
    fun onContentAreaChange(width: Int, height: Int)
    /** The user pulled the sheet past its lowest detent and let go. */
    fun onPullToRefresh()
    /** Inline sheets: the sheet's top at rest, in the host view, in px. */
    fun onRestingTopChange(top: Int)
  }

  protected var config: ModalConfig = config
    private set

  /** Lives in [inlineHost] rather than in a window of its own. */
  protected val isInline: Boolean
    get() = inlineHost != null

  protected val dialog by lazy { ComponentDialog(activity, R.style.NitroModal_Dialog) }
  protected val backdrop: View = BackdropView(activity)

  /** System bars + display cutout. */
  protected var systemInsets: Insets = Insets.NONE
    private set

  /** Current keyboard height, updated every frame while it animates. */
  protected var imeHeight: Int = 0
    private set

  private var imeAnimating = false
  private var entering = false
  private var backGestureActive = false
  private var finished = false
  private var dismissReason = DismissReason.PROGRAMMATIC

  protected var isDismissing = false
    private set

  private val backCallback = object : OnBackPressedCallback(true) {
    // Predictive back (Android 14+): preview the exit while the gesture runs.
    override fun handleOnBackStarted(backEvent: BackEventCompat) {
      if (!config.dismissOnBackButton || entering || isDismissing || finished) return
      backGestureActive = true
      onBackGestureStarted(backEvent)
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
      if (backGestureActive) onBackGestureProgressed(backEvent)
    }

    override fun handleOnBackCancelled() = cancelBackGesture()

    override fun handleOnBackPressed() {
      // Already on its way out: the back press is spent.
      if (isDismissing || finished) return
      listener.onBackPress()
      // Still here (back didn't dismiss): settle the preview.
      cancelBackGesture()
    }
  }

  protected abstract fun createLayout(): ViewGroup

  /**
   * Animates from the current state to presented, then calls
   * [notifyPresented]. Runs on show and again when an exit is reversed.
   */
  protected abstract fun animateIn()

  /**
   * Animates from the current state (possibly mid-enter) to gone, then calls
   * [onEnd]. [fromBackGesture]: a predictive back preview is being committed.
   */
  protected abstract fun animateOut(fromBackGesture: Boolean, onEnd: () -> Unit)

  protected abstract fun onConfigChanged(previous: ModalConfig)

  /** System insets or the settled keyboard height changed. */
  protected abstract fun onInsetsChanged()

  /** Called every frame while the keyboard animates. Must not trigger layout. */
  protected abstract fun onKeyboardProgress()

  abstract fun onContentSizeChanged()

  open fun snapToDetent(index: Int) = Unit

  /** Runs once, right before the layout is torn down. */
  protected open fun onTeardown() = Unit

  protected open fun onBackGestureStarted(event: BackEventCompat) = Unit
  protected open fun onBackGestureProgressed(event: BackEventCompat) = Unit
  protected open fun onBackGestureCancelled() = Unit

  /** Whether the running exit can still be turned around. */
  protected open val canReverseDismiss: Boolean
    get() = true

  fun show() {
    (contentRoot.parent as? ViewGroup)?.removeView(contentRoot)
    val root = createLayout()
    inlineHost?.let { host ->
      host.addInlineRoot(root)
      updateInlineInsets()
      entering = true
      animateIn()
      return
    }
    backdrop.setBackgroundColor(config.effectiveBackdropColor)
    backdrop.alpha = 0f
    backdrop.setOnClickListener { if (!isDismissing) listener.onBackdropPress() }

    dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    dialog.setCancelable(false)
    dialog.onBackPressedDispatcher.addCallback(dialog, backCallback)
    observeInsets(root)

    val window = requireNotNull(dialog.window)
    window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isStatusBarContrastEnforced = false
      window.isNavigationBarContrastEnforced = false
    }
    activity.window?.let { activityWindow ->
      val source = WindowCompat.getInsetsController(activityWindow, activityWindow.decorView)
      WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = source.isAppearanceLightStatusBars
        isAppearanceLightNavigationBars = source.isAppearanceLightNavigationBars
      }
      if (activityWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
      }
    }
    applyBlur()

    // Like RN's Modal: not focusable while showing so immersive system bars
    // don't flash, then focusable again so text inputs work.
    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    dialog.show()
    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    reactContext.onExtraWindowCreate(window)

    entering = true
    animateIn()
  }

  fun update(newConfig: ModalConfig) {
    val previous = config
    if (previous == newConfig) return
    config = newConfig
    backdrop.setBackgroundColor(config.effectiveBackdropColor)
    if (!isInline && previous.backdropBlurRadius != newConfig.backdropBlurRadius) applyBlur()
    onConfigChanged(previous)
  }

  /** The inline host moved or resized. */
  fun onHostLayoutChanged() {
    if (isInline && !finished) updateInlineInsets()
  }

  /**
   * Animated dismissal, also from mid-enter. [Listener.onDismissed] follows
   * when it completes, unless [cancelDismiss] turns it around first.
   */
  fun dismiss(reason: DismissReason) {
    if (isDismissing || finished) return
    isDismissing = true
    entering = false
    dismissReason = reason
    val fromBackGesture = backGestureActive
    backGestureActive = false
    releaseInput()
    hideKeyboard()
    animateOut(fromBackGesture) { finish() }
  }

  /**
   * Reverses a running exit from wherever it is. [Listener.onPresented]
   * follows once it settles. Returns false when the exit can't be reversed.
   */
  fun cancelDismiss(): Boolean {
    if (!isDismissing || finished || !canReverseDismiss) return false
    isDismissing = false
    dismissReason = DismissReason.PROGRAMMATIC
    if (!isInline) dialog.window?.clearFlags(INPUT_RELEASE_FLAGS)
    entering = true
    animateIn()
    return true
  }

  /** Tears the window down without animating or notifying the listener. */
  fun dismissImmediately() {
    if (finished) return
    finished = true
    teardown()
  }

  /** For subclasses: the running enter animation settled. */
  protected fun notifyPresented() {
    if (!entering || isDismissing || finished) return
    entering = false
    listener.onPresented()
  }

  /** For subclasses: the user dismissed the modal natively (e.g. swipe). */
  protected fun finishUserDismissal(reason: DismissReason) {
    if (finished) return
    if (!isDismissing) dismissReason = reason
    finish()
  }

  protected fun reportContentArea(width: Int, height: Int) {
    if (width > 0 && height > 0) listener.onContentAreaChange(width, height)
  }

  /** Keyboard overlap below the bottom system inset, in px. */
  protected val keyboardOverlap: Int
    get() = (imeHeight - systemInsets.bottom).coerceAtLeast(0)

  protected fun dp(value: Float): Int = activity.dpToPx(value).toInt()

  private fun cancelBackGesture() {
    if (!backGestureActive) return
    backGestureActive = false
    onBackGestureCancelled()
  }

  /**
   * A dismissed native dialog leaves the screen at once, so taps and back
   * presses during the exit reach whatever is below. Do the same while the
   * exit animates. Focus is kept while the keyboard is up: giving it away
   * then would cut the keyboard's hide animation short.
   */
  private fun releaseInput() {
    if (isInline) return
    val window = dialog.window ?: return
    val keyboardVisible = imeAnimating || imeHeight > 0
    window.addFlags(
      if (keyboardVisible) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else INPUT_RELEASE_FLAGS,
    )
  }

  private fun finish() {
    if (finished) return
    finished = true
    teardown()
    listener.onDismissed(dismissReason)
  }

  private fun teardown() {
    backdrop.animate().cancel()
    onTeardown()
    if (inlineHost != null) {
      (contentRoot.parent as? ViewGroup)?.removeView(contentRoot)
      inlineHost.removeAllViews()
      return
    }
    dialog.window?.let { reactContext.onExtraWindowDestroy(it) }
    if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
      try {
        dialog.dismiss()
      } catch (e: IllegalArgumentException) {
        // The window was already detached from the window manager.
      }
    }
    (contentRoot.parent as? ViewGroup)?.removeView(contentRoot)
  }

  private fun hideKeyboard() {
    if (isInline && !contentRoot.hasFocus()) return
    val token = (if (isInline) contentRoot.windowToken else dialog.window?.decorView?.windowToken) ?: return
    (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)
      ?.hideSoftInputFromWindow(token, 0)
  }

  /**
   * Inline: the system bars and cutout overlapping the host view, so the
   * sheet keeps its content clear of them like a modal sheet does.
   */
  private fun updateInlineInsets() {
    val host = inlineHost ?: return
    val window = ViewCompat.getRootWindowInsets(host)
      ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      ?: Insets.NONE
    val location = IntArray(2)
    host.getLocationInWindow(location)
    val root = host.rootView
    val next = Insets.of(
      (window.left - location[0]).coerceIn(0, host.width),
      (window.top - location[1]).coerceIn(0, host.height),
      (location[0] + host.width - (root.width - window.right)).coerceIn(0, host.width),
      (location[1] + host.height - (root.height - window.bottom)).coerceIn(0, host.height),
    )
    if (next == systemInsets) return
    systemInsets = next
    onInsetsChanged()
  }

  private fun applyBlur() {
    val window = dialog.window ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val radius = dp(config.backdropBlurRadius)
    if (radius > 0 && activity.windowManager.isCrossWindowBlurEnabled) {
      window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
      window.attributes = window.attributes.apply { blurBehindRadius = radius }
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    }
  }

  private fun observeInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      // While the keyboard animates, the end state arrives up front; the
      // animation callback below feeds the in-between frames instead.
      if (!imeAnimating) imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
      onInsetsChanged()
      WindowInsetsCompat.CONSUMED
    }
    ViewCompat.setWindowInsetsAnimationCallback(
      root,
      object : WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
          if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
        }

        override fun onProgress(
          insets: WindowInsetsCompat,
          runningAnimations: MutableList<WindowInsetsAnimationCompat>,
        ): WindowInsetsCompat {
          if (imeAnimating) {
            imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            onKeyboardProgress()
          }
          return insets
        }

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
          if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
          imeAnimating = false
          ViewCompat.getRootWindowInsets(root)?.let {
            imeHeight = it.getInsets(WindowInsetsCompat.Type.ime()).bottom
          }
          onInsetsChanged()
        }
      },
    )
  }

  private companion object {
    const val INPUT_RELEASE_FLAGS =
      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
  }
}

/**
 * Full-window scrim. It draws a single rect, so fading it needs no offscreen
 * layer; the default (overlapping) would allocate a full-screen buffer for
 * every animated frame.
 */
private class BackdropView(context: Context) : View(context) {
  init {
    // Native dialogs don't click when tapped outside.
    isSoundEffectsEnabled = false
  }

  override fun hasOverlappingRendering(): Boolean = false
}
