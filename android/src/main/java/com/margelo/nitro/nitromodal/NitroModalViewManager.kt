package com.margelo.nitro.nitromodal

import android.view.View
import com.facebook.react.uimanager.ReactStylesDiffMap
import com.facebook.react.uimanager.StateWrapper
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.ViewGroupManager
import com.margelo.nitro.R.id.associated_hybrid_view_tag
import com.margelo.nitro.nitromodal.views.HybridNitroModalStateUpdater

/**
 * View manager for the "NitroModal" Nitro view.
 *
 * Nitrogen generates a `SimpleViewManager`, which cannot have children. This
 * is the same manager as a `ViewGroupManager`: props still flow through the
 * generated C++ state updater, and React children are redirected into the
 * modal's [ModalContentRoot].
 */
class NitroModalViewManager : ViewGroupManager<NitroModalHostView>() {
  private class HybridViewHolder(val hybridView: HybridNitroModal, var lastState: StateWrapper? = null)

  override fun getName(): String = "NitroModal"

  override fun createViewInstance(reactContext: ThemedReactContext): NitroModalHostView {
    val hybridView = HybridNitroModal(reactContext)
    val view = hybridView.view
    view.setTag(associated_hybrid_view_tag, HybridViewHolder(hybridView))
    return view
  }

  override fun addEventEmitters(reactContext: ThemedReactContext, view: NitroModalHostView) {
    view.contentRoot.eventDispatcher = UIManagerHelper.getEventDispatcher(reactContext)
  }

  override fun updateState(view: NitroModalHostView, props: ReactStylesDiffMap, stateWrapper: StateWrapper): Any? {
    val holder = holder(view) ?: throw IllegalStateException("Couldn't find the HybridNitroModal for $view")
    val hybridView = holder.hybridView
    hybridView.beforeUpdate()
    HybridNitroModalStateUpdater.updateViewProps(hybridView, stateWrapper, holder.lastState)
    hybridView.afterUpdate()
    holder.lastState = stateWrapper
    return super.updateState(view, props, stateWrapper)
  }

  override fun onDropViewInstance(view: NitroModalHostView) {
    holder(view)?.let {
      it.lastState = null
      it.hybridView.onDropView()
    }
    super.onDropViewInstance(view)
  }

  // region Children live in the modal window, not in the host view.

  override fun addView(parent: NitroModalHostView, child: View, index: Int) = parent.addReactChild(child, index)

  override fun getChildCount(parent: NitroModalHostView): Int = parent.reactChildCount

  override fun getChildAt(parent: NitroModalHostView, index: Int): View? = parent.getReactChildAt(index)

  override fun removeViewAt(parent: NitroModalHostView, index: Int) = parent.removeReactChildAt(index)

  // endregion

  private fun holder(view: View): HybridViewHolder? = view.getTag(associated_hybrid_view_tag) as? HybridViewHolder
}
