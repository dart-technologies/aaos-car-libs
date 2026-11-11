/*
 * Copyright (C) 2026 Dart Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package art.dart.a2ui.carapp;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

import art.dart.a2ui.poi.PoiFlow;
import art.dart.a2ui.poi.PoiRepository;

/** One connection from the host. Hands back the showcase screen, or POI search on request. */
public final class A2uiSession extends Session {

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        if (PoiFlow.isPoiLaunch(intent)) {
            return new A2uiPoiSearchScreen(getCarContext(), PoiRepository.anchored());
        }
        return new A2uiShowcaseScreen(getCarContext());
    }
}
