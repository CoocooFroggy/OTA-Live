package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.Main;
import com.coocoofroggy.otalive.objects.BuildIdentity;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.coocoofroggy.otalive.objects.SigningStatus;
import com.coocoofroggy.otalive.objects.pallas.Asset;
import com.dd.plist.*;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TssUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(TssUtils.class);
    private static final String tssRequestTemplate = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
                <dict>
                    <key>ApECID</key>
                    <integer>1</integer>
                    <key>UniqueBuildID</key>
                    <data>{{UBID}}</data>
                    <key>ApChipID</key>
                    <string>{{CPID}}</string>
                    <key>ApBoardID</key>
                    <string>{{BDID}}</string>
                    <key>ApSecurityDomain</key>
                    <string>{{SDOM}}</string>
                    <key>ApNonce</key>
                    <data>q83vASNFZ4mrze8BI0VniavN7wE=</data>
                    <key>@ApImg4Ticket</key>
                    <true/>
                    <key>ApSecurityMode</key>
                    <true/>
                    <key>ApProductionMode</key>
                    <true/>
                    <key>SepNonce</key>
                    <data>z59YgWI9Pv3oNas53hhBJXc4S0E=</data>
                </dict>
            </plist>
            """;

    public static BuildIdentity buildIdentityFromBm(File bm, String boardId) throws PropertyListFormatException, IOException, ParseException, ParserConfigurationException, SAXException {
        NSDictionary rootDict = (NSDictionary) PropertyListParser.parse(bm);
        NSObject[] buildIdentities = ((NSArray) rootDict.objectForKey("BuildIdentities")).getArray();
        // Loop through all the identities in BM
        for (NSObject buildIdentityObj : buildIdentities) {
            if (!buildIdentityObj.getClass().equals(NSDictionary.class)) return null;
            NSDictionary buildIdentityPlist = (NSDictionary) buildIdentityObj;
            // Get info of this current build identity
            NSDictionary info = (NSDictionary) buildIdentityPlist.objectForKey("Info");
            String currentBoardId = info.objectForKey("DeviceClass").toString();
            if (!currentBoardId.equalsIgnoreCase(boardId)) continue;
            return new BuildIdentity(((NSData) buildIdentityPlist.objectForKey("UniqueBuildID")).getBase64EncodedData())
                    .setApBoardID(buildIdentityPlist.objectForKey("ApBoardID").toString())
                    .setApChipID(buildIdentityPlist.objectForKey("ApChipID").toString())
                    .setApSecurityDomain(buildIdentityPlist.objectForKey("ApSecurityDomain").toString());
        }
        return null;
    }

    public static SigningStatus tssCheckSigned(BuildIdentity buildIdentity) throws IOException {
        String requestString = tssRequestTemplate.replaceFirst("\\{\\{UBID}}", buildIdentity.getBuildIdentityB64())
                .replaceFirst("\\{\\{CPID}}", buildIdentity.getApChipID())
                .replaceFirst("\\{\\{BDID}}", buildIdentity.getApBoardID())
                .replaceFirst("\\{\\{SDOM}}", buildIdentity.getApSecurityDomain());

        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("http://gs.apple.com/TSS/controller?action=2");
        httpPost.setEntity(new StringEntity(requestString));
        httpPost.addHeader("Content-Type", "application/xml");

        CloseableHttpResponse response = client.execute(httpPost);
        String responseString = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

        response.close();
        client.close();

        if (responseString.contains("STATUS=94&MESSAGE=This device isn't eligible for the requested build."))
            return SigningStatus.UNSIGNED;
        else if (responseString.contains("STATUS=0&MESSAGE=SUCCESS"))
            return SigningStatus.SIGNED;
        else
            return SigningStatus.UNKNOWN;
    }

    public static File downloadBmFromUrl(String url) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder("pzb", "-g", "AssetData/boot/BuildManifest.plist", url);
        Process process = processBuilder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        while (reader.readLine() != null) ;
        process.waitFor();
        return new File("BuildManifest.plist");
    }

    public static boolean runTssScanner(GlobalObject globalObject) {
        boolean somethingGotUnsigned = false;

        int attempts = 0;
        int maxAttempts = 3;

        while (true) {
            LOGGER.debug("Starting TSS scanner...");
            Main.jda.getPresence().setPresence(OnlineStatus.ONLINE, Activity.playing("checking TSS..."));
            List<BuildIdentity> buildIdentities = null;
            try {
                buildIdentities = MongoUtils.fetchAllSignedBuildIdentities();
            } catch (Exception e) {
                // Try again (because of while true loop) but on third try, just quit
                if (++attempts > maxAttempts) {
                    Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
                    LOGGER.error("Interrupted. Quitting, max attempts was three.");
                    throw new RuntimeException(e);
                }
                LOGGER.error("Interrupted. Trying again.");
                continue;
            }
            for (BuildIdentity buildIdentity : buildIdentities) {
                Asset asset = buildIdentity.getAsset();
                LOGGER.debug("Checking if " + asset.getBuildId() + " " + asset.getSupportedDevicesPretty() + " is unsigned...");
                try {
                    SigningStatus signingStatus = tssCheckSigned(buildIdentity);
                    if (signingStatus == SigningStatus.UNSIGNED) {
                        LOGGER.info("Marking " + asset.getBuildId() + " " + asset.getSupportedDevicesPretty() + " as unsigned.");
                        MongoUtils.markBuildIdentityAsUnsigned(buildIdentity);
                        somethingGotUnsigned = true;

                        // Notify us
                        Guild guild = Main.jda.getGuildById(globalObject.getGuildId());
                        TextChannel channel = guild.getTextChannelById(globalObject.getChannelId());

                        EmbedBuilder embedBuilder = new EmbedBuilder();
                        embedBuilder.setTitle("Unsigned: " + asset.getHumanReadableName() + " — " + asset.getSupportedDevicesPretty())
                                .setColor(new Color(0xB00000))
                                .addField("Build ID", asset.getBuildId(), true)
                                .addField("OS Version", asset.getOsVersion(), true)
                                .addField("URL", asset.getFullUrl(), false);

                        channel.sendMessageEmbeds(embedBuilder.build()).queue();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    LOGGER.error("Continuing anyways.");
                }
            }
            Main.jda.getPresence().setPresence(OnlineStatus.IDLE, null);
            LOGGER.debug("Finished scanning TSS.");
            return somethingGotUnsigned;
        }
    }

    public static EmbedBuilder signedFirmwareEmbed() {
        HashMap<String, Integer> buildIdSignedDevicesCount = getBuildIdSignedDevicesCount();

        StringBuilder stringBuilder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : buildIdSignedDevicesCount.entrySet()) {
            stringBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append(" devices.\n");
        }

        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle("Signed OTAs")
                .setColor(new Color(0x38C700))
                .setDescription(stringBuilder.toString());
        return embedBuilder;
    }

    // This one compares it to the initial one
    public static EmbedBuilder signedFirmwareEmbed(HashMap<String, Integer> initialBuildIdSignedDevicesCount) {
        HashMap<String, Integer> buildIdSignedDevicesCount = getBuildIdSignedDevicesCount();

        StringBuilder stringBuilder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : buildIdSignedDevicesCount.entrySet()) {
            // If there is a new firmware added
            if (!initialBuildIdSignedDevicesCount.containsKey(entry.getKey()))
                stringBuilder.append("**NEW →** ");
                // If it's not a new firmware, but the values are different
            else if (!initialBuildIdSignedDevicesCount.get(entry.getKey()).equals(entry.getValue()))
                stringBuilder.append("**CHANGED →** ");
            stringBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append(" devices.\n");
        }
        // Check if anything was unsigned, and say so
        for (Map.Entry<String, Integer> entry : initialBuildIdSignedDevicesCount.entrySet()) {
            // If the current buildIdSignedDevicesCount doesn't have a key that was there initially, it got unsigned
            if (!buildIdSignedDevicesCount.containsKey(entry.getKey()))
                stringBuilder.append("**UNSIGNED →** ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" devices.\n");
        }

        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle("Signed OTAs")
                .setColor(new Color(0x38C700))
                .setDescription(stringBuilder.toString());
        return embedBuilder;
    }

    @NotNull
    public static HashMap<String, Integer> getBuildIdSignedDevicesCount() {
        HashMap<String, Integer> buildIdSignedDevicesCount = new HashMap<>();
        List<BuildIdentity> buildIdentities = MongoUtils.fetchAllSignedBuildIdentities();
        for (BuildIdentity buildIdentity : buildIdentities) {
            // iOS 16 Developer Beta 2 (`18A24`)
            String key = buildIdentity.getAsset().getHumanReadableName() + " (`" + buildIdentity.getAsset().getBuildId() + "`)";
            Integer count = buildIdSignedDevicesCount.get(key);
            if (count == null) count = 0;
            buildIdSignedDevicesCount.put(key, count + 1);
        }
        return buildIdSignedDevicesCount;
    }
}
