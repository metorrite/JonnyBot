package com.younglings.bot.discord;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.util.List;

/**
 * Simple in-memory pagination for the various "list of entries" views across the bot (signup
 * panels, admin lists, etc). Entry counts here are clan-sized, not web-scale, so paginating a
 * list already fetched in full is simpler and safer than adding LIMIT/OFFSET query variants to
 * every repository — the tradeoff would only matter at a scale this bot never sees.
 * <p>
 * The page number is meant to be encoded directly in each nav button's custom ID (e.g.
 * {@code "signup_view_full_page:" + signupId + ":" + (page.pageIndex() + 1)}) — there's no
 * server-side pagination state to track, so a stale button from an old page always still works.
 */
public final class Pagination {
    private Pagination() {}

    public static final int DEFAULT_PAGE_SIZE = 10;

    public record Page<T>(List<T> items, int pageIndex, int pageCount, int totalCount) {
        public boolean hasPrevious() { return pageIndex > 0; }
        public boolean hasNext() { return pageIndex < pageCount - 1; }
        public boolean isSinglePage() { return pageCount <= 1; }
    }

    public static <T> Page<T> paginate(List<T> all, int pageIndex, int pageSize) {
        int totalCount = all.size();
        int pageCount = Math.max(1, (totalCount + pageSize - 1) / pageSize);
        int clampedIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
        int from = Math.min(clampedIndex * pageSize, totalCount);
        int to = Math.min(from + pageSize, totalCount);
        return new Page<>(all.subList(from, to), clampedIndex, pageCount, totalCount);
    }

    public static <T> Page<T> paginate(List<T> all, int pageIndex) {
        return paginate(all, pageIndex, DEFAULT_PAGE_SIZE);
    }

    /**
     * A Prev / page-indicator / Next row. {@code idPrefix} is combined with the target page's
     * plain 0-based index to build each nav button's ID — e.g. passing
     * {@code "signup_view_full_page:42:"} while on page index 0 produces
     * {@code "signup_view_full_page:42:1"} for "next". The receiving handler should parse that
     * trailing number as-is (no off-by-one conversion) and pass it straight to
     * {@link #paginate(List, int)}. The middle button is always disabled — it's a label, not a
     * control, and reuses "label" as its ID since it's never clicked.
     */
    public static ActionRow navRow(Page<?> page, String idPrefix) {
        Button prev = Button.secondary(idPrefix + (page.pageIndex() - 1), "◀ Prev");
        Button label = Button.secondary(idPrefix + "label", "Page " + (page.pageIndex() + 1) + "/" + page.pageCount());
        Button next = Button.secondary(idPrefix + (page.pageIndex() + 1), "Next ▶");

        return ActionRow.of(
                page.hasPrevious() ? prev : prev.asDisabled(),
                label.asDisabled(),
                page.hasNext() ? next : next.asDisabled()
        );
    }
}
