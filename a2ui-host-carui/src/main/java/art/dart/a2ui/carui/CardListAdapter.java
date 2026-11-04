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
package art.dart.a2ui.carui;

import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.CarUiText;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;

import art.dart.a2ui.policy.A2uiActionBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visitor surfaces as surface cards, bound from the very rows the car-ui lists hold, so a card
 * can show no more than its row: same text, same listener (or none), same enabled state. Shows at
 * most {@link #setVisibleCount} rows, the count the content limit leaves on screen.
 */
final class CardListAdapter extends RecyclerView.Adapter<CardListAdapter.Holder> {

    /** Right-aligned meta lifted from a title's last segment: a clock time, minutes or miles. */
    private static final Pattern TITLE_META =
            Pattern.compile("^(.*\\S)\\s·\\s(\\d{1,2}:\\d{2}\\s?[AP]M|\\d+\\s?min|\\d+(\\.\\d+)?\\s?mi)$");
    /** A subtitle that leads with a distance. */
    private static final Pattern SUBTITLE_DISTANCE =
            Pattern.compile("^(\\d+(\\.\\d+)?\\s?mi)\\s·\\s(.+)$");
    /** A subtitle that is only a distance. */
    private static final Pattern SUBTITLE_ONLY_DISTANCE = Pattern.compile("^\\d+(\\.\\d+)?\\s?mi$");
    /** A subtitle that ends with a position, e.g. "(3/6)". */
    private static final Pattern SUBTITLE_POSITION = Pattern.compile("^(.*\\S)\\s\\((\\d+/\\d+)\\)$");
    private static final String VOICE_HINT_PREFIX = "say “";

    private final List<CarUiListItem> mItems;
    private int mVisible;

    CardListAdapter(@NonNull List<CarUiListItem> items) {
        mItems = items;
    }

    /** Rows the content limit leaves on screen; never more than the rows held. */
    void setVisibleCount(int visible) {
        mVisible = Math.max(0, visible);
    }

    @Override
    public int getItemCount() {
        return Math.min(mItems.size(), mVisible);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.a2ui_card_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        CarUiListItem row = mItems.get(position);
        if (row instanceof CarUiContentListItem) {
            holder.bind((CarUiContentListItem) row);
        }
    }

    /** What a card shows for a row, as values, so the lifting rules are testable. */
    @VisibleForTesting
    static final class Card {
        @NonNull final String mTitle;
        @Nullable final String mSubtitle;
        @NonNull final List<String> mExtra;
        @Nullable final String mMeta;

        Card(@NonNull String title, @Nullable String subtitle, @NonNull List<String> extra,
                @Nullable String meta) {
            mTitle = title;
            mSubtitle = subtitle;
            mExtra = extra;
            mMeta = meta;
        }
    }

    /** Title, first body line, further lines, meta. Voice hints are dropped; nothing is added. */
    @VisibleForTesting
    @NonNull
    static Card cardFor(@Nullable CharSequence title, @NonNull List<String> body) {
        String t = title == null ? "" : title.toString();
        String meta = null;
        Matcher m = TITLE_META.matcher(t);
        if (m.matches()) {
            t = m.group(1);
            meta = m.group(2);
        }
        List<String> lines = new ArrayList<>();
        for (String line : body) {
            if (line != null && !line.isEmpty() && !line.startsWith(VOICE_HINT_PREFIX)) {
                lines.add(line);
            }
        }
        String subtitle = lines.isEmpty() ? null : lines.remove(0);
        if (subtitle != null && meta == null) {
            Matcher d = SUBTITLE_DISTANCE.matcher(subtitle);
            Matcher p = SUBTITLE_POSITION.matcher(subtitle);
            if (d.matches()) {
                meta = d.group(1);
                subtitle = d.group(3);
            } else if (SUBTITLE_ONLY_DISTANCE.matcher(subtitle).matches()) {
                meta = subtitle;
                subtitle = lines.isEmpty() ? null : lines.remove(0);
            } else if (p.matches()) {
                meta = p.group(2);
                subtitle = p.group(1);
            }
        }
        return new Card(t, subtitle, lines, meta);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final View mLead;
        private final ImageView mIcon;
        private final TextView mTitle;
        private final TextView mSubtitle;
        private final TextView mExtra;
        private final TextView mMeta;
        private final View mChevron;

        Holder(@NonNull View view) {
            super(view);
            mLead = view.findViewById(R.id.card_lead);
            mIcon = view.findViewById(R.id.card_icon);
            mTitle = view.findViewById(R.id.card_title);
            mSubtitle = view.findViewById(R.id.card_subtitle);
            mExtra = view.findViewById(R.id.card_extra);
            mMeta = view.findViewById(R.id.card_meta);
            mChevron = view.findViewById(R.id.card_chevron);
            float radius = 14 * view.getResources().getDisplayMetrics().density;
            mIcon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            mIcon.setClipToOutline(true);
        }

        void bind(@NonNull CarUiContentListItem item) {
            List<String> body = new ArrayList<>();
            if (item.getBody() != null) {
                for (CarUiText line : item.getBody()) {
                    body.add(String.valueOf(line.getPreferredText()));
                }
            }
            Card card = cardFor(item.getTitle() == null ? null
                    : item.getTitle().getPreferredText(), body);
            mTitle.setText(card.mTitle);
            show(mSubtitle, card.mSubtitle);
            show(mExtra, card.mExtra.isEmpty() ? null : String.join("\n", card.mExtra));
            show(mMeta, card.mMeta);

            if (item.getIcon() == null) {
                mLead.setVisibility(View.GONE);
                mIcon.setImageDrawable(null);
            } else {
                mLead.setVisibility(View.VISIBLE);
                mIcon.setImageDrawable(item.getIcon());
                boolean art = item.getPrimaryIconType() == CarUiContentListItem.IconType.CONTENT;
                int inset = art ? 0 : Math.round(14 * itemView.getResources()
                        .getDisplayMetrics().density);
                mIcon.setPadding(inset, inset, inset, inset);
                mIcon.setScaleType(art ? ImageView.ScaleType.CENTER_CROP
                        : ImageView.ScaleType.FIT_CENTER);
                mIcon.setBackgroundResource(art ? 0 : R.drawable.a2ui_glyph_background);
            }

            CarUiContentListItem.OnClickListener click = item.getOnClickListener();
            boolean refused = !item.isEnabled() || body.contains(A2uiActionBinder.REFUSED_NOTE);
            itemView.setAlpha(refused ? 0.55f : 1f);
            if (click != null && item.isEnabled()) {
                itemView.setOnClickListener(v -> click.onClick(item));
                itemView.setClickable(true);
                itemView.setFocusable(true);
            } else {
                itemView.setOnClickListener(null);
                itemView.setClickable(false);
                itemView.setFocusable(false);
            }
            mChevron.setVisibility(click != null && item.isEnabled() && card.mMeta == null
                    && item.getAction() == CarUiContentListItem.Action.CHEVRON
                    ? View.VISIBLE : View.GONE);
            itemView.setContentDescription(card.mTitle
                    + (card.mSubtitle == null ? "" : ". " + card.mSubtitle)
                    + (card.mMeta == null ? "" : ". " + card.mMeta));
        }

        private static void show(@NonNull TextView view, @Nullable String text) {
            view.setText(text);
            view.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }
    }
}
