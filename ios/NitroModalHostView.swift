//
//  NitroModalHostView.swift
//  NitroModal
//

import UIKit

/// The view Nitro mounts into the React tree. React children mounted into it
/// (see `HybridNitroModalComponent+Children.mm`) are hosted by `contentView`.
///
/// Modal: an invisible placeholder; `contentView` travels into the presented
/// controller. Inline: the area the sheet lives in. It hosts the `SheetView`
/// and only takes touches that land on the sheet.
final class NitroModalHostView: UIView {
  let contentView = NitroModalContentView()
  var onWindowChange: (() -> Void)?
  /// The bounds or safe area changed.
  var onLayoutChange: (() -> Void)?

  var isInline = false {
    didSet {
      isUserInteractionEnabled = isInline
      contentView.isInline = isInline
    }
  }

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

  override func layoutSubviews() {
    super.layoutSubviews()
    onLayoutChange?()
  }

  override func safeAreaInsetsDidChange() {
    super.safeAreaInsetsDidChange()
    onLayoutChange?()
  }

  override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
    let hit = super.hitTest(point, with: event)
    return hit === self ? nil : hit
  }

  func showInlineSheet(_ sheet: SheetView) {
    sheet.frame = bounds
    sheet.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    addSubview(sheet)
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

/// Holds the React children inside the modal and reports the size they
/// occupy, which drives the `fitContent` detent and the popup card size.
final class NitroModalContentView: UIView {
  var onContentSizeChange: ((CGSize) -> Void)?

  /// Hosted by an inline sheet, inside the React root view.
  ///
  /// The Fabric touch handler attached to this view is then disabled: a modal
  /// lives outside the root view and needs its own, but inline the root's
  /// handler already sees these touches.
  var isInline = false {
    didSet {
      updateTouchHandlers()
      setNeedsLayout()
    }
  }

  private var reactChildren: [UIView] = []
  private var observations: [ObjectIdentifier: [NSKeyValueObservation]] = [:]
  private var lastReportedSize: CGSize = .zero

  /// Where React placed the children. Inline, React lays the content out at
  /// the sheet's resting top so that `measure()` matches the screen; this
  /// view's bounds origin cancels that offset out on screen.
  private var reactOrigin: CGPoint {
    guard isInline else { return .zero }
    let minY = reactChildren.map { $0.center.y - $0.bounds.height / 2 }.min() ?? 0
    return CGPoint(x: 0, y: minY)
  }

  /// Bounding size of the React children. Fabric positions them with
  /// `center`/`bounds`, so this stays correct even with transforms applied.
  var contentSize: CGSize {
    let origin = reactOrigin
    return reactChildren.reduce(.zero) { size, child in
      CGSize(
        width: max(size.width, child.center.x + child.bounds.width / 2 - origin.x),
        height: max(size.height, child.center.y + child.bounds.height / 2 - origin.y)
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
    // The touch handler is attached right before the first child mounts.
    updateTouchHandlers()
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
    let origin = reactOrigin
    if bounds.origin != origin {
      bounds.origin = origin
    }
    let size = contentSize
    guard size != lastReportedSize else { return }
    lastReportedSize = size
    onContentSizeChange?(size)
  }

  private func updateTouchHandlers() {
    gestureRecognizers?.forEach { $0.isEnabled = !isInline }
  }
}
