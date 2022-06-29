package com.coocoofroggy.otalive.utils;

import com.coocoofroggy.otalive.objects.GlobalObject;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;

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

    // endregion

    // region Collections

    public static MongoCollection<GlobalObject> getGlobalPersistenceCollection() {
        return database.getCollection("Global Persistence", GlobalObject.class);
    }

    // endregion
}
