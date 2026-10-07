//
//  SheetViewController.swift
//  NitroModal
//

import UIKit

/// Modal `bottomSheet` mode: an edge-attached `SheetView` presented
/// `.overFullScreen`.
///
/// `UISheetPresentationController` is not used because iOS 26 always floats
/// sheets inset from the screen edges at partial detents, with no API to opt
/// out. Detents, dragging, swipe-to-dismiss and the hand-off to scroll views
/// inside the content are implemented by `SheetView` with a pan gesture.
final class SheetViewController: UIViewController, ModalPresenting, UIViewControllerTransitioningDelegate {
  weak var presentationDelegate: ModalPresentationDelegate? {
    didSet { sheet.delegate = presentationDelegate }
  }

  private let sheet: SheetView

  init(contentView: NitroModalContentView, config: ModalConfig) {
    sheet = SheetView(contentView: contentView, config: config, isInline: false)
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .overFullScreen
    transitioningDelegate = self
    sheet.isTransitioning = { [weak self] in
      guard let self else { return false }
      return self.isBeingPresented || self.isBeingDismissed
    }
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  override func loadView() {
    view = sheet
  }

  override func viewDidDisappear(_ animated: Bool) {
    super.viewDidDisappear(animated)
    sheet.unlockScroll()
  }

  override func accessibilityPerformEscape() -> Bool {
    sheet.performEscape()
  }

  // MARK: ModalPresenting

  func apply(_ newConfig: ModalConfig) {
    sheet.apply(newConfig)
  }

  func contentSizeDidChange() {
    sheet.contentSizeDidChange()
  }

  func snapToDetent(at index: Int) {
    sheet.snapToDetent(at: index)
  }

  // MARK: UIViewControllerTransitioningDelegate

  func animationController(
    forPresented presented: UIViewController,
    presenting: UIViewController,
    source: UIViewController
  ) -> UIViewControllerAnimatedTransitioning? {
    SheetTransition(presenting: true)
  }

  func animationController(forDismissed dismissed: UIViewController) -> UIViewControllerAnimatedTransitioning? {
    SheetTransition(presenting: false)
  }

  fileprivate func animateTransition(toOffscreen offscreen: Bool, animated: Bool, duration: TimeInterval, completion: @escaping () -> Void) {
    sheet.animateTransition(toOffscreen: offscreen, animated: animated, duration: duration, completion: completion)
  }
}

/// Slide transition for `SheetViewController`.
private final class SheetTransition: NSObject, UIViewControllerAnimatedTransitioning {
  private let presenting: Bool

  init(presenting: Bool) {
    self.presenting = presenting
  }

  func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval {
    presenting ? 0.5 : 0.35
  }

  func animateTransition(using transitionContext: UIViewControllerContextTransitioning) {
    let key: UITransitionContextViewControllerKey = presenting ? .to : .from
    guard let sheet = transitionContext.viewController(forKey: key) as? SheetViewController else {
      transitionContext.completeTransition(!transitionContext.transitionWasCancelled)
      return
    }

    if presenting {
      sheet.view.frame = transitionContext.finalFrame(for: sheet)
      transitionContext.containerView.addSubview(sheet.view)
      sheet.view.layoutIfNeeded()
    }

    sheet.animateTransition(
      toOffscreen: !presenting,
      animated: transitionContext.isAnimated,
      duration: transitionDuration(using: transitionContext)
    ) {
      transitionContext.completeTransition(!transitionContext.transitionWasCancelled)
    }
  }
}
