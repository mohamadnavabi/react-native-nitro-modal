import type { ReactNativeView } from 'react-native-nitro-modules';
import type { NitroModalMethods, NitroModalProps } from './NitroModal.nitro';

let warned = false;

/**
 * Platforms without a native implementation (e.g. web) render nothing.
 * iOS and Android use `NitroModalView.native.tsx`.
 */
function UnsupportedNitroModalView() {
  if (!warned) {
    warned = true;
    console.warn(
      'react-native-nitro-modal is only implemented on iOS and Android.'
    );
  }
  return null;
}

export const NitroModalView =
  UnsupportedNitroModalView as unknown as ReactNativeView<
    NitroModalProps,
    NitroModalMethods
  >;
