import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules';

export interface NitroModalProps extends HybridViewProps {
  color: string;
}
export interface NitroModalMethods extends HybridViewMethods {}

export type NitroModal = HybridView<
  NitroModalProps,
  NitroModalMethods
>;
