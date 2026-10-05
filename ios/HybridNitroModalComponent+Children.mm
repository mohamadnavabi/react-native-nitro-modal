//
//  HybridNitroModalComponent+Children.mm
//  NitroModal
//
//  Nitro's generated Fabric component view mounts React children as its own
//  subviews. A modal needs them inside the presented view controller instead,
//  so this category routes child (un)mounting to the Swift host view, and
//  attaches a Fabric touch handler to the view that hosts them (it lives
//  outside the React root view, so the root's touch handler never sees it).
//
//  This mirrors what React Native's own RCTModalHostViewComponentView does.
//

#import <UIKit/UIKit.h>
#import <React/RCTSurfaceTouchHandler.h>
#import <React/RCTViewComponentView.h>

/// Implemented in Swift by `NitroModalHostView`. The pod does not emit an
/// ObjC header for its Swift code, so the methods are reached by selector.
@protocol NitroModalChildHost <NSObject>
- (void)nitroModalMountChild:(UIView *)child atIndex:(NSInteger)index;
- (void)nitroModalUnmountChild:(UIView *)child;
@property (nonatomic, readonly) UIView *nitroModalTouchRoot;
@end

/// Defined by nitrogen in HybridNitroModalComponent.mm.
@interface HybridNitroModalComponent : RCTViewComponentView
@end

@implementation HybridNitroModalComponent (NitroModalChildren)

- (nullable id<NitroModalChildHost>)nitroModal_childHost
{
  UIView *host = self.contentView;
  if ([host respondsToSelector:@selector(nitroModalMountChild:atIndex:)] &&
      [host respondsToSelector:@selector(nitroModalUnmountChild:)] &&
      [host respondsToSelector:@selector(nitroModalTouchRoot)]) {
    return (id<NitroModalChildHost>)host;
  }
  return nil;
}

- (void)mountChildComponentView:(UIView<RCTComponentViewProtocol> *)childComponentView index:(NSInteger)index
{
  id<NitroModalChildHost> host = [self nitroModal_childHost];
  if (host == nil) {
    [super mountChildComponentView:childComponentView index:index];
    return;
  }

  UIView *touchRoot = host.nitroModalTouchRoot;
  BOOL hasTouchHandler = NO;
  for (UIGestureRecognizer *recognizer in touchRoot.gestureRecognizers) {
    if ([recognizer isKindOfClass:[RCTSurfaceTouchHandler class]]) {
      hasTouchHandler = YES;
      break;
    }
  }
  if (!hasTouchHandler) {
    // The view retains the recognizer; it goes away together with the host.
    [[RCTSurfaceTouchHandler new] attachToView:touchRoot];
  }

  [host nitroModalMountChild:childComponentView atIndex:index];
}

- (void)unmountChildComponentView:(UIView<RCTComponentViewProtocol> *)childComponentView index:(NSInteger)index
{
  id<NitroModalChildHost> host = [self nitroModal_childHost];
  if (host == nil) {
    [super unmountChildComponentView:childComponentView index:index];
    return;
  }
  [host nitroModalUnmountChild:childComponentView];
}

@end
