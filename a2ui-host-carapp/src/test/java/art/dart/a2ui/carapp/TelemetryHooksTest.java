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
package art.dart.a2ui.carapp;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Template;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestCarContext;
import androidx.lifecycle.Lifecycle;

import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.TelemetryBackend;
import art.dart.a2ui.telemetry.TelemetryEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host A's telemetry hooks fire with metadata only: the session root, the voice gate's decision
 * (never the transcript — the refused path emits its event without opening the microphone), and
 * the rendered template's class.
 */
@RunWith(RobolectricTestRunner.class)
public class TelemetryHooksTest {

    private final FakeBackend mBackend = new FakeBackend();

    @Before
    public void setUp() {
        A2uiTelemetry.install(mBackend);
    }

    @After
    public void tearDown() {
        A2uiTelemetry.resetForTesting();
    }

    @Test
    public void sessionRootIsReported() {
        A2uiSession.reportRoot("showcase");

        assertThat(mBackend.events).containsExactly(TelemetryEvent.SESSION_START);
        assertThat(mBackend.lastParams).containsExactly("root", "showcase", "host", "a");
    }

    /** The refusal event fires, and the microphone never opened for it. */
    @Test
    public void refusedVoiceDecisionIsReportedWithoutListening() {
        FakeSource source = new FakeSource();
        A2uiVoiceCapture capture = new A2uiVoiceCapture(mock(A2uiMessageProcessor.class), source,
                gate(false, TranscriptSource.Unavailable.RESTRICTED));

        capture.listen("surface", () -> null, new SilentListener());

        assertThat(source.listenCalls).isEqualTo(0);
        assertThat(mBackend.events).containsExactly(TelemetryEvent.VOICE_DECISION);
        assertThat(mBackend.lastParams)
                .containsExactly("outcome", "refused", "reason", "RESTRICTED");
    }

    @Test
    public void allowedVoiceDecisionIsReported() {
        A2uiVoiceCapture capture = new A2uiVoiceCapture(mock(A2uiMessageProcessor.class),
                new FakeSource(), gate(true, TranscriptSource.Unavailable.RESTRICTED));

        capture.listen("surface", () -> null, new SilentListener());

        assertThat(mBackend.events).containsExactly(TelemetryEvent.VOICE_DECISION);
        assertThat(mBackend.lastParams).containsExactly("outcome", "allowed", "reason", "none");
    }

    @Test
    public void renderedTemplateIsReported() {
        TestCarContext carContext =
                TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        ConstraintManager constraints = mock(ConstraintManager.class);
        when(constraints.getContentLimit(anyInt())).thenReturn(100);
        carContext.overrideCarService(ConstraintManager.class, constraints);
        CarUxRestrictionsUtil.getInstance(carContext).setUxRestrictions(
                new CarUxRestrictions.Builder(false,
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 0).build());
        A2uiShowcaseScreen screen = new A2uiShowcaseScreen(carContext, new FakeSource());
        new ScreenController(screen).moveToState(Lifecycle.State.STARTED);

        Template rendered = screen.onGetTemplate();

        assertThat(mBackend.events).containsExactly(TelemetryEvent.TEMPLATE_RENDERED);
        assertThat(mBackend.lastParams).containsExactly(
                "template", rendered.getClass().getSimpleName(), "host", "a");
    }

    private static A2uiVoiceCapture.Gate gate(boolean allowed,
            TranscriptSource.Unavailable reason) {
        return new A2uiVoiceCapture.Gate() {
            @Override
            public boolean allowsListening() {
                return allowed;
            }

            @Override
            @NonNull
            public TranscriptSource.Unavailable reason() {
                return reason;
            }
        };
    }

    private static final class FakeBackend implements TelemetryBackend {
        final List<TelemetryEvent> events = new ArrayList<>();
        Map<String, String> lastParams = new HashMap<>();

        @Override
        public void log(TelemetryEvent event, Map<String, String> params) {
            events.add(event);
            lastParams = params;
        }
    }

    private static final class FakeSource implements TranscriptSource {
        int listenCalls;

        @Override
        public void listen(@NonNull Callback callback) {
            listenCalls++;
        }

        @Override
        public void cancel() {
        }
    }

    private static final class SilentListener implements A2uiVoiceCapture.Listener {
        @Override
        public void onDispatched(@NonNull String spoken,
                @NonNull A2uiMessageProcessor.VoiceOutcome outcome) {
        }

        @Override
        public void onUnavailable(@NonNull TranscriptSource.Unavailable reason,
                @Nullable String detail) {
        }
    }
}
