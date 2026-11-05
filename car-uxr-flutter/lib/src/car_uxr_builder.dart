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

import 'package:flutter/widgets.dart';

import 'car_ux_restrictions.dart';
import 'car_uxr_channel.dart';

/// Rebuilds when UX restrictions change; the documented way to consume this library.
///
/// [builder] receives a non-nullable [CarUxRestrictionsState] that is
/// [CarUxRestrictionsState.failClosed] until a genuine reading arrives, so there is no null to
/// default (`snapshot.data?.hasAny(...) ?? false` would permit).
///
/// ```dart
/// CarUxrBuilder(
///   builder: (context, uxr) => uxr.allowsVideo ? const VideoSurface() : const NowPlayingText(),
/// )
/// ```
class CarUxrBuilder extends StatelessWidget {
  const CarUxrBuilder({super.key, required this.builder});

  /// Called with the current restrictions. Never null; fail-closed until a reading arrives.
  final Widget Function(BuildContext context, CarUxRestrictionsState uxr) builder;

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<CarUxRestrictionsState>(
      stream: CarUxr.restrictions,
      initialData: CarUxRestrictionsState.failClosed,
      builder: (BuildContext context, AsyncSnapshot<CarUxRestrictionsState> snapshot) {
        // The stream seeds itself, so data is populated from the first frame. The ?? is a last
        // resort that still denies — an error state must never render as permissive.
        return builder(context, snapshot.data ?? CarUxRestrictionsState.failClosed);
      },
    );
  }
}
