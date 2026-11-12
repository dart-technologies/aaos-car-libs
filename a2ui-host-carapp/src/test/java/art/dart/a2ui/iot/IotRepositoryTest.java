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
package art.dart.a2ui.iot;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The offline fixture: two lights and a thermostat, toggled and leveled with clamps. */
@RunWith(RobolectricTestRunner.class)
public class IotRepositoryTest {

    @Test
    public void anchoredSetHasTwoLightsAndThermostat() {
        IotRepository repository = IotRepository.anchored();

        assertThat(repository.devices()).hasSize(3);
        assertThat(repository.device("living_room_light").isOn()).isTrue();
        assertThat(repository.device("porch_light").isOn()).isFalse();
        assertThat(repository.device("hall_thermostat").getType())
                .isEqualTo(IotDevice.Type.THERMOSTAT);
    }

    @Test
    public void toggleFlipsAndUnknownIdsAreRefused() {
        IotRepository repository = IotRepository.anchored();

        assertThat(repository.toggle("porch_light")).isTrue();
        assertThat(repository.device("porch_light").isOn()).isTrue();
        assertThat(repository.toggle("porch_light")).isTrue();
        assertThat(repository.device("porch_light").isOn()).isFalse();

        assertThat(repository.toggle("toaster")).isFalse();
        assertThat(repository.device("toaster")).isNull();
    }

    @Test
    public void levelsClampToTheType() {
        IotRepository repository = IotRepository.anchored();

        assertThat(repository.setLevel("living_room_light", 150)).isTrue();
        assertThat(repository.device("living_room_light").getLevel()).isEqualTo(100);
        assertThat(repository.setLevel("living_room_light", -5)).isTrue();
        assertThat(repository.device("living_room_light").getLevel()).isEqualTo(0);

        // Half-degrees, 15-30 C.
        assertThat(repository.setLevel("hall_thermostat", 200)).isTrue();
        assertThat(repository.device("hall_thermostat").getLevel()).isEqualTo(60);
        assertThat(repository.setLevel("hall_thermostat", 0)).isTrue();
        assertThat(repository.device("hall_thermostat").getLevel()).isEqualTo(30);

        assertThat(repository.setLevel("toaster", 50)).isFalse();
    }
}
