#!/usr/bin/env bash
set -uo pipefail
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.audioflow.SettingsUiTest,app.audioflow.DeviceTest#updateMetadataRetainsNotesAndRequiresOfficialStableAsset,app.audioflow.DeviceTest#installedReleaseContainsOfflineNotesForItsOwnVersion,app.audioflow.DeviceTest#audioPipelineAcceptsEmptyInputOnAndroid,app.audioflow.DeviceTest#themeSelectionPersistsAcrossStoreInstances --stacktrace
result=$?
adb pull /sdcard/Android/data/app.audioflow.debug/files/screenshots app/build/reports/screenshots || true
exit "$result"
