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

import android.os.Bundle;
import android.speech.RecognitionListener;

/**
 * Empty defaults for {@link RecognitionListener}, whose eight callbacks are mostly noise here.
 *
 * <p>Only {@code onResults} and {@code onError} carry anything this host acts on. The rest —
 * beginning of speech, RMS changes, partial results, buffer received — are for a UI that visualises
 * listening, which this showcase deliberately does not build: the thing being demonstrated is that
 * a spoken control reaches the same policy a tapped one does, and a waveform would draw attention
 * away from that.
 */
abstract class RecognitionListenerAdapter implements RecognitionListener {
    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }
}
