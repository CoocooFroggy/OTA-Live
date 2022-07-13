package com.coocoofroggy.otalive;

import com.coocoofroggy.otalive.listeners.DiscordListener;
import com.coocoofroggy.otalive.utils.AzureUtils;
import com.coocoofroggy.otalive.utils.MongoUtils;
import com.coocoofroggy.otalive.utils.TimerUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Objects;

public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    public static JDA jda;

    public static void main(String[] args) {
        // Trust Apple
        try {
            addX509CertificateToTrustStore("AppleROOTCA.pem", "Apple", System.getProperty("java.home") + "/lib/security/cacerts", System.getenv("STORE_PWD"));
        } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        MongoUtils.connectToDb();
        AzureUtils.connectToAzure();
        startBot();
        new Thread(Main::upsertAllCommands).start();
        TimerUtils.startLoopScheduler();
    }

    private static void upsertAllCommands() {
        LOGGER.info("Commands queued for updating.");
        jda.upsertCommand("audience", "Manage asset audiences")
                .addSubcommands(
                        new SubcommandData("add", "Add an asset audience")
                                .addOption(OptionType.STRING, "asset-audience", "The asset audience to add", true),
                        new SubcommandData("remove", "Remove an asset audience")
                                .addOption(OptionType.STRING, "asset-audience", "The asset audience to remove", true),
                        new SubcommandData("list", "Lists asset audiences"))
                .complete();
        jda.upsertCommand("debug", "Debug commands")
                .addSubcommands(
                        new SubcommandData("force-run", "Force runs the scanner"),
                        new SubcommandData("signing-status", "Sends the embed with signing status"))
                .complete();
        LOGGER.info("Commands updated.");
    }

    private static void startBot() {
        JDABuilder builder = JDABuilder.createDefault(System.getenv("TOKEN"));
        builder.addEventListeners(new DiscordListener());
        try {
            jda = builder.build();
            jda.awaitReady();
            // Default idle 🌙
            jda.getPresence().setPresence(OnlineStatus.IDLE, null);
        } catch (LoginException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // https://stackoverflow.com/a/24115112/13668740
    // https://stackoverflow.com/a/69853772/13668740
    public static void addX509CertificateToTrustStore(String certPath, String certAlias, String storePath, String storePassword)
            throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {

        char[] storePasswordCharArr = Objects.requireNonNull(storePassword, "").toCharArray();

        KeyStore keystore;
        try (FileInputStream storeInputStream = new FileInputStream(storePath);
             FileInputStream certInputStream = new FileInputStream(certPath)) {
            keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(storeInputStream, storePasswordCharArr);

            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate certificate = certificateFactory.generateCertificate(certInputStream);

            keystore.setCertificateEntry(certAlias, certificate);
        }

        try (FileOutputStream storeOutputStream = new FileOutputStream(storePath)) {
            keystore.store(storeOutputStream, storePasswordCharArr);
        }
    }
}
