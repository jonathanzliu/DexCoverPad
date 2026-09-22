#!/bin/bash

echo "=== Updating system packages ==="
sudo apt update
sudo apt install -y unzip wget openjdk-17-jdk

echo "=== Creating Android SDK directory ==="
mkdir -p $HOME/Android/Sdk

echo "=== Downloading Android command-line tools ==="
cd $HOME/Android/Sdk
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip

echo "=== Unzipping tools ==="
unzip cmdline-tools.zip -d cmdline-tools-temp
mkdir -p cmdline-tools
mv cmdline-tools-temp/* cmdline-tools/
rm -rf cmdline-tools-temp cmdline-tools.zip

echo "=== Adding Android SDK to PATH ==="
{
    echo 'export ANDROID_HOME=$HOME/Android/Sdk'
    echo 'export PATH=$ANDROID_HOME/cmdline-tools/bin:$PATH'
    echo 'export PATH=$ANDROID_HOME/platform-tools:$PATH'
    echo 'export PATH=$ANDROID_HOME/emulator:$PATH'
} >> ~/.bashrc

source ~/.bashrc

echo "=== Installing Android SDK components ==="
yes | sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-34" "build-tools;34.0.0"
yes | sdkmanager --licenses --sdk_root=$ANDROID_HOME

echo "=== Creating local.properties for DexCoverPad ==="
cd ~/DexCoverPad
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

echo "=== Setup complete! You can now build DexCoverPad with: ./gradlew assembleRelease ==="
