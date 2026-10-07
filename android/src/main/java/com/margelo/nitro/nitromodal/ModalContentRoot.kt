package com.margelo.nitro.nitromodal

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import com.facebook.react.common.annotations.UnstableReactNativeAPI
import com.facebook.react.config.ReactFeatureFlags
import com.facebook.react.uimanager.JSPointerDispatcher
import com.facebook.react.uimanager.JSTouchDispatcher
import com.facebook.react.uimanager.RootView
import com.facebook.react.uimanager.RootViewUtil
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.EventDispatcher
import com.facebook.react.views.view.ReactViewGroup

/**
 * Holds the React children inside the modal window.
 *
 * The dialog is a separate window, so touches there never reach the React
 * root view. Like React Native's own `Modal`, this view is a [RootView] that
 * forwards touch and pointer events to JS itself. Children are laid out by
 * Fabric; [ReactViewGroup.onLayout] is a no-op.
 *
 * An inline sheet keeps this view inside the React root view, which already
 * forwards its touches: then it only passes native gestures on to that root.
 */
@SuppressLint("ViewConstructor")
internal class ModalContentRoot(private val reactContext: ThemedReactContext) :
  ReactViewGroup(reactContext), RootView {

  var eventDispatcher: EventDispatcher? = null

  /** Called when a child's laid-out bounds change. */
  var onContentSizeChange: (() -> Unit)? = null

  /** Hosted by an inline sheet, inside the React root view. */
  var isInline: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      updateReactOrigin()
    }

  private val touchDispatcher = JSTouchDispatcher(this)
  private val pointerDispatcher: JSPointerDispatcher? =
    if (ReactFeatureFlags.dispatchPointerEvents) JSPointerDispatcher(this) else null

  /** Dispatches touches to JS itself: only outside the React root view. */
  private val activeEventDispatcher: EventDispatcher?
    get() = if (isInline) null else eventDispatcher

  private val childLayoutListener =
    OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
        updateReactOrigin()
        onContentSizeChange?.invoke()
      }
    }

  /**
   * Where React placed the children, in px. Inline, React lays the content
   * out at the sheet's resting top so that `measure()` matches the screen;
   * scrolling this view by the same amount cancels that offset out on screen.
   */
  private val reactOriginY: Int
    get() = if (isInline) (0 until childCount).minOfOrNull { getChildAt(it).top } ?: 0 else 0

  /** Bounding size of the React children, in px. */
  val contentWidth: Int
    get() = (0 until childCount).maxOfOrNull { getChildAt(it).right } ?: 0

  val contentHeight: Int
    get() = ((0 until childCount).maxOfOrNull { getChildAt(it).bottom } ?: 0) - reactOriginY

  override fun onViewAdded(child: View) {
    super.onViewAdded(child)
    child.addOnLayoutChangeListener(childLayoutListener)
    updateReactOrigin()
    onContentSizeChange?.invoke()
  }

  override fun onViewRemoved(child: View) {
    super.onViewRemoved(child)
    child.removeOnLayoutChangeListener(childLayoutListener)
    updateReactOrigin()
    onContentSizeChange?.invoke()
  }

  private fun updateReactOrigin() {
    val originY = reactOriginY
    if (scrollY != originY) scrollTo(0, originY)
  }

  // region Touch forwarding (mirrors ReactModalHostView.DialogRootViewGroup)

  override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
    activeEventDispatcher?.let {
      touchDispatcher.handleTouchEvent(event, it, reactContext)
      pointerDispatcher?.handleMotionEvent(event, it, true)
    }
    return super.onInterceptTouchEvent(event)
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    activeEventDispatcher?.let {
      touchDispatcher.handleTouchEvent(event, it, reactContext)
      pointerDispatcher?.handleMotionEvent(event, it, false)
    }
    super.onTouchEvent(event)
    // Keep receiving the gesture even if no child is interested in it.
    return true
  }

  override fun onInterceptHoverEvent(event: MotionEvent): Boolean {
    activeEventDispatcher?.let { pointerDispatcher?.handleMotionEvent(event, it, true) }
    return super.onInterceptHoverEvent(event)
  }

  override fun onHoverEvent(event: MotionEvent): Boolean {
    activeEventDispatcher?.let { pointerDispatcher?.handleMotionEvent(event, it, false) }
    return super.onHoverEvent(event)
  }

  @OptIn(UnstableReactNativeAPI::class)
  override fun onChildStartedNativeGesture(childView: View?, ev: MotionEvent) {
    if (isInline) {
      outerRootView()?.onChildStartedNativeGesture(childView, ev)
      return
    }
    eventDispatcher?.let {
      touchDispatcher.onChildStartedNativeGesture(ev, it, reactContext)
      pointerDispatcher?.onChildStartedNativeGesture(childView, ev, it)
    }
  }

  override fun onChildEndedNativeGesture(childView: View, ev: MotionEvent) {
    if (isInline) {
      outerRootView()?.onChildEndedNativeGesture(childView, ev)
      return
    }
    eventDispatcher?.let { touchDispatcher.onChildEndedNativeGesture(ev, it) }
    pointerDispatcher?.onChildEndedNativeGesture()
  }

  /** The React root view an inline sheet lives in. */
  private fun outerRootView(): RootView? = (parent as? View)?.let { RootViewUtil.getRootView(it) }

  override fun handleException(t: Throwable) {
    reactContext.reactApplicationContext.handleException(RuntimeException(t))
  }

  override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
    // Keep seeing events in onInterceptTouchEvent, but still let a nested
    // scroller stop the sheet's drag behavior from stealing its gesture.
    parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
  }

  // endregion
}
