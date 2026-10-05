package com.margelo.nitro.nitromodal

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
 * Everything here runs on the UI thread.
 */
internal abstract class ModalPresenter(
  protected val activity: Activity,
  private val reactContext: ThemedReactContext,
  protected val contentRoot: ModalContentRoot,
  config: ModalConfig,
  protected val listener: Listener,
) {
  interface Listener {
    fun onPresented()
    /** The window is gone. Called exactly once per presenter. */
    fun onDismissed(reason: DismissReason)
    fun onBackdropPress()
    fun onBackPress()
    fun onDetentChange(index: Int)
    /** Area available to the content, in px. */
    fun onContentAreaChange(width: Int, height: Int)
  }

  protected var config: ModalConfig = config
    private set

  protected val dialog = ComponentDialog(activity, R.style.NitroModal_Dialog)
  protected val backdrop = View(activity)

  /** System bars + display cutout. */
  protected var systemInsets: Insets = Insets.NONE
    private set

  /** Current keyboard height, updated every frame while it animates. */
  protected var imeHeight: Int = 0
    private set

  private var imeAnimating = false
  private var presentedNotified = false
  private var finished = false
  private var dismissReason = DismissReason.PROGRAMMATIC

  protected var isDismissing = false
    private set

  protected abstract fun createLayout(): ViewGroup
  protected abstract fun animateIn()
  protected abstract fun animateOut(onEnd: () -> Unit)
  protected abstract fun onConfigChanged(previous: ModalConfig)

  /** System insets or the settled keyboard height changed. */
  protected abstract fun onInsetsChanged()

  /** Called every frame while the keyboard animates. Must not trigger layout. */
  protected abstract fun onKeyboardProgress()

  abstract fun onContentSizeChanged()

  open fun snapToDetent(index: Int) = Unit

  fun show() {
    (contentRoot.parent as? ViewGroup)?.removeView(contentRoot)
    val root = createLayout()
    backdrop.setBackgroundColor(config.effectiveBackdropColor)
    backdrop.alpha = 0f
    backdrop.setOnClickListener { listener.onBackdropPress() }

    dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    dialog.setCancelable(false)
    dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() = listener.onBackPress()
    })
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

    animateIn()
  }

  fun update(newConfig: ModalConfig) {
    val previous = config
    if (previous == newConfig) return
    config = newConfig
    backdrop.setBackgroundColor(config.effectiveBackdropColor)
    if (previous.backdropBlurRadius != newConfig.backdropBlurRadius) applyBlur()
    onConfigChanged(previous)
  }

  /** Animated dismissal. [Listener.onDismissed] follows when it completes. */
  fun dismiss(reason: DismissReason) {
    if (isDismissing || finished) return
    isDismissing = true
    dismissReason = reason
    hideKeyboard()
    animateOut { finish() }
  }

  /** Tears the window down without animating or notifying the listener. */
  fun dismissImmediately() {
    if (finished) return
    finished = true
    teardown()
  }

  /** For subclasses: the enter animation finished. */
  protected fun notifyPresented() {
    if (presentedNotified || isDismissing || finished) return
    presentedNotified = true
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

  private fun finish() {
    if (finished) return
    finished = true
    teardown()
    listener.onDismissed(dismissReason)
  }

  private fun teardown() {
    backdrop.animate().cancel()
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
    val token = dialog.window?.decorView?.windowToken ?: return
    (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)
      ?.hideSoftInputFromWindow(token, 0)
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
}
