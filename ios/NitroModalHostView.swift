//
//  NitroModalHostView.swift
//  NitroModal
//

import UIKit

/// The view Nitro mounts into the React tree. It is an invisible placeholder:
/// React children mounted into it (see `HybridNitroModalComponent+Children.mm`)
/// are hosted by `contentView`, which travels into the presented controller.
final class NitroModalHostView: UIView {
  let contentView = NitroModalContentView()
  var onWindowChange: (() -> Void)?

  override init(frame: CGRect) {
    super.init(frame: frame)
    isUserInteractionEnabled = false
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    onWindowChange?()
  }

  @objc(nitroModalMountChild:atIndex:)
  func mountChild(_ child: UIView, atIndex index: Int) {
    contentView.insertReactChild(child, at: index)
  }

  @objc(nitroModalUnmountChild:)
  func unmountChild(_ child: UIView) {
    contentView.removeReactChild(child)
  }

  @objc var nitroModalTouchRoot: UIView {
    contentView
  }
}

/// Holds the React children inside the presented modal and reports the size
/// they occupy, which drives the `fitContent` detent and the popup card size.
final class NitroModalContentView: UIView {
  var onContentSizeChange: ((CGSize) -> Void)?

  private var reactChildren: [UIView] = []
  private var observations: [ObjectIdentifier: [NSKeyValueObservation]] = [:]
  private var lastReportedSize: CGSize = .zero

  /// Bounding size of the React children. Fabric positions them with
  /// `center`/`bounds`, so this stays correct even with transforms applied.
  var contentSize: CGSize {
    reactChildren.reduce(.zero) { size, child in
      CGSize(
        width: max(size.width, child.center.x + child.bounds.width / 2),
        height: max(size.height, child.center.y + child.bounds.height / 2)
      )
    }
  }

  func insertReactChild(_ child: UIView, at index: Int) {
    let index = min(max(index, 0), reactChildren.count)
    reactChildren.insert(child, at: index)
    insertSubview(child, at: index)
    // Fabric writes `center` then `bounds`; coalesce both into one layout pass.
    observations[ObjectIdentifier(child)] = [
      child.layer.observe(\.bounds) { [weak self] _, _ in self?.setNeedsLayout() },
      child.layer.observe(\.position) { [weak self] _, _ in self?.setNeedsLayout() },
    ]
    setNeedsLayout()
  }

  func removeReactChild(_ child: UIView) {
    observations.removeValue(forKey: ObjectIdentifier(child))?.forEach { $0.invalidate() }
    reactChildren.removeAll { $0 === child }
    child.removeFromSuperview()
    setNeedsLayout()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    let size = contentSize
    guard size != lastReportedSize else { return }
    lastReportedSize = size
    onContentSizeChange?(size)
  }
}
