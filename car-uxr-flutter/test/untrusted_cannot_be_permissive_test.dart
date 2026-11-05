// Copyright (C) 2026 Dart Technologies
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import 'package:car_uxr/car_uxr.dart';
import 'package:flutter_test/flutter_test.dart';

/// A permissive answer must be obtainable only from a delivered reading. A partially parsed payload
/// (valid flags, unparseable provenance) must not yield "unrestricted but untrusted": if the source
/// is not [RestrictionsSource.delivered], the state is fully restricted.
void main() {
  group('untrusted readings cannot be permissive', () {
    test('valid flags with an unparseable source must not yield BASELINE', () {
      // Parses cleanly as "no restrictions", but the provenance field is garbage.
      final state = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'requiresDistractionOptimization': false,
        'source': 'some_future_value',
      });

      expect(state.source, RestrictionsSource.neverDelivered);
      // The dangerous outcome: unrestricted, from a payload we could not fully understand.
      expect(state.activeRestrictions, isNot(UxRestrictions.baseline));
      expect(state.isFullyRestricted, isTrue);
      expect(state.requiresDistractionOptimization, isTrue);
    });

    test('a missing source field is likewise not permissive', () {
      final state = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'requiresDistractionOptimization': false,
      });

      expect(state.isFullyRestricted, isTrue);
    });

    test('a disconnected reading discards whatever flags accompanied it', () {
      final state = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'requiresDistractionOptimization': false,
        'source': 'disconnected',
      });

      expect(state.source, RestrictionsSource.disconnected);
      expect(state.isFullyRestricted, isTrue);
    });

    test('only a delivered source can carry BASELINE', () {
      final state = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'requiresDistractionOptimization': false,
        'source': 'delivered',
      });

      expect(state.source, RestrictionsSource.delivered);
      expect(state.activeRestrictions, UxRestrictions.baseline);
      expect(state.isFullyRestricted, isFalse);
    });

    test('the unavailable constructor ignores any flags offered to it', () {
      final state = CarUxRestrictionsState.unavailable(RestrictionsSource.disconnected);

      expect(state.activeRestrictions, UxRestrictions.fullyRestricted);
      expect(state.requiresDistractionOptimization, isTrue);
      expect(state.isTrustworthy, isFalse);
    });
  });

  group('polarity: the ?? false default must fail CLOSED', () {
    // Under "allowed" polarity the `?? false` shorthand denies; under "restricted" it permits.

    test('allows() is false on an untrusted state, whatever the flags said', () {
      final untrusted = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'source': 'garbage',
      });

      expect(untrusted.allows(UxRestrictions.noVideo), isFalse);
      expect(untrusted.allowsVideo, isFalse);
      expect(untrusted.allowsKeyboard, isFalse);
    });

    test('the null-shorthand a caller will actually write fails closed', () {
      // Sourced through a function so the analyzer cannot fold the null away — this is the shape a
      // real call site has, where the value comes from a snapshot or a provider.
      CarUxRestrictionsState? maybe() => null;

      // The line that would ship. Under "allows" polarity the careless default denies.
      expect(maybe()?.allowsVideo ?? false, isFalse);

      // For contrast, the same habit under "is it restricted?" polarity would have PERMITTED:
      expect(maybe()?.hasAny(UxRestrictions.noVideo) ?? false, isFalse);
      // ^ false means "not restricted" -> video plays. Same shorthand, opposite outcome.
    });

    test('allows() is true only on a delivered, unrestricted reading', () {
      final parked = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'requiresDistractionOptimization': false,
        'source': 'delivered',
      });

      expect(parked.allowsVideo, isTrue);
      expect(parked.allowsKeyboard, isTrue);
      expect(parked.allows(UxRestrictions.limitContent), isTrue);
    });

    test('allows() respects individual flags on a delivered reading', () {
      final noVideo = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.noVideo,
        'requiresDistractionOptimization': true,
        'source': 'delivered',
      });

      expect(noVideo.allowsVideo, isFalse);
      expect(noVideo.allowsKeyboard, isTrue);
    });

    test('failClosed allows nothing', () {
      const s = CarUxRestrictionsState.failClosed;

      expect(s.allowsVideo, isFalse);
      expect(s.allowsKeyboard, isFalse);
      expect(s.allowsSetup, isFalse);
      expect(s.allowsFiltering, isFalse);
      expect(s.allowsDialpad, isFalse);
      expect(s.allowsTextMessage, isFalse);
      expect(s.allowsVoiceTranscription, isFalse);
      expect(s.allows(UxRestrictions.limitContent), isFalse);
    });
  });
}
