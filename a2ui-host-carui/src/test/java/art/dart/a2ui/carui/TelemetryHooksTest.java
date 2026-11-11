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
package art.dart.a2ui.carui;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import androidx.annotation.NonNull;

import art.dart.a2ui.agent.AgentProvider;
import art.dart.a2ui.agent.AgentTransport;
import art.dart.a2ui.agent.AgentTransportException;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.TelemetryBackend;
import art.dart.a2ui.telemetry.TelemetryEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host B's telemetry hooks fire with metadata only: the session root on activity creation, the
 * rendered root's catalog type per render pass (never a surface id — agent ids fail the
 * scrubber), and the agent lane's voice decisions (never the phrase).
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
    public void sessionStartIsReported() {
        // onCreate's own call: the activity path installs LogBackend first under Robolectric's
        // debuggable flag, so the hook is asserted through the same seam Host A uses.
        A2uiCarUiActivity.reportSessionStart();

        assertThat(mBackend.events).containsExactly(TelemetryEvent.SESSION_START);
        assertThat(mBackend.lastParamsFor(TelemetryEvent.SESSION_START))
                .containsExactly("root", "demo", "host", "b");
    }

    @Test
    public void renderedRootTypeIsReported() {
        A2uiComponent video = new A2uiComponent.Builder(ComponentType.VIDEO).build();

        A2uiCarUiActivity.reportRendered(video);
        A2uiCarUiActivity.reportRendered(null);

        assertThat(mBackend.events).containsExactly(
                TelemetryEvent.TEMPLATE_RENDERED, TelemetryEvent.TEMPLATE_RENDERED);
        assertThat(mBackend.params.get(0)).containsExactly(
                "template", "Video", "host", "b");
        assertThat(mBackend.params.get(1)).containsExactly(
                "template", "empty", "host", "b");
    }

    @Test
    public void allowedVoiceDecisionIsReported() {
        AgentLane lane = lane();
        A2uiMessageProcessor.VoiceDispatchResult result = dispatch(lane.processor(), "Play",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);

        lane.onVoice("s1", result);

        assertThat(result.getOutcome())
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.DISPATCHED);
        assertThat(mBackend.events).containsExactly(TelemetryEvent.VOICE_DECISION);
        assertThat(mBackend.lastParamsFor(TelemetryEvent.VOICE_DECISION))
                .containsExactly("outcome", "allowed", "reason", "none");
    }

    @Test
    public void refusedVoiceDecisionIsReported() {
        AgentLane lane = lane();
        A2uiMessageProcessor.VoiceDispatchResult result = dispatch(lane.processor(), "Buy album",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);

        lane.onVoice("s1", result);

        assertThat(result.getOutcome())
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.REFUSED_BY_POLICY);
        assertThat(mBackend.events).containsExactly(TelemetryEvent.VOICE_DECISION);
        assertThat(mBackend.lastParamsFor(TelemetryEvent.VOICE_DECISION))
                .containsExactly("outcome", "refused", "reason", "none");
    }

    private static AgentLane lane() {
        return new AgentLane(mock(AgentTransport.class), null, Runnable::run,
                new AgentLane.Listener() {
                    @Override
                    public void onPending(@NonNull AgentProvider provider) {
                    }

                    @Override
                    public void onSurface(@NonNull String surfaceId,
                            @NonNull AgentProvider provider, @NonNull String badge) {
                    }

                    @Override
                    public void onFailure(@NonNull AgentProvider provider,
                            @NonNull AgentTransportException failure) {
                    }
                });
    }

    /** Dispatches a phrase against a speakable surface under the given restrictions. */
    private static A2uiMessageProcessor.VoiceDispatchResult dispatch(
            A2uiMessageProcessor processor, String phrase, int restrictions) {
        processor.process(A2uiMessage.createSurface("v0.9", "s1", "automotive"));
        Map<String, Object> root = A2uiMessage.component("root", "Column");
        root.put("children", Arrays.asList("play", "buy"));
        Map<String, Object> play = A2uiMessage.component("play", "Button");
        play.put("action", "play");
        play.put("voicePhrase", "Play");
        Map<String, Object> buy = A2uiMessage.component("buy", "Button");
        buy.put("action", "purchase");
        buy.put("voicePhrase", "Buy album");
        processor.process(A2uiMessage.updateComponents("v0.9", "s1",
                Arrays.asList(root, play, buy)));
        A2uiMessageProcessor.RestrictionReader reader =
                new A2uiMessageProcessor.RestrictionReader() {
                    @Override
                    public int activeRestrictions() {
                        return restrictions;
                    }

                    @Override
                    public boolean isTrustworthy() {
                        return true;
                    }
                };
        A2uiComponent rendered = processor.getSurface("s1").project("root");
        return processor.dispatchByVoiceForResult("s1", phrase, rendered, reader);
    }

    private static final class FakeBackend implements TelemetryBackend {
        final List<TelemetryEvent> events = new ArrayList<>();
        final List<Map<String, String>> params = new ArrayList<>();

        @Override
        public void log(TelemetryEvent event, Map<String, String> params) {
            events.add(event);
            this.params.add(new HashMap<>(params));
        }

        Map<String, String> lastParamsFor(TelemetryEvent event) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i) == event) {
                    return params.get(i);
                }
            }
            return new HashMap<>();
        }
    }
}
