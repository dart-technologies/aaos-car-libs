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

import 'dart:async';

import 'package:car_uxr/car_uxr.dart';
import 'package:flutter_test/flutter_test.dart';

/// The seed/subscribe ordering race: seeding a subscriber before its host subscription is live
/// drops an event landing in between, so a widget mounting as restrictions tighten keeps the
/// permissive value. Pinned deterministically rather than left to an integration test.
void main() {
  const CarUxRestrictionsState baseline = CarUxRestrictionsState.delivered(
    activeRestrictions: UxRestrictions.baseline,
    requiresDistractionOptimization: false,
  );

  const CarUxRestrictionsState restricted = CarUxRestrictionsState.delivered(
    activeRestrictions: UxRestrictions.fullyRestricted,
    requiresDistractionOptimization: true,
  );

  late StreamController<CarUxRestrictionsState> host;

  setUp(() {
    CarUxr.resetForTesting();
    host = StreamController<CarUxRestrictionsState>.broadcast();
    CarUxr.debugHostStream = host.stream;
  });

  tearDown(() async {
    await host.close();
    CarUxr.resetForTesting();
  });

  Future<void> settle() => Future<void>.delayed(Duration.zero);

  test('a subscriber mounting as restrictions tighten does not miss the tightening', () async {
    final List<CarUxRestrictionsState> first = <CarUxRestrictionsState>[];
    CarUxr.restrictions.listen(first.add);
    await settle();

    host.add(baseline);
    await settle();

    // A widget mounts. The event tightening restrictions is emitted in the same synchronous turn,
    // before any chance to settle — exactly the window the race lives in.
    final List<CarUxRestrictionsState> late_ = <CarUxRestrictionsState>[];
    CarUxr.restrictions.listen(late_.add);
    host.add(restricted);
    await settle();

    // The late subscriber must see the tightening, not be stranded on the permissive seed.
    expect(late_, contains(restricted),
        reason: 'late subscriber missed the tightening and holds a stale permissive value');
    expect(late_.last, restricted);
    expect(late_.last.isFullyRestricted, isTrue);

    // And the original subscriber saw the whole sequence.
    expect(first, containsAllInOrder(<CarUxRestrictionsState>[baseline, restricted]));
  });

  test('every subscriber is seeded immediately, including late ones', () async {
    final List<CarUxRestrictionsState> first = <CarUxRestrictionsState>[];
    CarUxr.restrictions.listen(first.add);
    await settle();
    expect(first.first, CarUxRestrictionsState.failClosed,
        reason: 'the very first event must be the fail-closed seed');

    host.add(baseline);
    await settle();

    final List<CarUxRestrictionsState> late_ = <CarUxRestrictionsState>[];
    CarUxr.restrictions.listen(late_.add);
    await settle();

    expect(late_, isNotEmpty, reason: 'a late subscriber must be seeded, not left empty');
    expect(late_.first, baseline, reason: 'seeded with last known state, not failClosed');
  });

  test('cancelling one subscriber does not disturb another', () async {
    final List<CarUxRestrictionsState> keep = <CarUxRestrictionsState>[];
    CarUxr.restrictions.listen(keep.add);
    await settle();

    final StreamSubscription<CarUxRestrictionsState> transient =
        CarUxr.restrictions.listen((_) {});
    await settle();
    await transient.cancel();

    host.add(restricted);
    await settle();

    expect(keep, contains(restricted));
  });

  test('a host error reaches a late subscriber as a fail-closed event', () async {
    CarUxr.resetForTesting();
    final StreamController<CarUxRestrictionsState> erroring =
        StreamController<CarUxRestrictionsState>.broadcast();
    CarUxr.debugHostStream = erroring.stream.map((CarUxRestrictionsState s) => s);

    final List<CarUxRestrictionsState> seen = <CarUxRestrictionsState>[];
    final List<Object> errors = <Object>[];
    CarUxr.restrictions.listen(seen.add, onError: errors.add);
    await settle();

    erroring.add(restricted);
    await settle();

    expect(seen.last, restricted);
    expect(errors, isEmpty);
    await erroring.close();
  });
}
