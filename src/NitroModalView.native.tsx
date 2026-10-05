import { getHostComponent } from 'react-native-nitro-modules';
const NitroModalConfig = require('../nitrogen/generated/shared/json/NitroModalConfig.json');
import type {
  NitroModalMethods,
  NitroModalProps,
} from './NitroModal.nitro';

export const NitroModalView = getHostComponent<
  NitroModalProps,
  NitroModalMethods
>('NitroModal', () => NitroModalConfig);
