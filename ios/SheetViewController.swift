//
//  SheetViewController.swift
//  NitroModal
//

import UIKit

/// `bottomSheet` mode: an edge-attached sheet presented `.overFullScreen`.
///
/// `UISheetPresentationController` is not used because iOS 26 always floats
/// sheets inset from the screen edges at partial detents, with no API to opt
/// out. Detents, dragging, swipe-to-dismiss and the hand-off to scroll views
/// inside the content are implemented here with a pan gesture instead.
final class SheetViewController: UIViewController, ModalPresenting, UIViewControllerTransitioningDelegate, UIGestureRecognizerDelegate {
  weak var presentationDelegate: ModalPresentationDelegate?

  /// Gap kept above the sheet at its tallest, like a system page sheet.
  private static let topGap: CGFloat = 10
  /// Extra sheet height below the screen so it stays attached to the bottom
  /// edge while rubber-banding upward.
  private static let overscroll: CGFloat = 400
  private static let defaultCornerRadius: CGFloat = 16
  private static let regularMaxWidth: CGFloat = 704
  private static let grabberSize = CGSize(width: 36, height: 5)

  private let contentView: NitroModalContentView
  private let backdrop = BackdropView()
  private let sheetView = UIView()
  private let grabber = UIView()
  private var config: ModalConfig
  private var selectedIndex: Int
  private var keyboardHeight: CGFloat = 0

  /// The sheet sits below the screen (before presenting / while dismissing).
  private var isOffscreen = true
  /// Top of the sheet while the user drags it; `nil` rests on `selectedIndex`.
  private var dragTop: CGFloat?
  private var drag: DragState?
  /// Vertical velocity of the swipe that requested the dismissal.
  private var releaseVelocity: CGFloat = 0

  private var lockedScrollView: UIScrollView?
  private var lockedOffsetY: CGFloat = 0
  private var scrollLock: NSKeyValueObservation?

  private struct DragState {
    /// Unconstrained sheet top, before rubber-banding.
    var rawTop: CGFloat
    var lastTranslation: CGFloat = 0
    var scrollView: UIScrollView?
    /// Whether the last movement moved the sheet rather than the scroll view.
    var sheetDrove = false
  }

  init(contentView: NitroModalContentView, config: ModalConfig) {
    self.contentView = contentView
    self.config = config
    selectedIndex = config.clampedInitialDetentIndex
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
    root.addSubview(backdrop)

    sheetView.clipsToBounds = true
    sheetView.layer.cornerCurve = .continuous
    sheetView.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
    sheetView.accessibilityViewIsModal = true
    sheetView.addSubview(contentView)

    grabber.isUserInteractionEnabled = false
    grabber.backgroundColor = .tertiaryLabel
    grabber.layer.cornerRadius = Self.grabberSize.height / 2
    sheetView.addSubview(grabber)
    root.addSubview(sheetView)

    let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
    pan.delegate = self
    sheetView.addGestureRecognizer(pan)

    view = root
    applyAppearance()
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    layoutSheet()
    reportContentArea()
  }

  override func viewDidDisappear(_ animated: Bool) {
    super.viewDidDisappear(animated)
    unlockScroll()
  }

  override func accessibilityPerformEscape() -> Bool {
    guard config.dismissOnSwipe else { return false }
    presentationDelegate?.modalPresentationDidRequestSwipeDismiss()
    return true
  }

  // MARK: ModalPresenting

  func apply(_ newConfig: ModalConfig) {
    config = newConfig
    selectedIndex = min(selectedIndex, max(detents.count - 1, 0))
    guard isViewLoaded else { return }
    applyAppearance()
    animateToRest()
  }

  func contentSizeDidChange() {
    guard config.detents.contains(.fitcontent) else { return }
    animateToRest()
  }

  func snapToDetent(at index: Int) {
    guard detents.indices.contains(index) else { return }
    selectedIndex = index
    animateToRest()
    presentationDelegate?.modalPresentationDidChangeDetent(index)
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

  /// Slides the sheet in or out; a swipe dismissal keeps its release velocity.
  fileprivate func animateTransition(toOffscreen offscreen: Bool, animated: Bool, duration: TimeInterval, completion: @escaping () -> Void) {
    let from = sheetView.frame.minY
    let velocity = offscreen ? releaseVelocity : 0
    releaseVelocity = 0
    isOffscreen = offscreen
    dragTop = nil
    guard animated else {
      layoutSheet()
      completion()
      return
    }
    animate(
      distance: currentTop - from,
      velocity: velocity,
      damping: offscreen ? 1 : 0.88,
      duration: duration,
      animations: { self.layoutSheet() },
      completion: completion
    )
  }

  // MARK: Layout

  private var detents: [SheetDetent] {
    config.detents.isEmpty ? [.fitcontent] : config.detents
  }

  /// Tallest height the content may occupy (excludes the bottom safe area).
  private var maximumDetentValue: CGFloat {
    let insets = view.safeAreaInsets
    return max(0, view.bounds.height - insets.top - insets.bottom - Self.topGap)
  }

  private var keyboardLift: CGFloat {
    config.keyboardBehavior == .none ? 0 : max(0, keyboardHeight - view.safeAreaInsets.bottom)
  }

  private func height(of detent: SheetDetent) -> CGFloat {
    let maximum = maximumDetentValue
    switch detent {
    case .small: return maximum * 0.25
    case .medium: return maximum * 0.5
    case .large: return maximum
    case .fitcontent: return min(max(contentView.contentSize.height, 1), maximum)
    }
  }

  /// Sheet top when resting on `detents[index]`. The bottom safe area is
  /// added below the content.
  private func restingTop(for index: Int) -> CGFloat {
    let detent = detents[min(max(index, 0), detents.count - 1)]
    let top = view.bounds.height - view.safeAreaInsets.bottom - keyboardLift - height(of: detent)
    return max(top, view.safeAreaInsets.top + Self.topGap)
  }

  private var detentTops: [CGFloat] {
    detents.indices.map(restingTop(for:))
  }

  private var currentTop: CGFloat {
    if isOffscreen { return view.bounds.height }
    return dragTop ?? restingTop(for: selectedIndex)
  }

  private func layoutSheet() {
    let bounds = view.bounds
    let width = Self.width(for: bounds.width, sizeClass: traitCollection.horizontalSizeClass)
    let top = currentTop
    backdrop.frame = bounds
    sheetView.frame = CGRect(x: (bounds.width - width) / 2, y: top, width: width, height: bounds.height + Self.overscroll)
    contentView.frame = CGRect(x: 0, y: 0, width: width, height: maximumDetentValue)
    grabber.frame = CGRect(
      origin: CGPoint(x: (width - Self.grabberSize.width) / 2, y: 5),
      size: Self.grabberSize
    )
    backdrop.alpha = backdropAlpha(forTop: top)
  }

  /// Fully dimmed at every detent; fades out below the lowest one.
  private func backdropAlpha(forTop top: CGFloat) -> CGFloat {
    let bottom = view.bounds.height
    guard let lowest = detentTops.max(), bottom > lowest else { return top >= bottom ? 0 : 1 }
    return min(max((bottom - top) / (bottom - lowest), 0), 1)
  }

  private func applyAppearance() {
    backdrop.configure(color: config.backdropColor, opacity: config.backdropOpacity, blurRadius: config.backdropBlurRadius)
    sheetView.backgroundColor = config.contentBackgroundColor
    sheetView.layer.cornerRadius = config.cornerRadius ?? Self.defaultCornerRadius
    grabber.isHidden = !config.grabberVisible
  }

  private func animateToRest() {
    guard isViewLoaded, drag == nil, !isOffscreen else { return }
    dragTop = nil
    UIView.animate(withDuration: 0.3, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
      self.view.setNeedsLayout()
      self.view.layoutIfNeeded()
    }
  }

  private func animate(
    distance: CGFloat,
    velocity: CGFloat,
    damping: CGFloat = 0.9,
    duration: TimeInterval = 0.45,
    animations: @escaping () -> Void,
    completion: (() -> Void)? = nil
  ) {
    // UIKit expects the initial velocity relative to the distance travelled.
    let relativeVelocity = abs(distance) > 1 ? min(max(velocity / distance, -30), 30) : 0
    UIView.animate(
      withDuration: duration,
      delay: 0,
      usingSpringWithDamping: damping,
      initialSpringVelocity: relativeVelocity,
      options: [.allowUserInteraction, .beginFromCurrentState],
      animations: animations,
      completion: { _ in completion?() }
    )
  }

  // MARK: Content area

  private func reportContentArea() {
    guard isViewLoaded, view.bounds.width > 0 else { return }
    var height = Self.contentHeight(for: config.detents, maximum: maximumDetentValue)
    if config.keyboardBehavior == .resize {
      height -= keyboardLift
    }
    let width = Self.width(for: view.bounds.width, sizeClass: traitCollection.horizontalSizeClass)
    presentationDelegate?.modalPresentationDidChangeContentArea(CGSize(width: width, height: max(height, 0)))
  }

  /// Height the content may occupy at the largest of `detents`.
  static func contentHeight(for detents: [SheetDetent], maximum: CGFloat) -> CGFloat {
    if detents.isEmpty || detents.contains(.large) || detents.contains(.fitcontent) {
      return maximum
    }
    return detents.contains(.medium) ? maximum * 0.5 : maximum * 0.25
  }

  /// Best guess of `maximumDetentValue` before the sheet is on screen.
  static func estimatedMaximumDetentValue(in window: UIWindow) -> CGFloat {
    let insets = window.safeAreaInsets
    return window.bounds.height - insets.top - insets.bottom - topGap
  }

  /// Best guess of the sheet width before it is on screen.
  static func estimatedWidth(in window: UIWindow) -> CGFloat {
    width(for: window.bounds.width, sizeClass: window.traitCollection.horizontalSizeClass)
  }

  private static func width(for available: CGFloat, sizeClass: UIUserInterfaceSizeClass) -> CGFloat {
    sizeClass == .regular ? min(available, regularMaxWidth) : available
  }

  // MARK: Dragging

  func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
    guard let pan = gestureRecognizer as? UIPanGestureRecognizer,
          !isBeingPresented, !isBeingDismissed, !isOffscreen else { return false }
    let velocity = pan.velocity(in: view)
    return abs(velocity.y) > abs(velocity.x)
  }

  /// Scroll views inside the content keep scrolling; `handlePan` decides
  /// frame by frame whether the sheet or the scroll view moves.
  func gestureRecognizer(
    _ gestureRecognizer: UIGestureRecognizer,
    shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
  ) -> Bool {
    guard let scrollView = other.view as? UIScrollView else { return false }
    return other === scrollView.panGestureRecognizer && scrollView.isDescendant(of: sheetView)
  }

  @objc private func handlePan(_ pan: UIPanGestureRecognizer) {
    switch pan.state {
    case .began:
      // Grab the sheet where it is, even mid-animation.
      let top = sheetView.layer.presentation()?.frame.minY ?? sheetView.frame.minY
      sheetView.layer.removeAllAnimations()
      backdrop.layer.removeAllAnimations()
      let translation = pan.translation(in: sheetView)
      let location = pan.location(in: sheetView)
      let start = CGPoint(x: location.x - translation.x, y: location.y - translation.y)
      var state = DragState(rawTop: top)
      state.lastTranslation = pan.translation(in: view).y
      state.scrollView = verticalScrollView(at: start)
      if let scrollView = state.scrollView {
        lockedOffsetY = scrollView.contentOffset.y
      }
      drag = state
      dragTop = top
      layoutSheet()

    case .changed:
      guard var state = drag, let minTop = detentTops.min() else { return }
      let translation = pan.translation(in: view).y
      let delta = translation - state.lastTranslation
      state.lastTranslation = translation

      if let scrollView = state.scrollView {
        // Up: expand the sheet before scrolling. Down: scroll back to the top
        // before collapsing the sheet.
        let drive = delta < 0 ? state.rawTop > minTop + 0.5 : scrollView.isScrolledToTop
        if drive {
          state.rawTop = max(state.rawTop + delta, minTop)
          lockScroll(scrollView, at: delta < 0 ? lockedOffsetY : scrollView.topOffsetY)
        } else {
          unlockScroll()
          lockedOffsetY = scrollView.contentOffset.y
        }
        state.sheetDrove = drive
      } else {
        state.rawTop += delta
        state.sheetDrove = true
      }
      drag = state
      dragTop = constrained(state.rawTop)
      layoutSheet()

    case .ended, .cancelled, .failed:
      guard let state = drag else { return }
      drag = nil
      let velocity = state.sheetDrove ? pan.velocity(in: view).y : 0
      endDrag(at: dragTop ?? currentTop, velocity: velocity, holdingScroll: state.sheetDrove)

    default:
      break
    }
  }

  private func endDrag(at top: CGFloat, velocity: CGFloat, holdingScroll: Bool) {
    if !holdingScroll {
      unlockScroll()
    }
    let tops = detentTops
    guard let lowest = tops.max() else { return }
    // Where a fling of this velocity would come to rest.
    let projected = top + velocity * 0.2

    if config.dismissOnSwipe, velocity >= 0, projected > lowest + (view.bounds.height - lowest) / 2 {
      releaseVelocity = velocity
      presentationDelegate?.modalPresentationDidRequestSwipeDismiss()
      if isBeingDismissed {
        return
      }
      releaseVelocity = 0
    }

    let index = tops.indices.min { abs(tops[$0] - projected) < abs(tops[$1] - projected) } ?? selectedIndex
    if index != selectedIndex {
      selectedIndex = index
      presentationDelegate?.modalPresentationDidChangeDetent(index)
    }
    dragTop = nil
    animate(distance: restingTop(for: index) - top, velocity: velocity, animations: { self.layoutSheet() }) { [weak self] in
      guard let self, self.drag == nil else { return }
      self.unlockScroll()
    }
  }

  /// Rubber-bands above the tallest detent, and below the lowest one when
  /// swiping cannot dismiss.
  private func constrained(_ top: CGFloat) -> CGFloat {
    let tops = detentTops
    guard let minTop = tops.min(), let maxTop = tops.max() else { return top }
    let dimension = max(view.bounds.height, 1)
    if top < minTop {
      return minTop - Self.rubberBand(minTop - top, dimension: dimension)
    }
    if !config.dismissOnSwipe, top > maxTop {
      return maxTop + Self.rubberBand(top - maxTop, dimension: dimension)
    }
    return top
  }

  private static func rubberBand(_ offset: CGFloat, dimension: CGFloat) -> CGFloat {
    (1 - 1 / (offset * 0.55 / dimension + 1)) * dimension
  }

  /// The innermost vertically scrollable view under `point` (in `sheetView`).
  private func verticalScrollView(at point: CGPoint) -> UIScrollView? {
    var candidate = sheetView.hitTest(point, with: nil)
    while let current = candidate, current !== sheetView {
      if let scrollView = current as? UIScrollView, scrollView.isScrollEnabled, scrollView.canScrollVertically {
        return scrollView
      }
      candidate = current.superview
    }
    return nil
  }

  /// Pins the scroll view's offset while the sheet moves, so content does not
  /// scroll (or bounce) at the same time.
  private func lockScroll(_ scrollView: UIScrollView, at offsetY: CGFloat) {
    if lockedScrollView !== scrollView {
      unlockScroll()
      lockedScrollView = scrollView
    }
    lockedOffsetY = offsetY
    if scrollView.contentOffset.y != offsetY {
      scrollView.contentOffset.y = offsetY
    }
    guard scrollLock == nil else { return }
    scrollLock = scrollView.observe(\.contentOffset) { [weak self] scrollView, _ in
      guard let self, scrollView.contentOffset.y != self.lockedOffsetY else { return }
      scrollView.contentOffset.y = self.lockedOffsetY
    }
  }

  private func unlockScroll() {
    guard let scrollView = lockedScrollView else { return }
    scrollLock?.invalidate()
    scrollLock = nil
    lockedScrollView = nil
    // Stops any deceleration left over from a fling that moved the sheet.
    scrollView.setContentOffset(scrollView.contentOffset, animated: false)
  }

  // MARK: Keyboard

  @objc private func keyboardWillChangeFrame(_ notification: Notification) {
    guard isViewLoaded, let window = view.window,
          let frame = (notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
    let keyboard = window.convert(frame, from: window.screen.coordinateSpace)
    updateKeyboardHeight(max(0, window.bounds.maxY - keyboard.minY), notification: notification)
  }

  @objc private func keyboardWillHide(_ notification: Notification) {
    updateKeyboardHeight(0, notification: notification)
  }

  private func updateKeyboardHeight(_ height: CGFloat, notification: Notification) {
    guard isViewLoaded, height != keyboardHeight else { return }
    keyboardHeight = height
    guard drag == nil else { return }
    notification.animateAlongsideKeyboard {
      self.view.setNeedsLayout()
      self.view.layoutIfNeeded()
    }
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

private extension UIScrollView {
  var topOffsetY: CGFloat {
    -adjustedContentInset.top
  }

  var isScrolledToTop: Bool {
    contentOffset.y <= topOffsetY + 0.5
  }

  var canScrollVertically: Bool {
    alwaysBounceVertical
      || contentSize.height + adjustedContentInset.top + adjustedContentInset.bottom > bounds.height + 0.5
  }
}
