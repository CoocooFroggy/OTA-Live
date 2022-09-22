package com.coocoofroggy.otalive.listeners;

import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.utils.MongoUtils;
import com.coocoofroggy.otalive.utils.TimerUtils;
import com.coocoofroggy.otalive.utils.TssUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DiscordListener extends ListenerAdapter {
    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "audience" -> {
                if (!event.getUser().getId().equals("353561670934855681")) {
                    event.reply("You must be CoocooFroggy to use commands lol").setEphemeral(true).queue();
                    return;
                }
                assert event.getSubcommandName() != null; // Always has a subcommand
                switch (event.getSubcommandName()) {
                    case "add" -> {
                        String assetAudience = Objects.requireNonNull(event.getOption("asset-audience")).getAsString();
                        GlobalObject globalObject = MongoUtils.fetchGlobalObject();
                        globalObject.getAssetAudiences().add(assetAudience);
                        MongoUtils.replaceGlobalObject(globalObject);
                        event.reply("Added.").queue();
                    }
                    case "remove" -> {
                        String assetAudience = Objects.requireNonNull(event.getOption("asset-audience")).getAsString();
                        GlobalObject globalObject = MongoUtils.fetchGlobalObject();
                        boolean removed = globalObject.getAssetAudiences().remove(assetAudience);
                        if (!removed) {
                            event.reply("Nothing was changed.").queue();
                            return;
                        }
                        MongoUtils.replaceGlobalObject(globalObject);
                        event.reply("Removed.").queue();
                    }
                    case "list" -> {
                        GlobalObject globalObject = MongoUtils.fetchGlobalObject();
                        List<String> assetAudiences = globalObject.getAssetAudiences();
                        if (assetAudiences.isEmpty()) {
                            event.reply("None added yet.").queue();
                            return;
                        }
                        String collect = assetAudiences.stream().collect(Collectors.joining("`\n`", "`", "`"));
                        event.reply(collect).queue();
                    }
                }
            }
            case "debug" -> {
                assert event.getSubcommandName() != null; // Always has a subcommand
                switch (event.getSubcommandName()) {
                    case "force-run" -> {
                        if (!event.getUser().getId().equals("353561670934855681")) {
                            event.reply("You must be CoocooFroggy to use commands lol").setEphemeral(true).queue();
                            return;
                        }
                        event.reply("Running").setEphemeral(true).queue();
                        TimerUtils.scanLoop();
                    }
                    case "signing-status" -> {
                        InteractionHook hook = event.deferReply().complete();
                        // All embeds (could be > 10)
                        List<EmbedBuilder> firmwareEmbeds = TssUtils.firmwareEmbeds();
                        // Queued embeds to send in groups of 10
                        List<MessageEmbed> queuedEmbeds = new ArrayList<>();
                        for (int i = 0; i < firmwareEmbeds.size(); i++) {
                            if (i % 10 == 0 && i != 0) {
                                hook.sendMessageEmbeds(queuedEmbeds).queue();
                                queuedEmbeds = new ArrayList<>();
                            }
                            queuedEmbeds.add(firmwareEmbeds.get(i).build());
                        }
                        if (!queuedEmbeds.isEmpty())
                            hook.sendMessageEmbeds(queuedEmbeds).queue();
                    }
                }
            }
        }
    }

}
