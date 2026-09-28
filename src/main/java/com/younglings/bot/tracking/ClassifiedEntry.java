package com.younglings.bot.tracking;

/** A single classified, fully-rendered announcement line — {@link TrackingEventRouter} only ever posts these, verbatim, as plain text (no embed). */
public record ClassifiedEntry(TrackingGroup group, String line) {}
