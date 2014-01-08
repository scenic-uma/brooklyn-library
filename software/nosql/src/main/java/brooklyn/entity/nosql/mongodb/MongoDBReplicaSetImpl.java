package brooklyn.entity.nosql.mongodb;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.enricher.CustomAggregatingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.group.AbstractMembershipTrackingPolicy;
import brooklyn.entity.group.DynamicClusterImpl;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.event.AttributeSensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.location.Location;
import brooklyn.util.collections.MutableList;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.collections.MutableSet;
import brooklyn.util.text.Strings;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

/**
 * Implementation of {@link MongoDBReplicaSet}.
 *
 * Replica sets have a <i>minimum</i> of three members.
 *
 * Removal strategy is always {@link #NON_PRIMARY_REMOVAL_STRATEGY}.
 */
public class MongoDBReplicaSetImpl extends DynamicClusterImpl implements MongoDBReplicaSet {

    private static final Logger LOG = LoggerFactory.getLogger(MongoDBReplicaSetImpl.class);

    // 8th+ members should have 0 votes
    private static final int MIN_MEMBERS = 3;
    private static final int MAX_MEMBERS = 7;

    // Provides IDs for replica set members. The first member will have ID 0.
    private final AtomicInteger nextMemberId = new AtomicInteger(0);

    private AbstractMembershipTrackingPolicy policy;
    private final AtomicBoolean mustInitialise = new AtomicBoolean(true);

    @SuppressWarnings("unchecked")
    protected static final List<AttributeSensor<Long>> SENSORS_TO_SUM = Arrays.asList(MongoDBServer.OPCOUNTERS_INSERTS, 
        MongoDBServer.OPCOUNTERS_QUERIES,
        MongoDBServer.OPCOUNTERS_UPDATES,
        MongoDBServer.OPCOUNTERS_DELETES,
        MongoDBServer.OPCOUNTERS_GETMORE,
        MongoDBServer.OPCOUNTERS_COMMAND,
        MongoDBServer.NETWORK_BYTES_IN,
        MongoDBServer.NETWORK_BYTES_OUT,
        MongoDBServer.NETWORK_NUM_REQUESTS);
    
    public MongoDBReplicaSetImpl() {
    }

    /**
     * Manages member addition and removal.
     *
     * It's important that this is a single thread: the concurrent addition and removal
     * of members from the set would almost certainly have unintended side effects,
     * like reconfigurations using outdated ReplicaSetConfig instances.
     */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    /** true iff input is a non-null MongoDBServer with attribute REPLICA_SET_MEMBER_STATUS PRIMARY. */
    static final Predicate<Entity> IS_PRIMARY = new Predicate<Entity>() {
        // getPrimary relies on instanceof check
        @Override public boolean apply(@Nullable Entity input) {
            return input != null
                    && input instanceof MongoDBServer
                    && ReplicaSetMemberStatus.PRIMARY.equals(input.getAttribute(MongoDBServer.REPLICA_SET_MEMBER_STATUS));
        }
    };

    /** true iff. input is a non-null MongoDBServer with attribute REPLICA_SET_MEMBER_STATUS SECONDARY. */
    static final Predicate<Entity> IS_SECONDARY = new Predicate<Entity>() {
        @Override public boolean apply(@Nullable Entity input) {
            // getSecondaries relies on instanceof check
            return input != null
                    && input instanceof MongoDBServer
                    && ReplicaSetMemberStatus.SECONDARY.equals(input.getAttribute(MongoDBServer.REPLICA_SET_MEMBER_STATUS));
        }
    };

    /**
     * {@link Function} for use as the cluster's removal strategy. Chooses any entity with
     * {@link MongoDBServer#IS_PRIMARY_REPLICA_SET} true last of all.
     */
    private static final Function<Collection<Entity>, Entity> NON_PRIMARY_REMOVAL_STRATEGY = new Function<Collection<Entity>, Entity>() {
        @Override
        public Entity apply(@Nullable Collection<Entity> entities) {
            checkArgument(entities != null && entities.size() > 0, "Expect list of MongoDBServers to have at least one entry");
            return Iterables.tryFind(entities, Predicates.not(IS_PRIMARY)).or(Iterables.get(entities, 0));
        }
    };

    /** @return {@link #NON_PRIMARY_REMOVAL_STRATEGY} */
    @Override
    public Function<Collection<Entity>, Entity> getRemovalStrategy() {
        return NON_PRIMARY_REMOVAL_STRATEGY;
    }

    @Override
    protected EntitySpec<?> getMemberSpec() {
        return getConfig(MEMBER_SPEC, EntitySpec.create(MongoDBServer.class));
    }

    /**
     * Sets {@link MongoDBServer#REPLICA_SET_ENABLED} and {@link MongoDBServer#REPLICA_SET_NAME}.
     */
    @Override
    protected Map<?,?> getCustomChildFlags() {
        return ImmutableMap.builder()
                .putAll(super.getCustomChildFlags())
                .put(MongoDBServer.REPLICA_SET_ENABLED, true)
                .put(MongoDBServer.REPLICA_SET_NAME, getReplicaSetName())
                .build();
    }

    @Override
    public String getReplicaSetName() {
        return getConfig(REPLICA_SET_NAME);
    }

    @Override
    public MongoDBServer getPrimary() {
        return Iterables.tryFind(getReplicas(), IS_PRIMARY).orNull();
    }

    @Override
    public Collection<MongoDBServer> getSecondaries() {
        return FluentIterable.from(getReplicas())
                .filter(IS_SECONDARY)
                .toList();
    }

    @Override
    public Collection<MongoDBServer> getReplicas() {
        return FluentIterable.from(getMembers())
                .transform(new Function<Entity, MongoDBServer>() {
                    @Override public MongoDBServer apply(Entity input) {
                        return MongoDBServer.class.cast(input);
                    }
                })
                .toList();
    }

    /**
     * Ignore attempts to resize the replica set to an even number of entities to avoid
     * having to introduce arbiters.
     * @see <a href="http://docs.mongodb.org/manual/administration/replica-set-architectures/#arbiters">
     *         http://docs.mongodb.org/manual/administration/replica-set-architectures/#arbiters</a>
     * @param desired
     *          The new size of the entity group. Ignored if even, less than {@link #MIN_MEMBERS}
     *          or more than {@link #MAX_MEMBERS}.
     * @return The eventual size of the replica set.
     */
    @Override
    public Integer resize(Integer desired) {
        // TODO support more modes than all-nodes-voting
        // (as per https://github.com/brooklyncentral/brooklyn/issues/1116)
        
        if ((desired >= MIN_MEMBERS && desired <= MAX_MEMBERS && desired % 2 == 1) || desired == 0)
            return super.resize(desired);
        
        if (desired % 2 == 0)
            throw new IllegalStateException("Ignored request to resize replica set to even number of members (only voting nodes permitted currently)");
        if (desired < MIN_MEMBERS)
            throw new IllegalStateException("Ignored request to resize replica set to size smaller than minimum (only voting nodes permitted currently)");
        if (desired > MAX_MEMBERS)
            throw new IllegalStateException("Ignored request to resize replica set to size larger than maximum (only voting nodes permitted currently)");
        
        return getCurrentSize();
    }

    /**
     * Initialises the replica set with the given server as primary if {@link #mustInitialise} is true,
     * otherwise schedules the addition of a new secondary.
     */
    private void serverAdded(MongoDBServer server) {
        LOG.debug("Server added: {}. SERVICE_UP: {}", server, server.getAttribute(MongoDBServer.SERVICE_UP));

        // Set the primary if the replica set hasn't been initialised.
        if (mustInitialise.compareAndSet(true, false)) {
            if (LOG.isInfoEnabled())
                LOG.info("First server up in {} is: {}", getReplicaSetName(), server);
            boolean replicaSetInitialised = server.getClient().initializeReplicaSet(getReplicaSetName(), nextMemberId.getAndIncrement());
            if (replicaSetInitialised) {
                setAttribute(PRIMARY_ENTITY, server);
                setAttribute(Startable.SERVICE_UP, true);
            } else {
                setAttribute(SERVICE_STATE, Lifecycle.ON_FIRE);
            }
        } else {
            if (LOG.isDebugEnabled())
                LOG.debug("Scheduling addition of member to {}: {}", getReplicaSetName(), server);
            executor.submit(addSecondaryWhenPrimaryIsNonNull(server));
        }
    }

    /**
     * Adds a server as a secondary in the replica set.
     * <p/>
     * If {@link #getPrimary} returns non-null submit the secondary to the primary's
     * {@link MongoClientSupport}. Otherwise, reschedule the task to run again in three
     * seconds time (in the hope that next time the primary will be available).
     */
    private Runnable addSecondaryWhenPrimaryIsNonNull(final MongoDBServer secondary) {
        return new Runnable() {
            @Override
            public void run() {
                // SERVICE_UP is not guaranteed when additional members are added to the set.
                Boolean isAvailable = secondary.getAttribute(MongoDBServer.SERVICE_UP);
                MongoDBServer primary = getPrimary();
                if (isAvailable && primary != null) {
                    primary.getClient().addMemberToReplicaSet(secondary, nextMemberId.incrementAndGet());
                    if (LOG.isInfoEnabled()) {
                        LOG.info("{} added to replica set {}", secondary, getReplicaSetName());
                    }
                } else {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Rescheduling addition of member {} to replica set {}: service_up={}, primary={}",
                            new Object[]{secondary, getReplicaSetName(), isAvailable, primary});
                    }
                    // Could limit number of retries
                    executor.schedule(this, 3, TimeUnit.SECONDS);
                }
            }
        };
    }

    private void serverRemoved(MongoDBServer server) {
        if (LOG.isDebugEnabled())
            LOG.debug("Scheduling removal of member from {}: {}", getReplicaSetName(), server);
        // FIXME is there a chance of race here?
        if (server.equals(getAttribute(PRIMARY_ENTITY)))
            setAttribute(PRIMARY_ENTITY, null);
        executor.submit(removeMember(server));
    }

    private Runnable removeMember(final MongoDBServer member) {
        return new Runnable() {
            @Override
            public void run() {
                // Wait until the server has been stopped before reconfiguring the set. Quoth the MongoDB doc:
                // for best results always shut down the mongod instance before removing it from a replica set.
                Boolean isAvailable = member.getAttribute(MongoDBServer.SERVICE_UP);
                // Wait for the replica set to elect a new primary if the set is reconfiguring itself.
                MongoDBServer primary = getPrimary();
                if (primary != null && !isAvailable) {
                    primary.getClient().removeMemberFromReplicaSet(member);
                    if (LOG.isInfoEnabled()) {
                        LOG.info("Removed {} from replica set {}", member, getReplicaSetName());
                    }
                } else {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Rescheduling removal of member {} from replica set {}: service_up={}, primary={}",
                            new Object[]{member, getReplicaSetName(), isAvailable, primary});
                    }
                    executor.schedule(this, 3, TimeUnit.SECONDS);
                }
            }
        };
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        // Promises that all the cluster's members have SERVICE_UP true on returning.
        super.start(locations);
        policy = new AbstractMembershipTrackingPolicy(MutableMap.of("name", getReplicaSetName() + " membership tracker")) {
            @Override protected void onEntityChange(Entity member) {
                // Ignored
            }
            @Override protected void onEntityAdded(Entity member) {
                serverAdded((MongoDBServer) member);
            }
            @Override protected void onEntityRemoved(Entity member) {
                serverRemoved((MongoDBServer) member);
            }
        };

        addPolicy(policy);
        policy.setGroup(this);

        for (AttributeSensor<Long> sensor: SENSORS_TO_SUM)
            addEnricher(CustomAggregatingEnricher.newSummingEnricher(MutableMap.of("allMembers", true), sensor, sensor, null, null));
        
        // FIXME would it be simpler to have a *subscription* on four or five sensors on allMembers, including SERVICE_UP
        // (which we currently don't check), rather than an enricher, and call to an "update" method?
        addEnricher(CustomAggregatingEnricher.newEnricher(MutableMap.of("allMembers", true), MongoDBServer.REPLICA_SET_PRIMARY_ENDPOINT, MongoDBServer.REPLICA_SET_PRIMARY_ENDPOINT,
            new Function<Collection<String>,String>() {
                @Override
                public String apply(Collection<String> input) {
                    if (input==null || input.isEmpty()) return null;
                    Set<String> distinct = MutableSet.of();
                    for (String endpoint: input)
                        if (!Strings.isBlank(endpoint))
                            distinct.add(endpoint);
                    if (distinct.size()>1) 
                        LOG.warn("Mongo replica set "+MongoDBReplicaSetImpl.this+" detetcted multiple masters (transitioning?): "+distinct);
                    return input.iterator().next();
                }
            }));

        addEnricher(CustomAggregatingEnricher.newEnricher(MutableMap.of("allMembers", true), MongoDBServer.MONGO_SERVER_ENDPOINT, REPLICA_SET_ENDPOINTS,
            new Function<Collection<String>,List<String>>() {
                @Override
                public List<String> apply(Collection<String> input) {
                    Set<String> endpoints = new TreeSet<String>();
                    for (String endpoint: input) {
                        if (!Strings.isBlank(endpoint)) {
                            endpoints.add(endpoint);
                        }
                    }
                    return MutableList.copyOf(endpoints);
                }
            }));


        subscribeToMembers(this, MongoDBServer.IS_PRIMARY_FOR_REPLICA_SET, new SensorEventListener<Boolean>() {
            @Override public void onEvent(SensorEvent<Boolean> event) {
                if (Boolean.TRUE == event.getValue())
                    setAttribute(PRIMARY_ENTITY, (MongoDBServer)event.getSource());
            }
        });

    }

    @Override
    public void stop() {
        // Do we want to remove the members from the replica set?
        //  - if the set is being stopped forever it's irrelevant
        //  - if the set might be restarted I think it just inconveniences us
        // Terminate the executor immediately.
        // Note that after this the executor will not run if the set is restarted.
        executor.shutdownNow();
        super.stop();
        setAttribute(Startable.SERVICE_UP, false);
    }

}