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

import 'package:meta/meta.dart';

/// UX restriction flags, mirroring `android.car.drivingstate.CarUxRestrictions`.
///
/// Values read from `android.car.jar` with `javap -constants` (identical across API 34-37), not
/// from documentation, whose widely copied table is wrong in most rows. See [fullyRestricted].
abstract final class UxRestrictions {
  /// No restrictions. 0x0.
  static const int baseline = 0x0;

  /// 0x1.
  static const int noDialpad = 0x1;

  /// 0x2.
  static const int noFiltering = 0x2;

  /// 0x4.
  static const int limitStringLength = 0x4;

  /// 0x8.
  static const int noKeyboard = 0x8;

  /// 0x10.
  static const int noVideo = 0x10;

  /// 0x20.
  static const int limitContent = 0x20;

  /// 0x40.
  static const int noSetup = 0x40;

  /// 0x80.
  static const int noTextMessage = 0x80;

  /// 0x100.
  static const int noVoiceTranscription = 0x100;

  /// 0x1ff: the union of the flags above, not a flag of its own. Never test it with `==` on the
  /// whole field or with `& != 0`; use [CarUxRestrictionsState.isFullyRestricted] or
  /// [CarUxRestrictionsState.hasAll].
  static const int fullyRestricted = 0x1ff;
}

/// Where a [CarUxRestrictionsState] came from. A fail-closed default is byte-identical to a genuine
/// fully restricted reading; anything other than [delivered] means the conservative fallback.
enum RestrictionsSource {
  /// Car service has never delivered a reading.
  neverDelivered,

  /// A genuine reading from car service.
  delivered,

  /// Car service delivered readings and then went away.
  disconnected;

  /// Parses the wire value, failing closed: anything unrecognised, null or malformed is
  /// [neverDelivered], never optimistically trusted.
  static RestrictionsSource fromWire(Object? raw) {
    return switch (raw) {
      'delivered' => RestrictionsSource.delivered,
      'disconnected' => RestrictionsSource.disconnected,
      _ => RestrictionsSource.neverDelivered,
    };
  }
}

/// An immutable snapshot of the UX restrictions for one display.
///
/// Only [CarUxRestrictionsState.delivered] can produce anything other than fully restricted; every
/// other route ([unavailable], [failClosed], a payload [fromMap] cannot fully parse) is forced to
/// [UxRestrictions.fullyRestricted], so "permissive but untrusted" is unrepresentable.
@immutable
class CarUxRestrictionsState {
  const CarUxRestrictionsState._({
    required this.activeRestrictions,
    required this.requiresDistractionOptimization,
    required this.source,
    required this.displayId,
    required this.maxCumulativeContentItems,
    required this.maxRestrictedStringLength,
  });

  /// A genuine reading from car service. The only constructor that can produce a permissive state.
  const CarUxRestrictionsState.delivered({
    required this.activeRestrictions,
    required this.requiresDistractionOptimization,
    this.displayId = 0,
    this.maxCumulativeContentItems = -1,
    this.maxRestrictedStringLength = -1,
  }) : source = RestrictionsSource.delivered;

  /// No trustworthy reading. Always fully restricted, whatever flags may have accompanied it.
  ///
  /// [source] is recorded for diagnostics only — it cannot make the state more permissive.
  factory CarUxRestrictionsState.unavailable(RestrictionsSource source, {int displayId = 0}) {
    return CarUxRestrictionsState._(
      activeRestrictions: UxRestrictions.fullyRestricted,
      requiresDistractionOptimization: true,
      source: source == RestrictionsSource.delivered
          // Defensive: "delivered but unavailable" is a contradiction. Treat it as no reading.
          ? RestrictionsSource.neverDelivered
          : source,
      displayId: displayId,
      maxCumulativeContentItems: -1,
      maxRestrictedStringLength: -1,
    );
  }

  /// The bitmask of active `UxRestrictions` flags.
  final int activeRestrictions;

  /// Whether the platform requires distraction-optimised presentation.
  final bool requiresDistractionOptimization;

  /// Provenance of this reading. See [RestrictionsSource].
  final RestrictionsSource source;

  /// The display these restrictions apply to. Restrictions are per-display.
  final int displayId;

  /// Item cap under [UxRestrictions.limitContent]. `-1` when unknown.
  final int maxCumulativeContentItems;

  /// Character cap under [UxRestrictions.limitStringLength]. `-1` when unknown.
  final int maxRestrictedStringLength;

  /// The value used whenever the signal cannot be trusted: fully restricted, distraction
  /// optimisation required, provenance [RestrictionsSource.neverDelivered]. A null must never
  /// become "unrestricted".
  static const CarUxRestrictionsState failClosed = CarUxRestrictionsState._(
    activeRestrictions: UxRestrictions.fullyRestricted,
    requiresDistractionOptimization: true,
    source: RestrictionsSource.neverDelivered,
    displayId: 0,
    maxCumulativeContentItems: -1,
    maxRestrictedStringLength: -1,
  );

  /// Whether **every** bit in [mask] is active.
  ///
  /// The correct test for composite masks, above all [UxRestrictions.fullyRestricted].
  bool hasAll(int mask) => (activeRestrictions & mask) == mask;

  /// Whether **any** bit in [mask] is active.
  ///
  /// Correct for single flags and for "restrict if any of these apply" checks. Wrong for
  /// [UxRestrictions.fullyRestricted] — see [hasAll].
  bool hasAny(int mask) => (activeRestrictions & mask) != 0;

  /// Whether the vehicle is fully restricted, tested as the composite it is.
  bool get isFullyRestricted => hasAll(UxRestrictions.fullyRestricted);

  /// Whether this reading came from car service rather than being a fallback.
  bool get isTrustworthy => source == RestrictionsSource.delivered;

  /// Whether none of the flags in [mask] are active, i.e. whether the thing they gate is permitted.
  /// Always false unless [isTrustworthy].
  ///
  /// Prefer this to [hasAny]: with the usual `?? false` shorthand, "is it allowed?" denies on null
  /// while "is it restricted?" permits.
  ///
  /// ```dart
  /// if (uxr?.allowsVideo ?? false) showVideo();   // null -> video hidden
  /// ```
  bool allows(int mask) => isTrustworthy && (activeRestrictions & mask) == 0;

  /// Video playback is permitted on this surface.
  bool get allowsVideo => allows(UxRestrictions.noVideo);

  /// Free text entry is permitted.
  bool get allowsKeyboard => allows(UxRestrictions.noKeyboard);

  /// Multi-step configuration is permitted.
  bool get allowsSetup => allows(UxRestrictions.noSetup);

  /// Narrowing a result set is permitted.
  bool get allowsFiltering => allows(UxRestrictions.noFiltering);

  /// A dialpad may be shown.
  bool get allowsDialpad => allows(UxRestrictions.noDialpad);

  /// Message content may be displayed.
  bool get allowsTextMessage => allows(UxRestrictions.noTextMessage);

  /// Voice transcription may be displayed.
  bool get allowsVoiceTranscription => allows(UxRestrictions.noVoiceTranscription);

  /// Content length is unconstrained.
  bool get allowsUnlimitedContent => allows(UxRestrictions.limitContent);

  /// String length is unconstrained.
  bool get allowsFullStringLength => allows(UxRestrictions.limitStringLength);

  /// Rebuilds from a platform map, failing closed on anything unexpected: total, with no throw and
  /// no null, since a malformed payload is indistinguishable from a hostile one.
  static CarUxRestrictionsState fromMap(Object? raw) {
    if (raw is! Map) return failClosed;

    final display = raw['displayId'];
    final int displayId = display is int ? display : 0;

    // Provenance first: anything short of a delivered reading discards the flags.
    final RestrictionsSource source = RestrictionsSource.fromWire(raw['source']);
    if (source != RestrictionsSource.delivered) {
      return CarUxRestrictionsState.unavailable(source, displayId: displayId);
    }

    final active = raw['activeRestrictions'];
    if (active is! int) {
      return CarUxRestrictionsState.unavailable(
          RestrictionsSource.neverDelivered, displayId: displayId);
    }

    final requiresDo = raw['requiresDistractionOptimization'];

    return CarUxRestrictionsState._(
      activeRestrictions: active,
      // A missing or non-bool flag must not read as "no optimisation required".
      requiresDistractionOptimization: requiresDo is bool ? requiresDo : true,
      source: RestrictionsSource.delivered,
      displayId: displayId,
      maxCumulativeContentItems: raw['maxCumulativeContentItems'] is int
          ? raw['maxCumulativeContentItems'] as int
          : -1,
      maxRestrictedStringLength: raw['maxRestrictedStringLength'] is int
          ? raw['maxRestrictedStringLength'] as int
          : -1,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CarUxRestrictionsState &&
          other.activeRestrictions == activeRestrictions &&
          other.requiresDistractionOptimization == requiresDistractionOptimization &&
          other.source == source &&
          other.displayId == displayId &&
          other.maxCumulativeContentItems == maxCumulativeContentItems &&
          other.maxRestrictedStringLength == maxRestrictedStringLength;

  @override
  int get hashCode => Object.hash(
        activeRestrictions,
        requiresDistractionOptimization,
        source,
        displayId,
        maxCumulativeContentItems,
        maxRestrictedStringLength,
      );

  @override
  String toString() => 'CarUxRestrictionsState('
      'active: 0x${activeRestrictions.toRadixString(16)}, '
      'requiresDO: $requiresDistractionOptimization, '
      'source: ${source.name}, display: $displayId)';
}
