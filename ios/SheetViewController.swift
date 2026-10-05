//
//  SheetViewController.swift
//  NitroModal
//

import UIKit

/// `bottomSheet` mode: a page sheet driven by `UISheetPresentationController`.
/// UIKit owns the pan gesture, detent snapping and swipe-to-dismiss, so all
/// of it runs on the render server with no JS involvement.
final class SheetViewController: UIViewController, ModalPresenting, UISheetPresentationControllerDelegate {
  weak var presentationDelegate: ModalPresentationDelegate?

  private let contentView: NitroModalContentView
  private let backdrop = BackdropView()
  private var config: ModalConfig
  private var detentIdentifiers: [UISheetPresentationController.Detent.Identifier] = []
  /// The latest `maximumDetentValue` UIKit handed to one of our detent resolvers.
  private var maximumDetentValue: CGFloat?
  private var keyboardHeight: CGFloat = 0

  init(contentView: NitroModalContentView, config: ModalConfig) {
    self.contentView = contentView
    self.config = config
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .pageSheet
    backdrop.onTap = { [weak self] in
      self?.presentationDelegate?.modalPresentationDidTapBackdrop()
    }
    backdrop.configure(color: config.backdropColor, opacity: config.backdropOpacity, blurRadius: config.backdropBlurRadius)
    configureSheet(selectingDetentAt: config.clampedInitialDetentIndex)

    let center = NotificationCenter.default
    center.addObserver(self, selector: #selector(keyboardWillChangeFrame(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
    center.addObserver(self, selector: #selector(keyboardWillHide(_:)), name: UIResponder.keyboardWillHideNotification, object: nil)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  override func loadView() {
    let root = UIView()
    root.backgroundColor = config.contentBackgroundColor
    root.accessibilityViewIsModal = true
    contentView.frame = root.bounds
    contentView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    root.addSubview(contentView)
    view = root
  }

  override func viewWillAppear(_ animated: Bool) {
    super.viewWillAppear(animated)
    installBackdrop()
    animateBackdrop(to: 1)
  }

  override func viewWillDisappear(_ animated: Bool) {
    super.viewWillDisappear(animated)
    if isBeingDismissed || presentingViewController?.isBeingDismissed == true {
      animateBackdrop(to: 0)
    }
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    reportContentArea()
  }

  // MARK: ModalPresenting

  func apply(_ newConfig: ModalConfig) {
    let selectedIndex = sheetPresentationController?.selectedDetentIdentifier
      .flatMap { detentIdentifiers.firstIndex(of: $0) }
    config = newConfig
    if isViewLoaded {
      view.backgroundColor = config.contentBackgroundColor
    }
    backdrop.configure(color: config.backdropColor, opacity: config.backdropOpacity, blurRadius: config.backdropBlurRadius)
    let index = min(selectedIndex ?? config.clampedInitialDetentIndex, max(config.detents.count - 1, 0))
    guard let sheet = sheetPresentationController else { return }
    sheet.animateChanges {
      configureSheet(selectingDetentAt: index)
    }
    reportContentArea()
  }

  func contentSizeDidChange() {
    guard config.detents.contains(.fitcontent), let sheet = sheetPresentationController else { return }
    if #available(iOS 16.0, *) {
      sheet.animateChanges {
        sheet.invalidateDetents()
      }
    }
  }

  func snapToDetent(at index: Int) {
    guard detentIdentifiers.indices.contains(index), let sheet = sheetPresentationController else { return }
    sheet.animateChanges {
      sheet.selectedDetentIdentifier = detentIdentifiers[index]
    }
    // UIKit only notifies the delegate for user-driven changes.
    presentationDelegate?.modalPresentationDidChangeDetent(index)
  }

  // MARK: UISheetPresentationControllerDelegate

  func sheetPresentationControllerDidChangeSelectedDetentIdentifier(_ sheet: UISheetPresentationController) {
    guard let identifier = sheet.selectedDetentIdentifier,
          let index = detentIdentifiers.firstIndex(of: identifier) else { return }
    presentationDelegate?.modalPresentationDidChangeDetent(index)
  }

  func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
    presentationDelegate?.modalPresentationDidDismissInteractively()
  }

  // MARK: Sheet configuration

  private func configureSheet(selectingDetentAt index: Int) {
    guard let sheet = sheetPresentationController else { return }
    sheet.delegate = self
    // Blocks swipe-to-dismiss while still allowing drags between detents.
    isModalInPresentation = !config.dismissOnSwipe
    sheet.prefersGrabberVisible = config.grabberVisible
    sheet.preferredCornerRadius = config.cornerRadius
    sheet.prefersScrollingExpandsWhenScrolledToEdge = true
    sheet.prefersEdgeAttachedInCompactHeight = true

    let (detents, identifiers) = makeDetents()
    sheet.detents = detents
    detentIdentifiers = identifiers
    // `BackdropView` replaces the system dimming so color, opacity, blur and
    // tap-outside are configurable. Undimming up to `.large` turns it off.
    sheet.largestUndimmedDetentIdentifier = .large
    if identifiers.indices.contains(index) {
      sheet.selectedDetentIdentifier = identifiers[index]
    }
  }

  private func makeDetents() -> ([UISheetPresentationController.Detent], [UISheetPresentationController.Detent.Identifier]) {
    let requested = config.detents.isEmpty ? [.fitcontent] : config.detents
    guard #available(iOS 16.0, *) else {
      // iOS 15 only has the two system detents, and `Detent.identifier` is iOS 16+.
      let identifiers: [UISheetPresentationController.Detent.Identifier] = requested.map { $0 == .large ? .large : .medium }
      return (identifiers.map { $0 == .large ? .large() : .medium() }, identifiers)
    }
    let detents = requested.enumerated().map { index, detent in
      UISheetPresentationController.Detent.custom(
        identifier: .init("nitro.\(index).\(detent.stringValue)")
      ) { [weak self] context in
        self?.resolve(detent, maximum: context.maximumDetentValue)
      }
    }
    return (detents, detents.map(\.identifier))
  }

  /// Detent heights exclude the bottom safe area; UIKit adds it below.
  private func resolve(_ detent: SheetDetent, maximum: CGFloat) -> CGFloat {
    maximumDetentValue = maximum
    switch detent {
    case .small: return maximum * 0.25
    case .medium: return maximum * 0.5
    case .large: return maximum
    case .fitcontent: return min(max(contentView.contentSize.height, 1), maximum)
    }
  }

  // MARK: Backdrop

  private func installBackdrop() {
    guard let container = sheetPresentationController?.containerView, backdrop.superview !== container else { return }
    backdrop.frame = container.bounds
    backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    backdrop.alpha = 0
    container.insertSubview(backdrop, at: 0)
  }

  /// Rides the transition coordinator so the backdrop tracks interactive
  /// dismissals frame-by-frame and restores itself if the swipe is cancelled.
  private func animateBackdrop(to alpha: CGFloat) {
    guard let coordinator = transitionCoordinator else {
      backdrop.alpha = alpha
      return
    }
    let initial = backdrop.alpha
    coordinator.animate(alongsideTransition: { [backdrop] _ in
      backdrop.alpha = alpha
    }, completion: { [backdrop] context in
      if context.isCancelled {
        backdrop.alpha = initial
      }
    })
  }

  // MARK: Content area

  private func reportContentArea() {
    guard isViewLoaded, view.bounds.width > 0 else { return }
    let maximum = maximumDetentValue ?? view.window.map(Self.estimatedMaximumDetentValue(in:)) ?? view.bounds.height
    var height = Self.contentHeight(for: config.detents, maximum: maximum)
    if config.keyboardBehavior == .resize {
      height -= max(0, keyboardHeight - view.safeAreaInsets.bottom)
    }
    presentationDelegate?.modalPresentationDidChangeContentArea(CGSize(width: view.bounds.width, height: max(height, 0)))
  }

  /// Height the content may occupy at the largest of `detents`.
  static func contentHeight(for detents: [SheetDetent], maximum: CGFloat) -> CGFloat {
    if detents.isEmpty || detents.contains(.large) || detents.contains(.fitcontent) {
      return maximum
    }
    return detents.contains(.medium) ? maximum * 0.5 : maximum * 0.25
  }

  /// Best guess of UIKit's `maximumDetentValue` before the sheet is on screen.
  static func estimatedMaximumDetentValue(in window: UIWindow) -> CGFloat {
    let insets = window.safeAreaInsets
    return window.bounds.height - insets.top - insets.bottom - 10
  }

  /// Best guess of the sheet width before it is on screen.
  static func estimatedWidth(in window: UIWindow) -> CGFloat {
    window.traitCollection.horizontalSizeClass == .regular ? min(window.bounds.width, 704) : window.bounds.width
  }

  // MARK: Keyboard

  // UIKit already lifts sheets above the keyboard; we only track the height
  // so `resize` can shrink the area reported to the content.
  @objc private func keyboardWillChangeFrame(_ notification: Notification) {
    guard isViewLoaded, let window = view.window,
          let frame = (notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
    let keyboard = window.convert(frame, from: window.screen.coordinateSpace)
    keyboardHeight = max(0, window.bounds.maxY - keyboard.minY)
    reportContentArea()
  }

  @objc private func keyboardWillHide(_ notification: Notification) {
    keyboardHeight = 0
    reportContentArea()
  }
}
