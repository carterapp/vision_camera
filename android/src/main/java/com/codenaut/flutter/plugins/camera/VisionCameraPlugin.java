package com.codenaut.flutter.plugins.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Surface;


import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class VisionCameraPlugin implements MethodCallHandler {

  private static final int cameraRequestId = 513469796;
  private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
  private static CameraManager cameraManager;

  @SuppressLint("UseSparseArrays")
  private static Map<Long, Cam> cams = new HashMap<>();
  private static String LOG_TAG = "vision_camera";
  static {
    ORIENTATIONS.append(Surface.ROTATION_0, 0);
    ORIENTATIONS.append(Surface.ROTATION_90, 90);
    ORIENTATIONS.append(Surface.ROTATION_180, 180);
    ORIENTATIONS.append(Surface.ROTATION_270, 270);
  }

  private final FlutterView view;
  private Activity activity;
  private Registrar registrar;
  // The code to run after requesting the permission.
  private Runnable cameraPermissionContinuation;

  private VisionCameraPlugin(Registrar registrar, FlutterView view, Activity activity) {
    this.registrar = registrar;

    registrar.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());
    this.view = view;
    this.activity = activity;

    activity
        .getApplication()
        .registerActivityLifecycleCallbacks(
            new Application.ActivityLifecycleCallbacks() {
              @Override
              public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

              @Override
              public void onActivityStarted(Activity activity) {}

              @Override
              public void onActivityResumed(Activity activity) {
                if (activity == VisionCameraPlugin.this.activity) {
                  for (Cam cam : cams.values()) {
                    cam.resume();
                  }
                }
              }

              @Override
              public void onActivityPaused(Activity activity) {
                if (activity == VisionCameraPlugin.this.activity) {
                  for (Cam cam : cams.values()) {
                    cam.pause();
                  }
                }
              }

              @Override
              public void onActivityStopped(Activity activity) {
                if (activity == VisionCameraPlugin.this.activity) {
                  disposeAllCams();
                }
              }

              @Override
              public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

              @Override
              public void onActivityDestroyed(Activity activity) {}
            });
  }

  public static void registerWith(Registrar registrar) {
    final MethodChannel channel =
        new MethodChannel(registrar.messenger(), "plugins.flutter.codenaut.com/vision_camera");
    cameraManager = (CameraManager) registrar.activity().getSystemService(Context.CAMERA_SERVICE);

    channel.setMethodCallHandler(
        new VisionCameraPlugin(registrar, registrar.view(), registrar.activity()));
  }

  private Size getBestPreviewSize(
      StreamConfigurationMap streamConfigurationMap, Size minPreviewSize, Size captureSize) {
    Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
    List<Size> goodEnough = new ArrayList<>();
    for (Size s : sizes) {
      if (s.getHeight() * captureSize.getWidth() == s.getWidth() * captureSize.getHeight()
          && minPreviewSize.getWidth() < s.getWidth()
          && minPreviewSize.getHeight() < s.getHeight()) {
        goodEnough.add(s);
      }
    }
    if (goodEnough.isEmpty()) {
      return sizes[0];
    }
    return Collections.min(goodEnough, new CompareSizesByArea());
  }

  private Size getBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
    // For still image captures, we use the largest available size.
    return Collections.max(
        Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)),
        new CompareSizesByArea());
  }

  private long textureIdOfCall(MethodCall call) {
    return ((Number) call.argument("textureId")).longValue();
  }

  private Cam getCamOfCall(MethodCall call) {
    return cams.get(textureIdOfCall(call));
  }

  private void disposeAllCams() {
    for (Cam cam : cams.values()) {
      cam.dispose();
    }
    cams.clear();
  }

  @Override
  public void onMethodCall(MethodCall call, final Result result) {
    switch (call.method) {
      case "init":
        disposeAllCams();
        result.success(null);
        break;
      case "list":
        try {
          String[] cameraNames = cameraManager.getCameraIdList();
          List<Map<String, Object>> cameras = new ArrayList<>();
          for (String cameraName : cameraNames) {
            HashMap<String, Object> details = new HashMap<>();
            CameraCharacteristics characteristics =
                cameraManager.getCameraCharacteristics(cameraName);
            details.put("name", cameraName);

            @SuppressWarnings("ConstantConditions")
            int lens_facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            switch (lens_facing) {
              case CameraMetadata.LENS_FACING_FRONT:
                details.put("lensFacing", "front");
                break;
              case CameraMetadata.LENS_FACING_BACK:
                details.put("lensFacing", "back");
                break;
              case CameraMetadata.LENS_FACING_EXTERNAL:
                details.put("lensFacing", "external");
                break;
            }
            cameras.add(details);
          }
          result.success(cameras);
        } catch (CameraAccessException e) {
          result.error("cameraAccess", e.getMessage(), null);
        }
        break;
      case "create":
        {
          FlutterView.SurfaceTextureEntry surfaceTexture = view.createSurfaceTexture();
          final EventChannel eventChannel =
              new EventChannel(
                  registrar.messenger(),
                  "codenaut.com/visionCameraPlugin/cameraEvents" + surfaceTexture.id());
          String cameraName = call.argument("cameraName");
          String resolutionPreset = call.argument("resolutionPreset");
          Map<String, Object> options =call.argument("options");
          Cam cam = new Cam(eventChannel, surfaceTexture, cameraName, resolutionPreset, result, options);
          cams.put(cam.getTextureId(), cam);
          break;
        }
      case "start":
        {
          Cam cam = getCamOfCall(call);
          cam.start();
          result.success(null);
          break;
        }
      case "capture": {
        Cam cam = getCamOfCall(call);
        cam.capture((String) call.argument("path"), result);
        break;
      }
      case "flash": {
        Cam cam = getCamOfCall(call);
        cam.turnOnFlash((Boolean)call.argument("turnOn"), result);
        break;
      }
      case "stop":
        {
          Cam cam = getCamOfCall(call);
          cam.stop();
          result.success(null);
          break;
        }
      case "dispose":
        {
          Cam cam = getCamOfCall(call);
          if (cam != null) {
            cam.dispose();
          }
          cams.remove(textureIdOfCall(call));
          result.success(null);
          break;
        }
      default:
        result.notImplemented();
        break;
    }
  }

  private static class CompareSizesByArea implements Comparator<Size> {
    @Override
    public int compare(Size lhs, Size rhs) {
      // We cast here to ensure the multiplications won't overflow
      return Long.signum(
          (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  private class CameraRequestPermissionsListener
      implements PluginRegistry.RequestPermissionsResultListener {
    @Override
    public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
      if (id == cameraRequestId) {
        cameraPermissionContinuation.run();
        return true;
      }
      return false;
    }
  }



  private class Cam {
    private final FlutterView.SurfaceTextureEntry textureEntry;
    private CameraDevice cameraDevice;
    private Surface previewSurface;
    private CameraCaptureSession cameraCaptureSession;
    private EventChannel.EventSink eventSink;
    private ImageReader imageReader;
    private boolean started = false;
    private int sensorOrientation;
    private boolean facingFront;
    private String cameraName;
    private boolean initialized = false;
    private Size captureSize;
    private Size previewSize;
    private ImageReader barcodeImageReader;
    private BarcodeDetector barcodeDetector;
    private long barcodeReadInterval;
    private boolean turnOnFlash;

    <T> T defaultValue(Map<String, Object> map, String key, T defVal){
      if (map == null) {
        return defVal;
      }
      final Object v = map.get(key);
      if (v != null && defVal.getClass().isInstance(v)) {
          return (T)v;
      } else {
        return defVal;
      }
    }
    Cam(
        final EventChannel eventChannel,
        final FlutterView.SurfaceTextureEntry textureEntry,
        final String cameraName,
        final String resolutionPreset,
        final Result result,
        final Map<String, Object> options) {

      this.textureEntry = textureEntry;
      this.cameraName = cameraName;

      try {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);

        Size minPreviewSize;
        switch (resolutionPreset) {
          case "high":
            minPreviewSize = new Size(1024, 768);
            break;
          case "medium":
            minPreviewSize = new Size(640, 480);
            break;
          case "low":
            minPreviewSize = new Size(320, 240);
            break;
          default:
            throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
        }
        StreamConfigurationMap streamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        captureSize = getBestCaptureSize(streamConfigurationMap);
        previewSize = getBestPreviewSize(streamConfigurationMap, minPreviewSize, captureSize);
        imageReader =
            ImageReader.newInstance(
                captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);
        barcodeImageReader =
                ImageReader.newInstance(
                        previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
        if (options != null) {
          barcodeReadInterval = defaultValue(options, "barcodeReadInterval", 500L);
          if (barcodeDetector != null) {
            Log.i(LOG_TAG, "releasing old barcodeDetector");
            barcodeDetector.release();
          }
          final int barcodeTypes = defaultValue(options, "barcodeTypes", 0);
          barcodeDetector =
                  new BarcodeDetector.Builder(activity.getApplicationContext())
                          .setBarcodeFormats(barcodeTypes)
                          .build();
          if (!barcodeDetector.isOperational()) {
            result.error("DetectorError", "Failed to configure detector", null);
            barcodeDetector.release();
            return;
          }
          barcodeDetector.setProcessor(new BarcodeProcessor());
          Log.d(LOG_TAG, "Barcode scanner set up");
        }

        SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(surfaceTexture);
        eventChannel.setStreamHandler(
            new EventChannel.StreamHandler() {
              @Override
              public void onListen(Object arguments, EventChannel.EventSink eventSink) {
                Cam.this.eventSink = eventSink;
              }

              @Override
              public void onCancel(Object arguments) {
                Cam.this.eventSink = null;
              }
            });
        if (cameraPermissionContinuation != null) {
          result.error("cameraPermission", "Camera permission request ongoing", null);
        }
        cameraPermissionContinuation =
            new Runnable() {
              @Override
              public void run() {
                cameraPermissionContinuation = null;
                openCamera(result);
              }
            };
        if (hasCameraPermission()) {
          cameraPermissionContinuation.run();
        } else {
          activity.requestPermissions(new String[] {Manifest.permission.CAMERA}, cameraRequestId);
        }
      } catch (CameraAccessException e) {
        result.error("cameraAccess", e.getMessage(), null);
      }
    }

    private boolean hasCameraPermission() {
      return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
          || activity.checkSelfPermission(Manifest.permission.CAMERA)
              == PackageManager.PERMISSION_GRANTED;
    }

    private void openCamera(final Result result) {
      if (!hasCameraPermission()) {
        result.error("cameraPermission", "Camera permission not granted", null);
      } else {
        try {
          CameraCharacteristics characteristics =
              cameraManager.getCameraCharacteristics(cameraName);
          //noinspection ConstantConditions
          sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
          //noinspection ConstantConditions
          facingFront =
              characteristics.get(CameraCharacteristics.LENS_FACING)
                  == CameraMetadata.LENS_FACING_FRONT;
          cameraManager.openCamera(
              cameraName,
              new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                  Cam.this.cameraDevice = cameraDevice;
                  List<Surface> surfaceList = new ArrayList<>();
                  surfaceList.add(previewSurface);
                  surfaceList.add(imageReader.getSurface());
                  if (barcodeImageReader != null) {
                    surfaceList.add(barcodeImageReader.getSurface());
                  }

                  try {
                    cameraDevice.createCaptureSession(
                        surfaceList,
                        new CameraCaptureSession.StateCallback() {
                          @Override
                          public void onConfigured(
                              @NonNull CameraCaptureSession cameraCaptureSession) {
                            Cam.this.cameraCaptureSession = cameraCaptureSession;
                            initialized = true;
                            Map<String, Object> reply = new HashMap<>();
                            reply.put("textureId", textureEntry.id());
                            reply.put("previewWidth", previewSize.getWidth());
                            reply.put("previewHeight", previewSize.getHeight());
                            result.success(reply);
                          }

                          @Override
                          public void onConfigureFailed(
                              @NonNull CameraCaptureSession cameraCaptureSession) {
                            result.error(
                                "configureFailed", "Failed to configure camera session", null);
                          }
                        },
                        null);
                  } catch (CameraAccessException e) {
                    result.error("cameraAccess", e.getMessage(), null);
                  }

                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                  if (eventSink != null) {
                    Map<String, String> event = new HashMap<>();
                    event.put("eventType", "error");
                    event.put("errorDescription", "The camera was disconnected");
                    eventSink.success(event);
                  }
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
                  if (eventSink != null) {
                    String errorDescription;
                    switch (errorCode) {
                      case ERROR_CAMERA_IN_USE:
                        errorDescription = "The camera device is in use already.";
                        break;
                      case ERROR_MAX_CAMERAS_IN_USE:
                        errorDescription = "Max cameras in use";
                        break;
                      case ERROR_CAMERA_DISABLED:
                        errorDescription =
                            "The camera device could not be opened due to a device policy.";
                        break;
                      case ERROR_CAMERA_DEVICE:
                        errorDescription = "The camera device has encountered a fatal error";
                        break;
                      case ERROR_CAMERA_SERVICE:
                        errorDescription = "The camera service has encountered a fatal error.";
                        break;
                      default:
                        errorDescription = "Unknown camera error";
                    }
                    Map<String, String> event = new HashMap<>();
                    event.put("eventType", "error");
                    event.put("errorDescription", errorDescription);
                    eventSink.success(event);
                  }
                }
              },
              null);
        } catch (CameraAccessException e) {
          result.error("cameraAccess", e.getMessage(), null);
        }
      }
    }

    class BarcodeProcessor implements Detector.Processor<Barcode> {

      @Override
      public void release() {
      }

      @Override
      public void receiveDetections(Detector.Detections<Barcode> detections) {
        final List<Map<String, Object>> barcodes = processBarcodes(detections.getDetectedItems());
        if (!barcodes.isEmpty()) {
          final Map<String, Object> results = new HashMap<>();
          results.put("eventType", "barcodes");
          results.put("barcodes", barcodes);
          eventSink.success(results);
        }
      }
    }

    void start() {
      if (!initialized) {
        return;
      }

      if (barcodeImageReader != null) {
        final AtomicLong lastRead = new AtomicLong(0);

        barcodeImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
          @Override
          public void onImageAvailable(ImageReader imageReader) {
            long ts = lastRead.get();
            long now = System.currentTimeMillis();
            if (now - ts < barcodeReadInterval) {
              final Image latestImage = imageReader.acquireLatestImage();
              if (latestImage != null) {
                latestImage.close();
              }
            } else {
              lastRead.set(now);
              detectBarcodes(imageReader);
            }
          }
        }, null);
      }
      try {
        final CaptureRequest.Builder previewRequestBuilder =
            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
              previewRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                      turnOnFlash?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);
        Log.d(LOG_TAG, "Flash mode: " + turnOnFlash);
        previewRequestBuilder.addTarget(previewSurface);

        if (barcodeImageReader != null) {
          previewRequestBuilder.addTarget(barcodeImageReader.getSurface());
        }
        CaptureRequest previewRequest = previewRequestBuilder.build();
        cameraCaptureSession.setRepeatingRequest(
                previewRequest,
                new CameraCaptureSession.CaptureCallback() {
                  @Override
                  public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
                    super.onCaptureBufferLost(session, request, target, frameNumber);
                    if (eventSink != null) {
                      eventSink.success("lost buffer");
                    }
                  }
                },
            null);
      } catch (CameraAccessException exception) {
        Map<String, String> event = new HashMap<>();
        event.put("eventType", "error");
        event.put("errorDescription", "Unable to start camera");
        eventSink.success(event);
      }
      started = true;
    }

    void pause() {
      if (!initialized) {
        return;
      }
      if (started && cameraCaptureSession != null) {
        try {
          cameraCaptureSession.stopRepeating();
        } catch (CameraAccessException e) {
          Map<String, String> event = new HashMap<>();
          event.put("eventType", "error");
          event.put("errorDescription", "Unable to pause camera");
          eventSink.success(event);
        }
      }
      if (cameraCaptureSession != null) {
        cameraCaptureSession.close();
        cameraCaptureSession = null;
      }
      if (cameraDevice != null) {
        cameraDevice.close();
        cameraDevice = null;
      }
    }

    void resume() {
      if (!initialized) {
        return;
      }
      openCamera(
          new Result() {
            @Override
            public void success(Object o) {
              if (started) {
                start();
              }
            }

            @Override
            public void error(String s, String s1, Object o) {}

            @Override
            public void notImplemented() {}
          });
    }

    private void writeToFile(ByteBuffer buffer, File file) throws IOException {
      try (FileOutputStream outputStream = new FileOutputStream(file)) {
        while (0 < buffer.remaining()) {
          outputStream.getChannel().write(buffer);
        }
      }
    }



    private List<Map<String, Object>> processBarcodes(SparseArray<Barcode> barcodes) {
      final List<Map<String, Object>> results = new LinkedList<>();
      for (int i = 0; i < barcodes.size(); i++) {
        final Barcode b = barcodes.valueAt(i);
        final Map<String, Object> r = new HashMap<>();
        r.put("rawValue", b.rawValue);
        r.put("format", b.format);
        final Map<String, Object> bbox = new HashMap<>();
        final Rect bb = b.getBoundingBox();
        bbox.put("top", bb.top);
        bbox.put("bottom", bb.bottom);
        bbox.put("left", bb.left);
        bbox.put("right", bb.right);
        r.put("boundingBox", bbox);
        results.add(r);
      }
      if (!results.isEmpty()) {
        Log.d(LOG_TAG, "Returning results: " + results);
      }
      return results;
    }
    private byte[] convertYUV_420_888ToNV21(Image imgYUV420) {
      // Convert YUV_420_888 data to NV21 (YUV_420_SP) so barcode reader can use it
      final ByteBuffer buffer0 = imgYUV420.getPlanes()[0].getBuffer();
      final ByteBuffer buffer2 = imgYUV420.getPlanes()[2].getBuffer();
      final int buf0Size = buffer0.remaining();
      final int buf2Size = buffer2.remaining();
      final byte[] data = new byte[buf0Size + buf2Size];
      buffer0.get(data, 0, buf0Size);
      buffer2.get(data, buf0Size, buf2Size);
      return data;
    }
    private void detectBarcodes(ImageReader imageReader) {
      boolean success = false;
      try (Image image = imageReader.acquireLatestImage()) {
        byte[] imgData = convertYUV_420_888ToNV21(image);
        final Frame frame = new Frame.Builder()
                .setImageData(ByteBuffer.wrap(imgData), imageReader.getWidth(), imageReader.getHeight(),
                        ImageFormat.NV21)
                .build();
        barcodeDetector.receiveFrame(frame);
        success = true;
      } catch (Exception e) {
        if (!success) {
          e.printStackTrace();
          throw e;
        }
      }
    }

    void turnOnFlash(Boolean turnOn, final Result result) {
      this.turnOnFlash = turnOn==null?!turnOnFlash:turnOn;
      this.stop();
      this.start();
      result.success(null);
    }

    void capture(String path, final Result result) {
      final File file = new File(path);
      imageReader.setOnImageAvailableListener(
          new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
              boolean success = false;
              try (Image image = reader.acquireLatestImage()) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                writeToFile(buffer, file);
                success = true;
                result.success(null);
              } catch (IOException e) {
                // Theoretically image.close() could throw, so only report the error
                // if we have not successfully written the file.
                if (!success) {
                  result.error("IOError", "Failed saving image", null);
                }
              }
            }
          },
          null);

      try {

        final CaptureRequest.Builder captureBuilder =
            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(imageReader.getSurface());
        int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int displayOrientation = ORIENTATIONS.get(displayRotation);
        if (facingFront) displayOrientation = -displayOrientation;

        captureBuilder.set(
            CaptureRequest.JPEG_ORIENTATION, (-displayOrientation + sensorOrientation) % 360);

        cameraCaptureSession.capture(
            captureBuilder.build(),
            new CameraCaptureSession.CaptureCallback() {
              @Override
              public void onCaptureFailed(
                  @NonNull CameraCaptureSession session,
                  @NonNull CaptureRequest request,
                  @NonNull CaptureFailure failure) {
                String reason;
                switch (failure.getReason()) {
                  case CaptureFailure.REASON_ERROR:
                    reason = "An error happened in the framework";
                    break;
                  case CaptureFailure.REASON_FLUSHED:
                    reason = "The capture has failed due to an abortCaptures() call";
                    break;
                  default:
                    reason = "Unknown reason";
                }
                result.error("captureFailure", reason, null);
              }
            },
            null);
      } catch (CameraAccessException e) {
        result.error("cameraAccess", e.getMessage(), null);
      }
    }

    void stop() {
      try {
        cameraCaptureSession.stopRepeating();
        started = false;
      } catch (CameraAccessException e) {
        Map<String, String> event = new HashMap<>();
        event.put("eventType", "error");
        event.put("errorDescription", "Unable to pause camera");
        eventSink.success(event);
      }
    }

    long getTextureId() {
      return textureEntry.id();
    }

    void dispose() {
      if (cameraCaptureSession != null) {
        cameraCaptureSession.close();
        cameraCaptureSession = null;
      }
      if (cameraDevice != null) {
        cameraDevice.close();
        cameraDevice = null;
      }
      textureEntry.release();
    }
  }
}
