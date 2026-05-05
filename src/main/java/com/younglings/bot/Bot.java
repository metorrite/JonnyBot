package com.younglings.bot;

import com.younglings.bot.config.BotConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class Bot {

    private JDA jda;

    public void start() throws InterruptedException {
        jda = JDABuilder.createDefault(BotConfig.getToken())
                .enableIntents(GatewayIntent.GUILD_MESSAGES)
                .setStatus(OnlineStatus.ONLINE)
                .setActivity(Activity.playing(BotConfig.getActivity()))
                .build();

        jda.awaitReady();

        System.out.println("Bot is online as: " + jda.getSelfUser().getAsTag());
    }

    public JDA getJda() {
        return jda;
    }
}
