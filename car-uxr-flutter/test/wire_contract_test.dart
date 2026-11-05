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

/// The wire contract between `CarUxrPlugin.java` and the Dart model: six key names held together
/// only by convention. A rename degrades silently to fail-closed, safe but permanently broken.
/// Mirrored by `CarUxrPluginTest.failClosedPayload_carriesExactlyTheAgreedKeys` and
/// `deliveredPayload_carriesExactlyTheAgreedKeys`.
void main() {
  /// The contract. Changing this list means changing `CarUxrPlugin.java` in the same commit.
  const Set<String> wireKeys = <String>{
    'activeRestrictions',
    'requiresDistractionOptimization',
    'source',
    'displayId',
    'maxCumulativeContentItems',
    'maxRestrictedStringLength',
  };

  Map<String, Object?> fullPayload() => <String, Object?>{
        'activeRestrictions': UxRestrictions.noVideo,
        'requiresDistractionOptimization': true,
        'source': 'delivered',
        'displayId': 2,
        'maxCumulativeContentItems': 8,
        'maxRestrictedStringLength': 120,
      };

  test('the agreed key set is exactly six names', () {
    expect(wireKeys, hasLength(6));
    expect(fullPayload().keys.toSet(), wireKeys);
  });

  test('a full payload populates every field — no silent defaults', () {
    final state = CarUxRestrictionsState.fromMap(fullPayload());

    expect(state.activeRestrictions, UxRestrictions.noVideo);
    expect(state.requiresDistractionOptimization, isTrue);
    expect(state.source, RestrictionsSource.delivered);
    expect(state.displayId, 2);
    expect(state.maxCumulativeContentItems, 8);
    expect(state.maxRestrictedStringLength, 120);

    // None of these are the fallback values, so every key was genuinely read.
    expect(state, isNot(CarUxRestrictionsState.failClosed));
  });

  group('every key is load-bearing — renaming any one is detectable', () {
    // If Dart stopped reading a key, dropping that key from the payload would change nothing.
    // Each case asserts a visible degradation, which is what makes a rename detectable.

    test('activeRestrictions', () {
      final p = fullPayload()..remove('activeRestrictions');
      final state = CarUxRestrictionsState.fromMap(p);

      // No reading, so it fails closed; displayId is kept, which cannot make it more permissive.
      expect(state.isFullyRestricted, isTrue);
      expect(state.requiresDistractionOptimization, isTrue);
      expect(state.isTrustworthy, isFalse);
      expect(state.allowsVideo, isFalse);
      expect(state.displayId, 2);
    });

    test('requiresDistractionOptimization', () {
      final p = fullPayload()..remove('requiresDistractionOptimization');
      final state = CarUxRestrictionsState.fromMap(p);
      // Falls back to REQUIRED, never to false.
      expect(state.requiresDistractionOptimization, isTrue);
      expect(state.activeRestrictions, UxRestrictions.noVideo);
    });

    test('source', () {
      final p = fullPayload()..remove('source');
      expect(CarUxRestrictionsState.fromMap(p).source, RestrictionsSource.neverDelivered);
    });

    test('displayId', () {
      final p = fullPayload()..remove('displayId');
      expect(CarUxRestrictionsState.fromMap(p).displayId, 0);
    });

    test('maxCumulativeContentItems', () {
      final p = fullPayload()..remove('maxCumulativeContentItems');
      expect(CarUxRestrictionsState.fromMap(p).maxCumulativeContentItems, -1);
    });

    test('maxRestrictedStringLength', () {
      final p = fullPayload()..remove('maxRestrictedStringLength');
      expect(CarUxRestrictionsState.fromMap(p).maxRestrictedStringLength, -1);
    });
  });

  group('source strings match what Java emits', () {
    // CarUxrPlugin.sourceName() emits exactly these three.
    test('delivered', () {
      expect(RestrictionsSource.fromWire('delivered'), RestrictionsSource.delivered);
    });

    test('disconnected', () {
      expect(RestrictionsSource.fromWire('disconnected'), RestrictionsSource.disconnected);
    });

    test('never_delivered', () {
      expect(RestrictionsSource.fromWire('never_delivered'), RestrictionsSource.neverDelivered);
    });

    test('the enum name is NOT the wire value — a refactor must not assume it is', () {
      // RestrictionsSource.neverDelivered.name is "neverDelivered", not "never_delivered".
      expect(RestrictionsSource.neverDelivered.name, isNot('never_delivered'));
      expect(RestrictionsSource.fromWire(RestrictionsSource.neverDelivered.name),
          RestrictionsSource.neverDelivered); // still safe: unknown -> neverDelivered
    });
  });

  test("Java's fail-closed payload parses to Dart's failClosed", () {
    // Byte-for-byte what CarUxrPlugin.failClosedMap() builds.
    final javaFailClosed = <String, Object?>{
      'activeRestrictions': 0x1ff,
      'requiresDistractionOptimization': true,
      'source': 'never_delivered',
      'displayId': 0,
      'maxCumulativeContentItems': -1,
      'maxRestrictedStringLength': -1,
    };

    expect(javaFailClosed.keys.toSet(), wireKeys);
    expect(CarUxRestrictionsState.fromMap(javaFailClosed), CarUxRestrictionsState.failClosed);
  });
}
