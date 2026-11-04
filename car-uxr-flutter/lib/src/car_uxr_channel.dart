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

import 'package:flutter/services.dart';
import 'package:meta/meta.dart';

import 'car_ux_restrictions.dart';

/// Android Automotive UX restriction state.
///
/// [restrictions] is the API: subscribing delivers the current state immediately, then every
/// change. [current] is for one-shot questions and must never seed a listener, since a change
/// landing between the read and the subscribe would be lost.
///
/// Everything unknown (missing plugin, channel error, malformed payload) resolves to
/// [CarUxRestrictionsState.failClosed], delivered as an event rather than a stream error so an
/// `onData`-only consumer cannot keep a permissive value.
abstract final class CarUxr {
  static const MethodChannel _methods = MethodChannel('art.dart.car_uxr/methods');
  static const EventChannel _events = EventChannel('art.dart.car_uxr/restrictions');

  /// Most recent state seen on this isolate, starting fail-closed. Replayed to each new
  /// subscriber, because the shared host subscription seeds only the first.
  static CarUxRestrictionsState _last = CarUxRestrictionsState.failClosed;

  static Stream<CarUxRestrictionsState>? _raw;

  /// Testing only: substitutes the host stream so the seed/subscribe race is deterministic.
  @visibleForTesting
  static Stream<CarUxRestrictionsState>? debugHostStream;

  static Stream<CarUxRestrictionsState> get _hostStream {
    final Stream<CarUxRestrictionsState>? override = debugHostStream;
    if (override != null) return override;
    return _raw ??= _events.receiveBroadcastStream().transform(
          StreamTransformer<dynamic, CarUxRestrictionsState>.fromHandlers(
            handleData: (dynamic event, EventSink<CarUxRestrictionsState> sink) {
              sink.add(CarUxRestrictionsState.fromMap(event));
            },
            handleError: (Object error, StackTrace stack,
                EventSink<CarUxRestrictionsState> sink) {
              // Do not forward the error. A consumer with no onError handler would keep the last
              // value, which may be permissive. Emit the safe state instead.
              sink.add(CarUxRestrictionsState.failClosed);
            },
          ),
        );
  }

  /// The current UX restrictions for this Flutter view's display, then every change. The first
  /// event is immediate and never null: the last-known state, or [CarUxRestrictionsState.failClosed].
  ///
  /// Subscribe first, seed second: seeding before the host subscription is live leaves a window in
  /// which a tightening event is dropped. Pinned by `test/seed_subscribe_race_test.dart`.
  static Stream<CarUxRestrictionsState> get restrictions {
    late final StreamController<CarUxRestrictionsState> out;
    StreamSubscription<CarUxRestrictionsState>? sub;

    out = StreamController<CarUxRestrictionsState>(
      onListen: () {
        // 1. Subscribe, so nothing emitted from here on can be lost.
        sub = _hostStream.listen(
          (CarUxRestrictionsState state) {
            _last = state;
            out.add(state);
          },
          // Belt and braces: _hostStream already converts channel errors into fail-closed events,
          // but a substituted stream may not. Never surface an error without a safe state.
          onError: (Object _, StackTrace __) => out.add(CarUxRestrictionsState.failClosed),
          onDone: () => out.close(),
        );
        // 2. Only now seed, into a live pipeline.
        out.add(_last);
      },
      onCancel: () => sub?.cancel(),
    );

    return out.stream;
  }

  /// A one-shot read. **Never use this to seed a listener** — see the class docs.
  ///
  /// Fails closed on a missing plugin or any platform error.
  static Future<CarUxRestrictionsState> current() async {
    try {
      final Object? reply = await _methods.invokeMethod<Object?>('getCurrentRestrictions');
      final CarUxRestrictionsState state = CarUxRestrictionsState.fromMap(reply);
      _last = state;
      return state;
    } on MissingPluginException {
      // Plugin not registered — e.g. running on a non-automotive host, or in a test harness.
      return CarUxRestrictionsState.failClosed;
    } on PlatformException {
      return CarUxRestrictionsState.failClosed;
    }
  }

  /// Resets cached state. Testing only.
  @visibleForTesting
  static void resetForTesting() {
    _last = CarUxRestrictionsState.failClosed;
    _raw = null;
    debugHostStream = null;
  }
}
