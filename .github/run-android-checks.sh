#!/usr/bin/env bash
set -uo pipefail
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.audioflow.SettingsUiTest,app.audioflow.DeviceTest#updateMetadataRetainsNotesAndRequiresOfficialStableAsset,app.audioflow.DeviceTest#installedReleaseContainsOfflineNotesForItsOwnVersion,app.audioflow.DeviceTest#audioPipelineAcceptsEmptyInputOnAndroid,app.audioflow.DeviceTest#themeSelectionPersistsAcrossStoreInstances --stacktrace
result=$?
mkdir -p app/build/reports/screenshots
for image in settings ocean-dark ocean-light updates-0 updates-1; do
  adb pull "/data/local/tmp/AudioFlow-$image.png" app/build/reports/screenshots/ || true
done
exit "$result"
