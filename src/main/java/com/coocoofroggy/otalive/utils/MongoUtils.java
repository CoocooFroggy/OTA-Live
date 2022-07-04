package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.objects.BuildIdentity;
import com.coocoofroggy.otalive.objects.GlobalObject;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;

import java.util.ArrayList;
import java.util.List;

public class MongoUtils {
    static MongoDatabase database;

    public static void connectToDb() {
        MongoClient mongoClient = MongoClients.create(System.getenv("MONGO_URL"));

        // https://stackoverflow.com/a/49918311/13668740
        CodecRegistry defaultCodecRegistry = MongoClientSettings.getDefaultCodecRegistry();
        CodecRegistry fromProvider = CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build());
        CodecRegistry pojoCodecRegistry = CodecRegistries.fromRegistries(defaultCodecRegistry, fromProvider);
        database = mongoClient.getDatabase("OTA-Live-DB").withCodecRegistry(pojoCodecRegistry);
    }

    // region Global Persistence

    public static GlobalObject fetchGlobalObject() {
        GlobalObject globalObject = getGlobalPersistenceCollection().find().first();
        if (globalObject == null) {
            globalObject = new GlobalObject();
            replaceGlobalObject(globalObject);
        }
        return globalObject;
    }

    public static UpdateResult replaceGlobalObject(GlobalObject globalObject) {
        return getGlobalPersistenceCollection().replaceOne(
                Filters.eq(globalObject.getId()),
                globalObject,
                new ReplaceOptions().upsert(true));
    }

    public static UpdateResult pushToProcessedBuildIdDeviceCombo(GlobalObject globalObject, String combo) {
        return getGlobalPersistenceCollection().updateOne(
                Filters.eq(globalObject.getId()),
                Updates.push("processedBuildIdDeviceCombo", combo));
    }

    // endregion

    // region Build Identities

    public static void insertBuildIdentity(BuildIdentity buildIdentity) {
        MongoCollection<BuildIdentity> buildIdentitiesCollection = getBuildIdentitiesCollection();
        buildIdentitiesCollection.insertOne(buildIdentity); // Constructor makes signed true by default
    }

    public static void markBuildIdentityAsUnsigned(BuildIdentity buildIdentity) {
        MongoCollection<BuildIdentity> buildIdentitiesCollection = getBuildIdentitiesCollection();
        buildIdentitiesCollection.updateOne(
                Filters.eq("buildIdentityB64", buildIdentity.getBuildIdentityB64()),
                Updates.set("signed", false));
    }

    public static List<BuildIdentity> fetchAllSignedBuildIdentities() {
        MongoCollection<BuildIdentity> buildIdentitiesCollection = getBuildIdentitiesCollection();
        FindIterable<BuildIdentity> iterable = buildIdentitiesCollection.find(Filters.eq("signed", true));
        List<BuildIdentity> signedBuildIdentities = new ArrayList<>();
        for (BuildIdentity buildIdentity : iterable) {
            signedBuildIdentities.add(buildIdentity);
        }
        return signedBuildIdentities;
    }

    // endregion

    // region Collections

    public static MongoCollection<GlobalObject> getGlobalPersistenceCollection() {
        return database.getCollection("Global Persistence", GlobalObject.class);
    }

    public static MongoCollection<BuildIdentity> getBuildIdentitiesCollection() {
        return database.getCollection("Build Identities", BuildIdentity.class);
    }

    // endregion
}
