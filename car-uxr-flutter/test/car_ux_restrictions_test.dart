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

void main() {
  group('fails closed on anything unrecognised', () {
    // Every unknown must land on failClosed; a null must never become "no restrictions".

    test('null payload', () {
      expect(CarUxRestrictionsState.fromMap(null), CarUxRestrictionsState.failClosed);
    });

    test('non-map payload', () {
      expect(CarUxRestrictionsState.fromMap('nope'), CarUxRestrictionsState.failClosed);
      expect(CarUxRestrictionsState.fromMap(42), CarUxRestrictionsState.failClosed);
      expect(CarUxRestrictionsState.fromMap(<int>[1, 2]), CarUxRestrictionsState.failClosed);
    });

    test('empty map', () {
      expect(CarUxRestrictionsState.fromMap(<String, Object?>{}),
          CarUxRestrictionsState.failClosed);
    });

    test('missing activeRestrictions', () {
      expect(
        CarUxRestrictionsState.fromMap(<String, Object?>{
          'requiresDistractionOptimization': false,
          'source': 'delivered',
        }),
        CarUxRestrictionsState.failClosed,
      );
    });

    test('activeRestrictions of the wrong type', () {
      expect(
        CarUxRestrictionsState.fromMap(<String, Object?>{'activeRestrictions': '0'}),
        CarUxRestrictionsState.failClosed,
      );
    });

    test('unknown source string does not become delivered', () {
      final state = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'requiresDistractionOptimization': false,
        'source': 'some_future_value',
      });

      expect(state.source, RestrictionsSource.neverDelivered);
      expect(state.isTrustworthy, isFalse);
    });

    test('missing requiresDistractionOptimization defaults to REQUIRED', () {
      final state = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'source': 'delivered',
      });

      // The dangerous default would be false.
      expect(state.requiresDistractionOptimization, isTrue);
    });

    test('the fail-closed value is genuinely restrictive', () {
      const s = CarUxRestrictionsState.failClosed;

      expect(s.activeRestrictions, UxRestrictions.fullyRestricted);
      expect(s.activeRestrictions, isNot(UxRestrictions.baseline));
      expect(s.requiresDistractionOptimization, isTrue);
      expect(s.isFullyRestricted, isTrue);
      expect(s.source, RestrictionsSource.neverDelivered);
      expect(s.isTrustworthy, isFalse);
    });
  });

  group('composite mask handling', () {
    test('fullyRestricted is the union of the ten flags, not a flag', () {
      const union = UxRestrictions.noDialpad |
          UxRestrictions.noFiltering |
          UxRestrictions.limitStringLength |
          UxRestrictions.noKeyboard |
          UxRestrictions.noVideo |
          UxRestrictions.limitContent |
          UxRestrictions.noSetup |
          UxRestrictions.noTextMessage |
          UxRestrictions.noVoiceTranscription;

      expect(UxRestrictions.fullyRestricted, 0x1ff);
      expect(union, UxRestrictions.fullyRestricted);
    });

    test('a single bit does NOT satisfy the composite', () {
      final onlyNoVideo = _state(UxRestrictions.noVideo);

      expect(onlyNoVideo.hasAny(UxRestrictions.fullyRestricted), isTrue); // the wrong test
      expect(onlyNoVideo.hasAll(UxRestrictions.fullyRestricted), isFalse); // the right one
      expect(onlyNoVideo.isFullyRestricted, isFalse);
    });

    test('one bit short of the composite is not fully restricted', () {
      final almost = _state(UxRestrictions.fullyRestricted & ~UxRestrictions.noKeyboard);

      expect(almost.isFullyRestricted, isFalse);
    });

    test('survives future bits that an equality test would fail on', () {
      const futureBit = 0x200;
      final future = _state(UxRestrictions.fullyRestricted | futureBit);

      expect(future.activeRestrictions == UxRestrictions.fullyRestricted, isFalse);
      expect(future.isFullyRestricted, isTrue);
    });

    test('hasAny is correct for single flags', () {
      final s = _state(UxRestrictions.noSetup);

      expect(s.hasAny(UxRestrictions.noSetup | UxRestrictions.noKeyboard), isTrue);
      expect(s.hasAny(UxRestrictions.limitContent), isFalse);
    });
  });

  group('round trip', () {
    test('a well-formed delivered payload survives intact', () {
      final state = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.noVideo | UxRestrictions.limitContent,
        'requiresDistractionOptimization': true,
        'source': 'delivered',
        'displayId': 2,
        'maxCumulativeContentItems': 8,
        'maxRestrictedStringLength': 120,
      });

      expect(state.activeRestrictions, UxRestrictions.noVideo | UxRestrictions.limitContent);
      expect(state.source, RestrictionsSource.delivered);
      expect(state.isTrustworthy, isTrue);
      expect(state.displayId, 2);
      expect(state.maxCumulativeContentItems, 8);
      expect(state.maxRestrictedStringLength, 120);
      expect(state.isFullyRestricted, isFalse);
    });

    test('a genuine baseline reading is distinguishable from a fallback', () {
      final parked = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.baseline,
        'requiresDistractionOptimization': false,
        'source': 'delivered',
      });

      expect(parked.activeRestrictions, UxRestrictions.baseline);
      expect(parked.isTrustworthy, isTrue);
      expect(parked, isNot(CarUxRestrictionsState.failClosed));
    });

    test('a genuine FULLY_RESTRICTED reading differs from failClosed only by provenance', () {
      final moving = CarUxRestrictionsState.fromMap(<String, Object?>{
        'activeRestrictions': UxRestrictions.fullyRestricted,
        'requiresDistractionOptimization': true,
        'source': 'disconnected',
      });

      expect(moving.activeRestrictions,
          CarUxRestrictionsState.failClosed.activeRestrictions);
      expect(moving.source, isNot(CarUxRestrictionsState.failClosed.source));
    });
  });
}

CarUxRestrictionsState _state(int active) => CarUxRestrictionsState.delivered(
      activeRestrictions: active,
      requiresDistractionOptimization: active != UxRestrictions.baseline,
    );
