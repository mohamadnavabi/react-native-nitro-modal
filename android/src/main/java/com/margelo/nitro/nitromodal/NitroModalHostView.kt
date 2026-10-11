package com.margelo.nitro.nitromodal

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import com.facebook.react.uimanager.PointerEvents
import com.facebook.react.uimanager.ReactPointerEventsView
import com.facebook.react.uimanager.ThemedReactContext

/**
 * The view Nitro mounts into the React tree. React children added to it (via
 * [NitroModalViewManager]) are hosted by [contentRoot].
 *
 * Modal: an invisible placeholder; [contentRoot] travels into the modal
 * window. Inline: the area the sheet lives in. Its only child is the sheet's
 * layout, which it lays out itself, and only touches on the sheet hit it.
 */
@SuppressLint("ViewConstructor")
class NitroModalHostView(context: ThemedReactContext) : ViewGroup(context), ReactPointerEventsView {
  internal val contentRoot = ModalContentRoot(context)
  internal var onAttachedChange: ((attached: Boolean) -> Unit)? = null

  /** The size or position changed. */
  internal var onLayoutChange: (() -> Unit)? = null

  internal var isInline: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      contentRoot.isInline = value
    }

  internal val reactChildCount: Int
    get() = contentRoot.childCount

  // Inline: touches that miss the sheet go to the views behind it.
  override val pointerEvents: PointerEvents
    get() = if (isInline) PointerEvents.BOX_NONE else PointerEvents.AUTO

  private var layoutPending = false

  private val measureAndLayout = Runnable {
    layoutPending = false
    forceLayout()
    measure(
      MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
    )
    layout(left, top, right, bottom)
  }

  internal fun addReactChild(child: View, index: Int) = contentRoot.addView(child, index)

  internal fun getReactChildAt(index: Int): View? = contentRoot.getChildAt(index)

  internal fun removeReactChildAt(index: Int) = contentRoot.removeViewAt(index)

  override fun setId(id: Int) {
    super.setId(id)
    // Touches that hit no child target the root; give it our React tag.
    contentRoot.id = id
  }

  override fun requestLayout() {
    super.requestLayout()
    // React Native views drop layout requests; the inline sheet's layout
    // (CoordinatorLayout, BottomSheetBehavior) relies on them. Also called
    // from the super constructor, before any child exists.
    if (childCount > 0) scheduleLayout()
  }

  private fun scheduleLayout() {
    if (layoutPending) return
    layoutPending = true
    post(measureAndLayout)
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    // Laid out by Fabric. A modal's children live in the modal window; an
    // inline sheet's layout fills this view.
    layoutChildren(r - l, b - t)
    if (changed) onLayoutChange?.invoke()
    if (forcePendingLayout()) {
      layoutChildren(r - l, b - t)
      if (forcePendingLayout()) scheduleLayout()
    }
  }

  private fun layoutChildren(width: Int, height: Int) {
    for (i in 0 until childCount) {
      val child = getChildAt(i)
      child.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
      )
      child.layout(0, 0, width, height)
    }
  }

  /**
   * Whether a view in the sheet's layout waits for a layout pass. Marks the
   * views above it so the next pass reaches it. The React content is laid out
   * by Fabric, and gone views take no part in layout.
   */
  private fun forcePendingLayout(view: ViewGroup = this): Boolean {
    var pending = false
    for (i in 0 until view.childCount) {
      val child = view.getChildAt(i)
      if (child.visibility == GONE || child is ModalContentRoot) continue
      if ((child is ViewGroup && forcePendingLayout(child)) || child.isLayoutRequested) pending = true
    }
    if (pending && view !== this) view.forceLayout()
    return pending
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    onAttachedChange?.invoke(true)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    onAttachedChange?.invoke(false)
  }

  // A modal's content is handled for accessibility inside the modal window.
  override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
    if (isInline) super.addChildrenForAccessibility(outChildren)
  }

  override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
    isInline && super.dispatchPopulateAccessibilityEvent(event)

  internal fun addInlineRoot(root: ViewGroup) {
    addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    // addView requests layout before the child is added, when requestLayout
    // above still skips it. Until something lays this view out, the sheet's
    // own layout requests stop here and it never leaves its hidden state.
    scheduleLayout()
  }
}
