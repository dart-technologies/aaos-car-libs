/*
 * Copyright (C) 2026 Dart Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package art.dart.car_uxr;

import android.app.Activity;
import android.car.drivingstate.CarUxRestrictions;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.ui.utils.CarUxRestrictionsUtil;

import java.util.HashMap;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Exposes {@link CarUxRestrictionsUtil} to Dart. Load-bearing details:
 *
 * <ol>
 *   <li>{@code onListen} seeds the current state as event #1: the platform listener is
 *       change-only.
 *   <li>The listener is held strongly: the util's observer set is weak.
 *   <li>The util is resolved at activity attach, not engine attach: the engine's application
 *       context is non-visual, and restrictions are per-display.
 *   <li>Registration is released on both paths: hot restart detaches and reattaches constantly,
 *       and a leaked registration stops delivery process-wide.
 * </ol>
 */
public class CarUxrPlugin
    implements FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
        EventChannel.StreamHandler {

  private static final String METHOD_CHANNEL = "art.dart.car_uxr/methods";
  private static final String EVENT_CHANNEL = "art.dart.car_uxr/restrictions";

  private MethodChannel methodChannel;
  private EventChannel eventChannel;

  @Nullable private CarUxRestrictionsUtil uxr;
  @Nullable private EventChannel.EventSink sink;
  private volatile long generation;

  /** Strong reference: the util's observer set is weak (point 2 above). */
  @Nullable private CarUxRestrictionsUtil.OnUxRestrictionsChangedListener listener;

  // ---------------------------------------------------------------- FlutterPlugin

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    // Channels only: the binding's application context is non-visual (getDisplay() throws), so
    // resolving the util here would bind every view to the driver display.
    methodChannel = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL);
    methodChannel.setMethodCallHandler(this);

    eventChannel = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL);
    eventChannel.setStreamHandler(this);
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    unregisterListener();
    sink = null;
    uxr = null;

    if (methodChannel != null) {
      methodChannel.setMethodCallHandler(null);
      methodChannel = null;
    }
    if (eventChannel != null) {
      eventChannel.setStreamHandler(null);
      eventChannel = null;
    }
  }

  // ---------------------------------------------------------------- ActivityAware

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    bindTo(binding.getActivity());
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    bindTo(binding.getActivity());
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    releaseActivity();
  }

  @Override
  public void onDetachedFromActivity() {
    releaseActivity();
  }

  /**
   * Resolves the per-display util for this activity and, if Dart is already listening, moves the
   * registration onto it and re-seeds. The activity may sit on a different display than the last
   * one did.
   */
  private void bindTo(Activity activity) {
    unregisterListener();
    uxr = CarUxRestrictionsUtil.getInstance(activity);
    generation++;
    if (sink != null) {
      registerListener();
      emitCurrent();
    }
  }

  private void releaseActivity() {
    unregisterListener();
    uxr = null;
    generation++;
    // Dart may still be subscribed. Tell it the signal is no longer trustworthy rather than
    // leaving it on a stale, possibly permissive value.
    if (sink != null) {
      postToMain(sink, failClosedMap(), generation);
    }
  }

  // ---------------------------------------------------------------- EventChannel

  // @Nullable, not @NonNull: StreamHandler's parameters are platform-nullness, and narrowing them
  // is metalava's InvalidNullabilityOverride.
  @Override
  public void onListen(@Nullable Object arguments, @Nullable EventChannel.EventSink events) {
    sink = events;
    generation++;
    registerListener();
    // Seed synchronously as event #1, whether or not an activity is attached yet. If it is not,
    // this emits the fail-closed value, and bindTo() re-seeds with the real one on attach.
    emitCurrent();
  }

  @Override
  public void onCancel(@Nullable Object arguments) {
    unregisterListener();
    sink = null;
    generation++;
  }

  // ---------------------------------------------------------------- MethodChannel

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    if ("getCurrentRestrictions".equals(call.method)) {
      result.success(currentMap());
    } else {
      result.notImplemented();
    }
  }

  // ---------------------------------------------------------------- internals

  private void registerListener() {
    if (uxr == null || listener != null) {
      return;
    }
    final CarUxRestrictionsUtil boundUtil = uxr;
    final EventChannel.EventSink boundSink = sink;
    final long boundGeneration = generation;
    listener = carUxRestrictions -> {
      CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
          boundUtil.getRestrictionsSnapshot();
      Map<String, Object> event = toMap(snapshot.getRestrictions(),
          snapshot.getSource(), boundUtil.getDisplayId());
      postToMain(boundSink, event, boundGeneration);
    };
    // register() also delivers current state synchronously, so this is itself a seed.
    uxr.register(listener);
  }

  private void unregisterListener() {
    if (uxr != null && listener != null) {
      uxr.unregister(listener);
    }
    listener = null;
    generation++;
  }

  private void emitCurrent() {
    if (sink != null) {
      postToMain(sink, currentMap(), generation);
    }
  }

  private Map<String, Object> currentMap() {
    if (uxr == null) {
      return failClosedMap();
    }
    CarUxRestrictionsUtil.RestrictionsSnapshot snapshot = uxr.getRestrictionsSnapshot();
    return toMap(snapshot.getRestrictions(), snapshot.getSource(), uxr.getDisplayId());
  }

  private Map<String, Object> toMap(@Nullable CarUxRestrictions restrictions,
      @NonNull CarUxRestrictionsUtil.RestrictionsSource source, int displayId) {
    if (restrictions == null) {
      return failClosedMap();
    }
    Map<String, Object> map = new HashMap<>();
    map.put("activeRestrictions", restrictions.getActiveRestrictions());
    map.put("requiresDistractionOptimization", restrictions.isRequiresDistractionOptimization());
    map.put("maxCumulativeContentItems", restrictions.getMaxCumulativeContentItems());
    map.put("maxRestrictedStringLength", restrictions.getMaxRestrictedStringLength());
    map.put(
        "source", sourceName(source));
    map.put("displayId", displayId);
    return map;
  }

  private void postToMain(@Nullable EventChannel.EventSink target,
      @NonNull Map<String, Object> event, long eventGeneration) {
    if (target == null) return;
    Runnable deliver = () -> {
      if (sink == target && generation == eventGeneration) {
        target.success(event);
      }
    };
    Looper main = Looper.getMainLooper();
    if (main == null || Looper.myLooper() == main) {
      deliver.run();
    } else {
      new Handler(main).post(deliver);
    }
  }

  /**
   * The value sent whenever the signal cannot be trusted. Mirrors Dart's
   * {@code CarUxRestrictionsState.failClosed} exactly; the two must not drift.
   */
  private static Map<String, Object> failClosedMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("activeRestrictions", CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    map.put("requiresDistractionOptimization", true);
    map.put("source", "never_delivered");
    map.put("displayId", 0);
    map.put("maxCumulativeContentItems", -1);
    map.put("maxRestrictedStringLength", -1);
    return map;
  }

  private static String sourceName(CarUxRestrictionsUtil.RestrictionsSource source) {
    switch (source) {
      case DELIVERED:
        return "delivered";
      case DEMO:
        return "demo";
      case DISCONNECTED:
        return "disconnected";
      case NEVER_DELIVERED:
      default:
        return "never_delivered";
    }
  }
}
