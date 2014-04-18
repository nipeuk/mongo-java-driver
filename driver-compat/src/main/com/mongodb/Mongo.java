/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb;

import org.mongodb.Codec;
import org.mongodb.Document;
import org.mongodb.ServerCursor;
import org.mongodb.annotations.ThreadSafe;
import org.mongodb.binding.ClusterBinding;
import org.mongodb.binding.ReadBinding;
import org.mongodb.binding.WriteBinding;
import org.mongodb.codecs.PrimitiveCodecs;
import org.mongodb.connection.BufferProvider;
import org.mongodb.connection.Cluster;
import org.mongodb.connection.ClusterConnectionMode;
import org.mongodb.connection.ClusterDescription;
import org.mongodb.connection.ClusterSettings;
import org.mongodb.connection.Connection;
import org.mongodb.connection.DefaultClusterFactory;
import org.mongodb.connection.PowerOfTwoBufferPool;
import org.mongodb.connection.ServerDescription;
import org.mongodb.connection.SocketStreamFactory;
import org.mongodb.management.JMXConnectionPoolListener;
import org.mongodb.operation.GetDatabaseNamesOperation;
import org.mongodb.operation.ReadOperation;
import org.mongodb.operation.WriteOperation;
import org.mongodb.protocol.KillCursor;
import org.mongodb.protocol.KillCursorProtocol;
import org.mongodb.selector.CompositeServerSelector;
import org.mongodb.selector.LatencyMinimizingServerSelector;
import org.mongodb.selector.MongosHAServerSelector;
import org.mongodb.selector.ServerAddressSelector;
import org.mongodb.selector.ServerSelector;
import org.mongodb.session.ClusterSession;
import org.mongodb.session.PinnedSession;
import org.mongodb.session.ServerConnectionProvider;
import org.mongodb.session.ServerConnectionProviderOptions;
import org.mongodb.session.Session;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mongodb.MongoExceptions.mapException;
import static com.mongodb.ReadPreference.primary;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mongodb.connection.ClusterConnectionMode.MULTIPLE;
import static org.mongodb.connection.ClusterType.REPLICA_SET;

/**
 * A database connection with internal connection pooling. For most applications, you should have one Mongo instance
 * for the entire JVM.
 * <p>
 * The following are equivalent, and all connect to the local database running on the default port:
 * <pre>
 * Mongo mongo1 = new Mongo();
 * Mongo mongo1 = new Mongo("localhost");
 * Mongo mongo2 = new Mongo("localhost", 27017);
 * Mongo mongo4 = new Mongo(new ServerAddress("localhost"));
 * </pre>
 * <p>
 * You can connect to a
 * <a href="http://www.mongodb.org/display/DOCS/Replica+Sets">replica set</a> using the Java driver by passing
 * a ServerAddress list to the Mongo constructor. For example:
 * <pre>
 * Mongo mongo = new Mongo(Arrays.asList(
 *   new ServerAddress("localhost", 27017),
 *   new ServerAddress("localhost", 27018),
 *   new ServerAddress("localhost", 27019)));
 * </pre>
 * You can connect to a sharded cluster using the same constructor.  Mongo will auto-detect whether the servers are
 * a list of replica set members or a list of mongos servers.
 * <p>
 * By default, all read and write operations will be made on the primary,
 * but it's possible to read from secondaries by changing the read preference:
 * <p>
 * <pre>
 * mongo.setReadPreference(ReadPreference.secondary());
 * </pre>
 * By default, write operations will not throw exceptions on failure, but that is easily changed too:
 * <p>
 * <pre>
 * mongo.setWriteConcern(WriteConcern.SAFE);
 * </pre>
 *
 * Note: This class has been superseded by {@code MongoClient}, and may be deprecated in a future release.
 *
 * @see MongoClient
 * @see ReadPreference
 * @see WriteConcern
 */
@ThreadSafe
public class Mongo {
    static final String ADMIN_DATABASE_NAME = "admin";
    private static final String VERSION = "3.0.0-SNAPSHOT";

    private final ConcurrentMap<String, DB> dbCache = new ConcurrentHashMap<String, DB>();

    private volatile WriteConcern writeConcern;
    private volatile ReadPreference readPreference;

    private final MongoClientOptions options;
    private final List<MongoCredential> credentialsList;

    private final Bytes.OptionHolder optionHolder;

    private final Codec<Document> documentCodec;
    private final Cluster cluster;
    private final BufferProvider bufferProvider = new PowerOfTwoBufferPool();

    private final ThreadLocal<SessionHolder> pinnedSession = new ThreadLocal<SessionHolder>();
    private final ConcurrentLinkedQueue<ServerCursor> orphanedCursors = new ConcurrentLinkedQueue<ServerCursor>();
    private final ExecutorService cursorCleaningService;

    /**
     * Creates a Mongo instance based on a (single) mongodb node (localhost, default port)
     *
     * @throws UnknownHostException
     * @throws MongoException
     * @deprecated Replaced by {@link MongoClient#MongoClient()})
     */
    @Deprecated
    public Mongo() throws UnknownHostException {
        this(new ServerAddress(), createLegacyOptions());
    }

    /**
     * Creates a Mongo instance based on a (single) mongodb node (default port)
     *
     * @param host server to connect to
     * @throws UnknownHostException if the database host cannot be resolved
     * @throws MongoException
     * @deprecated Replaced by {@link MongoClient#MongoClient(String)}
     */
    @Deprecated
    public Mongo(final String host) throws UnknownHostException {
        this(new ServerAddress(host), createLegacyOptions());
    }


    /**
     * Creates a Mongo instance based on a (single) mongodb node (default port)
     *
     * @param host    server to connect to
     * @param options default query options
     * @throws UnknownHostException if the database host cannot be resolved
     * @throws MongoException
     * @deprecated Replaced by {@link MongoClient#MongoClient(String, MongoClientOptions)}
     */
    @Deprecated
    public Mongo(final String host,
                 @SuppressWarnings("deprecation")
                 final MongoOptions options)
        throws UnknownHostException {
        this(new ServerAddress(host), options.toClientOptions());
    }

    /**
     * Creates a Mongo instance based on a (single) mongodb node
     *
     * @param host the host address of the database
     * @param port the port on which the database is running
     * @throws UnknownHostException if the database host cannot be resolved
     * @throws MongoException
     * @deprecated Replaced by {@link MongoClient#MongoClient(String, int)}
     */
    @Deprecated
    public Mongo(final String host, final int port) throws UnknownHostException {
        this(new ServerAddress(host, port), createLegacyOptions());
    }

    /**
     * Creates a Mongo instance based on a (single) mongodb node
     *
     * @param address the database address
     * @throws MongoException
     * @see com.mongodb.ServerAddress
     * @deprecated Replaced by {@link MongoClient#MongoClient(ServerAddress)}
     */
    @Deprecated
    public Mongo(final ServerAddress address) {
        this(address, createLegacyOptions());
    }

    /**
     * Creates a Mongo instance based on a (single) mongo node using a given ServerAddress
     *
     * @param address the database address
     * @param options default query options
     * @throws MongoException
     * @see com.mongodb.ServerAddress
     * @deprecated Replaced by {@link MongoClient#MongoClient(ServerAddress, MongoClientOptions)}
     */
    @Deprecated
    public Mongo(final ServerAddress address,
                 @SuppressWarnings("deprecation")
                 final MongoOptions options) {
        this(address, options.toClientOptions());
    }

    /**
     * <p>Creates a Mongo in paired mode. <br/> This will also work for a replica set and will find all members (the master will be used by
     * default).</p>
     *
     * @param left  left side of the pair
     * @param right right side of the pair
     * @throws MongoException
     * @see com.mongodb.ServerAddress
     * @deprecated Please use {@link MongoClient#MongoClient(java.util.List)} instead.
     */
    @Deprecated
    public Mongo(final ServerAddress left, final ServerAddress right) {
        this(asList(left, right), createLegacyOptions());
    }

    /**
     * <p>Creates a Mongo connection in paired mode. <br/> This will also work for a replica set and will find all members (the master will
     * be used by default).</p>
     *
     * @param left    left side of the pair
     * @param right   right side of the pair
     * @param options the optional settings for the Mongo instance
     * @throws MongoException
     * @see com.mongodb.ServerAddress
     * @deprecated Please use {@link MongoClient#MongoClient(java.util.List, MongoClientOptions)} instead.
     */
    @Deprecated
    public Mongo(final ServerAddress left, final ServerAddress right,
                 @SuppressWarnings("deprecation")
                 final MongoOptions options) {
        this(asList(left, right), options.toClientOptions());
    }

    /**
     * Creates a Mongo based on a list of replica set members or a list of mongos. It will find all members (the master will be used by
     * default). If you pass in a single server in the list, the driver will still function as if it is a replica set. If you have a
     * standalone server, use the Mongo(ServerAddress) constructor.
     * <p/>
     * If this is a list of mongos servers, it will pick the closest (lowest ping time) one to send all requests to, and automatically fail
     * over to the next server if the closest is down.
     *
     * @param seeds Put as many servers as you can in the list and the system will figure out the rest.  This can either be a list of mongod
     *              servers in the same replica set or a list of mongos servers in the same sharded cluster.
     * @throws MongoException
     * @see com.mongodb.ServerAddress
     * @deprecated Replaced by {@link MongoClient#MongoClient(java.util.List)}
     */
    @Deprecated
    public Mongo(final List<ServerAddress> seeds) {
        this(seeds, createLegacyOptions());
    }

    /**
     * Creates a Mongo based on a list of replica set members or a list of mongos. It will find all members (the master will be used by
     * default). If you pass in a single server in the list, the driver will still function as if it is a replica set. If you have a
     * standalone server, use the Mongo(ServerAddress) constructor.
     * <p/>
     * If this is a list of mongos servers, it will pick the closest (lowest ping time) one to send all requests to, and automatically fail
     * over to the next server if the closest is down.
     *
     * @param seeds   Put as many servers as you can in the list and the system will figure out the rest.  This can either be a list of
     *                mongod servers in the same replica set or a list of mongos servers in the same sharded cluster.
     * @param options for configuring this Mongo instance
     * @throws MongoException
     * @see com.mongodb.ServerAddress
     * @deprecated Replaced by {@link MongoClient#MongoClient(java.util.List, MongoClientOptions)}
     */
    @Deprecated
    public Mongo(final List<ServerAddress> seeds,
                 @SuppressWarnings("deprecation")
                 final MongoOptions options) {
        this(seeds, options.toClientOptions());
    }

    /**
     * Creates a Mongo described by a URI. If only one address is used it will only connect to that node, otherwise it will discover all
     * nodes. If the URI contains database credentials, the database will be authenticated lazily on first use with those credentials.
     *
     * @param uri URI to connect to, optionally containing additional information like credentials
     * @throws MongoException
     * @throws UnknownHostException
     * @mongodb.driver.manual reference/connection-string Connection String URI Format
     * @see MongoURI <p>examples: <li>mongodb://localhost</li> <li>mongodb://fred:foobar@localhost/</li> </p>
     * @deprecated Replaced by {@link MongoClient#MongoClient(MongoClientURI)}
     */
    @Deprecated
    public Mongo(@SuppressWarnings("deprecation") final MongoURI uri) throws UnknownHostException {
        this(uri.toClientURI());
    }

    Mongo(final List<ServerAddress> seedList, final MongoClientOptions options) {
        this(seedList, Collections.<MongoCredential>emptyList(), options);
    }

    Mongo(final ServerAddress serverAddress, final MongoClientOptions options) {
        this(serverAddress, Collections.<MongoCredential>emptyList(), options);
    }

    Mongo(final ServerAddress serverAddress, final List<MongoCredential> credentialsList, final MongoClientOptions options) {
        this(createCluster(serverAddress, credentialsList, options), options, credentialsList);
    }

    Mongo(final List<ServerAddress> seedList, final List<MongoCredential> credentialsList, final MongoClientOptions options) {
        this(createCluster(seedList, credentialsList, options), options, credentialsList);
    }

    Mongo(final MongoClientURI mongoURI) throws UnknownHostException {
        this(createCluster(mongoURI),
             mongoURI.getOptions(),
             mongoURI.getCredentials() != null ? asList(mongoURI.getCredentials()) : Collections.<MongoCredential>emptyList());
    }

    Mongo(final Cluster cluster, final MongoClientOptions options, final List<MongoCredential> credentialsList) {
        this.cluster = cluster;
        this.documentCodec = new DocumentCodec(PrimitiveCodecs.createDefault());
        this.options = options;
        this.readPreference = options.getReadPreference() != null ? options.getReadPreference() : primary();
        this.writeConcern = options.getWriteConcern() != null ? options.getWriteConcern() : WriteConcern.UNACKNOWLEDGED;
        this.optionHolder = new Bytes.OptionHolder(null);
        this.credentialsList = unmodifiableList(credentialsList);
        cursorCleaningService = options.isCursorFinalizerEnabled() ? createCursorCleaningService() : null;
    }

    /**
     * Sets the write concern for this database. Will be used as default for writes to any collection in any database. See the documentation
     * for {@link WriteConcern} for more information.
     *
     * @param writeConcern write concern to use
     */
    public void setWriteConcern(final WriteConcern writeConcern) {
        this.writeConcern = writeConcern;
    }

    /**
     * Gets the default write concern
     *
     * @return the default write concern
     */
    public WriteConcern getWriteConcern() {
        return writeConcern;
    }

    /**
     * Sets the read preference for this database. Will be used as default for reads from any collection in any database. See the
     * documentation for {@link ReadPreference} for more information.
     *
     * @param readPreference Read Preference to use
     */
    public void setReadPreference(final ReadPreference readPreference) {
        this.readPreference = readPreference;
    }

    /**
     * Gets the default read preference
     *
     * @return the default read preference
     */
    public ReadPreference getReadPreference() {
        return readPreference;
    }

    /**
     * Gets the current driver version.
     *
     * @return the full version string, e.g. "3.0.0"
     */
    public String getVersion() {
        return VERSION;
    }

    /**
     * Gets a list of all server addresses used when this Mongo was created
     *
     * @return list of server addresses
     * @throws MongoException
     */
    public List<ServerAddress> getAllAddress() {
        //TODO It should return the address list without auto-discovered nodes. Not sure if it's required. Maybe users confused with name.
        return getServerAddressList();
    }

    /**
     * Gets the list of server addresses currently seen by this client. This includes addresses auto-discovered from a replica set.
     *
     * @return list of server addresses
     * @throws MongoException
     */
    public List<ServerAddress> getServerAddressList() {
        List<ServerAddress> serverAddresses = new ArrayList<ServerAddress>();
        for (final ServerDescription cur : getClusterDescription().getAll()) {
            serverAddresses.add(new ServerAddress(cur.getAddress()));
        }
        return serverAddresses;
    }

    private ClusterDescription getClusterDescription() {
        try {
            return cluster.getDescription(options.getMaxWaitTime(), TimeUnit.MILLISECONDS);
        } catch (org.mongodb.MongoException e) {
            //TODO: test this
            throw mapException(e);
        }
    }

    /**
     * Gets the address of the current master
     *
     * @return the address
     */
    public ServerAddress getAddress() {
        ClusterDescription description = getClusterDescription();
        if (description.getPrimaries().isEmpty()) {
            return null;
        }
        return new ServerAddress(description.getPrimaries().get(0).getAddress());
    }

    /**
     * Returns the mongo options.
     * <p/>
     * Please be aware that since 3.0 changes to {@code MongoOptions} that are done after connection are not reflected.
     *
     * @return the mongo options
     * @deprecated Please use {@link MongoClient} class to connect to server and corresponding {@link
     *             com.mongodb.MongoClient#getMongoClientOptions()}
     */
    @Deprecated
    public MongoOptions getMongoOptions() {
        return new MongoOptions(getMongoClientOptions());
    }

    /**
     * Get the status of the replica set cluster.
     *
     * @return replica set status information
     */
    public ReplicaSetStatus getReplicaSetStatus() {
        return getClusterDescription().getType() == REPLICA_SET && getClusterDescription().getConnectionMode() == MULTIPLE
               ? new ReplicaSetStatus(cluster) : null; // this is intended behavior in 2.x
    }


    /**
     * Gets a list of the names of all databases on the connected server.
     *
     * @return list of database names
     * @throws MongoException
     */
    public List<String> getDatabaseNames() {
        return execute(new GetDatabaseNamesOperation(), primary());
    }

    /**
     * Gets a database object
     *
     * @param dbName the name of the database to retrieve
     * @return a DB representing the specified database
     */
    public DB getDB(final String dbName) {
        DB db = dbCache.get(dbName);
        if (db != null) {
            return db;
        }

        db = new DB(this, dbName, documentCodec);
        DB temp = dbCache.putIfAbsent(dbName, db);
        if (temp != null) {
            return temp;
        }
        return db;
    }

    /**
     * Returns the list of databases used by the driver since this Mongo instance was created. This may include DBs that exist in the client
     * but not yet on the server.
     *
     * @return a collection of database objects
     */
    public Collection<DB> getUsedDatabases() {
        return dbCache.values();
    }


    /**
     * Drops the database if it exists.
     *
     * @param dbName name of database to drop
     * @throws MongoException
     */
    public void dropDatabase(final String dbName) {
        getDB(dbName).dropDatabase();
    }

    /**
     * Closes all resources associated with this instance, in particular any open network connections. Once called, this instance and any
     * databases obtained from it can no longer be used.
     */
    public void close() {
        cluster.close();
        cursorCleaningService.shutdownNow();
    }

    /**
     * Makes it possible to run read queries on secondary nodes
     *
     * @see ReadPreference#secondaryPreferred()
     * @deprecated Replaced with {@code ReadPreference.secondaryPreferred()}
     */
    @Deprecated
    public void slaveOk() {
        addOption(Bytes.QUERYOPTION_SLAVEOK);
    }

    /**
     * Set the default query options.
     *
     * @param options value to be set
     */
    public void setOptions(final int options) {
        optionHolder.set(options);
    }

    /**
     * Reset the default query options.
     */
    public void resetOptions() {
        optionHolder.reset();
    }

    /**
     * Add the default query option.
     *
     * @param option value to be added to current options
     */
    public void addOption(final int option) {
        optionHolder.add(option);
    }

    /**
     * Gets the default query options
     *
     * @return an int representing the options to be used by queries
     */
    public int getOptions() {
        return optionHolder.get();
    }

    /**
     * Forces the master server to fsync the RAM data to disk This is done automatically by the server at intervals, but can be forced for
     * better reliability.
     *
     * @param async if true, the fsync will be done asynchronously on the server.
     * @return result of the command execution
     * @throws MongoException
     * @mongodb.driver.manual reference/command/fsync/ fsync command
     */
    public CommandResult fsync(final boolean async) {
        DBObject command = new BasicDBObject("fsync", 1);
        if (async) {
            command.put("async", 1);
        }
        return getDB(ADMIN_DATABASE_NAME).command(command);
    }

    /**
     * Forces the master server to fsync the RAM data to disk, then lock all writes. The database will be read-only after this command
     * returns.
     *
     * @return result of the command execution
     * @throws MongoException
     * @mongodb.driver.manual reference/command/fsync/ fsync command
     */
    public CommandResult fsyncAndLock() {
        DBObject command = new BasicDBObject("fsync", 1);
        command.put("lock", 1);
        return getDB(ADMIN_DATABASE_NAME).command(command);
    }

    /**
     * Unlocks the database, allowing the write operations to go through. This command may be asynchronous on the server, which means there
     * may be a small delay before the database becomes writable.
     *
     * @return {@code DBObject} in the following form {@code {"ok": 1,"info": "unlock completed"}}
     * @throws MongoException
     * @mongodb.driver.manual reference/command/fsync/ fsync command
     */
    public DBObject unlock() {
        return getDB(ADMIN_DATABASE_NAME).getCollection("$cmd.sys.unlock").findOne();
    }

    /**
     * Returns true if the database is locked (read-only), false otherwise.
     *
     * @return result of the command execution
     * @throws MongoException
     * @mongodb.driver.manual reference/command/fsync/ fsync command
     */
    public boolean isLocked() {
        DBCollection inprogCollection = getDB(ADMIN_DATABASE_NAME).getCollection("$cmd.sys.inprog");
        BasicDBObject result = (BasicDBObject) inprogCollection.findOne();
        return result.containsField("fsyncLock") && result.getInt("fsyncLock") == 1;
    }

    @Override
    public String toString() {
        return "Mongo{"
               + "VERSION='" + VERSION + '\''
               + ", options=" + getOptions()
               + '}';
    }

    /**
     * Gets the maximum size for a BSON object supported by the current master server. Note that this value may change over time depending
     * on which server is master.
     *
     * @return the maximum size, or 0 if not obtained from servers yet.
     * @throws MongoException
     */
    public int getMaxBsonObjectSize() {
        List<ServerDescription> primaries = getClusterDescription().getPrimaries();
        return primaries.isEmpty() ? ServerDescription.getDefaultMaxDocumentSize() : primaries.get(0).getMaxDocumentSize();
    }

    /**
     * Gets a {@code String} representation of current connection point, i.e. master.
     *
     * @return server address in a host:port form
     */
    public String getConnectPoint() {
        ServerAddress master = getAddress();
        return master != null ? String.format("%s:%d", master.getHost(), master.getPort()) : null;
    }

    private static MongoClientOptions createLegacyOptions() {
        return MongoClientOptions.builder()
                                 .legacyDefaults()
                                 .build();
    }

    private static List<org.mongodb.MongoCredential> createNewCredentialList(final List<MongoCredential> credentialsList) {
        if (credentialsList == null) {
            return Collections.emptyList();
        }
        List<org.mongodb.MongoCredential> retVal = new ArrayList<org.mongodb.MongoCredential>(credentialsList.size());
        for (final MongoCredential cur : credentialsList) {
            retVal.add(cur.toNew());
        }
        return retVal;
    }

    private static Cluster createCluster(final MongoClientURI mongoURI) throws UnknownHostException {

        List<MongoCredential> credentialList = mongoURI.getCredentials() != null
                                               ? asList(mongoURI.getCredentials())
                                               : null;

        if (mongoURI.getHosts().size() == 1) {
            return createCluster(new ServerAddress(mongoURI.getHosts().get(0)),
                                 credentialList,
                                 mongoURI.getOptions());
        } else {
            List<ServerAddress> seedList = new ArrayList<ServerAddress>(mongoURI.getHosts().size());
            for (final String host : mongoURI.getHosts()) {
                seedList.add(new ServerAddress(host));
            }
            return createCluster(seedList, credentialList, mongoURI.getOptions());
        }
    }

    private static Cluster createCluster(final List<ServerAddress> seedList,
                                         final List<MongoCredential> credentialsList, final MongoClientOptions options) {
        return createCluster(ClusterSettings.builder().hosts(createNewSeedList(seedList))
                                            .requiredReplicaSetName(options.getRequiredReplicaSetName())
                                            .serverSelector(createServerSelector())
                                            .build(),
                             credentialsList, options);
    }

    private static Cluster createCluster(final ServerAddress serverAddress, final List<MongoCredential> credentialsList,
                                         final MongoClientOptions options) {
        return createCluster(ClusterSettings.builder()
                                            .mode(getSingleServerClusterMode(options.toNew()))
                                            .hosts(asList(serverAddress.toNew()))
                                            .requiredReplicaSetName(options.getRequiredReplicaSetName())
                                            .serverSelector(createServerSelector())
                                            .build(),
                             credentialsList, options
                            );
    }

    private static Cluster createCluster(final ClusterSettings settings, final List<MongoCredential> credentialsList,
                                         final MongoClientOptions options) {
        return new DefaultClusterFactory().create(settings,
                                                  options.getServerSettings(),
                                                  options.getConnectionPoolSettings(),
                                                  new SocketStreamFactory(options.getSocketSettings(), options.getSocketFactory()),
                                                  new SocketStreamFactory(options.getHeartbeatSocketSettings(),
                                                                          options.getSocketFactory()),
                                                  createNewCredentialList(credentialsList),
                                                  null, new JMXConnectionPoolListener(), null
                                                 );
    }

    private static List<org.mongodb.connection.ServerAddress> createNewSeedList(final List<ServerAddress> seedList) {
        List<org.mongodb.connection.ServerAddress> retVal = new ArrayList<org.mongodb.connection.ServerAddress>(seedList.size());
        for (final ServerAddress cur : seedList) {
            retVal.add(cur.toNew());
        }
        return retVal;
    }

    private static ServerSelector createServerSelector() {
        return new CompositeServerSelector(asList(new MongosHAServerSelector(), new LatencyMinimizingServerSelector(15, MILLISECONDS)));
    }

    Cluster getCluster() {
        return cluster;
    }

    Bytes.OptionHolder getOptionHolder() {
        return optionHolder;
    }

    BufferProvider getBufferProvider() {
        return bufferProvider;
    }

    Session getSession() {
        if (pinnedSession.get() != null) {
            return pinnedSession.get().session;
        }
        return new ClusterSession(getCluster());
    }

    MongoClientOptions getMongoClientOptions() {
        return options;
    }

    List<MongoCredential> getCredentialsList() {
        return credentialsList;
    }

    void pinSession() {
        SessionHolder currentlyBound = pinnedSession.get();
        if (currentlyBound == null) {
            pinnedSession.set(new SessionHolder(new PinnedSession(cluster)));
        } else {
            currentlyBound.nestedBindings++;
        }
    }

    void unpinSession() {
        SessionHolder currentlyBound = pinnedSession.get();
        if (currentlyBound != null) {
            if (currentlyBound.nestedBindings > 0) {
                currentlyBound.nestedBindings--;
            } else {
                pinnedSession.remove();
                currentlyBound.session.close();
            }
        }
    }

    void addOrphanedCursor(final ServerCursor serverCursor) {
        orphanedCursors.add(serverCursor);
    }

    public <T> T execute(final ReadOperation<T> operation, final ReadPreference readPreference) {
        ReadBinding binding = new ClusterBinding(cluster, readPreference.toNew(), options.getMaxWaitTime(), MILLISECONDS);
        try {
            return operation.execute(binding);
        } catch (org.mongodb.MongoException e) {
            throw mapException(e);
        } finally {
            binding.release();
        }
    }

    public <T> T execute(final WriteOperation<T> operation) {
        WriteBinding binding = new ClusterBinding(cluster, primary().toNew(), options.getMaxWaitTime(), MILLISECONDS);
        try {
            return operation.execute(binding);
        } catch (org.mongodb.MongoException e) {
            throw mapException(e);
        } finally {
            binding.release();
        }
    }

    private ExecutorService createCursorCleaningService() {
        ScheduledExecutorService newTimer = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
        newTimer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                cleanCursors();
            }
        }, 1, 1, SECONDS);
        return newTimer;
    }

    private void cleanCursors() {
        Session session = new ClusterSession(cluster);
        ServerCursor cur;
        try {
            while ((cur = orphanedCursors.poll()) != null) {
                ServerConnectionProviderOptions options = new ServerConnectionProviderOptions(false,
                                                                                              new ServerAddressSelector(cur.getAddress()));
                ServerConnectionProvider provider = session.createServerConnectionProvider(options);
                Connection connection = provider.getConnection();
                try {
                    new KillCursorProtocol(new KillCursor(cur)).execute(connection);
                } finally {
                    connection.close();
                }
            }
        } finally {
            session.close();
        }
    }

    private static ClusterConnectionMode getSingleServerClusterMode(final org.mongodb.MongoClientOptions options) {
        if (options.getRequiredReplicaSetName() == null) {
            return ClusterConnectionMode.SINGLE;
        } else {
            return ClusterConnectionMode.MULTIPLE;
        }
    }

    /**
     * Mongo.Holder can be used as a static place to hold several instances of Mongo. Security is not enforced at this level, and needs to
     * be done on the application side.
     */
    public static class Holder {

        private static final Holder INSTANCE = new Holder();
        private final ConcurrentMap<String, Mongo> clients = new ConcurrentHashMap<String, Mongo>();

        public static Holder singleton() {
            return INSTANCE;
        }

        /**
         * Attempts to find an existing MongoClient instance matching that URI in the holder, and returns it if exists. Otherwise creates a
         * new Mongo instance based on this URI and adds it to the holder.
         *
         * @param uri the Mongo URI
         * @return the client
         * @throws MongoException
         * @throws UnknownHostException
         * @deprecated Please use {@link #connect(MongoClientURI)} instead.
         */
        @Deprecated
        public Mongo connect(final MongoURI uri) throws UnknownHostException {
            return connect(uri.toClientURI());
        }

        /**
         * Attempts to find an existing MongoClient instance matching that URI in the holder, and returns it if exists. Otherwise creates a
         * new Mongo instance based on this URI and adds it to the holder.
         *
         * @param uri the Mongo URI
         * @return the client
         * @throws MongoException
         * @throws UnknownHostException
         */
        public Mongo connect(final MongoClientURI uri) throws UnknownHostException {

            String key = toKey(uri);

            Mongo client = clients.get(key);

            if (client == null) {
                Mongo newbie = new MongoClient(uri);
                client = clients.putIfAbsent(key, newbie);
                if (client == null) {
                    client = newbie;
                } else {
                    newbie.close();
                }
            }

            return client;
        }

        private String toKey(final MongoClientURI uri) {
            return uri.toString();
        }
    }

    private static final class SessionHolder {
        private final Session session;
        private int nestedBindings;

        private SessionHolder(final Session session) {
            this.session = session;
        }
    }

    // Custom thread factory for scheduled executor service that creates daemon threads.  Otherwise,
    // applications that neglect to close MongoClient will not exit.
    static class DaemonThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        DaemonThreadFactory() {
            namePrefix = "pool-" + poolNumber.getAndIncrement() + "-thread-";
        }

        public Thread newThread(final Runnable runnable) {
            Thread t = new Thread(runnable, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}