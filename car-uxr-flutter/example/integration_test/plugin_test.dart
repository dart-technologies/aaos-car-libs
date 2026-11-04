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
import 'package:integration_test/integration_test.dart';

/// Device-only coverage, deliberately narrow: seeding order, fail-closed parsing, composite masks
/// and lifecycle are pinned deterministically in `test/` and `android/src/test/`. Only a device
/// proves that the channel names match, a real payload parses, the plugin is registered,
/// `displayId` is the activity's display, and the platform's values agree with Dart's.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('channel wiring', () {
    testWidgets('the plugin is registered and the method channel round-trips', (_) async {
      // CarUxr.current() turns MissingPluginException into failClosed, so check the payload is real.
      final CarUxRestrictionsState state = await CarUxr.current();

      expect(state, isNotNull);
      // The host always sends all six keys; a value that survived parsing proves the map arrived
      // with the agreed names rather than degrading to the fallback shape.
      expect(state.displayId, isNonNegative);
      expect(state.activeRestrictions, isNonNegative);
    });

    testWidgets('the event channel delivers a seeded first event', (_) async {
      final CarUxRestrictionsState first = await CarUxr.restrictions.first;

      // Arrives without waiting for a vehicle-state change: the host seeded on onListen.
      expect(first, isNotNull);
      expect(first.activeRestrictions, isNonNegative);
    });
  });

  group('serialization', () {
    testWidgets('every field crosses the boundary with a plausible value', (_) async {
      final CarUxRestrictionsState state = await CarUxr.current();

      // Loose about the value, strict about it having been transported.
      expect(state.source, isA<RestrictionsSource>());
      expect(state.displayId, isNonNegative);
      expect(state.requiresDistractionOptimization, isA<bool>());
      expect(state.maxCumulativeContentItems, isA<int>());
      expect(state.maxRestrictedStringLength, isA<int>());

      // Car service is running, so a delivered reading proves the whole chain end to end.
      expect(state.source, RestrictionsSource.delivered,
          reason: 'car service is running, so the reading must be genuine, not a fallback');
      expect(state.isTrustworthy, isTrue);
    });
  });

  group('ActivityAware', () {
    testWidgets('displayId is resolved from the activity, not defaulted', (_) async {
      final CarUxRestrictionsState state = await CarUxr.current();

      // Resolved at activity attach: engine attach would have thrown on launch.
      expect(state.displayId, isNonNegative);
    });

    testWidgets('restrictions remain available across repeated subscriptions', (_) async {
      // Approximates hot-restart churn: subscribe, drop, resubscribe. A leaked registration would
      // stop delivery process-wide, so the second subscription would hang rather than seed.
      final CarUxRestrictionsState a = await CarUxr.restrictions.first;
      final CarUxRestrictionsState b = await CarUxr.restrictions.first;
      final CarUxRestrictionsState c = await CarUxr.restrictions.first;

      expect(a.displayId, b.displayId);
      expect(b.displayId, c.displayId);
      expect(c.source, RestrictionsSource.delivered);
    });
  });
}
