//
//  HybridNitroModal.swift
//  NitroModal
//

import NitroModules
import UIKit

/// The Nitro Hybrid View. Props arrive on the main thread (Fabric
/// `updateProps`); methods arrive on the JS thread and hop to main.
///
/// Presentation is a small state machine so that rapid `isOpen` toggles,
/// interactive dismissals and unmounts always settle on a consistent state,
/// and every accepted present request ends with exactly one `onDismiss`.
///
/// A modal is presented as a view controller; an inline sheet is a
/// `SheetView` shown inside `view`.
final class HybridNitroModal: HybridNitroModalSpec {
  let view = NitroModalHostView()

  // MARK: Props

  var isOpen = false {
    didSet {
      guard isOpen != oldValue else { return }
      if isOpen {
        requestPresent()
      } else {
        requestDismiss(.programmatic)
      }
    }
  }

  var mode: ModalMode = .bottomsheet
  var isInline = false
  var detents: [SheetDetent] = [.first(.fitcontent)]
  var initialDetentIndex: Double = 0
  var backdropColor: Double = 0xFF00_0000
  var backdropOpacity: Double = 0.4
  var backdropBlurRadius: Double = 0
  var dismissOnBackdropPress = true
  var dismissOnSwipe = true
  var dismissOnBackButton = true
  var grabberVisible = false
  var cornerRadius: Double = -1
  var borderWidth: Double = 0
  var borderColor: Double?
  var contentBackgroundColor: Double?
  var keyboardBehavior: KeyboardBehavior = .pan
  var popupAnimation: PopupAnimation = .scale
  var pullToRefreshEnabled = false
  var refreshing: Bool?
  var refreshIndicatorColor: Double?

  var onPresent: (() -> Void)?
  var onDismiss: ((DismissReason) -> Void)?
  var onDetentChange: ((Double) -> Void)?
  var onBackdropPress: (() -> Void)?
  var onBackButtonPress: (() -> Void)?
  var onContentAreaChange: ((ModalContentArea) -> Void)?
  var onPullToRefresh: (() -> Void)?
  var onRestingTopChange: ((Double) -> Void)?

  // MARK: State

  private enum Phase {
    case idle, presenting, presented, dismissing
  }

  private var config = ModalConfig()
  private var phase = Phase.idle
  /// What React (or an imperative call) asked for.
  private var wantsOpen = false
  /// A present request was accepted and its `onDismiss` is still owed.
  private var sessionActive = false
  private var dismissReason: DismissReason = .programmatic
  private var reconcileScheduled = false
  private var isDropped = false
  private var presentation: ModalPresenting?
  private var inlineSheet: SheetView?
  private var lastContentArea: CGSize?

  override init() {
    super.init()
    view.onWindowChange = { [weak self] in
      self?.hostWindowDidChange()
    }
    view.onLayoutChange = { [weak self] in
      self?.hostLayoutDidChange()
    }
    view.contentView.onContentSizeChange = { [weak self] _ in
      self?.presentation?.contentSizeDidChange()
      self?.inlineSheet?.contentSizeDidChange()
    }
  }

  deinit {
    // The last reference can be dropped from the JS thread; UIKit work must
    // happen on main. `controller` and `sheet` are captured strongly on purpose.
    let controller = presentation
    let sheet = inlineSheet
    guard controller != nil || sheet != nil else { return }
    DispatchQueue.main.async {
      controller?.presentingViewController?.dismiss(animated: false)
      sheet?.removeFromSuperview()
    }
  }

  // MARK: HybridView

  func afterUpdate() {
    let next = makeConfig()
    guard next != config else { return }
    config = next
    view.isInline = next.isInline
    if let presentation {
      presentation.apply(next)
    } else if let inlineSheet {
      inlineSheet.apply(next)
    } else {
      reportEstimatedContentArea()
    }
  }

  func onDropView() {
    isDropped = true
    wantsOpen = false
    sessionActive = false
    phase = .idle
    if let sheet = inlineSheet {
      inlineSheet = nil
      sheet.removeFromSuperview()
    }
    guard let controller = presentation else { return }
    presentation = nil
    controller.presentingViewController?.dismiss(animated: false)
  }

  // MARK: Methods

  func present() throws {
    DispatchQueue.main.async { [weak self] in
      self?.requestPresent()
    }
  }

  func dismiss() throws {
    DispatchQueue.main.async { [weak self] in
      self?.requestDismiss(.programmatic)
    }
  }

  func snapToDetent(index: Double) throws {
    guard index.isFinite else { return }
    DispatchQueue.main.async { [weak self] in
      self?.presentation?.snapToDetent(at: Int(index))
      self?.inlineSheet?.snapToDetent(at: Int(index))
    }
  }

  // MARK: State machine

  private func requestPresent() {
    guard !isDropped else { return }
    wantsOpen = true
    sessionActive = true
    scheduleReconcile()
  }

  private func requestDismiss(_ reason: DismissReason) {
    if wantsOpen {
      dismissReason = reason
    }
    wantsOpen = false
    scheduleReconcile()
  }

  /// Deferred to the next run loop turn so the children and layout committed
  /// in the same Fabric transaction are mounted before we present.
  private func scheduleReconcile() {
    guard !reconcileScheduled else { return }
    reconcileScheduled = true
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.reconcileScheduled = false
      self.reconcile()
    }
  }

  private func reconcile() {
    switch phase {
    case .presenting, .dismissing:
      return // The transition's completion reconciles again.
    case .presented:
      if !wantsOpen {
        startDismiss()
      }
    case .idle:
      if wantsOpen {
        startPresent()
      } else if sessionActive {
        endSession() // Cancelled before anything appeared.
      }
    }
  }

  private func startPresent() {
    if config.isInline {
      startInlinePresent()
      return
    }
    // Without a window there is nothing to present from; `hostWindowDidChange` retries.
    guard let presenter = topViewController() else { return }

    let controller: ModalPresenting = config.mode == .popup
      ? PopupViewController(contentView: view.contentView, config: config)
      : SheetViewController(contentView: view.contentView, config: config)
    controller.presentationDelegate = self
    presentation = controller
    phase = .presenting

    presenter.present(controller, animated: isAnimated) { [weak self, weak controller] in
      guard let self, let controller, self.presentation === controller else { return }
      self.didPresent(announcing: controller.view)
    }

    if controller.presentingViewController == nil {
      // UIKit refused (e.g. another transition is still running). Retry shortly.
      presentation = nil
      phase = .idle
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
        self?.reconcile()
      }
    }
  }

  /// Slides an inline sheet up inside `view`.
  private func startInlinePresent() {
    // Needs its laid-out area; `hostLayoutDidChange` retries.
    guard view.window != nil, view.bounds.width > 0, view.bounds.height > 0 else { return }

    let sheet = SheetView(contentView: view.contentView, config: config, isInline: true)
    sheet.delegate = self
    inlineSheet = sheet
    phase = .presenting
    view.showInlineSheet(sheet)
    sheet.layoutIfNeeded()
    sheet.animateTransition(toOffscreen: false, animated: true, duration: 0.5) { [weak self, weak sheet] in
      guard let self, let sheet, self.inlineSheet === sheet else { return }
      self.didPresent(announcing: nil)
    }
  }

  private func didPresent(announcing announcedView: UIView?) {
    phase = .presented
    if !isDropped {
      onPresent?()
    }
    if let announcedView {
      UIAccessibility.post(notification: .screenChanged, argument: announcedView)
    }
    reconcile()
  }

  private func startDismiss() {
    if let sheet = inlineSheet {
      phase = .dismissing
      sheet.endEditing(true)
      sheet.animateTransition(toOffscreen: true, animated: true, duration: 0.35) { [weak self] in
        self?.finishDismiss()
      }
      return
    }
    guard let controller = presentation, let presenting = controller.presentingViewController else {
      finishDismiss()
      return
    }
    phase = .dismissing
    controller.view.endEditing(true)
    presenting.dismiss(animated: isAnimated) { [weak self] in
      self?.finishDismiss()
    }
  }

  private func finishDismiss() {
    phase = .idle
    presentation = nil
    if let sheet = inlineSheet {
      inlineSheet = nil
      sheet.unlockScroll()
      sheet.removeFromSuperview()
    }
    view.contentView.removeFromSuperview()
    endSession()
    reconcile()
  }

  private func endSession() {
    guard sessionActive else { return }
    let reason = dismissReason
    dismissReason = .programmatic
    // A present requested while we were dismissing starts a new session.
    sessionActive = wantsOpen
    if !isDropped {
      onDismiss?(reason)
    }
  }

  // MARK: Helpers

  private var isAnimated: Bool {
    !(config.mode == .popup && config.popupAnimation == .none)
  }

  private func makeConfig() -> ModalConfig {
    let inline = isInline && mode == .bottomsheet
    return ModalConfig(
      mode: mode,
      inlinePresentation: isInline,
      detents: detents.map(Detent.init),
      initialDetentIndex: initialDetentIndex.isFinite ? Int(initialDetentIndex) : 0,
      backdropColor: UIColor(processedColor: backdropColor),
      backdropOpacity: CGFloat(backdropOpacity),
      backdropBlurRadius: CGFloat(backdropBlurRadius),
      // An inline sheet is part of the screen; the user can't dismiss it.
      dismissOnBackdropPress: dismissOnBackdropPress && !inline,
      dismissOnSwipe: dismissOnSwipe && !inline,
      grabberVisible: grabberVisible,
      cornerRadius: cornerRadius >= 0 ? CGFloat(cornerRadius) : nil,
      borderWidth: borderWidth.isFinite ? max(CGFloat(borderWidth), 0) : 0,
      borderColor: borderColor.map(UIColor.init(processedColor:)) ?? .clear,
      contentBackgroundColor: contentBackgroundColor.map(UIColor.init(processedColor:)) ?? .systemBackground,
      keyboardBehavior: keyboardBehavior,
      popupAnimation: popupAnimation,
      pullToRefreshEnabled: pullToRefreshEnabled,
      refreshing: refreshing,
      refreshIndicatorColor: refreshIndicatorColor.map(UIColor.init(processedColor:))
    )
  }

  private func topViewController() -> UIViewController? {
    var top = view.window?.rootViewController
    while let presented = top?.presentedViewController, !presented.isBeingDismissed {
      top = presented
    }
    return top
  }

  private func hostWindowDidChange() {
    guard view.window != nil else { return }
    if presentation == nil, inlineSheet == nil {
      reportEstimatedContentArea()
    }
    scheduleReconcile()
  }

  private func hostLayoutDidChange() {
    guard config.isInline, inlineSheet == nil else { return }
    reportEstimatedContentArea()
    if wantsOpen {
      scheduleReconcile()
    }
  }

  /// Lets React size the content before the first presentation.
  private func reportEstimatedContentArea() {
    guard let window = view.window else { return }
    if config.isInline {
      let bounds = view.bounds
      let maximum = SheetView.maximumDetentValue(height: bounds.height, insets: view.safeAreaInsets, isInline: true)
      reportContentArea(CGSize(width: bounds.width, height: SheetView.contentHeight(for: config.detents, maximum: maximum)))
      return
    }
    switch config.mode {
    case .popup:
      reportContentArea(PopupViewController.availableRect(in: window.bounds, safeArea: window.safeAreaInsets).size)
    case .bottomsheet:
      let maximum = SheetView.maximumDetentValue(height: window.bounds.height, insets: window.safeAreaInsets, isInline: false)
      reportContentArea(CGSize(
        width: SheetView.estimatedWidth(in: window),
        height: SheetView.contentHeight(for: config.detents, maximum: maximum)
      ))
    }
  }

  private func reportContentArea(_ size: CGSize) {
    let area = CGSize(width: size.width.rounded(.down), height: size.height.rounded(.down))
    guard area != lastContentArea, area.width > 0, area.height > 0 else { return }
    lastContentArea = area
    onContentAreaChange?(ModalContentArea(width: Double(area.width), height: Double(area.height)))
  }
}

// MARK: - ModalPresentationDelegate

extension HybridNitroModal: ModalPresentationDelegate {
  func modalPresentationDidTapBackdrop() {
    onBackdropPress?()
    guard config.dismissOnBackdropPress, phase == .presenting || phase == .presented else { return }
    requestDismiss(.backdrop)
    reconcile()
  }

  func modalPresentationDidRequestSwipeDismiss() {
    guard config.dismissOnSwipe, phase == .presenting || phase == .presented else { return }
    requestDismiss(.swipe)
    reconcile()
  }

  func modalPresentationDidChangeDetent(_ index: Int) {
    onDetentChange?(Double(index))
  }

  func modalPresentationDidChangeContentArea(_ size: CGSize) {
    reportContentArea(size)
  }

  func modalPresentationDidPullToRefresh() {
    guard !isDropped else { return }
    onPullToRefresh?()
  }

  func modalPresentationDidChangeRestingTop(_ top: CGFloat) {
    guard !isDropped else { return }
    onRestingTopChange?(Double(top))
  }
}
