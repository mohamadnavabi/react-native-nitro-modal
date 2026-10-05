//
//  ModalConfig.swift
//  NitroModal
//

import UIKit

/// Snapshot of the React props that shape a presentation.
struct ModalConfig: Equatable {
  var mode: ModalMode = .bottomsheet
  var detents: [SheetDetent] = [.fitcontent]
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

  var clampedInitialDetentIndex: Int {
    min(max(initialDetentIndex, 0), max(detents.count - 1, 0))
  }
}

/// Callbacks from a presented modal controller back to `HybridNitroModal`.
protocol ModalPresentationDelegate: AnyObject {
  func modalPresentationDidTapBackdrop()
  /// The user swiped the sheet down far enough to dismiss it.
  func modalPresentationDidRequestSwipeDismiss()
  func modalPresentationDidChangeDetent(_ index: Int)
  func modalPresentationDidChangeContentArea(_ size: CGSize)
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
