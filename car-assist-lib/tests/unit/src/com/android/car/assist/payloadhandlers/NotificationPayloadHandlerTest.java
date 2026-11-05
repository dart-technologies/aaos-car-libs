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

package com.android.car.assist.payloadhandlers;

import static com.google.common.truth.Truth.assertThat;

import android.app.Notification;
import android.os.Bundle;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.test.core.app.ApplicationProvider;

import com.android.car.assist.CarVoiceInteractionSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * First tests for {@code car-assist-lib} — 659 lines, previously zero.
 *
 * <p>This module is what the voice assistant calls to read a notification payload and act on it:
 * extract the messages, find the reply action, fire it. It is on the path between "driver says
 * something" and "a message is sent", which makes its absent-value handling safety-relevant in the
 * same way the restriction predicates are.
 *
 * <p>The payload arrives as a {@link Bundle} across an IPC from an arbitrary app, so every read here
 * is a **partial-parse** surface — instance 5's shape in
 * {@code docs/ARCHITECTURE.md}. These tests concentrate on what the handler does when the
 * payload is missing, malformed, or half-populated, because that is the part no caller controls.
 */
@RunWith(RobolectricTestRunner.class)
public class NotificationPayloadHandlerTest {

    private NotificationPayloadHandler mHandler;

    @Before
    public void setUp() {
        mHandler = new NotificationPayloadHandler(ApplicationProvider.getApplicationContext());
    }

    private static Notification messagingNotification(String... messages) {
        Person sender = new Person.Builder().setName("Alice").setKey("alice").build();
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(sender);
        for (int i = 0; i < messages.length; i++) {
            style.addMessage(messages[i], 100L + i, sender);
        }
        return new NotificationCompat.Builder(
                ApplicationProvider.getApplicationContext(), "channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(style)
                .build();
    }

    // ---------------------------------------------------------------- absent payload

    /**
     * An empty Bundle must yield null, not throw. The assistant hands this straight through from
     * whatever the system delivered.
     */
    @Test
    public void getNotification_emptyBundle_returnsNull() {
        assertThat(mHandler.getNotification(new Bundle())).isNull();
    }

    @Test
    public void getStatusBarNotification_emptyBundle_returnsNull() {
        assertThat(mHandler.getStatusBarNotification(new Bundle())).isNull();
    }

    /**
     * The key present but holding the wrong type. {@code Bundle.getParcelable} returns null on a
     * type mismatch rather than throwing, so this must fall through the same path as absent.
     */
    @Test
    public void getNotification_wrongTypeUnderKey_returnsNull() {
        Bundle args = new Bundle();
        args.putString(CarVoiceInteractionSession.KEY_NOTIFICATION, "not a notification");

        assertThat(mHandler.getNotification(args)).isNull();
    }

    // ---------------------------------------------------------------- message extraction

    @Test
    public void getMessages_readsMessagingStyle() {
        List<NotificationCompat.MessagingStyle.Message> messages =
                mHandler.getMessages(messagingNotification("first", "second"));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText().toString()).isEqualTo("first");
        assertThat(messages.get(1).getText().toString()).isEqualTo("second");
    }

    /**
     * A notification with no MessagingStyle must yield an empty list, never null — the caller
     * iterates it directly.
     */
    @Test
    public void getMessages_nonMessagingNotification_returnsEmptyNotNull() {
        Notification plain = new NotificationCompat.Builder(
                ApplicationProvider.getApplicationContext(), "channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("plain")
                .build();

        List<NotificationCompat.MessagingStyle.Message> messages = mHandler.getMessages(plain);

        assertThat(messages).isNotNull();
        assertThat(messages).isEmpty();
    }

    @Test
    public void getMessages_messagingStyleWithNoMessages_returnsEmpty() {
        assertThat(mHandler.getMessages(messagingNotification())).isEmpty();
    }

    // ---------------------------------------------------------------- action lookup

    /**
     * Looking up a semantic action on a notification that has no actions at all must return null
     * rather than throwing on a null array — {@code Notification.actions} is null, not empty, when
     * no actions were added.
     */
    @Test
    public void getAction_notificationWithNoActions_returnsNull() {
        Notification plain = new NotificationCompat.Builder(
                ApplicationProvider.getApplicationContext(), "channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        assertThat(plain.actions).isNull();
        assertThat(mHandler.getAction(plain, Notification.Action.SEMANTIC_ACTION_REPLY)).isNull();
    }

    /**
     * CHARACTERISATION — the empty-Bundle path through the action lookup.
     *
     * <p>{@code getAction(Bundle, int)} resolves the notification first. With no notification in the
     * Bundle there is nothing to search, and the caller must get null rather than an exception.
     */
    @Test
    public void getAction_emptyBundle_returnsNullRatherThanThrowing() {
        assertThat(mHandler.getAction(new Bundle(), Notification.Action.SEMANTIC_ACTION_REPLY))
                .isNull();
    }

    // ---------------------------------------------------------------- fireAction

    /**
     * Firing a null action must report failure, not crash.
     *
     * <p>This is the end of the voice-reply path: if the assistant could not find a reply action,
     * {@code fireAction} is still called with whatever was found. Returning {@code false} lets the
     * caller tell the driver it did not work; throwing would take the assistant down mid-drive.
     */
    @Test
    public void fireAction_nullAction_returnsFalse() {
        assertThat(mHandler.fireAction(null, null)).isFalse();
    }
}
