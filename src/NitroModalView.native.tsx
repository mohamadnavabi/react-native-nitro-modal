import { getHostComponent } from 'react-native-nitro-modules';
// Resolved through the package's own `exports` so the path works from both
// `src/` and the compiled `lib/module/` output (a relative `../nitrogen` breaks there).
const NitroModalConfig = require('react-native-nitro-modal/nitrogen/generated/shared/json/NitroModalConfig.json');
import type { NitroModalMethods, NitroModalProps } from './NitroModal.nitro';

// Host components need their function props wrapped. Exported from here so
// web bundles never import `react-native-nitro-modules`.
export { callback } from 'react-native-nitro-modules';

export const NitroModalView = getHostComponent<
  NitroModalProps,
  NitroModalMethods
>('NitroModal', () => NitroModalConfig);
