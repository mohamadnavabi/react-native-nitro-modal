import { useRef, useState } from 'react';
import {
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  NitroModal,
  type DismissReason,
  type NitroModalRef,
} from 'react-native-nitro-modal';

const ROWS = Array.from({ length: 40 }, (_, i) => `Row ${i + 1}`);

export default function App() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [listOpen, setListOpen] = useState(false);
  const [popupOpen, setPopupOpen] = useState(false);
  const [lastEvent, setLastEvent] = useState('—');
  const imperativeSheet = useRef<NitroModalRef>(null);

  const log = (event: string) => setLastEvent(event);

  return (
    <View style={styles.screen}>
      <Text style={styles.title}>NitroModal</Text>
      <Text style={styles.caption}>Last event: {lastEvent}</Text>

      <Button
        title="Bottom sheet (fit content)"
        onPress={() => setSheetOpen(true)}
      />
      <Button
        title="Bottom sheet (detents + list)"
        onPress={() => setListOpen(true)}
      />
      <Button title="Popup" onPress={() => setPopupOpen(true)} />
      <Button
        title="Imperative sheet (ref)"
        onPress={() => imperativeSheet.current?.present()}
      />

      {/* 1. Content-sized sheet with a text field. */}
      <NitroModal
        isOpen={sheetOpen}
        mode="bottomSheet"
        detents={['fitContent']}
        showGrabber
        keyboardBehavior="pan"
        onPresent={() => log('sheet presented')}
        onDismiss={(reason: DismissReason) => {
          setSheetOpen(false);
          log(`sheet dismissed (${reason})`);
        }}
      >
        <View style={styles.sheetContent}>
          <Text style={styles.heading}>Fit content</Text>
          <Text style={styles.body}>
            Swipe down, tap the backdrop or press the button to close. The sheet
            grows with its content and lifts above the keyboard.
          </Text>
          <TextInput placeholder="Type something…" style={styles.input} />
          <Button title="Close" onPress={() => setSheetOpen(false)} />
        </View>
      </NitroModal>

      {/* 2. Multi-detent sheet filled by a scrolling list. */}
      <NitroModal
        isOpen={listOpen}
        detents={['medium', 'large']}
        backdropOpacity={0.25}
        backdropBlur={12}
        showGrabber
        onDetentChange={(index) => log(`detent → ${index}`)}
        onDismiss={(reason) => {
          setListOpen(false);
          log(`list dismissed (${reason})`);
        }}
      >
        <FlatList
          data={ROWS}
          keyExtractor={(item) => item}
          nestedScrollEnabled
          contentContainerStyle={styles.list}
          renderItem={({ item }) => <Text style={styles.row}>{item}</Text>}
        />
      </NitroModal>

      {/* 3. Centered popup that only closes from its own buttons. */}
      <NitroModal
        isOpen={popupOpen}
        mode="popup"
        popupAnimation="scale"
        backdropOpacity={0.5}
        dismissOnBackdropPress={false}
        onBackdropPress={() => log('backdrop pressed (ignored)')}
        onBackButtonPress={() => log('back button')}
        onDismiss={(reason) => {
          setPopupOpen(false);
          log(`popup dismissed (${reason})`);
        }}
      >
        <View style={styles.popupContent}>
          <Text style={styles.heading}>Delete item?</Text>
          <Text style={styles.body}>This popup ignores backdrop taps.</Text>
          <View style={styles.actions}>
            <Button title="Cancel" onPress={() => setPopupOpen(false)} />
            <Button
              title="Delete"
              destructive
              onPress={() => {
                log('deleted');
                setPopupOpen(false);
              }}
            />
          </View>
        </View>
      </NitroModal>

      {/* 4. Uncontrolled: opened and closed through the ref. */}
      <NitroModal
        ref={imperativeSheet}
        detents={['small', 'medium']}
        onDismiss={(reason) => log(`imperative dismissed (${reason})`)}
      >
        <View style={styles.sheetContent}>
          <Text style={styles.heading}>Driven by a ref</Text>
          <Button
            title="Expand"
            onPress={() => imperativeSheet.current?.snapToDetent(1)}
          />
          <Button
            title="Close"
            onPress={() => imperativeSheet.current?.dismiss()}
          />
        </View>
      </NitroModal>
    </View>
  );
}

function Button({
  title,
  onPress,
  destructive,
}: {
  title: string;
  onPress: () => void;
  destructive?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        destructive && styles.destructive,
        pressed && styles.pressed,
      ]}
    >
      <Text style={styles.buttonText}>{title}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    justifyContent: 'center',
    padding: 24,
    gap: 12,
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
  },
  caption: {
    color: '#666',
    marginBottom: 12,
  },
  sheetContent: {
    padding: 24,
    paddingTop: 32,
    gap: 12,
  },
  popupContent: {
    width: 300,
    padding: 24,
    gap: 12,
  },
  heading: {
    fontSize: 20,
    fontWeight: '600',
  },
  body: {
    fontSize: 15,
    color: '#555',
  },
  input: {
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: '#999',
    borderRadius: 8,
    padding: 12,
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
  },
  list: {
    paddingTop: 24,
    paddingBottom: 48,
  },
  row: {
    paddingHorizontal: 24,
    paddingVertical: 14,
    fontSize: 16,
  },
  button: {
    backgroundColor: '#2f6fed',
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 10,
    alignItems: 'center',
  },
  destructive: {
    backgroundColor: '#d93a3a',
  },
  pressed: {
    opacity: 0.7,
  },
  buttonText: {
    color: 'white',
    fontWeight: '600',
  },
});
