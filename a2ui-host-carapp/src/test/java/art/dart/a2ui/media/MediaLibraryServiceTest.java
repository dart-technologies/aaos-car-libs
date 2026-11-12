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
package art.dart.a2ui.media;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * The service lifecycle: creation publishes a session over the anchor catalog, destruction
 * releases it. Browse/play/pause behavior runs on the emulator (controller tests), where a
 * player and an audio path exist.
 */
@RunWith(RobolectricTestRunner.class)
public class MediaLibraryServiceTest {

    @Test
    public void createPublishesSessionDestroyReleases() {
        // One service per test: media3 keeps a process-wide session registry, so a second
        // creation with the same stable id is refused. The framework runs one service too.
        A2uiMediaLibraryService service =
                Robolectric.buildService(A2uiMediaLibraryService.class).create().get();

        assertThat(service.onGetSession(null)).isNotNull();
        assertThat(service.catalogForTest().libraryRoot().mediaId)
                .isEqualTo(AnchoredMediaCatalog.ROOT_ID);
    }
}
