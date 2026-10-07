import type {
  callback as nitroCallback,
  ReactNativeView,
} from 'react-native-nitro-modules';
import type { NitroModalMethods, NitroModalProps } from './NitroModal.nitro';

let warned = false;

/**
 * Platforms without an implementation render nothing. iOS and Android use
 * `NitroModalView.native.tsx`, web uses `NitroModalView.web.tsx`.
 */
function UnsupportedNitroModalView() {
  if (!warned) {
    warned = true;
    console.warn(
      'react-native-nitro-modal is only implemented on iOS, Android and web.'
    );
  }
  return null;
}

export const callback = ((func: unknown) => func) as typeof nitroCallback;

export const NitroModalView =
  UnsupportedNitroModalView as unknown as ReactNativeView<
    NitroModalProps,
    NitroModalMethods
  >;
