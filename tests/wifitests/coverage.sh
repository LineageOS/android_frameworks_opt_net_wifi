#!/bin/sh

# A shell script to generate a coverage report for opt/net/wifi

if [[ ! ($# == 1) ]]; then
  echo "$0: usage: coverage.sh OUTPUT_DIR"
  exit 1
fi

if [ -z $ANDROID_BUILD_TOP ]; then
  echo "You need to source and lunch before you can use this script"
  exit 1
fi

# Make the output directory and get its full name
OUTPUT_DIR="$1"
mkdir -p $OUTPUT_DIR || exit 1
OUTPUT_DIR="`(cd $OUTPUT_DIR && pwd)`"
BUILD_OUT_DIR=$OUTPUT_DIR/out

cd "$(dirname $0)" #cd to directory containing this script

REPORTER_JAR=$ANDROID_HOST_OUT/framework/jacoco-cli.jar
if [ -f $REPORTER_JAR ]; then
  echo "jacoco-cli.jar found, skipping uninstrumented build"
else
  echo "Building jacoco cli and adb"
  $ANDROID_BUILD_TOP/build/soong/soong_ui.bash --make-mode \
      MODULES-IN-system-core MODULES-IN-external-jacoco || exit 1
fi

OUTPUT_DIR=$1

echo "Running tests and generating coverage report"
echo "Output dir: $OUTPUT_DIR"

REMOTE_COVERAGE_OUTPUT_FILE=/data/data/com.android.server.wifi.test/files/coverage.ec
COVERAGE_OUTPUT_FILE=$OUTPUT_DIR/wifi_coverage.ec

set -e # fail early
set -x # print commands

bash <<END_OF_BUILD_SCRIPT || { exit 1; }
  cd $ANDROID_BUILD_TOP
  source build/make/envsetup.sh
  tapas FrameworksWifiTests
  export OUT_DIR=$BUILD_OUT_DIR
  export TARGET_PRODUCT=$TARGET_PRODUCT
  export EMMA_INSTRUMENT=true
  export EMMA_INSTRUMENT_FRAMEWORK=false
  export EMMA_INSTRUMENT_STATIC=true
  export ANDROID_COMPILE_WITH_JACK=false
  export SKIP_BOOT_JARS_CHECK=true
  m
END_OF_BUILD_SCRIPT

APK_NAME="$(ls -t $(find $BUILD_OUT_DIR -name FrameworksWifiTests.apk) | head -n 1)"

adb root
adb wait-for-device

adb shell rm -f $REMOTE_COVERAGE_OUTPUT_FILE

adb install -r -g "$APK_NAME"

adb shell am instrument -e coverage true --no-hidden-api-checks -w 'com.android.server.wifi.test/com.android.server.wifi.CustomTestRunner'


adb pull $REMOTE_COVERAGE_OUTPUT_FILE $COVERAGE_OUTPUT_FILE

java -jar $REPORTER_JAR \
  report \
  --classfiles $BUILD_OUT_DIR/soong/.intermediates/frameworks/opt/net/wifi/service/wifi-service/android_common/javac/classes/ \
  --html $OUTPUT_DIR \
  --sourcefiles $ANDROID_BUILD_TOP/frameworks/opt/net/wifi/tests/wifitests/src \
  --sourcefiles $ANDROID_BUILD_TOP/frameworks/opt/net/wifi/service/java \
  --name wifi-coverage \
  $COVERAGE_OUTPUT_FILE

echo Created report at $OUTPUT_DIR/index.html

