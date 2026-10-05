//
//  BackdropView.swift
//  NitroModal
//

import UIKit

/// Dimming (and optional blur) layer behind the modal. Its `alpha` is driven
/// by the presentation transitions, so it fades with the modal, including
/// interactive swipe-to-dismiss.
final class BackdropView: UIView {
  var onTap: (() -> Void)?

  private let dimView = UIView()
  private var blurView: UIVisualEffectView?

  override init(frame: CGRect) {
    super.init(frame: frame)
    dimView.frame = bounds
    dimView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    addSubview(dimView)
    addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleTap)))
    isAccessibilityElement = true
    accessibilityTraits = .button
    accessibilityLabel = "Dismiss"
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  func configure(color: UIColor, opacity: CGFloat, blurRadius: CGFloat) {
    var alpha: CGFloat = 1
    color.getRed(nil, green: nil, blue: nil, alpha: &alpha)
    dimView.backgroundColor = color.withAlphaComponent(alpha * min(max(opacity, 0), 1))

    guard blurRadius > 0 else {
      blurView?.removeFromSuperview()
      blurView = nil
      return
    }
    let blurView = self.blurView ?? {
      let view = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterial))
      view.frame = bounds
      view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      insertSubview(view, belowSubview: dimView)
      self.blurView = view
      return view
    }()
    // UIKit has no public blur radius. Blend a thin system material instead,
    // reaching full strength at ~30pt.
    blurView.alpha = min(blurRadius / 30, 1)
  }

  @objc private func handleTap() {
    onTap?()
  }

  override func accessibilityActivate() -> Bool {
    onTap?()
    return true
  }
}
