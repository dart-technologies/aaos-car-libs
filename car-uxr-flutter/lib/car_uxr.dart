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

/// Android Automotive UX restriction state for Flutter.
///
/// Driver-distraction gating is a safety control, so this library fails closed everywhere: until a
/// genuine reading arrives, and any time one cannot be trusted, the reported state is fully
/// restricted.
library;

export 'src/car_ux_restrictions.dart'
    show CarUxRestrictionsState, RestrictionsSource, UxRestrictions;
export 'src/car_uxr_builder.dart' show CarUxrBuilder;
export 'src/car_uxr_channel.dart' show CarUxr;
