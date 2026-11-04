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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.car.drivingstate.CarUxRestrictions;

import com.android.car.ui.utils.CarUxRestrictionsUtil;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Map;

/**
 * Drives {@link CarUxrPlugin}'s lifecycle directly on the JVM: every guarantee here is ordering-
 * or lifecycle-sensitive, which an emulator run confirms only by luck.
 */
public class CarUxrPluginTest {

  private CarUxrPlugin plugin;
  private MockedStatic<CarUxRestrictionsUtil> staticUtil;
  private CarUxRestrictionsUtil util;
  private EventChannel.EventSink sink;
  private Activity activity;

  @Before
  public void setUp() {
    plugin = new CarUxrPlugin();
    sink = mock(EventChannel.EventSink.class);
    activity = mock(Activity.class);

    // Build the nested mock before the outer stubbing: stubbing inside thenReturn() leaves the
    // outer when() unfinished.
    CarUxRestrictions noVideo = restrictions(CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO);

    util = mock(CarUxRestrictionsUtil.class);
    CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
        mock(CarUxRestrictionsUtil.RestrictionsSnapshot.class);
    when(snapshot.getRestrictions()).thenReturn(noVideo);
    when(snapshot.getSource()).thenReturn(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
    when(util.getRestrictionsSnapshot()).thenReturn(snapshot);
    when(util.getDisplayId()).thenReturn(2);
    when(util.getRestrictionsSource()).thenReturn(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
    when(util.getCurrentRestrictions()).thenReturn(noVideo);

    staticUtil = mockStatic(CarUxRestrictionsUtil.class);
    staticUtil.when(() -> CarUxRestrictionsUtil.getInstance(any())).thenReturn(util);
  }

  @After
  public void tearDown() {
    staticUtil.close();
  }

  private static CarUxRestrictions restrictions(int active) {
    CarUxRestrictions uxr = mock(CarUxRestrictions.class);
    when(uxr.getActiveRestrictions()).thenReturn(active);
    when(uxr.isRequiresDistractionOptimization()).thenReturn(active != 0);
    when(uxr.getMaxCumulativeContentItems()).thenReturn(8);
    when(uxr.getMaxRestrictedStringLength()).thenReturn(120);
    return uxr;
  }

  private void snapshot(CarUxRestrictions restrictions,
      CarUxRestrictionsUtil.RestrictionsSource source) {
    CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
        mock(CarUxRestrictionsUtil.RestrictionsSnapshot.class);
    when(snapshot.getRestrictions()).thenReturn(restrictions);
    when(snapshot.getSource()).thenReturn(source);
    when(util.getRestrictionsSnapshot()).thenReturn(snapshot);
  }

  private void attachEngine() {
    FlutterPlugin.FlutterPluginBinding binding = mock(FlutterPlugin.FlutterPluginBinding.class);
    when(binding.getBinaryMessenger()).thenReturn(mock(BinaryMessenger.class));
    plugin.onAttachedToEngine(binding);
  }

  private void attachActivity() {
    ActivityPluginBinding binding = mock(ActivityPluginBinding.class);
    when(binding.getActivity()).thenReturn(activity);
    plugin.onAttachedToActivity(binding);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> lastEvent() {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(sink, org.mockito.Mockito.atLeastOnce()).success(captor.capture());
    return (Map<String, Object>) captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> firstEvent() {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(sink, org.mockito.Mockito.atLeastOnce()).success(captor.capture());
    return (Map<String, Object>) captor.getAllValues().get(0);
  }

  // ------------------------------------------------------------------ resolution timing

  /**
   * Engine attach must not resolve the util: the application context is non-visual and would bind
   * every Flutter view to the driver display.
   */
  @Test
  public void engineAttach_doesNotResolveTheUtil() {
    attachEngine();

    staticUtil.verify(() -> CarUxRestrictionsUtil.getInstance(any()), never());
  }

  @Test
  public void activityAttach_resolvesWithTheActivity_notSomeOtherContext() {
    attachEngine();
    attachActivity();

    staticUtil.verify(() -> CarUxRestrictionsUtil.getInstance(activity), times(1));
  }

  // ------------------------------------------------------------------ seeding

  /** The stream must deliver state as event #1 even with no activity attached yet. */
  @Test
  public void listenBeforeActivity_seedsFailClosedImmediately() {
    attachEngine();

    plugin.onListen(null, sink);

    Map<String, Object> event = firstEvent();
    assertThat(event.get("activeRestrictions"))
        .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    assertThat(event.get("requiresDistractionOptimization")).isEqualTo(true);
    assertThat(event.get("source")).isEqualTo("never_delivered");
  }

  @Test
  public void listenAfterActivity_seedsWithRealState() {
    attachEngine();
    attachActivity();

    plugin.onListen(null, sink);

    Map<String, Object> event = lastEvent();
    assertThat(event.get("activeRestrictions"))
        .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO);
    assertThat(event.get("source")).isEqualTo("delivered");
    assertThat(event.get("displayId")).isEqualTo(2);
  }

  /** Activity arriving while Dart is already subscribed must move the registration and re-seed. */
  @Test
  public void activityAttachWhileListening_registersAndReSeeds() {
    attachEngine();
    plugin.onListen(null, sink);

    attachActivity();

    verify(util, times(1)).register(any());
    Map<String, Object> event = lastEvent();
    assertThat(event.get("source")).isEqualTo("delivered");
    assertThat(event.get("displayId")).isEqualTo(2);
  }

  // ------------------------------------------------------------------ the WeakHashMap trap

  /**
   * {@link CarUxRestrictionsUtil} holds observers weakly. The plugin must keep the listener in a
   * field, and hand that same instance back on unregister — proving it was retained rather than
   * recreated or collected.
   */
  @Test
  public void theRegisteredListenerIsRetained_andIsTheOneUnregistered() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);

    ArgumentCaptor<CarUxRestrictionsUtil.OnUxRestrictionsChangedListener> registered =
        ArgumentCaptor.forClass(CarUxRestrictionsUtil.OnUxRestrictionsChangedListener.class);
    verify(util).register(registered.capture());
    assertThat(registered.getValue()).isNotNull();

    plugin.onCancel(null);

    ArgumentCaptor<CarUxRestrictionsUtil.OnUxRestrictionsChangedListener> unregistered =
        ArgumentCaptor.forClass(CarUxRestrictionsUtil.OnUxRestrictionsChangedListener.class);
    verify(util).unregister(unregistered.capture());
    assertThat(unregistered.getValue()).isSameInstanceAs(registered.getValue());
  }

  /** Delivery from the util must reach the sink. */
  @Test
  public void restrictionChanges_reachTheSink() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);

    ArgumentCaptor<CarUxRestrictionsUtil.OnUxRestrictionsChangedListener> registered =
        ArgumentCaptor.forClass(CarUxRestrictionsUtil.OnUxRestrictionsChangedListener.class);
    verify(util).register(registered.capture());

    CarUxRestrictions fullyRestricted =
        restrictions(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    snapshot(fullyRestricted, CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
    registered.getValue().onRestrictionsChanged(fullyRestricted);

    assertThat(lastEvent().get("activeRestrictions"))
        .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
  }

  @Test
  public void callbackCapturedBeforeCancelCannotReachTheOldSink() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);
    ArgumentCaptor<CarUxRestrictionsUtil.OnUxRestrictionsChangedListener> registered =
        ArgumentCaptor.forClass(CarUxRestrictionsUtil.OnUxRestrictionsChangedListener.class);
    verify(util).register(registered.capture());

    plugin.onCancel(null);
    clearInvocations(sink);
    registered.getValue().onRestrictionsChanged(
        restrictions(CarUxRestrictions.UX_RESTRICTIONS_BASELINE));

    verify(sink, never()).success(any());
  }

  @Test
  public void callbackCapturedBeforeDisplayRebindCannotReachTheCurrentSink() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);
    ArgumentCaptor<CarUxRestrictionsUtil.OnUxRestrictionsChangedListener> registered =
        ArgumentCaptor.forClass(CarUxRestrictionsUtil.OnUxRestrictionsChangedListener.class);
    verify(util).register(registered.capture());

    CarUxRestrictionsUtil next = mock(CarUxRestrictionsUtil.class);
    CarUxRestrictionsUtil.RestrictionsSnapshot nextSnapshot =
        mock(CarUxRestrictionsUtil.RestrictionsSnapshot.class);
    CarUxRestrictions nextReading =
        restrictions(CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
    when(nextSnapshot.getRestrictions()).thenReturn(nextReading);
    when(nextSnapshot.getSource()).thenReturn(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
    when(next.getRestrictionsSnapshot()).thenReturn(nextSnapshot);
    when(next.getDisplayId()).thenReturn(7);
    staticUtil.when(() -> CarUxRestrictionsUtil.getInstance(any())).thenReturn(next);
    attachActivity();

    clearInvocations(sink);
    registered.getValue().onRestrictionsChanged(
        restrictions(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED));

    verify(sink, never()).success(any());
  }

  // ------------------------------------------------------------------ releasing registration

  @Test
  public void onCancel_unregisters() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);

    plugin.onCancel(null);

    verify(util, times(1)).unregister(any());
  }

  @Test
  public void onDetachedFromEngine_unregisters() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);

    plugin.onDetachedFromEngine(mock(FlutterPlugin.FlutterPluginBinding.class));

    verify(util, times(1)).unregister(any());
  }

  @Test
  public void onDetachedFromActivity_unregisters_andTellsDartTheSignalIsUntrusted() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);

    plugin.onDetachedFromActivity();

    verify(util, times(1)).unregister(any());
    Map<String, Object> event = lastEvent();
    assertThat(event.get("activeRestrictions"))
        .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    assertThat(event.get("source")).isEqualTo("never_delivered");
  }

  /**
   * Hot restart detaches and reattaches constantly. Only one listener may be registered with
   * CarUxRestrictionsManager at a time, so a reattach must release before it re-registers.
   */
  @Test
  public void reattach_releasesBeforeRegisteringAgain_soNothingLeaks() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);
    verify(util, times(1)).register(any());

    plugin.onDetachedFromActivityForConfigChanges();
    ActivityPluginBinding rebind = mock(ActivityPluginBinding.class);
    when(rebind.getActivity()).thenReturn(activity);
    plugin.onReattachedToActivityForConfigChanges(rebind);

    verify(util, times(1)).unregister(any());
    verify(util, times(2)).register(any());
  }

  @Test
  public void cancellingTwice_doesNotDoubleUnregister() {
    attachEngine();
    attachActivity();
    plugin.onListen(null, sink);

    plugin.onCancel(null);
    plugin.onCancel(null);

    verify(util, times(1)).unregister(any());
  }

  // ------------------------------------------------------------------ the wire contract

  /**
   * The six key names the Dart side reads. A rename on either side degrades silently to
   * fail-closed — safe, but permanently broken, and nothing else would notice.
   *
   * <p>Mirrored by {@code test/wire_contract_test.dart}.
   */
  @Test
  public void failClosedPayload_carriesExactlyTheAgreedKeys() {
    attachEngine();

    plugin.onListen(null, sink);

    assertThat(firstEvent().keySet())
        .containsExactly(
            "activeRestrictions",
            "requiresDistractionOptimization",
            "source",
            "displayId",
            "maxCumulativeContentItems",
            "maxRestrictedStringLength");
  }

  @Test
  public void deliveredPayload_carriesExactlyTheAgreedKeys() {
    attachEngine();
    attachActivity();

    plugin.onListen(null, sink);

    assertThat(lastEvent().keySet())
        .containsExactly(
            "activeRestrictions",
            "requiresDistractionOptimization",
            "source",
            "displayId",
            "maxCumulativeContentItems",
            "maxRestrictedStringLength");
  }

  @Test
  public void sourceNames_matchTheStringsDartParses() {
    attachEngine();
    attachActivity();

    snapshot(restrictions(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED),
        CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);
    plugin.onListen(null, sink);
    assertThat(lastEvent().get("source")).isEqualTo("disconnected");

    snapshot(restrictions(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED),
        CarUxRestrictionsUtil.RestrictionsSource.NEVER_DELIVERED);
    plugin.onListen(null, sink);
    assertThat(lastEvent().get("source")).isEqualTo("never_delivered");

    snapshot(restrictions(CarUxRestrictions.UX_RESTRICTIONS_BASELINE),
        CarUxRestrictionsUtil.RestrictionsSource.DEMO);
    plugin.onListen(null, sink);
    assertThat(lastEvent().get("source")).isEqualTo("demo");
  }

  /** A null reading from the platform must not become "unrestricted". */
  @Test
  public void nullRestrictions_failClosed() {
    attachEngine();
    attachActivity();
    snapshot(null, CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);

    plugin.onListen(null, sink);

    assertThat(lastEvent().get("activeRestrictions"))
        .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    assertThat(lastEvent().get("requiresDistractionOptimization")).isEqualTo(true);
  }
}
