#!/bin/bash
SRC=/root/src/fcitx5-android-master/app/src/main/java/org/fcitx/fcitx5/android
DST=/root/jm3/app/src/main/java/com/jimeng/ime/kb

copy() {
  src=$1; dst=$2
  sed -e 's/org\.fcitx\.fcitx5\.android\.input\.keyboard/com.jimeng.ime.kb.keyboard/g' \
      -e 's/org\.fcitx\.fcitx5\.android\.input\.popup/com.jimeng.ime.kb.popup/g' \
      -e 's/org\.fcitx\.fcitx5\.android\.core/com.jimeng.ime.kb.core/g' \
      -e 's/org\.fcitx\.fcitx5\.android\.data\.theme/com.jimeng.ime.kb.theme/g' \
      -e 's/org\.fcitx\.fcitx5\.android\.data\.prefs/com.jimeng.ime.kb.prefs/g' \
      -e 's/org\.fcitx\.fcitx5\.android\.input\.picker\.PickerWindow/com.jimeng.ime.kb.picker.PickerWindow/g' \
      -e 's/org\.fcitx\.fcitx5\.android\.input\.AutoScaleTextView/com.jimeng.ime.kb.AutoScaleTextView/g' \
      -e 's/org\.fcitx\.fcitx5\.android\.data\.InputFeedbacks/com.jimeng.ime.kb.InputFeedbacks/g' \
      -e 's/org\.fcitx\.fcitx5\.android\.utils\./com.jimeng.ime.kb.utils./g' \
      -e 's/org\.fcitx\.fcitx5\.android\.R/com.jimeng.ime.R/g' \
      "$src" > "$dst"
  echo "  $(basename $dst)  $(wc -l < $dst)行"
}

cd $SRC/input/keyboard
echo '[keyboard]'
for f in BaseKeyboard CustomGestureView KeyAction KeyActionListener KeyDef KeyDefPreset KeyDrawable KeyView NumberKeyboard TextKeyboard SwipeSymbolDirection SpaceLongPressBehavior LangSwitchBehavior KeyboardHeightPercentBase; do
  copy $f.kt $DST/keyboard/$f.kt
done

cd $SRC/input/popup
echo '[popup]'
for f in PopupAction PopupActionListener PopupContainerUi PopupEntryUi PopupKeyboardUi PopupMenuUi PopupPreset; do
  copy $f.kt $DST/popup/$f.kt
done

cd $SRC/core
echo '[core]'
for f in KeyState KeySym; do copy $f.kt $DST/core/$f.kt; done

cd $SRC/input
echo '[misc]'
copy AutoScaleTextView.kt $DST/AutoScaleTextView.kt

cd $SRC/data
echo '[data]'
copy InputFeedbacks.kt $DST/InputFeedbacks.kt

echo '=== 统计 ==='
find $DST -name '*.kt' | wc -l
find $DST -name '*.kt' -exec wc -l {} + | tail -1