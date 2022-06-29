package com.coocoofroggy.otalive.listeners;

import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.utils.MongoUtils;
import com.coocoofroggy.otalive.utils.PallasUtils;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DiscordListener extends ListenerAdapter {
    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getUser().getId().equals("353561670934855681"))
            event.reply("You must be CoocooFroggy to use commands lol").setEphemeral(true).queue();
        switch (event.getName()) {
            case "audience" -> {
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
                        String collect = assetAudiences.stream().collect(Collectors.joining("`, `", "`", "`"));
                        event.reply(collect).queue();
                    }
                }
            }
            case "force-run" -> {
                event.reply("Running").setEphemeral(true).queue();
                PallasUtils.runScanner();
            }
        }
    }

}
