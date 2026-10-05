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
  var detents: [SheetDetent] = [.fitcontent]
  var initialDetentIndex: Double = 0
  var backdropColor: Double = 0xFF00_0000
  var backdropOpacity: Double = 0.4
  var backdropBlurRadius: Double = 0
  var dismissOnBackdropPress = true
  var dismissOnSwipe = true
  var dismissOnBackButton = true
  var grabberVisible = false
  var cornerRadius: Double = -1
  var contentBackgroundColor: Double?
  var keyboardBehavior: KeyboardBehavior = .pan
  var popupAnimation: PopupAnimation = .scale

  var onPresent: (() -> Void)?
  var onDismiss: ((DismissReason) -> Void)?
  var onDetentChange: ((Double) -> Void)?
  var onBackdropPress: (() -> Void)?
  var onBackButtonPress: (() -> Void)?
  var onContentAreaChange: ((ModalContentArea) -> Void)?

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
  private var lastContentArea: CGSize?

  override init() {
    super.init()
    view.onWindowChange = { [weak self] in
      self?.hostWindowDidChange()
    }
    view.contentView.onContentSizeChange = { [weak self] _ in
      self?.presentation?.contentSizeDidChange()
    }
  }

  deinit {
    // The last reference can be dropped from the JS thread; UIKit work must
    // happen on main. `controller` is captured strongly on purpose.
    guard let controller = presentation else { return }
    DispatchQueue.main.async {
      controller.presentingViewController?.dismiss(animated: false)
    }
  }

  // MARK: HybridView

  func afterUpdate() {
    let next = makeConfig()
    guard next != config else { return }
    config = next
    if let presentation {
      presentation.apply(next)
    } else {
      reportEstimatedContentArea()
    }
  }

  func onDropView() {
    isDropped = true
    wantsOpen = false
    sessionActive = false
    guard let controller = presentation else { return }
    presentation = nil
    phase = .idle
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
      self.phase = .presented
      if !self.isDropped {
        self.onPresent?()
      }
      UIAccessibility.post(notification: .screenChanged, argument: controller.view)
      self.reconcile()
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

  private func startDismiss() {
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
    ModalConfig(
      mode: mode,
      detents: detents,
      initialDetentIndex: initialDetentIndex.isFinite ? Int(initialDetentIndex) : 0,
      backdropColor: UIColor(processedColor: backdropColor),
      backdropOpacity: CGFloat(backdropOpacity),
      backdropBlurRadius: CGFloat(backdropBlurRadius),
      dismissOnBackdropPress: dismissOnBackdropPress,
      dismissOnSwipe: dismissOnSwipe,
      grabberVisible: grabberVisible,
      cornerRadius: cornerRadius >= 0 ? CGFloat(cornerRadius) : nil,
      contentBackgroundColor: contentBackgroundColor.map(UIColor.init(processedColor:)) ?? .systemBackground,
      keyboardBehavior: keyboardBehavior,
      popupAnimation: popupAnimation
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
    if presentation == nil {
      reportEstimatedContentArea()
    }
    scheduleReconcile()
  }

  /// Lets React size the content before the first presentation.
  private func reportEstimatedContentArea() {
    guard let window = view.window else { return }
    switch config.mode {
    case .popup:
      reportContentArea(PopupViewController.availableRect(in: window.bounds, safeArea: window.safeAreaInsets).size)
    case .bottomsheet:
      let maximum = SheetViewController.estimatedMaximumDetentValue(in: window)
      reportContentArea(CGSize(
        width: SheetViewController.estimatedWidth(in: window),
        height: SheetViewController.contentHeight(for: config.detents, maximum: maximum)
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
}
