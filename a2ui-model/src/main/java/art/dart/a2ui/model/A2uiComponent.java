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
package art.dart.a2ui.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.policy.A2uiActionClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One node of an A2UI surface: a catalog component, its text, and its children. */
public final class A2uiComponent {

    private final ComponentType mType;
    private final String mId;
    private final String mTitle;
    private final String mSubtitle;
    private final String mAction;
    private final String mVoicePhrase;
    private final A2uiActionClass mActionClass;
    private final List<A2uiComponent> mChildren;

    private A2uiComponent(Builder builder) {
        mType = builder.mType;
        mId = builder.mId;
        mTitle = builder.mTitle;
        mSubtitle = builder.mSubtitle;
        mAction = builder.mAction;
        mVoicePhrase = builder.mVoicePhrase;
        mActionClass = builder.mActionClass;
        mChildren = Collections.unmodifiableList(new ArrayList<>(builder.mChildren));
    }

    /**
     * The component's protocol id, or null for a component not built from a surface. Actions name
     * it on their way back to the agent.
     */
    @Nullable
    public String getId() {
        return mId;
    }

    /** The action to dispatch when this component is invoked, or null if it is not interactive. */
    @Nullable
    public String getAction() {
        return mAction;
    }

    /**
     * The class the agent declared for this action, or null. Never resolved here: only
     * {@code A2uiActionPolicy.classify} decides, and it may raise but never lower it.
     */
    @Nullable
    public A2uiActionClass getActionClass() {
        return mActionClass;
    }

    /** True if this component carries an action. */
    public boolean isInteractive() {
        return mAction != null;
    }

    /**
     * The phrase a driver would say to invoke this, or null. Defaults to the title, so a tappable
     * control is always speakable.
     */
    @Nullable
    public String getVoicePhrase() {
        if (!isInteractive()) {
            return null;
        }
        return mVoicePhrase != null ? mVoicePhrase : mTitle;
    }

    /**
     * The phrase the agent declared, without the title fallback, or null. {@code A2uiPruner}
     * clamps this, since clamping the effective value would turn fallbacks into declarations.
     */
    @Nullable
    public String getDeclaredVoicePhrase() {
        return mVoicePhrase;
    }

    @NonNull
    public ComponentType getType() {
        return mType;
    }

    @Nullable
    public String getTitle() {
        return mTitle;
    }

    @Nullable
    public String getSubtitle() {
        return mSubtitle;
    }

    /** Immutable; a pruned copy is a new tree rather than a mutation of this one. */
    @NonNull
    public List<A2uiComponent> getChildren() {
        return mChildren;
    }

    public boolean hasChildren() {
        return !mChildren.isEmpty();
    }

    @Override
    public String toString() {
        return mType.catalogName() + (mTitle == null ? "" : "(" + mTitle + ")")
                + (mChildren.isEmpty() ? "" : mChildren.toString());
    }

    /** Builder. */
    public static final class Builder {
        private final ComponentType mType;
        private String mId;
        private String mTitle;
        private String mSubtitle;
        private String mAction;
        private String mVoicePhrase;
        private A2uiActionClass mActionClass;
        private final List<A2uiComponent> mChildren = new ArrayList<>();

        public Builder(@NonNull ComponentType type) {
            mType = type;
        }

        /**
         * Copies everything except children from an existing component, so the transforms that
         * rebuild a tree cannot forget a field such as the action.
         */
        @NonNull
        // metalava api-lint: BuilderSetStyle, suppressed. Renaming to setPropertiesFrom trades it
        // for MissingGetterMatchingBuilder, and there is no property to get.
        @SuppressWarnings("BuilderSetStyle")
        public Builder copyPropertiesFrom(@NonNull A2uiComponent other) {
            mId = other.mId;
            mTitle = other.mTitle;
            mSubtitle = other.mSubtitle;
            mAction = other.mAction;
            mVoicePhrase = other.mVoicePhrase;
            mActionClass = other.mActionClass;
            return this;
        }

        @NonNull
        public Builder setId(@Nullable String id) {
            mId = id;
            return this;
        }

        @NonNull
        public Builder setAction(@Nullable String action) {
            mAction = action;
            return this;
        }

        @NonNull
        public Builder setVoicePhrase(@Nullable String voicePhrase) {
            mVoicePhrase = voicePhrase;
            return this;
        }

        @NonNull
        public Builder setActionClass(@Nullable A2uiActionClass actionClass) {
            mActionClass = actionClass;
            return this;
        }

        @NonNull
        public Builder setTitle(@Nullable String title) {
            mTitle = title;
            return this;
        }

        @NonNull
        public Builder setSubtitle(@Nullable String subtitle) {
            mSubtitle = subtitle;
            return this;
        }

        @NonNull
        // metalava api-lint: MissingGetterMatchingBuilder, suppressed: it demands getChilds(), and
        // getChildren() exists.
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public Builder addChild(@NonNull A2uiComponent child) {
            mChildren.add(child);
            return this;
        }

        @NonNull
        public A2uiComponent build() {
            return new A2uiComponent(this);
        }
    }
}
