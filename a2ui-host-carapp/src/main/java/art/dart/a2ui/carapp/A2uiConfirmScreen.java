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

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;

import art.dart.a2ui.policy.A2uiActionPolicy;

/**
 * The confirmation half of a consequential action. Confirm asks again rather than running the
 * action; the re-decision lives in {@code A2uiMessageProcessor.confirmAction}, and a refusal is
 * explained to the driver.
 */
public final class A2uiConfirmScreen extends Screen {

    private static final String TAG = "A2uiConfirmScreen";

    private final Confirmable mConfirmable;

    /** What the showcase screen supplies: the action to re-decide, and its description. */
    public interface Confirmable {
        /** Re-decides and, if still permitted, performs the action. */
        @NonNull
        A2uiActionPolicy.Decision confirm();

        /** Driver-facing description of what is being confirmed. */
        @NonNull
        String describe();
    }

    public A2uiConfirmScreen(@NonNull CarContext carContext, @NonNull Confirmable confirmable) {
        super(carContext);
        mConfirmable = confirmable;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        return new MessageTemplate.Builder(mConfirmable.describe())
                .setTitle("Confirm")
                .addAction(new Action.Builder()
                        .setTitle("Confirm")
                        .setOnClickListener(this::onConfirmed)
                        .build())
                .setHeaderAction(Action.BACK)
                .build();
    }

    private void onConfirmed() {
        A2uiActionPolicy.Decision decision = mConfirmable.confirm();
        Log.i(TAG, "confirmation answered; re-decided as " + decision);
        if (decision == A2uiActionPolicy.Decision.REFUSE) {
            // The state changed between asking and answering. Say so — the driver pressed Confirm
            // and is entitled to know the answer became no.
            CarToast.makeText(getCarContext(),
                    "Not available now — conditions changed", CarToast.LENGTH_LONG).show();
        } else {
            CarToast.makeText(getCarContext(),
                    mConfirmable.describe() + " — done", CarToast.LENGTH_SHORT).show();
        }
        getScreenManager().pop();
    }
}
