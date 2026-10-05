//
//  PopupViewController.swift
//  NitroModal
//

import UIKit

/// `popup` mode: a centered card over a full-screen backdrop, presented
/// `.overFullScreen` with a custom Core Animation transition.
final class PopupViewController: UIViewController, ModalPresenting, UIViewControllerTransitioningDelegate {
  weak var presentationDelegate: ModalPresentationDelegate?

  private static let margin: CGFloat = 16
  private static let defaultCornerRadius: CGFloat = 16

  private let contentView: NitroModalContentView
  private let backdrop = BackdropView()
  private let card = UIView()
  private var config: ModalConfig
  /// Portion of the view covered by the keyboard.
  private var keyboardOverlap: CGFloat = 0

  init(contentView: NitroModalContentView, config: ModalConfig) {
    self.contentView = contentView
    self.config = config
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .overFullScreen
    transitioningDelegate = self
    backdrop.onTap = { [weak self] in
      self?.presentationDelegate?.modalPresentationDidTapBackdrop()
    }

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
    root.backgroundColor = .clear

    backdrop.frame = root.bounds
    backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    root.addSubview(backdrop)

    card.clipsToBounds = true
    card.layer.cornerCurve = .continuous
    card.accessibilityViewIsModal = true
    card.addSubview(contentView)
    root.addSubview(card)

    view = root
    applyAppearance()
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    let available = availableRect(includingKeyboard: config.keyboardBehavior != .none)
    let content = contentView.contentSize
    // `bounds` + `center` keep the transition's scale transform intact.
    card.bounds = CGRect(
      origin: .zero,
      size: CGSize(width: min(content.width, available.width), height: min(content.height, available.height))
    )
    card.center = CGPoint(x: available.midX, y: available.midY)
    contentView.frame = card.bounds

    let area = config.keyboardBehavior == .resize ? available : availableRect(includingKeyboard: false)
    presentationDelegate?.modalPresentationDidChangeContentArea(area.size)
  }

  /// Animated state between hidden (0) and fully presented (1).
  fileprivate func setTransitionProgress(_ progress: CGFloat, presenting: Bool) {
    backdrop.alpha = progress
    card.alpha = config.popupAnimation == .none ? 1 : progress
    if config.popupAnimation == .scale && progress < 1 {
      let scale: CGFloat = presenting ? 0.9 : 0.95
      card.transform = CGAffineTransform(scaleX: scale, y: scale)
    } else {
      card.transform = .identity
    }
  }

  // MARK: ModalPresenting

  func apply(_ newConfig: ModalConfig) {
    config = newConfig
    guard isViewLoaded else { return }
    applyAppearance()
    view.setNeedsLayout()
  }

  func contentSizeDidChange() {
    guard isViewLoaded else { return }
    UIView.animate(withDuration: 0.25, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
      self.view.setNeedsLayout()
      self.view.layoutIfNeeded()
    }
  }

  func snapToDetent(at index: Int) {}

  // MARK: UIViewControllerTransitioningDelegate

  func animationController(
    forPresented presented: UIViewController,
    presenting: UIViewController,
    source: UIViewController
  ) -> UIViewControllerAnimatedTransitioning? {
    PopupTransition(presenting: true)
  }

  func animationController(forDismissed dismissed: UIViewController) -> UIViewControllerAnimatedTransitioning? {
    PopupTransition(presenting: false)
  }

  // MARK: Private

  private func applyAppearance() {
    backdrop.configure(color: config.backdropColor, opacity: config.backdropOpacity, blurRadius: config.backdropBlurRadius)
    card.backgroundColor = config.contentBackgroundColor
    card.layer.cornerRadius = config.cornerRadius ?? Self.defaultCornerRadius
  }

  /// Area a card may occupy inside `bounds`, keeping clear of the safe area.
  static func availableRect(in bounds: CGRect, safeArea: UIEdgeInsets) -> CGRect {
    bounds.inset(by: safeArea).insetBy(dx: margin, dy: margin)
  }

  private func availableRect(includingKeyboard: Bool) -> CGRect {
    var rect = Self.availableRect(in: view.bounds, safeArea: view.safeAreaInsets)
    if includingKeyboard {
      let covered = max(0, keyboardOverlap - view.safeAreaInsets.bottom)
      rect.size.height = max(0, rect.height - covered)
    }
    return rect
  }

  @objc private func keyboardWillChangeFrame(_ notification: Notification) {
    guard isViewLoaded, let window = view.window,
          let frame = (notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
    let keyboard = view.convert(frame, from: window.screen.coordinateSpace)
    updateKeyboardOverlap(max(0, view.bounds.maxY - keyboard.minY), notification: notification)
  }

  @objc private func keyboardWillHide(_ notification: Notification) {
    guard isViewLoaded else { return }
    updateKeyboardOverlap(0, notification: notification)
  }

  private func updateKeyboardOverlap(_ overlap: CGFloat, notification: Notification) {
    guard overlap != keyboardOverlap else { return }
    keyboardOverlap = overlap
    guard config.keyboardBehavior != .none else { return }
    notification.animateAlongsideKeyboard {
      self.view.setNeedsLayout()
      self.view.layoutIfNeeded()
    }
  }
}

/// Fade/scale transition for `PopupViewController`, run by Core Animation.
private final class PopupTransition: NSObject, UIViewControllerAnimatedTransitioning {
  private let presenting: Bool

  init(presenting: Bool) {
    self.presenting = presenting
  }

  func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval {
    presenting ? 0.4 : 0.2
  }

  func animateTransition(using transitionContext: UIViewControllerContextTransitioning) {
    let key: UITransitionContextViewControllerKey = presenting ? .to : .from
    guard let popup = transitionContext.viewController(forKey: key) as? PopupViewController else {
      transitionContext.completeTransition(!transitionContext.transitionWasCancelled)
      return
    }

    if presenting {
      popup.view.frame = transitionContext.finalFrame(for: popup)
      transitionContext.containerView.addSubview(popup.view)
      popup.view.layoutIfNeeded()
      popup.setTransitionProgress(0, presenting: true)
    }

    let finish: (Bool) -> Void = { _ in
      transitionContext.completeTransition(!transitionContext.transitionWasCancelled)
    }
    guard transitionContext.isAnimated else {
      popup.setTransitionProgress(presenting ? 1 : 0, presenting: presenting)
      finish(true)
      return
    }

    let duration = transitionDuration(using: transitionContext)
    if presenting {
      UIView.animate(
        withDuration: duration,
        delay: 0,
        usingSpringWithDamping: 0.82,
        initialSpringVelocity: 0,
        options: [.allowUserInteraction, .beginFromCurrentState],
        animations: { popup.setTransitionProgress(1, presenting: true) },
        completion: finish
      )
    } else {
      UIView.animate(
        withDuration: duration,
        delay: 0,
        options: [.curveEaseIn, .beginFromCurrentState],
        animations: { popup.setTransitionProgress(0, presenting: false) },
        completion: finish
      )
    }
  }
}
