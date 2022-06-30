package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.Asset;
import com.coocoofroggy.otalive.objects.BuildIdentity;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.objects.PallasResponse;
import com.dd.plist.PropertyListFormatException;
import com.google.gson.Gson;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PallasUtils {
    private static final Gson gson = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger(PallasUtils.class);
    private static final Pattern PZB_FILE_PATTERN = Pattern.compile(" f (.*)");
    private static final String[] DEV_KEYWORDS = new String[]{
            "development", "kasan", "debug", "diag", "factory", "device_map", "dev.im4p"
    };
    private static final String[] SPECIAL_CASE = new String[]{
            "DeviceTree", "iBoot", "LLB", "sep-firmware", "iBEC", "iBSS", "diag"
    };

    // region Partial Zip

    public static boolean runGdmfScanner() {
        boolean newFirmwareReleased = false;

        int attempts = 0;
        int maxAttempts = 3;

        while (true) {
            LOGGER.info("Starting scanner...");
            Main.jda.getPresence().setPresence(OnlineStatus.ONLINE, Activity.playing("scanning..."));
            GlobalObject globalObject = MongoUtils.fetchGlobalObject();
            try {
                List<String> lines = FileUtils.readLines(new File("devices.txt"), StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.isBlank() || line.startsWith("//")) continue;
                    String[] split = line.split(";");
                    String device = split[0];
                    String boardId = split[1];
                    String deviceHumanName = split[2];

                    for (String assetAudience : globalObject.getAssetAudiences()) {
                        String responseString = pallas(device, boardId, assetAudience);
                        PallasResponse pallasResponse = parseJwt(responseString);
                        for (Asset asset : pallasResponse.getAssets()) {
                            List<String> processedBuildIdDeviceCombos = globalObject.getProcessedBuildIdDeviceCombo();
                            // If this is a new build ID & device combo, process it
                            if (!processedBuildIdDeviceCombos.contains(asset.uniqueComboString())) {
                                LOGGER.info("NEW: " + asset.getBuildId() + " " + asset.getSupportedDevicesPretty());
                                newFirmwareReleased = true;
                                Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
                                TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());

                                EmbedBuilder embedBuilder = new EmbedBuilder();
                                // iOS16Beta2 — iPhone11,8
                                embedBuilder.setTitle(asset.getLongName() + " — " + asset.getSupportedDevicesPretty())
                                        .addField("Build ID", asset.getBuildId(), true)
                                        .addField("OS Version", asset.getOsVersion(), true)
                                        .addField("Device Name", deviceHumanName, true)
                                        .addField("URL", asset.getFullUrl(), false);

                                // Send initial message
                                Message message = channel.sendMessageEmbeds(embedBuilder.build()).complete();

                                // Scan for dev files
                                List<String> devFiles = listDevFiles(asset.getFullUrl(), boardId);
                                String collect = "None found";
                                if (!devFiles.isEmpty())
                                    collect = devFiles.stream().collect(Collectors.joining("`\n`", "`", "`"));
                                embedBuilder.setDescription("**Dev Files**\n" + collect.substring(0, Math.min(4081, collect.length())));

                                // Update the message
                                message.editMessageEmbeds(embedBuilder.build()).queue();

                                globalObject.getProcessedBuildIdDeviceCombo().add(asset.uniqueComboString());
                                MongoUtils.replaceGlobalObject(globalObject);

                                // Add info to DB that will allow us to check if it's signed
                                TssUtils.downloadBmFromUrl(asset.getFullUrl());
                                BuildIdentity buildIdentity = TssUtils.dataFromBm(boardId);
                                // Should never trigger. But just in case
                                if (buildIdentity == null) {
                                    channel.sendMessage("<@353561670934855681> couldn't parse data from BM 🚨.").queue();
                                    continue;
                                }
                                buildIdentity.setAsset(asset);
                                MongoUtils.insertBuildIdentity(buildIdentity);
                            } else {
                                LOGGER.info("Old: " + asset.getBuildId() + " " + asset.getSupportedDevicesPretty());
                            }
                        }
                    }
                }
                Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
                LOGGER.info("Scanner finished.");
                return newFirmwareReleased; // Otherwise we'd be stuck in infinite while true loop
            } catch (InterruptedException e) {
                // Try again (because of while true loop) but on third try, just quit
                if (++attempts > maxAttempts) {
                    LOGGER.error("Interrupted. Quitting, max attempts was three.");
                    throw new RuntimeException(e);
                }
                LOGGER.error("Interrupted. Trying again.");
            } catch (IOException | PropertyListFormatException | ParseException | ParserConfigurationException |
                     SAXException e) {
                Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
                LOGGER.error("Scanner terminated early.");
                throw new RuntimeException(e);
            }
        }
    }

    public static String pallas(String device, String boardId, String assetAudience) throws IOException {
        Map<String, Object> requestMap = Map.of(
                "ClientVersion", 2,
                "AssetType", "com.apple.MobileAsset.SoftwareUpdate",
                "AssetAudience", assetAudience,
                "ProductType", device,
                "HWModelStr", boardId,
                // These two are to not get deltas
                "ProductVersion", "0",
                "BuildVersion", "0",
                "CompatibilityVersion", 20
        );
        String s = gson.toJson(requestMap);

        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://gdmf.apple.com/v2/assets");
        httpPost.setEntity(new StringEntity(s));
        httpPost.addHeader("Content-Type", "application/json");

        CloseableHttpResponse response = client.execute(httpPost);
        String responseString = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

        response.close();
        client.close();

        return responseString;
    }

    public static PallasResponse parseJwt(String encodedString) {
        // The part between the two dots is what we need
        String meat = encodedString.split("\\.")[1];
        String decoded = new String(Base64.decodeBase64(meat), StandardCharsets.UTF_8);
        return gson.fromJson(decoded, PallasResponse.class);
    }

    private static List<String> listDevFiles(String urlString, String boardId) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder("pzb", "-l", urlString);
        Process process = processBuilder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null)
            output.append(line).append("\n");
        process.waitFor();

        List<String> devFiles = new ArrayList<>();
        Matcher matcher = PZB_FILE_PATTERN.matcher(output.toString());
        lineLabel:
        while (matcher.find()) {
            String fileName = matcher.group(1);
            for (String keyword : DEV_KEYWORDS) {
                // If it's a dev file
                if (fileName.toLowerCase().contains(keyword)) {
                    // Check for special case
                    for (String s : SPECIAL_CASE) {
                        if (fileName.contains(s)) {
                            // Only add it to the list of dev files if it matches our board ID
                            if (fileName.toLowerCase().contains(boardId.substring(0, 4).toLowerCase()))
                                devFiles.add(fileName);
                            // It will only match one special case—no need to check others
                            continue lineLabel;
                        }
                    }
                    // if it's a plist file we don't care
                    if (fileName.endsWith(".plist")) continue lineLabel;
                    // It's not a special case if we reach here
                    devFiles.add(fileName);
                    // Go to next line—we already found match
                    continue lineLabel;
                }
            }
        }
        return devFiles;
    }

    // endregion
}
