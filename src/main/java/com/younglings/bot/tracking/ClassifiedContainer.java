package com.younglings.bot.tracking;

import net.dv8tion.jda.api.components.container.Container;

/**
 * A single classified entry rendered as a full Components V2 {@link Container} rather than a plain
 * text line — {@link TrackingEventRouter#dispatchContainer} posts these. Used for Discord Admin Log
 * entries specifically: enough structured fields (action, actor, target, channel, time, reason) that a
 * bordered card reads far more clearly than one more plain-text line, especially once Discord's own
 * client groups several back-to-back posts from the same bot together with no visual break between
 * them otherwise.
 */
public record ClassifiedContainer(TrackingGroup group, Container container) {}
