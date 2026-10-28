package com.runeclans.bot.internal;

import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.SkillWeekService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * A member's vote for next week's skill of the week (T-711), from the clan website. Called by {@link MemberApi}, which has already tied the
 * request to the signed-in member and checked they are in the server.
 * <ul>
 *     <li>{@code GET skill-week/vote}: {@code {open, week, end, options: [{skillId, skill, votes}], mine}}; {@code open} false unless the server
 *     votes for its skill</li>
 *     <li>{@code POST skill-week/vote} {@code {skillId}}: casts or changes the member's vote, answering the same</li>
 * </ul>
 */
@BService
public class SkillWeekMemberApi {
    private final SkillWeekService weeks;

    public SkillWeekMemberApi(SkillWeekService weeks) {
        this.weeks = weeks;
    }

    public DataObject get(Guild guild, long userId) {
        return weeks.voteView(guild.getIdLong(), userId);
    }

    public DataObject vote(Guild guild, long userId, DataObject body) {
        int skillId;
        try {
            skillId = body.getInt("skillId");
        } catch (RuntimeException e) {
            throw new EventProblem(400, "Choose one of the skills on the list.");
        }
        return weeks.castVote(guild.getIdLong(), userId, skillId);
    }
}
