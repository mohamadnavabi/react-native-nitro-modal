//
//  ModalConfig.swift
//  NitroModal
//

import UIKit

/// A resting height of a `bottomSheet`.
enum Detent: Equatable {
  case small, medium, large, fitContent
  /// Content height in points, above the bottom safe area.
  case height(CGFloat)

  init(_ detent: SheetDetent) {
    switch detent {
    case .first(.small): self = .small
    case .first(.medium): self = .medium
    case .first(.large): self = .large
    case .first(.fitcontent): self = .fitContent
    case .second(let points): self = points.isFinite ? .height(CGFloat(points)) : .large
    }
  }

  /// Content height at this detent, given the tallest height the content may
  /// occupy and the measured height of the content (for `fitContent`).
  func height(maximum: CGFloat, contentHeight: CGFloat) -> CGFloat {
    switch self {
    case .small: return maximum * 0.25
    case .medium: return maximum * 0.5
    case .large: return maximum
    case .fitContent: return min(max(contentHeight, 1), maximum)
    case .height(let points): return min(max(points, 1), maximum)
    }
  }
}

/// Snapshot of the React props that shape a presentation.
struct ModalConfig: Equatable {
  var mode: ModalMode = .bottomsheet
  /// An inline `bottomSheet` (see `isInline`), as opposed to a modal one.
  var inlinePresentation = false
  var detents: [Detent] = [.fitContent]
  var initialDetentIndex = 0
  var backdropColor: UIColor = .black
  var backdropOpacity: CGFloat = 0.4
  var backdropBlurRadius: CGFloat = 0
  var dismissOnBackdropPress = true
  var dismissOnSwipe = true
  var grabberVisible = false
  /// `nil` uses the platform default.
  var cornerRadius: CGFloat?
  var contentBackgroundColor: UIColor = .systemBackground
  var keyboardBehavior: KeyboardBehavior = .pan
  var popupAnimation: PopupAnimation = .scale
  var pullToRefreshEnabled = false

  /// A `bottomSheet` living in the host view's bounds. Popups are always modal.
  var isInline: Bool {
    inlinePresentation && mode == .bottomsheet
  }

  var clampedInitialDetentIndex: Int {
    min(max(initialDetentIndex, 0), max(detents.count - 1, 0))
  }
}

/// Callbacks from a presented modal controller (or an inline sheet) back to
/// `HybridNitroModal`.
protocol ModalPresentationDelegate: AnyObject {
  func modalPresentationDidTapBackdrop()
  /// The user swiped the sheet down far enough to dismiss it.
  func modalPresentationDidRequestSwipeDismiss()
  func modalPresentationDidChangeDetent(_ index: Int)
  func modalPresentationDidChangeContentArea(_ size: CGSize)
  /// The user pulled the sheet past its lowest detent and let go.
  func modalPresentationDidPullToRefresh()
  /// Inline sheets: the sheet's top at rest, in the host view.
  func modalPresentationDidChangeRestingTop(_ top: CGFloat)
}

/// A view controller that presents the React content in one of the modal modes.
protocol ModalPresenting: UIViewController {
  var presentationDelegate: ModalPresentationDelegate? { get set }
  func apply(_ config: ModalConfig)
  func contentSizeDidChange()
  func snapToDetent(at index: Int)
}

extension UIColor {
  /// Decodes a color produced by React Native's `processColor` (0xAARRGGBB).
  convenience init(processedColor value: Double) {
    let argb = UInt32(truncatingIfNeeded: Int64(value))
    self.init(
      red: CGFloat((argb >> 16) & 0xFF) / 255,
      green: CGFloat((argb >> 8) & 0xFF) / 255,
      blue: CGFloat(argb & 0xFF) / 255,
      alpha: CGFloat((argb >> 24) & 0xFF) / 255
    )
  }
}

extension Notification {
  /// Runs `animations` with the keyboard's own duration and curve so layout
  /// changes move in lockstep with the keyboard.
  func animateAlongsideKeyboard(_ animations: @escaping () -> Void) {
    let duration = (userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
    let curve = (userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? UInt) ?? 7
    UIView.animate(
      withDuration: duration,
      delay: 0,
      options: [UIView.AnimationOptions(rawValue: curve << 16), .beginFromCurrentState, .allowUserInteraction],
      animations: animations
    )
  }
}
