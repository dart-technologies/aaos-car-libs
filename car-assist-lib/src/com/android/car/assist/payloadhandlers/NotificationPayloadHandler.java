/*
 * Copyright (C) 2019 The Android Open Source Project
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
 *
 * Modified by Dart Technologies, 2026; see FORK.md.
 */
package com.android.car.assist.payloadhandlers;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;

import com.android.car.assist.CarVoiceInteractionSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Class used by {@link CarVoiceInteractionSession} to handle payload actions for
 * {@link StatusBarNotification} payloads, such as getting payload data, writing to remote inputs,
 * and firing the appropriate actions upon completion.
 */
public class NotificationPayloadHandler {
    private static final String TAG = "NotificationPayloadHandler";

    /** The context used by this instance to fire actions */
    private final Context mContext;

    public NotificationPayloadHandler(Context context) {
        mContext = context;
    }

    /** @return The {@link StatusBarNotification}, or null if not found. */
    @Nullable
    public StatusBarNotification getStatusBarNotification(Bundle args) {
        return args.getParcelable(CarVoiceInteractionSession.KEY_NOTIFICATION);
    }

    /**
     * Returns the {@link Notification} of the {@link StatusBarNotification}
     * provided in the args {@link Bundle}.
     *
     * @return The {@link StatusBarNotification}'s {@link Notification}, or null if not found.
     */
    @Nullable
    public Notification getNotification(Bundle args) {
        StatusBarNotification sbn = args.getParcelable(CarVoiceInteractionSession.KEY_NOTIFICATION);

        return sbn == null ? null : sbn.getNotification();
    }

    /**
     * Retrieves all messages associated with the provided {@link Notification}
     * These messages are provided through the notification's {@link MessagingStyle},
     * using {@link MessagingStyle#addMessage(Message)}.
     *
     * @param notification the notification delivered to the voice interaction session
     * @return all messages provided in the {@link MessagingStyle}
     */
    public List<Message> getMessages(Notification notification) {
        MessagingStyle messagingStyle = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification);

        return messagingStyle == null ? new ArrayList<>() : messagingStyle.getMessages();
    }

    /**
     * Retrieves all messages associated with the provided {@link StatusBarNotification} in the
     * args {@link Bundle}. These messages are provided through the notification's
     * {@link MessagingStyle}, using {@link MessagingStyle#addMessage(Message)}.
     *
     * @param args the payload delivered to the voice interaction session
     * @return all messages provided in the {@link MessagingStyle}
     */
    public List<Message> getMessages(Bundle args) {
        Notification notification = getNotification(args);
        return getMessages(notification);
    }

    /**
     * Retrieves the corresponding {@link Action} from the notification's callback actions.
     *
     * @param notification the notification delivered to the voice interaction session
     * @param semanticAction the {@link Action.SemanticAction} on which to select
     * @return the first action for which {@link Action#getSemanticAction()} returns semanticAction,
     * or null if no such action exists
     */
    @Nullable
    public Action getAction(Notification notification, int semanticAction) {
        // Dart fork fix: both of these were unguarded NPEs, found by this module's first tests.
        //
        // Notification.actions is NULL — not empty — when a notification carries no actions, which
        // is the ordinary case for a plain notification. And getAction(Bundle, int) below resolves
        // to null whenever the payload has no notification under KEY_NOTIFICATION, which is the
        // ordinary case for a malformed or foreign payload.
        //
        // Both threw from the voice-assistant reply path — driver speaks, assistant reads the
        // notification, looks for the reply action — so an absent or unexpected payload took the
        // assistant down mid-drive instead of reporting "I couldn't do that". The javadoc already
        // promises null here; it just was not true.
        if (notification == null || notification.actions == null) {
            Log.w(TAG, String.format("No actions to search for semantic action: %d",
                    semanticAction));
            return null;
        }
        for (Action action : notification.actions) {
            if (action.getSemanticAction() == semanticAction) {
                return action;
            }
        }

        Log.w(TAG, String.format("Semantic action not found: %d", semanticAction));
        return null;
    }

    /**
     * Retrieves the corresponding {@link Action} from the notification's callback actions.
     *
     * @param args the payload delivered to the voice interaction session
     * @param semanticAction the {@link Action.SemanticAction} on which to select
     * @return the first action for which {@link Action#getSemanticAction()} returns semanticAction,
     * or null if no such action exists
     */
    @Nullable
    public Action getAction(Bundle args, int semanticAction) {
        Notification notification = getNotification(args);
        return getAction(notification, semanticAction);
    }

    /**
     * Fires the {@link PendingIntent} of the corresponding {@link Action}, ensuring that any
     * {@link RemoteInput}s corresponding to this action contain any addidional data.
     *
     * @param action the action to fire
     * @return true if the {@link PendingIntent} was sent successfully; false otherwise.
     */
    public boolean fireAction(Action action, @Nullable Intent additionalData) {
        // Dart fork fix: unguarded NPE. This is the end of the reply path and its callers pass
        // whatever getAction returned — which is documented as nullable and, after the fix above,
        // genuinely is. The method already returns false to mean "could not send", so an absent
        // action has a correct answer available; it simply was not given.
        if (action == null || action.actionIntent == null) {
            Log.w(TAG, "Cannot fire a null action or an action with no PendingIntent");
            return false;
        }
        PendingIntent pendingIntent = action.actionIntent;
        int resultCode = 0;
        try {
            if (additionalData == null) {
                pendingIntent.send(resultCode);
            } else {
                pendingIntent.send(mContext, resultCode, additionalData);
            }
        } catch (PendingIntent.CanceledException e) {
            return false;
        }

        return true;
    }

    /**
     * Writes the given reply to the {@link RemoteInput} of the provided action callback.
     * Requires that the action callback contains at least one {@link RemoteInput}.
     * In the case that multiple {@link RemoteInput}s are provided, the first will be used.
     *
     * @param actionCallback the action containing the {@link RemoteInput}
     * @param reply the reply that should be written to the {@link RemoteInput}
     * @return the additional data to provide to the action intent upon firing; null on error
     *
     * @see NotificationPayloadHandler#fireAction(Action action, Intent additionalData)
     */
    @Nullable
    public Intent writeReply(@Nullable Action actionCallback, CharSequence reply) {
        if (actionCallback == null) {
            Log.e(TAG, "No action callback was provided.");
            return null;
        }

        RemoteInput[] remoteInputs = actionCallback.getRemoteInputs();
        if (remoteInputs == null || remoteInputs.length == 0) {
            Log.e(TAG, "No RemoteInputs were provided in the action callback.");
            return null;
        }
        if (remoteInputs.length > 1) {
            Log.w(TAG, "Vague arguments. Using first RemoteInput.");
        }

        RemoteInput remoteInput = remoteInputs[0];
        if (remoteInput == null) {
            Log.e(TAG, "RemoteInput provided was null.");
            return null;
        }

        Intent additionalData = new Intent();
        Bundle results = new Bundle();
        results.putCharSequence(remoteInput.getResultKey(), reply);
        RemoteInput.addResultsToIntent(remoteInputs, additionalData, results);

        return additionalData;
    }

    /**
     * Writes the given reply to the {@link RemoteInput} of the reply callback, if present.
     * Requires that a reply callback be included in the args {@link Bundle}, and that this
     * callback contains at least one {@link RemoteInput}. In the case that multiple
     * {@link RemoteInput}s are provided, the first will be used.
     *
     * @param args the payload arguments provided to the session
     * @param reply the reply that should be written to the {@link RemoteInput}
     * @return the additional data to provide to the reply action intent upon firing; null on error
     */
    @Nullable
    public Intent writeReply(Bundle args, CharSequence reply) {
        return writeReply(getAction(args, Action.SEMANTIC_ACTION_REPLY), reply);
    }
}
