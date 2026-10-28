package com.runeclans.bot.linkedroles;

import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

/** The framework's bean for Discord Linked Roles: builds {@link LinkedRoles} from the settings and starts it when the bot is connected. */
@BService
public class LinkedRolesService {
    private final LinkedRoles roles;

    public LinkedRolesService(BotConfig config, LinkedRolesStore store, ClanProfileService clanProfiles, RuneClansHubClient hub) {
        String token;
        try {
            token = config.getToken();
        } catch (IllegalStateException e) {
            token = null;
        }
        this.roles = LinkedRoles.live(LinkedRolesSettings.from(config::getLinkedRolesSetting), store,
                guildId -> clanProfiles.publicView(guildId, false).isPresent(), RsLinkSource.hub(hub), token);
    }

    public LinkedRoles roles() {
        return roles;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        roles.start();
    }
}
