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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiMessageProcessor.VoiceOutcome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A benchmark for the voice path: a corpus of transcripts, each with its required outcome, driven
 * through {@link A2uiVoiceCapture} into a real {@link A2uiMessageProcessor}. It measures matching,
 * ambiguity, policy and restriction state, not acoustic recognition.
 */
public class VoiceBenchmarkTest {

    /** One benchmark case: what was said, and what must happen. */
    private static final class Case {
        final String spoken;
        final VoiceOutcome expected;
        final String why;

        Case(String spoken, VoiceOutcome expected, String why) {
            this.spoken = spoken;
            this.expected = expected;
            this.why = why;
        }
    }

    /**
     * The corpus: phrases that must match, must not match, are ambiguous, or are consequential, so
     * a degenerate implementation cannot pass.
     */
    private static List<Case> corpus() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("play", VoiceOutcome.DISPATCHED, "exact label on an allowed control"));
        cases.add(new Case("  PLAY  ", VoiceOutcome.DISPATCHED, "trimmed and case-folded"));
        cases.add(new Case("buy album", VoiceOutcome.CONFIRMATION_REQUIRED,
                "consequential: asked, never performed on a phrase alone"));
        cases.add(new Case("", VoiceOutcome.NO_MATCH, "empty is not a command"));
        cases.add(new Case("   ", VoiceOutcome.NO_MATCH, "whitespace is not a command"));
        cases.add(new Case("open the pod bay doors", VoiceOutcome.NO_MATCH,
                "nothing on the surface answers to it"));
        return cases;
    }

    /** A scripted source: hands back the phrase it was built with, exactly once. */
    private static final class ScriptedSource implements TranscriptSource {
        private final String mPhrase;

        ScriptedSource(String phrase) {
            mPhrase = phrase;
        }

        @Override
        public void listen(@NonNull Callback callback) {
            callback.onTranscript(mPhrase);
        }

        @Override
        public void cancel() {
        }
    }

    private static final class Result implements A2uiVoiceCapture.Listener {
        @Nullable VoiceOutcome mOutcome;
        @Nullable TranscriptSource.Unavailable mUnavailable;

        @Override
        public void onDispatched(@NonNull String spoken, @NonNull VoiceOutcome outcome) {
            mOutcome = outcome;
        }

        @Override
        public void onUnavailable(@NonNull TranscriptSource.Unavailable reason,
                @Nullable String detail) {
            mUnavailable = reason;
        }
    }

    private static A2uiVoiceCapture.Gate allowing() {
        return new A2uiVoiceCapture.Gate() {
            @Override
            public boolean allowsListening() {
                return true;
            }

            @Override
            @NonNull
            public TranscriptSource.Unavailable reason() {
                return TranscriptSource.Unavailable.RESTRICTED;
            }
        };
    }

    /** The whole corpus, scored at once; every mismatch is reported. */
    @Test
    public void everyPhraseInTheCorpusProducesItsExpectedOutcome() {
        Map<String, String> mismatches = new LinkedHashMap<>();
        int scored = 0;

        for (Case c : corpus()) {
            A2uiMessageProcessor processor = benchmarkProcessor();
            A2uiComponent rendered = processor.getSurface(DemoTrace.SURFACE_ID).project(DemoTrace.ROOT_ID);
            Result result = new Result();

            new A2uiVoiceCapture(processor, new ScriptedSource(c.spoken), allowing())
                    .listen(DemoTrace.SURFACE_ID, () -> rendered, result);

            scored++;
            if (result.mOutcome != c.expected) {
                mismatches.put("\"" + c.spoken + "\" (" + c.why + ")",
                        "expected " + c.expected + " but got "
                                + (result.mOutcome != null ? result.mOutcome
                                        : "unavailable:" + result.mUnavailable));
            }
        }

        // Anti-vacuity: a corpus that shrank to nothing would pass the mismatch assertion silently.
        assertThat(scored).isEqualTo(corpus().size());
        assertThat(scored).isAtLeast(6);
        assertThat(mismatches).isEmpty();
    }

    /** The corpus covers more than one outcome, so a constant answer cannot pass. */
    @Test
    public void theCorpusCoversMoreThanOneOutcome() {
        List<VoiceOutcome> expected = new ArrayList<>();
        for (Case c : corpus()) {
            if (!expected.contains(c.expected)) {
                expected.add(c.expected);
            }
        }

        assertThat(expected).containsAtLeast(
                VoiceOutcome.DISPATCHED, VoiceOutcome.CONFIRMATION_REQUIRED, VoiceOutcome.NO_MATCH);
    }

    /** A real processor over the demo trace via {@code DemoTrace}'s own factory, at BASELINE. */
    private static A2uiMessageProcessor benchmarkProcessor() {
        A2uiMessageProcessor processor = DemoTrace.newProcessor();
        // BASELINE and trustworthy, so refusals come from what was said, not from the reading.
        processor.setRestrictionReader(new A2uiMessageProcessor.RestrictionReader() {
            @Override
            public int activeRestrictions() {
                return 0;
            }

            @Override
            public boolean isTrustworthy() {
                return true;
            }
        });
        processor.processAll(DemoTrace.messages());
        return processor;
    }
}
