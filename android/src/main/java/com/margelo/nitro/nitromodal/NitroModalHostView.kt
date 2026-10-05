package com.margelo.nitro.nitromodal

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import com.facebook.react.uimanager.ThemedReactContext

/**
 * The view Nitro mounts into the React tree. It is an invisible placeholder:
 * React children added to it (via [NitroModalViewManager]) are hosted by
 * [contentRoot], which travels into the modal window.
 */
@SuppressLint("ViewConstructor")
class NitroModalHostView(context: ThemedReactContext) : ViewGroup(context) {
  internal val contentRoot = ModalContentRoot(context)
  internal var onAttachedChange: ((attached: Boolean) -> Unit)? = null

  internal val reactChildCount: Int
    get() = contentRoot.childCount

  internal fun addReactChild(child: View, index: Int) = contentRoot.addView(child, index)

  internal fun getReactChildAt(index: Int): View? = contentRoot.getChildAt(index)

  internal fun removeReactChildAt(index: Int) = contentRoot.removeViewAt(index)

  override fun setId(id: Int) {
    super.setId(id)
    // Touches that hit no child target the root; give it our React tag.
    contentRoot.id = id
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    // Laid out by Fabric; our children live in the modal window.
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    onAttachedChange?.invoke(true)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    onAttachedChange?.invoke(false)
  }

  // Accessibility for the content is handled inside the modal window.
  override fun addChildrenForAccessibility(outChildren: ArrayList<View>) = Unit

  override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean = false
}
