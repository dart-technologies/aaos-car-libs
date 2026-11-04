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
import 'package:flutter/material.dart';

void main() => runApp(const CarUxrExampleApp());

/// A readout of the plugin's state on a head unit, including which display this view is on and
/// whether the reading is genuine or a fail-closed fallback.
class CarUxrExampleApp extends StatelessWidget {
  const CarUxrExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      // Follow the head unit's day/night mode, matching the Android window's themes.
      theme: ThemeData.light(useMaterial3: true),
      darkTheme: ThemeData.dark(useMaterial3: true),
      themeMode: ThemeMode.system,
      home: Scaffold(
        appBar: AppBar(title: const Text('car_uxr')),
        // CarUxrBuilder, not StreamBuilder: the state is non-nullable and fail-closed.
        body: CarUxrBuilder(
          builder: (BuildContext context, CarUxRestrictionsState uxr) {
            return Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  _Row('display', '${uxr.displayId}', key: const Key('displayId')),
                  _Row(
                    'active',
                    '0x${uxr.activeRestrictions.toRadixString(16)}',
                    key: const Key('active'),
                  ),
                  _Row(
                    'requires DO',
                    '${uxr.requiresDistractionOptimization}',
                    key: const Key('requiresDO'),
                  ),
                  _Row('source', uxr.source.name, key: const Key('source')),
                  _Row(
                    'trustworthy',
                    '${uxr.isTrustworthy}',
                    key: const Key('trustworthy'),
                  ),
                  _Row(
                    'fully restricted',
                    '${uxr.isFullyRestricted}',
                    key: const Key('fullyRestricted'),
                  ),
                  const Divider(height: 32),
                  // The whole point, rendered: what this surface is allowed to be.
                  // "allows" polarity: permission requires a delivered reading.
                  if (uxr.allowsVideo)
                    const Text('video: allowed', key: Key('video'))
                  else
                    const Text('video: BLOCKED', key: Key('video')),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value, {super.key});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: <Widget>[
          SizedBox(width: 200, child: Text(label)),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}
