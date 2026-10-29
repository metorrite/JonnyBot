package com.runeclans.bot.internal;

import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.SkillWeekService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * A member's vote for next week's skill of the week, and their auto-enrol choice (T-711), from the clan website. Called by {@link MemberApi},
 * which has tied the request to the signed-in member.
 * <ul>
 *     <li>{@code GET skill-week/vote}: {@code {open, week, end, options: [{skillId, skill, votes}], mine}}; {@code open} false unless the server
 *     votes for its skill</li>
 *     <li>{@code POST skill-week/vote} {@code {skillId}}: casts or changes the member's vote, answering the same</li>
 *     <li>{@code GET skill-week/auto}: {@code {autoEnrol, optedOut}}, whether the server enters members by themselves and whether this member
 *     asked to be left out; {@code POST skill-week/auto} {@code {optedOut}} saves their choice, answering the same</li>
 * </ul>
 * Only members of the server may vote or choose (403 otherwise), on the home site as on a clan site.
 */
@BService
public class SkillWeekMemberApi {
    private final SkillWeekService weeks;

    public SkillWeekMemberApi(SkillWeekService weeks) {
        this.weeks = weeks;
    }

    public DataObject get(Guild guild, long userId) {
        requireMember(guild, userId);
        return weeks.voteView(guild.getIdLong(), userId);
    }

    public DataObject auto(Guild guild, long userId) {
        requireMember(guild, userId);
        return weeks.autoView(guild.getIdLong(), userId);
    }

    public DataObject setAuto(Guild guild, long userId, DataObject body) {
        requireMember(guild, userId);
        if (!body.hasKey("optedOut") || body.isNull("optedOut")) throw new EventProblem(400, "Say whether to leave you out.");
        boolean optedOut;
        try {
            optedOut = body.getBoolean("optedOut");
        } catch (RuntimeException e) {
            throw new EventProblem(400, "Say whether to leave you out.");
        }
        return weeks.setOptOut(guild.getIdLong(), userId, optedOut);
    }

    /** Only members of the server: the home site's route doesn't check this before calling (a clan site's does). */
    private static void requireMember(Guild guild, long userId) {
        if (ServerProfile.resolveMember(guild, userId) == null) throw new EventProblem(403, MemberApi.NOT_IN_SERVER);
    }

    public DataObject vote(Guild guild, long userId, DataObject body) {
        requireMember(guild, userId);
        int skillId;
        try {
            skillId = body.getInt("skillId");
        } catch (RuntimeException e) {
            throw new EventProblem(400, "Choose one of the skills on the list.");
        }
        return weeks.castVote(guild.getIdLong(), userId, skillId);
    }
}
