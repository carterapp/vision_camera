# Vision Camera Plugin


A barcode reader plugin for Flutter. Very much based on the camera plugin.

## Features:

* Display live camera preview in a widget.
* Snapshots can be captured and saved to a file.
* Detect barcodes

## Installation

First, add `vision_camera` as a [dependency in your pubspec.yaml file](https://flutter.io/using-packages/).

### iOS

Add a row to the `ios/Runner/Info.plist` of your app with the key `Privacy - Camera Usage Description` and a usage description.

Or in text format add the key:

```xml
<key>NSCameraUsageDescription</key>
<string>Can I use the camera please?</string>
```

### Android

Change the minimum Android sdk version to 21 (or higher) in your `android/app/build.gradle` file.

```
minSdkVersion 21
```


*Note*: This plugin is still under development, and some APIs might not be available yet.
[Feedback welcome](https://github.com/Codenaut/vision_camera/issues) and
[Pull Requests](https://github.com/Codenaut/vision_camera/pulls) are most welcome!
