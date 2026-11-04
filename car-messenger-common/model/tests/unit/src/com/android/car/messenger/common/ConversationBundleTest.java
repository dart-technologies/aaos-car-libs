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

package com.android.car.messenger.common;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNull;

import android.os.Bundle;
import android.os.Parcelable;

import androidx.core.app.Person;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Round-trip and partial-parse tests for {@link Conversation}.
 *
 * <p><b>These are the first tests this class has ever had, and until this commit it was not even
 * compiled.</b> Upstream excluded {@code Conversation.java} from the Gradle build in the
 * "libs Dialer depends on" commit; 919 of the module's 1,014 lines were absent from every Gradle
 * build and from the AAR. Removing the exclusion is what made testing it possible.
 *
 * <p>{@code fromBundle} is the interesting surface. Its own javadoc says the Bundle is for
 * "transferring across processes", so the producer is not necessarily {@code toBundle} — it can be
 * any messaging app on the other side of an IPC. That makes every absent or malformed key a real
 * input, and this class covers what the parser does with them.
 */
@RunWith(RobolectricTestRunner.class)
public class ConversationBundleTest {

    private static final String ID = "conv-1";
    private static final String TITLE = "Team thread";

    private static Person person(String name) {
        return new Person.Builder().setName(name).setKey(name).build();
    }

    private static Conversation.Message message(String text, long ts, Person sender) {
        return new Conversation.Message(text, ts, sender);
    }

    private static Conversation.Builder builder() {
        return new Conversation.Builder(person("me"), ID)
                .setMessages(Collections.singletonList(message("hi", 100L, person("alice"))))
                .setParticipants(Collections.singletonList(person("alice")));
    }

    // -------------------------------------------------------------------------------------
    // The happy path: what toBundle writes, fromBundle must read back
    // -------------------------------------------------------------------------------------

    @Test
    public void roundTrip_preservesEveryField() {
        Conversation original = builder()
                .setConversationTitle(TITLE)
                .setUnreadCount(4)
                .setMuted(true)
                .build();

        Conversation restored = Conversation.fromBundle(original.toBundle());

        assertThat(restored).isNotNull();
        assertThat(restored.getId()).isEqualTo(ID);
        assertThat(restored.getConversationTitle()).isEqualTo(TITLE);
        assertThat(restored.getUnreadCount()).isEqualTo(4);
        assertThat(restored.isMuted()).isTrue();
        assertThat(restored.getUser().getName().toString()).isEqualTo("me");
        assertThat(restored.getMessages()).hasSize(1);
        assertThat(restored.getMessages().get(0).getText()).isEqualTo("hi");
        assertThat(restored.getMessages().get(0).getTimestamp()).isEqualTo(100L);
        assertThat(restored.getParticipants()).hasSize(1);
    }

    @Test
    public void roundTrip_preservesMultipleMessagesInOrder() {
        Conversation original = builder()
                .setMessages(Arrays.asList(
                        message("first", 1L, person("alice")),
                        message("second", 2L, person("bob")),
                        message("third", 3L, person("alice"))))
                .build();

        Conversation restored = Conversation.fromBundle(original.toBundle());

        assertThat(restored).isNotNull();
        assertThat(restored.getMessages()).hasSize(3);
        assertThat(restored.getMessages().get(0).getText()).isEqualTo("first");
        assertThat(restored.getMessages().get(2).getText()).isEqualTo("third");
    }

    // -------------------------------------------------------------------------------------
    // Required keys: absent -> null. This is the fail-closed half, and it is correct.
    // -------------------------------------------------------------------------------------

    @Test
    public void fromBundle_null_returnsNull() {
        assertNull(Conversation.fromBundle(null));
    }

    @Test
    public void fromBundle_missingId_returnsNull() {
        Bundle b = builder().build().toBundle();
        b.remove("id");

        assertNull(Conversation.fromBundle(b));
    }

    @Test
    public void fromBundle_missingUser_returnsNull() {
        Bundle b = builder().build().toBundle();
        b.remove("user");

        assertNull(Conversation.fromBundle(b));
    }

    @Test
    public void fromBundle_missingMessages_returnsNull() {
        Bundle b = builder().build().toBundle();
        b.remove("messages");

        assertNull(Conversation.fromBundle(b));
    }

    @Test
    public void fromBundle_missingParticipants_returnsNull() {
        Bundle b = builder().build().toBundle();
        b.remove("participants");

        assertNull(Conversation.fromBundle(b));
    }

    // -------------------------------------------------------------------------------------
    // Partial parse. CHARACTERISATION — these pin current behaviour, they do not endorse it.
    // -------------------------------------------------------------------------------------

    /**
     * CHARACTERISATION — instance 5's shape, at an IPC boundary.
     *
     * <p>A message Bundle that fails validation is <em>silently dropped</em> and the surrounding
     * Conversation is returned as a success. Three messages in, two valid, and the caller receives
     * a two-message conversation with nothing to distinguish it from a genuine two-message one.
     *
     * <p>This is the same shape as {@code fromMap} partial-parse (instance 5 in
     * {@code docs/ARCHITECTURE.md}): partial success reported as success. It is recorded here
     * rather than fixed, because the API is shared with apps outside this repository and changing
     * the contract from "best effort" to "all or nothing" is not a fork's call to make silently.
     */
    @Test
    public void fromBundle_malformedMessage_isSilentlyDropped_notReported() {
        Conversation original = builder()
                .setMessages(Arrays.asList(
                        message("keep-1", 1L, person("alice")),
                        message("drop-me", 2L, person("bob")),
                        message("keep-2", 3L, person("alice"))))
                .build();
        Bundle b = original.toBundle();

        Parcelable[] messages = b.getParcelableArray("messages");
        assertThat(messages).hasLength(3);
        ((Bundle) messages[1]).remove("text");   // now fails getMessageFromBundle's key check
        b.putParcelableArray("messages", messages);

        Conversation restored = Conversation.fromBundle(b);

        // Not null, not an error, not a flag — just quietly two messages instead of three.
        assertThat(restored).isNotNull();
        assertThat(restored.getMessages()).hasSize(2);
        assertThat(restored.getMessages().get(0).getText()).isEqualTo("keep-1");
        assertThat(restored.getMessages().get(1).getText()).isEqualTo("keep-2");
    }

    /**
     * CHARACTERISATION — the same drop applies to a non-Bundle element entirely.
     */
    @Test
    public void fromBundle_nonBundleMessageElement_isSilentlyDropped() {
        Conversation original = builder()
                .setMessages(Arrays.asList(
                        message("keep", 1L, person("alice")),
                        message("gone", 2L, person("bob"))))
                .build();
        Bundle b = original.toBundle();

        // NB: the array returned by getParcelableArray has runtime type Bundle[], so a Uri cannot
        // be stored into it — build a fresh Parcelable[] instead of mutating in place.
        Parcelable[] messages = b.getParcelableArray("messages");
        Parcelable[] mixed = new Parcelable[]{
                messages[0], android.net.Uri.parse("content://not-a-message")};
        b.putParcelableArray("messages", mixed);

        Conversation restored = Conversation.fromBundle(b);

        assertThat(restored).isNotNull();
        assertThat(restored.getMessages()).hasSize(1);
    }

    /**
     * CHARACTERISATION — participants are filtered the same way.
     */
    @Test
    public void fromBundle_malformedParticipant_isSilentlyDropped() {
        Conversation original = builder()
                .setParticipants(Arrays.asList(person("alice"), person("bob")))
                .build();
        Bundle b = original.toBundle();

        Parcelable[] participants = b.getParcelableArray("participants");
        assertThat(participants).hasLength(2);
        b.putParcelableArray("participants", new Parcelable[]{
                participants[0], android.net.Uri.parse("content://not-a-person")});

        Conversation restored = Conversation.fromBundle(b);

        assertThat(restored).isNotNull();
        assertThat(restored.getParticipants()).hasSize(1);
    }

    /**
     * CHARACTERISATION — instance 2's shape: absence indistinguishable from a benign reading.
     *
     * <p>{@code isMuted} is read with {@code bundle.getBoolean(KEY_IS_MUTED)}, which returns
     * {@code false} for a missing key. So a producer that omits the field yields "not muted" —
     * the value that lets a notification through — and it is byte-identical to an explicit
     * {@code setMuted(false)}.
     *
     * <p>Low stakes relative to the driver-distraction findings, and it never arises on a
     * {@code toBundle} round-trip because that method always writes the key. It arises for any
     * other producer across the IPC, which is the documented use of this API. Pinned so the
     * behaviour is stated rather than assumed.
     */
    @Test
    public void fromBundle_absentMutedKey_readsAsUnmuted_sameAsExplicitFalse() {
        Bundle absent = builder().setMuted(true).build().toBundle();
        absent.remove("is_muted");
        Bundle explicitFalse = builder().setMuted(false).build().toBundle();

        Conversation fromAbsent = Conversation.fromBundle(absent);
        Conversation fromExplicit = Conversation.fromBundle(explicitFalse);

        assertThat(fromAbsent).isNotNull();
        assertThat(fromExplicit).isNotNull();
        assertThat(fromAbsent.isMuted()).isFalse();
        assertThat(fromExplicit.isMuted()).isFalse();
    }

    /**
     * CHARACTERISATION — the same for unread count: absent reads as 0.
     */
    @Test
    public void fromBundle_absentUnreadCount_readsAsZero() {
        Bundle b = builder().setUnreadCount(9).build().toBundle();
        b.remove("unread_count");

        Conversation restored = Conversation.fromBundle(b);

        assertThat(restored).isNotNull();
        assertThat(restored.getUnreadCount()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------------------
    // Optional fields survive absence without dropping the whole object
    // -------------------------------------------------------------------------------------

    @Test
    public void fromBundle_absentTitle_leavesTitleNull_andKeepsTheConversation() {
        Conversation restored = Conversation.fromBundle(builder().build().toBundle());

        assertThat(restored).isNotNull();
        assertThat(restored.getConversationTitle()).isNull();
    }

    @Test
    public void emptyMessageList_roundTripsAsEmpty_notNull() {
        Conversation original = new Conversation.Builder(person("me"), ID)
                .setMessages(Collections.emptyList())
                .setParticipants(Collections.emptyList())
                .build();

        Conversation restored = Conversation.fromBundle(original.toBundle());

        assertThat(restored).isNotNull();
        assertThat(restored.getMessages()).isEmpty();
        assertThat(restored.getParticipants()).isEmpty();
    }

    // -------------------------------------------------------------------------------------
    // toBuilder
    // -------------------------------------------------------------------------------------

    @Test
    public void toBuilder_preservesFields_andAllowsOverride() {
        Conversation original = builder().setUnreadCount(2).setMuted(true).build();

        Conversation modified = original.toBuilder().setUnreadCount(0).build();

        assertThat(modified.getId()).isEqualTo(ID);
        assertThat(modified.isMuted()).isTrue();
        assertThat(modified.getUnreadCount()).isEqualTo(0);
        // The original must be unaffected — it is documented as immutable.
        assertThat(original.getUnreadCount()).isEqualTo(2);
    }

    @Test
    public void builderDefaults_areZeroAndUnmuted() {
        Conversation c = new Conversation.Builder(person("me"), ID)
                .setMessages(Collections.<Conversation.Message>emptyList())
                .setParticipants(Collections.<Person>emptyList())
                .build();

        assertThat(c.getUnreadCount()).isEqualTo(0);
        assertThat(c.isMuted()).isFalse();
        assertThat(c.getConversationTitle()).isNull();
        assertThat(c.getConversationIcon()).isNull();
        assertThat(c.getActions()).isNull();
        assertThat(c.getExtras()).isNotNull();
    }

    @Test
    public void messageData_roundTripsWhenSet() {
        Conversation.Message m = message("see attached", 5L, person("alice"))
                .setData("image/png", android.net.Uri.parse("content://media/1"));
        Conversation original = builder().setMessages(Collections.singletonList(m)).build();

        Conversation restored = Conversation.fromBundle(original.toBundle());

        assertThat(restored).isNotNull();
        List<Conversation.Message> messages = restored.getMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getDataMimeType()).isEqualTo("image/png");
        assertThat(messages.get(0).getDataUri().toString()).isEqualTo("content://media/1");
    }
}
