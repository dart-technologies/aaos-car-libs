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

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.CarUiText;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;

import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiValueLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Host-owned rows (pickup, arrival, thinking) and the keyed diff that moves rows in place. */
final class HostRows {

    static final String THINKING = "host:thinking";
    static final String ARRIVAL = "host:arrival";
    static final String ORDER = "host:order";

    /** The noun a host card goes by in the change line, or null for one never reported. */
    @androidx.annotation.Nullable
    static String label(@NonNull String key) {
        switch (key) {
            case ORDER: return "Order";
            case ARRIVAL: return "Arrival";
            default: return null;
        }
    }

    private HostRows() {
    }

    /**
     * A glanceable card: text first, never a listener or action chrome, strings clamped to the
     * reading's budget. Art needs IMAGE, a glyph needs ICON; without either it is text alone.
     */
    @Nullable
    static CarUiContentListItem card(@NonNull Set<ComponentType> allowed,
            @NonNull A2uiValueLimits limits, @Nullable CharSequence title,
            @NonNull List<? extends CharSequence> body, @Nullable Drawable art,
            @Nullable Drawable glyph) {
        if (title == null || !allowed.contains(ComponentType.TEXT)) {
            return null;
        }
        CarUiContentListItem card = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        card.setTitle(clamp(title, limits));
        List<CarUiText> lines = new ArrayList<>();
        for (CharSequence line : body) {
            if (line != null && line.length() > 0) {
                lines.add(new CarUiText.Builder(clamp(line, limits)).build());
            }
        }
        if (!lines.isEmpty()) {
            card.setBody(lines);
        }
        if (art != null && allowed.contains(ComponentType.IMAGE)) {
            card.setIcon(art);
            card.setPrimaryIconType(CarUiContentListItem.IconType.CONTENT);
        } else if (glyph != null && allowed.contains(ComponentType.ICON)) {
            card.setIcon(glyph);
            card.setPrimaryIconType(CarUiContentListItem.IconType.STANDARD);
        }
        return card;
    }

    /** {@code A2uiPruner}'s rule: cut to the budget, the ellipsis spending one of it. */
    @NonNull
    static CharSequence clamp(@NonNull CharSequence text, @NonNull A2uiValueLimits limits) {
        int max = limits.getMaxStringLength();
        if (text.length() <= max) {
            return text;
        }
        if (max <= 1) {
            return max <= 0 ? "" : "…";
        }
        int keep = max - 1;
        if (Character.isHighSurrogate(text.charAt(keep - 1))) {
            keep--;
        }
        return text.subSequence(0, keep) + "…";
    }

    /** What a row shows. Equal signatures under one key need no rebind. */
    @NonNull
    static String signature(@NonNull CarUiListItem row) {
        if (!(row instanceof CarUiContentListItem)) {
            return row.getClass().getName();
        }
        CarUiContentListItem item = (CarUiContentListItem) row;
        StringBuilder s = new StringBuilder();
        if (item.getTitle() != null) {
            s.append(item.getTitle().getPreferredText());
        }
        s.append('\u0000');
        if (item.getBody() != null) {
            for (CarUiText line : item.getBody()) {
                s.append(line.getPreferredText()).append('\u0000');
            }
        }
        return s.append(item.getAction()).append(item.isEnabled()).append(item.isChecked())
                .append(item.getPrimaryIconType()).append(item.getIcon() != null)
                .append(item.getOnClickListener() != null)
                .append(item.getOnCheckedChangeListener() != null).toString();
    }

    @NonNull
    static DiffUtil.DiffResult diff(@NonNull List<String> oldKeys,
            @NonNull List<String> oldSignatures, @NonNull List<String> newKeys,
            @NonNull List<String> newSignatures) {
        return DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldKeys.size();
            }

            @Override
            public int getNewListSize() {
                return newKeys.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return oldKeys.get(oldPosition).equals(newKeys.get(newPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return oldSignatures.get(oldPosition).equals(newSignatures.get(newPosition));
            }
        });
    }

    /**
     * True when the adapter shows all {@code rows} rows one to one. A content cap or a "limited"
     * message row shifts positions, and a diff dispatched through either would be inconsistent.
     */
    static boolean exposesAll(@NonNull RecyclerView.Adapter<?> adapter, int rows,
            int messageViewType) {
        int count = adapter.getItemCount();
        return count == rows && (count == 0 || (adapter.getItemViewType(0) != messageViewType
                && adapter.getItemViewType(count - 1) != messageViewType));
    }
}
