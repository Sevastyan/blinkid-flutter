package com.microblink.flutter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.*;

import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

import com.microblink.MicroblinkSDK;
import com.microblink.directApi.DirectApiErrorListener;
import com.microblink.directApi.RecognizerRunner;
import com.microblink.entities.recognizers.RecognizerBundle;
import com.microblink.entities.recognizers.blinkid.generic.BlinkIdCombinedRecognizer;
import com.microblink.hardware.orientation.Orientation;
import com.microblink.intent.IntentDataTransferMode;
import com.microblink.metadata.MetadataCallbacks;
import com.microblink.metadata.glare.GlareCallback;
import com.microblink.metadata.recognition.FirstSideRecognitionCallback;
import com.microblink.recognition.RecognitionSuccessType;
import com.microblink.uisettings.UISettings;
import com.microblink.uisettings.ActivityRunner;

import com.microblink.flutter.recognizers.RecognizerSerializers;
import com.microblink.flutter.overlays.OverlaySettingsSerializers;
import com.microblink.view.recognition.ScanResultListener;

import org.json.JSONObject;
import org.json.JSONArray;


public class MicroblinkFlutterPlugin implements FlutterPlugin, MethodCallHandler, PluginRegistry.ActivityResultListener, ActivityAware, EventChannel.StreamHandler {

  private static final String CHANNEL = "blinkid_scanner";
  private static final String EVENT_CHANNEL_NAME = "blinkid_scanner_event_channel";

  private static final int SCAN_REQ_CODE = 1904;
  private static final String METHOD_SCAN = "scanWithCamera";
  private static final String METHOD_SET_LICENSE = "setLicense";
  private static final String METHOD_SCAN_PLANES = "scanPlanes";

  private static final String ARG_LICENSE = "license";
  private static final String ARG_LICENSE_KEY = "licenseKey";
  private static final String ARG_LICENSEE = "licensee";
  private static final String ARG_SHOW_LICENSE_WARNING = "showTimeLimitedLicenseKeyWarning";
  private static final String ARG_RECOGNIZER_COLLECTION = "recognizerCollection";
  private static final String ARG_OVERLAY_SETTINGS = "overlaySettings";

  private RecognizerBundle mRecognizerBundle;

  private MethodChannel channel;
  private EventChannel.EventSink eventSink;
  private Context context;
  private Activity activity;

  private Result pendingResult;

  private BlinkIdCombinedRecognizer recognizer;
  private RecognizerRunner recognizerRunner;
  private final MetadataCallbacks metadataCallbacks = new MetadataCallbacks();

  static {
      System.loadLibrary("native-lib");
      System.loadLibrary("yuv");
  }

  public native static int J420ToARGB(
          byte[] src_y,
          int src_stride_y,
          byte[] src_u,
          int src_stride_u,
          byte[] src_v,
          int src_stride_v,
          byte[] dst_argb,
          int dst_stride_argb,
          int width,
          int height
  );

  private void sendMetadataCallback(final String message) {
      new Handler(Looper.getMainLooper()).post(new Runnable() {
          @Override
          public void run() {
              eventSink.success(message);
          }
      });
  }

  // This static function is optional and equivalent to onAttachedToEngine. It supports the old
  // pre-Flutter-1.12 Android projects.
  public static void registerWith(Registrar registrar) {
    final MicroblinkFlutterPlugin plugin = new MicroblinkFlutterPlugin();
    plugin.setupPlugin(registrar.activity(), registrar.messenger());
    registrar.addActivityResultListener(plugin);
  }

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
      setupPlugin(
              binding.getApplicationContext(),
              binding.getBinaryMessenger()
      );
  }

  private void setupPlugin(Context context, BinaryMessenger messenger) {
    if (context != null) {
      this.context = context;
    }

    this.channel = new MethodChannel(messenger, CHANNEL);
    this.channel.setMethodCallHandler(this);
    EventChannel eventChannel = new EventChannel(messenger, EVENT_CHANNEL_NAME);
    eventChannel.setStreamHandler(this);
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
      if (call.method.equals(METHOD_SCAN)) {
          setLicense((Map) call.argument(ARG_LICENSE));
          pendingResult = result;

          JSONObject jsonOverlaySettings = new JSONObject((Map) call.argument(ARG_OVERLAY_SETTINGS));
          JSONObject jsonRecognizerCollection = new JSONObject((Map) call.argument(ARG_RECOGNIZER_COLLECTION));

          mRecognizerBundle = RecognizerSerializers.INSTANCE.deserializeRecognizerCollection(jsonRecognizerCollection);
          UISettings uiSettings = OverlaySettingsSerializers.INSTANCE.getOverlaySettings(context, jsonOverlaySettings, mRecognizerBundle);

          startScanning(SCAN_REQ_CODE, uiSettings);

      } else if (call.method.equals(METHOD_SET_LICENSE)) {
          setLicenseKey((String) call.arguments());
          result.success(null);
      } else if (call.method.equals(METHOD_SCAN_PLANES)) {
          onScanPlanes(call, result);
      } else {
      result.notImplemented();
    }
  }

  private void onScanPlanes(@NonNull MethodCall call, @NonNull Result result) {
      byte[] yBytes = (byte[]) call.argument("yBytes");
      int yStride = (int) call.argument("yStride");
      byte[] uBytes = (byte[]) call.argument("uBytes");
      int uStride = (int) call.argument("uStride");
      byte[] vBytes = (byte[]) call.argument("vBytes");
      int vStride = (int) call.argument("vStride");
      int width = (int) call.argument("width");
      int height = (int) call.argument("height");
      int fourCC = (int) call.argument("fourCC");

      try {
          Bitmap bitmap = createBitmap(yBytes, yStride, uBytes, uStride, vBytes, vStride, width, height, fourCC);
          recognizerRunner.recognizeBitmap(bitmap, Orientation.ORIENTATION_LANDSCAPE_RIGHT, createScanResultListener(result));
      } catch (Exception e) {
          result.error(e.getMessage(), null,null);
      }
  }

  private Bitmap createBitmap(byte [] yBytes, int yStride, byte [] uBytes, int uStride, byte [] vBytes, int vStride, int width, int height, int fourCC) throws Exception {
      byte[] rgbaBytes = convertToRgbaBytes(yBytes, yStride, uBytes, uStride, vBytes, vStride, width, height, fourCC);

      return bitmapFromRgba(rgbaBytes, width, height);
  }

  private byte[] convertToRgbaBytes(byte [] yBytes, int yStride, byte [] uBytes, int uStride, byte [] vBytes, int vStride, int width, int height, int fourCC) throws Exception {
      // https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888
      // Wider support may be implemented later by employing libyuv::ConvertToARGB.
      if (fourCC != 35) throw new Exception("The only supported image format on Android is YUV_420_888 (four character code equals to 35). You are trying to scan image which four character code is " + fourCC);

      byte[] rgbaBytes = new byte[width * height * 4];
      int rgbStride = width * 4;
      int conversionResultCode = J420ToARGB(yBytes, yStride, uBytes, uStride, vBytes, vStride, rgbaBytes, rgbStride, width, height);

      if (conversionResultCode == 0) {
          return rgbaBytes;
      } else {
          throw new Exception("Conversion to RGBA bytes failed with a return code: " + conversionResultCode);
      }
  }

    private Bitmap bitmapFromRgba(byte[] bytes, int width, int height) {
        int[] colorPixels = new int[width * height];
        int j = 0;

        for (int i = 0; i < colorPixels.length; i++) {
            byte R = bytes[j++];
            byte G = bytes[j++];
            byte B = bytes[j++];
            byte A = bytes[j++];

            colorPixels[i] = (A & 0xff) << 24 | (B & 0xff) << 16 | (G & 0xff) << 8 | (R & 0xff);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(colorPixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

  private ScanResultListener createScanResultListener(final Result channelResult) {
      return new ScanResultListener() {
          @Override
          public void onScanningDone(@NonNull final RecognitionSuccessType recognitionSuccessType) {
              new Handler(Looper.getMainLooper()).post(new Runnable() {
                  @Override
                  public void run() {
                      if (recognitionSuccessType == RecognitionSuccessType.SUCCESSFUL || recognitionSuccessType == RecognitionSuccessType.STAGE_SUCCESSFUL) {
                          JSONObject jsonResult = RecognizerSerializers.INSTANCE.getRecognizerSerialization(recognizer).serializeResult(recognizer);
                          channelResult.success(jsonResult.toString());
                          if (recognitionSuccessType == RecognitionSuccessType.SUCCESSFUL) recognizerRunner.resetRecognitionState();
                      } else {
                          channelResult.error(recognitionSuccessType.name(), "Scan was not successful", null);
                          recognizerRunner.resetRecognitionState();
                      }
                  }
              });
          }

          @Override
          public void onUnrecoverableError(@NonNull Throwable throwable) {
              channelResult.error("", throwable.toString(), null);
              recognizerRunner.resetRecognitionState();
          }
      };
  }

  @SuppressWarnings("unchecked")
  private void setLicense(Map licenseMap) {
      MicroblinkSDK.setShowTrialLicenseWarning((boolean)licenseMap.getOrDefault(ARG_SHOW_LICENSE_WARNING, true));

      String licenseKey = (String)licenseMap.get(ARG_LICENSE_KEY);
      String licensee = (String)licenseMap.getOrDefault(ARG_LICENSEE, null);

      if (licensee == null) {
          MicroblinkSDK.setLicenseKey(licenseKey, context);
      } else {
          MicroblinkSDK.setLicenseKey(licenseKey, licensee, context);
      }

      MicroblinkSDK.setIntentDataTransferMode(IntentDataTransferMode.PERSISTED_OPTIMISED);
  }

  @SuppressWarnings("unchecked")
  private void setLicenseKey(String licenseKey) {
      MicroblinkSDK.setShowTrialLicenseWarning(false);

      MicroblinkSDK.setLicenseKey(licenseKey, context);

      MicroblinkSDK.setIntentDataTransferMode(IntentDataTransferMode.PERSISTED_OPTIMISED);

      recognizer = new BlinkIdCombinedRecognizer();
      recognizer.setReturnFaceImage(true);

      recognizerRunner = RecognizerRunner.getSingletonInstance();
      recognizerRunner.initialize(context, new RecognizerBundle(recognizer), new DirectApiErrorListener() {
          @Override
          public void onRecognizerError(@NonNull Throwable throwable) {}
      });
      metadataCallbacks.setFirstSideRecognitionCallback(new FirstSideRecognitionCallback() {
          @Override
          public void onFirstSideRecognitionFinished() {
              sendMetadataCallback("onFirstSideRecognitionFinished");
          }
      });
      metadataCallbacks.setGlareCallback(new GlareCallback() {
          @Override
          public void onGlare(boolean b) {
              sendMetadataCallback("onGlare:" + b);
          }
      });
      recognizerRunner.setMetadataCallbacks(metadataCallbacks);
  }

  private void startScanning(int requestCode, UISettings uiSettings) {
      if (context instanceof Activity) {
          ActivityRunner.startActivityForResult(((Activity) context), requestCode, uiSettings);
      } else if (activity != null) {
          ActivityRunner.startActivityForResult(activity, requestCode, uiSettings);
      } else {
          pendingResult.error("Context can't be casted to Activity", null, null);
      }
  }

  @Override
  public void onDetachedFromActivity() {}

  @Override
  public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
      onAttachedToActivity(binding);
  }

  @Override
  public void onAttachedToActivity(ActivityPluginBinding binding) {
      activity = binding.getActivity();
      binding.addActivityResultListener(this);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {}

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    this.context = null;
    this.activity = null;

    recognizerRunner.terminate();
    this.channel.setMethodCallHandler(null);
    this.channel = null;
  }


  @Override
  public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
      if (pendingResult == null) {
          return true;
      }

      if (resultCode == Activity.RESULT_OK) {
          if (requestCode == SCAN_REQ_CODE  && mRecognizerBundle != null) {
              mRecognizerBundle.loadFromIntent(data);
              JSONArray resultList = RecognizerSerializers.INSTANCE.serializeRecognizerResults(mRecognizerBundle.getRecognizers());

              pendingResult.success(resultList.toString());

          }


      } else if (resultCode == Activity.RESULT_CANCELED) {
          pendingResult.success("null");

      } else {
          pendingResult.error("Unexpected error", null, null);
      }

      pendingResult = null;
      return true;
  }

    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public void onCancel(Object o) {
        this.eventSink = null;
    }
}

