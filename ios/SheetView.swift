//
//  SheetView.swift
//  NitroModal
//

import UIKit

/// The `bottomSheet` surface: detents, dragging, swipe-to-dismiss, the
/// hand-off to scroll views inside the content, pull-to-refresh and keyboard
/// tracking, implemented with a pan gesture.
///
/// A modal sheet fills the screen as the view of `SheetViewController`. An
/// inline sheet fills `NitroModalHostView`, in the screen's own layout: it has
/// no backdrop, lets touches outside the sheet reach the views behind it,
/// ignores the keyboard and can't be dismissed by the user.
final class SheetView: UIView, UIGestureRecognizerDelegate {
  weak var delegate: ModalPresentationDelegate?
  /// Whether a modal present/dismiss transition is running; the sheet can't be
  /// dragged meanwhile.
  var isTransitioning: () -> Bool = { false }

  /// Gap kept above a modal sheet at its tallest, like a system page sheet.
  private static let topGap: CGFloat = 10
  /// Extra sheet height below the screen so it stays attached to the bottom
  /// edge while rubber-banding upward.
  private static let overscroll: CGFloat = 400
  private static let defaultCornerRadius: CGFloat = 16
  private static let regularMaxWidth: CGFloat = 704
  private static let grabberSize = CGSize(width: 36, height: 5)
  /// How far below its lowest detent the sheet must be pulled to refresh,
  /// and where it rests while refreshing.
  private static let refreshThreshold: CGFloat = 56
  private static let touchHandlerClass: AnyClass? = NSClassFromString("RCTSurfaceTouchHandler")

  let isInline: Bool
  private let contentView: NitroModalContentView
  private let backdrop = BackdropView()
  private let sheetView = UIView()
  private let grabber = UIView()
  private let refreshIndicator = UIActivityIndicatorView(style: .medium)
  private var config: ModalConfig
  private var selectedIndex: Int
  private var keyboardHeight: CGFloat = 0

  /// The sheet sits below the bottom edge (before presenting / while dismissing).
  private var isOffscreen = true
  /// Top of the sheet while the user drags it; `nil` rests on `selectedIndex`.
  private var dragTop: CGFloat?
  private var drag: DragState?
  /// Vertical velocity of the swipe that requested the dismissal.
  private var releaseVelocity: CGFloat = 0
  private var reportedRestingTop: CGFloat?
  private var refreshFeedback: UIImpactFeedbackGenerator?
  /// The sheet rests below its lowest detent, the indicator spinning above it.
  private var isRefreshing = false

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
    /// The scroll view moved during this gesture. It then keeps the downward
    /// part too: scrolled content goes back to its top, and only a new
    /// gesture moves the sheet.
    var contentScrolled = false
    /// Started with the sheet resting on its lowest detent, so pulling it
    /// further down refreshes.
    var canRefresh = false
    /// Pulled far enough that letting go refreshes.
    var refreshArmed = false
    var cancelledReactTouches = false
  }

  init(contentView: NitroModalContentView, config: ModalConfig, isInline: Bool) {
    self.contentView = contentView
    self.config = config
    self.isInline = isInline
    selectedIndex = config.clampedInitialDetentIndex
    isRefreshing = config.refreshing ?? false
    super.init(frame: .zero)
    backgroundColor = .clear

    if isInline {
      // The sheet travels below the host's bottom edge; nothing may show there.
      clipsToBounds = true
    } else {
      backdrop.onTap = { [weak self] in
        self?.delegate?.modalPresentationDidTapBackdrop()
      }
      addSubview(backdrop)
      let center = NotificationCenter.default
      center.addObserver(self, selector: #selector(keyboardWillChangeFrame(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
      center.addObserver(self, selector: #selector(keyboardWillHide(_:)), name: UIResponder.keyboardWillHideNotification, object: nil)
    }

    sheetView.clipsToBounds = true
    sheetView.layer.cornerCurve = .continuous
    sheetView.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
    sheetView.accessibilityViewIsModal = !isInline
    sheetView.addSubview(contentView)

    grabber.isUserInteractionEnabled = false
    grabber.backgroundColor = .tertiaryLabel
    grabber.layer.cornerRadius = Self.grabberSize.height / 2
    sheetView.addSubview(grabber)

    // Inside the sheet, behind the content, which uncovers it as it's pulled down.
    refreshIndicator.hidesWhenStopped = false
    refreshIndicator.isUserInteractionEnabled = false
    refreshIndicator.alpha = 0
    sheetView.insertSubview(refreshIndicator, belowSubview: contentView)
    addSubview(sheetView)

    let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
    pan.delegate = self
    sheetView.addGestureRecognizer(pan)

    applyAppearance()
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    layoutSheet()
    reportContentArea()
  }

  override func safeAreaInsetsDidChange() {
    super.safeAreaInsetsDidChange()
    setNeedsLayout()
  }

  override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
    let hit = super.hitTest(point, with: event)
    // Inline, only the sheet takes touches; the rest reach the screen behind.
    return isInline && hit === self ? nil : hit
  }

  // MARK: Presentation

  func apply(_ newConfig: ModalConfig) {
    let refreshingChanged = newConfig.refreshing != config.refreshing
    config = newConfig
    selectedIndex = min(selectedIndex, max(detents.count - 1, 0))
    if refreshingChanged {
      setRefreshing(newConfig.refreshing ?? false)
    }
    applyAppearance()
    animateToRest()
  }

  func contentSizeDidChange() {
    guard config.detents.contains(.fitContent) else { return }
    animateToRest()
  }

  func snapToDetent(at index: Int) {
    guard detents.indices.contains(index) else { return }
    selectedIndex = index
    animateToRest()
    delegate?.modalPresentationDidChangeDetent(index)
  }

  /// Escape gesture (VoiceOver), modal sheets only.
  func performEscape() -> Bool {
    guard !isInline, config.dismissOnSwipe else { return false }
    delegate?.modalPresentationDidRequestSwipeDismiss()
    return true
  }

  /// Slides the sheet in or out; a swipe dismissal keeps its release velocity.
  func animateTransition(toOffscreen offscreen: Bool, animated: Bool, duration: TimeInterval, completion: @escaping () -> Void) {
    let from = sheetView.frame.minY + contentView.frame.minY
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
      completion: {
        self.stopRefreshIndicatorIfHidden()
        completion()
      }
    )
  }

  /// Releases the scroll view pinned by a drag.
  func unlockScroll() {
    guard let scrollView = lockedScrollView else { return }
    scrollLock?.invalidate()
    scrollLock = nil
    lockedScrollView = nil
    // Stops any deceleration left over from a fling that moved the sheet.
    scrollView.setContentOffset(scrollView.contentOffset, animated: false)
  }

  // MARK: Layout

  private var detents: [Detent] {
    config.detents.isEmpty ? [.fitContent] : config.detents
  }

  /// Tallest height the content may occupy (excludes the bottom safe area).
  private var maximumDetentValue: CGFloat {
    Self.maximumDetentValue(height: bounds.height, insets: safeAreaInsets, isInline: isInline)
  }

  private var keyboardLift: CGFloat {
    isInline || config.keyboardBehavior == .none ? 0 : max(0, keyboardHeight - safeAreaInsets.bottom)
  }

  private var sheetWidth: CGFloat {
    isInline ? bounds.width : Self.width(for: bounds.width, sizeClass: traitCollection.horizontalSizeClass)
  }

  /// Sheet top when resting on `detents[index]`. The bottom safe area is
  /// added below the content.
  private func restingTop(for index: Int) -> CGFloat {
    let detent = detents[min(max(index, 0), detents.count - 1)]
    let height = detent.height(maximum: maximumDetentValue, contentHeight: contentView.contentSize.height)
    let top = bounds.height - safeAreaInsets.bottom - keyboardLift - height
    return max(top, safeAreaInsets.top + (isInline ? 0 : Self.topGap))
  }

  private var detentTops: [CGFloat] {
    detents.indices.map(restingTop(for:))
  }

  /// Where the sheet settles on `detents[index]`: held below the lowest
  /// detent while refreshing.
  private func settledTop(for index: Int) -> CGFloat {
    let top = restingTop(for: index)
    guard isRefreshing, let lowest = detentTops.max(), abs(top - lowest) < 1 else { return top }
    return top + Self.refreshThreshold
  }

  private var settledTops: [CGFloat] {
    detents.indices.map(settledTop(for:))
  }

  private var currentTop: CGFloat {
    if isOffscreen { return bounds.height }
    return dragTop ?? settledTop(for: selectedIndex)
  }

  /// With a refresh indicator, a pull below the lowest detent keeps the sheet
  /// there and moves the content down inside it instead, uncovering the
  /// indicator at the top of the sheet.
  private var pullsContent: Bool {
    config.refreshing != nil && config.pullToRefreshEnabled && !config.dismissOnSwipe
  }

  /// Splits a sheet top into where the sheet goes and how far its content is pulled down.
  private func split(_ top: CGFloat) -> (sheetTop: CGFloat, pull: CGFloat) {
    guard pullsContent, !isOffscreen, let lowest = detentTops.max(), top > lowest else { return (top, 0) }
    return (lowest, top - lowest)
  }

  private func layoutSheet() {
    let width = sheetWidth
    let (top, pull) = split(currentTop)
    backdrop.frame = bounds
    sheetView.frame = CGRect(x: (bounds.width - width) / 2, y: top, width: width, height: bounds.height + Self.overscroll)
    contentView.frame = CGRect(x: 0, y: pull, width: width, height: maximumDetentValue)
    grabber.frame = CGRect(
      origin: CGPoint(x: (width - Self.grabberSize.width) / 2, y: 5),
      size: Self.grabberSize
    )
    backdrop.alpha = backdropAlpha(forTop: top)
    layoutRefreshIndicator(width: width, pull: pull)
    reportRestingTop()
  }

  /// Centered in the gap a pull uncovers above the content, fading in as the
  /// pull nears the threshold.
  private func layoutRefreshIndicator(width: CGFloat, pull: CGFloat) {
    let size = refreshIndicator.intrinsicContentSize
    refreshIndicator.bounds = CGRect(origin: .zero, size: size)
    refreshIndicator.center = CGPoint(x: width / 2, y: pull / 2)
    let progress = min(max(pull / Self.refreshThreshold, 0), 1)
    refreshIndicator.alpha = progress
    if progress > 0, !refreshIndicator.isAnimating {
      refreshIndicator.startAnimating()
    }
  }

  /// Called once the sheet settles, so a fading indicator keeps spinning.
  private func stopRefreshIndicatorIfHidden() {
    if drag == nil, refreshIndicator.alpha == 0 {
      refreshIndicator.stopAnimating()
    }
  }

  private func setRefreshing(_ refreshing: Bool) {
    guard refreshing != isRefreshing else { return }
    isRefreshing = refreshing
    if refreshing, !isOffscreen, config.refreshing != nil {
      refreshIndicator.startAnimating()
    }
  }

  /// Fully dimmed at every detent; fades out below the lowest one.
  private func backdropAlpha(forTop top: CGFloat) -> CGFloat {
    let bottom = bounds.height
    guard let lowest = detentTops.max(), bottom > lowest else { return top >= bottom ? 0 : 1 }
    return min(max((bottom - top) / (bottom - lowest), 0), 1)
  }

  private func applyAppearance() {
    backdrop.isHidden = isInline
    backdrop.configure(color: config.backdropColor, opacity: config.backdropOpacity, blurRadius: config.backdropBlurRadius)
    sheetView.backgroundColor = config.contentBackgroundColor
    sheetView.layer.cornerRadius = config.cornerRadius ?? Self.defaultCornerRadius
    // Drawn above the sublayers, so the content can't cover it.
    sheetView.layer.borderWidth = config.borderWidth
    sheetView.layer.borderColor = config.borderColor.cgColor
    grabber.isHidden = !config.grabberVisible
    refreshIndicator.color = config.refreshIndicatorColor
  }

  private func animateToRest() {
    guard drag == nil, !isOffscreen else { return }
    dragTop = nil
    UIView.animate(withDuration: 0.3, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction], animations: {
      self.setNeedsLayout()
      self.layoutIfNeeded()
    }, completion: { _ in
      self.stopRefreshIndicatorIfHidden()
    })
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
    guard bounds.width > 0 else { return }
    var height = Self.contentHeight(for: config.detents, maximum: maximumDetentValue)
    if config.keyboardBehavior == .resize {
      height -= keyboardLift
    }
    delegate?.modalPresentationDidChangeContentArea(CGSize(width: sheetWidth, height: max(height, 0)))
  }

  /// Inline: React lays the content out at the sheet's resting top, so what
  /// `measure()` reports (and presses rely on) matches the screen. Reported
  /// as soon as the target detent is known, before the sheet settles.
  private func reportRestingTop() {
    guard isInline, bounds.height > 0 else { return }
    let top = restingTop(for: selectedIndex).rounded()
    guard top != reportedRestingTop else { return }
    reportedRestingTop = top
    delegate?.modalPresentationDidChangeRestingTop(top)
  }

  /// Height the content may occupy at the largest of `detents`.
  static func contentHeight(for detents: [Detent], maximum: CGFloat) -> CGFloat {
    guard !detents.isEmpty else { return maximum }
    // `fitContent` content can grow up to the maximum.
    return detents.map { $0.height(maximum: maximum, contentHeight: maximum) }.max() ?? maximum
  }

  /// Tallest height the content may occupy in an area `height` tall.
  static func maximumDetentValue(height: CGFloat, insets: UIEdgeInsets, isInline: Bool) -> CGFloat {
    max(0, height - insets.top - insets.bottom - (isInline ? 0 : topGap))
  }

  /// Best guess of the sheet width before a modal sheet is on screen.
  static func estimatedWidth(in window: UIWindow) -> CGFloat {
    width(for: window.bounds.width, sizeClass: window.traitCollection.horizontalSizeClass)
  }

  private static func width(for available: CGFloat, sizeClass: UIUserInterfaceSizeClass) -> CGFloat {
    sizeClass == .regular ? min(available, regularMaxWidth) : available
  }

  // MARK: Dragging

  override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
    guard let pan = gestureRecognizer as? UIPanGestureRecognizer, pan.view === sheetView else {
      return super.gestureRecognizerShouldBegin(gestureRecognizer)
    }
    guard !isTransitioning(), !isOffscreen else { return false }
    let velocity = pan.velocity(in: self)
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
      let isSettled = (sheetView.layer.animationKeys()?.isEmpty ?? true)
        && (contentView.layer.animationKeys()?.isEmpty ?? true)
      let sheetTop = sheetView.layer.presentation()?.frame.minY ?? sheetView.frame.minY
      let pull = contentView.layer.presentation()?.frame.minY ?? contentView.frame.minY
      let top = sheetTop + pull
      sheetView.layer.removeAllAnimations()
      contentView.layer.removeAllAnimations()
      refreshIndicator.layer.removeAllAnimations()
      backdrop.layer.removeAllAnimations()
      let translation = pan.translation(in: sheetView)
      let location = pan.location(in: sheetView)
      let start = CGPoint(x: location.x - translation.x, y: location.y - translation.y)
      var state = DragState(rawTop: top)
      state.lastTranslation = pan.translation(in: self).y
      state.scrollView = verticalScrollView(at: start)
      if let scrollView = state.scrollView {
        lockedOffsetY = scrollView.contentOffset.y
      }
      if config.pullToRefreshEnabled, !config.dismissOnSwipe, !isRefreshing, isSettled,
         let lowest = detentTops.max(), abs(top - lowest) < 1 {
        state.canRefresh = true
        refreshFeedback = UIImpactFeedbackGenerator(style: .medium)
        refreshFeedback?.prepare()
      }
      drag = state
      dragTop = top
      layoutSheet()

    case .changed:
      guard var state = drag, let minTop = detentTops.min() else { return }
      let translation = pan.translation(in: self).y
      let delta = translation - state.lastTranslation
      state.lastTranslation = translation

      if let scrollView = state.scrollView {
        // Up: expand the sheet before scrolling. Down: scroll back to the top
        // before collapsing the sheet, in a separate gesture.
        let drive = delta < 0
          ? state.rawTop > minTop + 0.5
          : !state.contentScrolled && scrollView.isScrolledToTop
        if drive {
          state.rawTop = max(state.rawTop + delta, minTop)
          lockScroll(scrollView, at: delta < 0 ? lockedOffsetY : scrollView.topOffsetY)
        } else {
          unlockScroll()
          lockedOffsetY = scrollView.contentOffset.y
          if delta != 0 {
            state.contentScrolled = true
          }
        }
        state.sheetDrove = drive
      } else {
        state.rawTop += delta
        state.sheetDrove = true
      }
      let top = constrained(state.rawTop)
      if state.sheetDrove, isInline, !state.cancelledReactTouches {
        state.cancelledReactTouches = true
        cancelReactTouches()
      }
      if state.canRefresh, let lowest = detentTops.max() {
        let armed = top - lowest >= Self.refreshThreshold
        if armed, !state.refreshArmed {
          refreshFeedback?.impactOccurred()
        }
        state.refreshArmed = armed
      }
      drag = state
      dragTop = top
      layoutSheet()

    case .ended, .cancelled, .failed:
      guard let state = drag else { return }
      drag = nil
      refreshFeedback = nil
      let velocity = state.sheetDrove ? pan.velocity(in: self).y : 0
      let refresh = state.refreshArmed && pan.state == .ended
      if refresh {
        // Settles on the refreshing position; React confirms or ends it.
        setRefreshing(config.refreshing != nil)
      }
      endDrag(at: dragTop ?? currentTop, velocity: velocity, holdingScroll: state.sheetDrove)
      if refresh {
        delegate?.modalPresentationDidPullToRefresh()
      }

    default:
      break
    }
  }

  private func endDrag(at top: CGFloat, velocity: CGFloat, holdingScroll: Bool) {
    if !holdingScroll {
      unlockScroll()
    }
    let tops = settledTops
    guard let lowest = tops.max() else { return }
    // Where a fling of this velocity would come to rest.
    let projected = top + velocity * 0.2

    if config.dismissOnSwipe, velocity >= 0, projected > lowest + (bounds.height - lowest) / 2 {
      releaseVelocity = velocity
      delegate?.modalPresentationDidRequestSwipeDismiss()
      if isOffscreen || isTransitioning() {
        return
      }
      releaseVelocity = 0
    }

    let index = tops.indices.min { abs(tops[$0] - projected) < abs(tops[$1] - projected) } ?? selectedIndex
    let changed = index != selectedIndex
    selectedIndex = index
    dragTop = nil
    if changed {
      delegate?.modalPresentationDidChangeDetent(index)
    }
    animate(distance: settledTop(for: index) - top, velocity: velocity, animations: { self.layoutSheet() }) { [weak self] in
      guard let self, self.drag == nil else { return }
      self.unlockScroll()
      self.stopRefreshIndicatorIfHidden()
    }
  }

  /// Rubber-bands above the tallest detent, and below the lowest one when
  /// swiping cannot dismiss.
  private func constrained(_ top: CGFloat) -> CGFloat {
    let tops = settledTops
    guard let minTop = tops.min(), let maxTop = tops.max() else { return top }
    let dimension = max(bounds.height, 1)
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

  /// An inline sheet lives inside the React root view, whose touch handler
  /// only yields to gestures outside of it. Cancel its touches when the sheet
  /// starts moving, so the press under the finger doesn't fire on release.
  private func cancelReactTouches() {
    guard let handlerClass = Self.touchHandlerClass else { return }
    var current = superview
    while let view = current {
      for recognizer in view.gestureRecognizers ?? [] where recognizer.isEnabled && recognizer.isKind(of: handlerClass) {
        recognizer.isEnabled = false
        recognizer.isEnabled = true
      }
      current = view.superview
    }
  }

  // MARK: Keyboard (modal only)

  @objc private func keyboardWillChangeFrame(_ notification: Notification) {
    guard let window,
          let frame = (notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
    let keyboard = window.convert(frame, from: window.screen.coordinateSpace)
    updateKeyboardHeight(max(0, window.bounds.maxY - keyboard.minY), notification: notification)
  }

  @objc private func keyboardWillHide(_ notification: Notification) {
    updateKeyboardHeight(0, notification: notification)
  }

  private func updateKeyboardHeight(_ height: CGFloat, notification: Notification) {
    guard height != keyboardHeight else { return }
    keyboardHeight = height
    guard drag == nil else { return }
    notification.animateAlongsideKeyboard {
      self.setNeedsLayout()
      self.layoutIfNeeded()
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
