/*
 *
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.api.producer;

import static java.lang.System.currentTimeMillis;

import com.netflix.hollow.api.consumer.HollowConsumer;
import com.netflix.hollow.api.producer.HollowProducer.Validator.ValidationException;
import com.netflix.hollow.api.producer.HollowProducerListener.ProducerStatus;
import com.netflix.hollow.api.producer.HollowProducerListener.PublishStatus;
import com.netflix.hollow.api.producer.HollowProducerListener.RestoreStatus;
import com.netflix.hollow.api.producer.fs.HollowFilesystemBlobStager;
import com.netflix.hollow.core.read.engine.HollowBlobHeaderReader;
import com.netflix.hollow.core.read.engine.HollowBlobReader;
import com.netflix.hollow.core.read.engine.HollowReadStateEngine;
import com.netflix.hollow.core.schema.HollowSchema;
import com.netflix.hollow.core.util.HollowWriteStateCreator;
import com.netflix.hollow.core.write.HollowBlobWriter;
import com.netflix.hollow.core.write.HollowWriteStateEngine;
import com.netflix.hollow.core.write.objectmapper.HollowObjectMapper;
import com.netflix.hollow.tools.checksum.HollowChecksum;
import com.netflix.hollow.tools.compact.HollowCompactor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WARNING: Beta API subject to change.
 *
 * @author Tim Taylor {@literal<tim@toolbear.io>}
 */
public class HollowProducer {

    private static final long DEFAULT_TARGET_MAX_TYPE_SHARD_SIZE = 16L * 1024L * 1024L;

    private final Logger log = Logger.getLogger(HollowProducer.class.getName());
    private final BlobStager blobStager;
    private final Publisher publisher;
    private final List<Validator> validators;
    private final Announcer announcer;
    private HollowObjectMapper objectMapper;
    private final VersionMinter versionMinter;
    private final ListenerSupport listeners;
    private ReadStateHelper readStates;
    private final Executor snapshotPublishExecutor;
    private final int numStatesBetweenSnapshots;
    private int numStatesUntilNextSnapshot;

    public HollowProducer(Publisher publisher,
                          Announcer announcer) {
        this(new HollowFilesystemBlobStager(), publisher, announcer, Collections.<Validator>emptyList(), Collections.<HollowProducerListener>emptyList(), new VersionMinterWithCounter(), null, 0, DEFAULT_TARGET_MAX_TYPE_SHARD_SIZE);
    }

    public HollowProducer(Publisher publisher,
                          Validator validator,
                          Announcer announcer) {
        this(new HollowFilesystemBlobStager(), publisher, announcer, Collections.singletonList(validator), Collections.<HollowProducerListener>emptyList(), new VersionMinterWithCounter(), null, 0, DEFAULT_TARGET_MAX_TYPE_SHARD_SIZE);
    }

    protected HollowProducer(BlobStager blobStager,
                             Publisher publisher,
                             Announcer announcer,
                             List<Validator> validators,
                             List<HollowProducerListener> listeners,
                             VersionMinter versionMinter,
                             Executor snapshotPublishExecutor,
                             int numStatesBetweenSnapshots,
                             long targetMaxTypeShardSize) {
        this.publisher = publisher;
        this.validators = validators;
        this.announcer = announcer;
        this.versionMinter = versionMinter;
        this.blobStager = blobStager;
        this.snapshotPublishExecutor = snapshotPublishExecutor == null ? new Executor() {
            public void execute(Runnable command) {
                command.run();
            }
        } : snapshotPublishExecutor;
        this.numStatesBetweenSnapshots = numStatesBetweenSnapshots;

        HollowWriteStateEngine writeEngine = new HollowWriteStateEngine();
        writeEngine.setTargetMaxTypeShardSize(targetMaxTypeShardSize);

        this.objectMapper = new HollowObjectMapper(writeEngine);
        this.listeners = new ListenerSupport();
        this.readStates = ReadStateHelper.newDeltaChain();
        
        for(HollowProducerListener listener : listeners)
            this.listeners.add(listener);
    }

    public void initializeDataModel(Class<?>...classes) {
        long start = currentTimeMillis();
        for(Class<?> c : classes)
            objectMapper.initializeTypeState(c);
        listeners.fireProducerInit(currentTimeMillis() - start);
    }
    protected HollowWriteStateEngine getWriteEngine() {
        return objectMapper.getStateEngine();
    }

    public void initializeDataModel(HollowSchema... schemas) {
        long start = currentTimeMillis();
        HollowWriteStateCreator.populateStateEngineWithTypeWriteStates(getWriteEngine(), Arrays.asList(schemas));
        listeners.fireProducerInit(currentTimeMillis() - start);
    }

    public HollowProducer.ReadState restore(long versionDesired, HollowConsumer.BlobRetriever blobRetriever) {
        long start = currentTimeMillis();
        RestoreStatus status = RestoreStatus.unknownFailure();
        ReadState readState = null;

        try {
            listeners.fireProducerRestoreStart(versionDesired);
            if(versionDesired != Long.MIN_VALUE) {

                HollowConsumer client = HollowConsumer.withBlobRetriever(blobRetriever).build();
                client.triggerRefreshTo(versionDesired);
                readState = ReadStateHelper.newReadState(client.getCurrentVersionId(), client.getStateEngine());
                if(readState.getVersion() == versionDesired) {
                    readStates = ReadStateHelper.restored(readState);

                    // Need to restore data to new ObjectMapper since can't restore to non empty Write State Engine
                    HollowObjectMapper newObjectMapper = createNewHollowObjectMapperFromExisting(objectMapper);
                    newObjectMapper.getStateEngine().restoreFrom(readStates.current().getStateEngine());
                    status = RestoreStatus.success(versionDesired, readState.getVersion());
                    objectMapper = newObjectMapper; // Restore completed successfully so swap
                } else {
                    status = RestoreStatus.fail(versionDesired, readState.getVersion(), null);
                }
            }
        } catch(Throwable th) {
            status = RestoreStatus.fail(versionDesired, readState != null ? readState.getVersion() : Long.MIN_VALUE, th);
        } finally {
            listeners.fireProducerRestoreComplete(status, currentTimeMillis() - start);
        }
        return readState;
    }

    private static HollowObjectMapper createNewHollowObjectMapperFromExisting(HollowObjectMapper objectMapper) {
        Collection<HollowSchema> schemas = objectMapper.getStateEngine().getSchemas();
        HollowWriteStateEngine writeEngine = HollowWriteStateCreator.createWithSchemas(schemas);
        return new HollowObjectMapper(writeEngine);
    }

    /**
     * Each cycle produces a single data state.
     */
    public void runCycle(Populator task) {
        long toVersion = versionMinter.mint();

        if(!readStates.hasCurrent()) listeners.fireNewDeltaChain(toVersion);
        ProducerStatus.Builder cycleStatus = listeners.fireCycleStart(toVersion);

        try {
            runCycle(task, cycleStatus, toVersion);
        } finally {
            listeners.fireCycleComplete(cycleStatus);
        }
    }

    public boolean runCompactionCycle(HollowCompactor.CompactionConfig config) {
        if(config != null && readStates.hasCurrent()) {
            final HollowCompactor compactor = new HollowCompactor(getWriteEngine(), readStates.current().getStateEngine(), config);
            if(compactor.needsCompaction()) {
                runCycle(new Populator() {
                    @Override
                    public void populate(WriteState newState) throws Exception {
                        compactor.compact();
                    }
                });
                
                return true;
            }
        }
        
        return false;
    }

    protected void runCycle(Populator task, ProducerStatus.Builder cycleStatus, long toVersion) {
        // 1. Begin a new cycle
        Artifacts artifacts = new Artifacts();
        HollowWriteStateEngine writeEngine = getWriteEngine();
        try {
            // 1a. Prepare the write state
            writeEngine.prepareForNextCycle();
            WriteState writeState = new WriteStateImpl(toVersion, objectMapper, readStates.current());

            // 2. Populate the state
            ProducerStatus.Builder populateStatus = listeners.firePopulateStart(toVersion);
            try {
                task.populate(writeState);
                populateStatus.success();
            } catch (Throwable th) {
                populateStatus.fail(th);
                throw th;
            } finally {
                listeners.firePopulateComplete(populateStatus);
            }

            // 3. Produce a new state if there's work to do
            if(writeEngine.hasChangedSinceLastCycle()) {
                // 3a. Publish, run checks & validation, then announce new state consumers
                publish(writeState, artifacts);

                ReadStateHelper candidate = readStates.roundtrip(writeState);
                cycleStatus.version(candidate.pending());
                candidate = checkIntegrity(candidate, artifacts);

                validate(candidate.pending());

                announce(candidate.pending());
                readStates = candidate.commit();
                cycleStatus.version(readStates.current()).success();
            } else {
                // 3b. Nothing to do; reset the effects of Step 2
                writeEngine.resetToLastPrepareForNextCycle();
                listeners.fireNoDelta(cycleStatus.success());
            }
        } catch(Throwable th) {
            writeEngine.resetToLastPrepareForNextCycle();
            cycleStatus.fail(th);
        } finally {
            artifacts.cleanup();
        }
    }

    public void addListener(HollowProducerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(HollowProducerListener listener) {
        listeners.remove(listener);
    }

    private void publish(final WriteState writeState, final Artifacts artifacts) throws IOException {
        ProducerStatus.Builder psb = listeners.firePublishStart(writeState.getVersion());
        try {
            stageBlob(writeState, artifacts, Blob.Type.SNAPSHOT);
            
            if (readStates.hasCurrent()) {
                stageBlob(writeState, artifacts, Blob.Type.DELTA);
                stageBlob(writeState, artifacts, Blob.Type.REVERSE_DELTA);
                publishBlob(writeState, artifacts, Blob.Type.DELTA);
                publishBlob(writeState, artifacts, Blob.Type.REVERSE_DELTA);
                
                if(--numStatesUntilNextSnapshot < 0) {
                    snapshotPublishExecutor.execute(new Runnable() {
                        public void run() {
                            try {
                                publishBlob(writeState, artifacts, Blob.Type.SNAPSHOT);
                                artifacts.markSnapshotPublishComplete();
                            } catch(IOException e) {
                                log.log(Level.WARNING, "Snapshot publish failed", e);
                            }
                        }
                    });
                    numStatesUntilNextSnapshot = numStatesBetweenSnapshots;
                } else {
                    artifacts.markSnapshotPublishComplete();
                }
            } else {
                publishBlob(writeState, artifacts, Blob.Type.SNAPSHOT);
                artifacts.markSnapshotPublishComplete();
                numStatesUntilNextSnapshot = numStatesBetweenSnapshots;
            }
            
            psb.success();

        } catch (Throwable throwable) {
            psb.fail(throwable);
            throw throwable;
        } finally {
            listeners.firePublishComplete(psb);
        }
    }
    
    private void stageBlob(WriteState writeState, Artifacts artifacts, Blob.Type blobType) throws IOException {
        HollowBlobWriter writer = new HollowBlobWriter(getWriteEngine());
        try {
            switch (blobType) {
                case SNAPSHOT:
                    artifacts.snapshot = blobStager.openSnapshot(writeState.getVersion());
                    artifacts.snapshot.write(writer);
                    break;
                case DELTA:
                    artifacts.delta = blobStager.openDelta(readStates.current().getVersion(), writeState.getVersion());
                    artifacts.delta.write(writer);
                    break;
                case REVERSE_DELTA:
                    artifacts.reverseDelta = blobStager.openReverseDelta(writeState.getVersion(), readStates.current().getVersion());
                    artifacts.reverseDelta.write(writer);
                    break;
                default:
                    throw new IllegalStateException("unknown type, type=" + blobType);
            }

        } catch (Throwable th) {
            throw th;
        }
    }

    private void publishBlob(WriteState writeState, Artifacts artifacts, Blob.Type blobType) throws IOException {
        PublishStatus.Builder builder = (new PublishStatus.Builder());
        try {
            switch (blobType) {
                case SNAPSHOT:
                    builder.blob(artifacts.snapshot);
                    publisher.publish(artifacts.snapshot);
                    break;
                case DELTA:
                    builder.blob(artifacts.delta);
                    publisher.publish(artifacts.delta);
                    break;
                case REVERSE_DELTA:
                    builder.blob(artifacts.reverseDelta);
                    publisher.publish(artifacts.reverseDelta);
                    break;
                default:
                    throw new IllegalStateException("unknown type, type=" + blobType);
            }
            builder.success();

        } catch (Throwable th) {
            builder.fail(th);
            throw th;
        } finally {
            listeners.fireArtifactPublish(builder);
        }
    }

    /**
     *  Given these read states
     *
     *  * S(cur) at the currently announced version
     *  * S(pnd) at the pending version
     *
     *  Ensure that:
     *
     *  S(cur).apply(forwardDelta).checksum == S(pnd).checksum
     *  S(pnd).apply(reverseDelta).checksum == S(cur).checksum
     *
     * @param readStates
     * @return updated read states
     */
    private ReadStateHelper checkIntegrity(ReadStateHelper readStates, Artifacts artifacts) throws Exception {
        ProducerStatus.Builder status = listeners.fireIntegrityCheckStart(readStates.pending());
        try {
            ReadStateHelper result = readStates;
            HollowReadStateEngine current = readStates.hasCurrent() ? readStates.current().getStateEngine() : null;
            HollowReadStateEngine pending = readStates.pending().getStateEngine();
            readSnapshot(artifacts.snapshot, pending);

            if(readStates.hasCurrent()) {
                log.info("CHECKSUMS");
                HollowChecksum currentChecksum = HollowChecksum.forStateEngineWithCommonSchemas(current, pending);
                log.info("  CUR        " + currentChecksum);

                HollowChecksum pendingChecksum = HollowChecksum.forStateEngineWithCommonSchemas(pending, current);
                log.info("         PND " + pendingChecksum);

                if(artifacts.hasDelta()) {
                    // FIXME: timt: future cycles will fail unless this delta validates *and* we have a reverse
                    // delta *and* it also validates
                    applyDelta(artifacts.delta, current);
                    HollowChecksum forwardChecksum = HollowChecksum.forStateEngineWithCommonSchemas(current, pending);
                    //out.format("  CUR => PND %s\n", forwardChecksum);
                    if(!forwardChecksum.equals(pendingChecksum)) throw new ChecksumValidationException(Blob.Type.DELTA);
                }

                if(artifacts.hasReverseDelta()) {
                    applyDelta(artifacts.reverseDelta, pending);
                    HollowChecksum reverseChecksum = HollowChecksum.forStateEngineWithCommonSchemas(pending, current);
                    //out.format("  CUR <= PND %s\n", reverseChecksum);
                    if(!reverseChecksum.equals(currentChecksum)) throw new ChecksumValidationException(Blob.Type.REVERSE_DELTA);
                    result = readStates.swap();
                }
            }
            status.success();
            return result;
        } catch(Throwable th) {
            status.fail(th);
            throw th;
        } finally {
            listeners.fireIntegrityCheckComplete(status);
        }
    }

    public static final class ChecksumValidationException extends IllegalStateException {
        private static final long serialVersionUID = -4399719849669674206L;

        ChecksumValidationException(Blob.Type type) {
            super(type.name() + " checksum invalid");
        }
    }

    private void readSnapshot(Blob blob, HollowReadStateEngine stateEngine) throws IOException {
        InputStream is = blob.newInputStream();
        try {
            new HollowBlobReader(stateEngine, new HollowBlobHeaderReader()).readSnapshot(is);
        } finally {
            is.close();
        }
    }

    private void applyDelta(Blob blob, HollowReadStateEngine stateEngine) throws IOException {
        InputStream is = blob.newInputStream();
        try {
            new HollowBlobReader(stateEngine, new HollowBlobHeaderReader()).applyDelta(is);
        } finally {
            is.close();
        }
    }

    private void validate(HollowProducer.ReadState readState) {
        ProducerStatus.Builder status = listeners.fireValidationStart(readState);
        try {
            List<Throwable> validationFailures = new ArrayList<Throwable>();
            
            for(Validator validator : validators) {
                try {
                    validator.validate(readState);
                } catch(Throwable th) {
                    validationFailures.add(th);
                }
            }
            
            if(!validationFailures.isEmpty()) {
                ValidationException ex = new ValidationException("Validation Failed", validationFailures.get(0));
                ex.setIndividualFailures(validationFailures);
                throw ex;
            }
            
            status.success();
        } catch (Throwable th) {
            status.fail(th);
            throw th;
        } finally {
            listeners.fireValidationComplete(status);
        }
    }

    private void announce(HollowProducer.ReadState readState) {
        if(announcer != null) {
            ProducerStatus.Builder status = listeners.fireAnnouncementStart(readState);
            try {
                announcer.announce(readState.getVersion());
                status.success();
            } catch(Throwable th) {
                status.fail(th);
                throw th;
            } finally {
                listeners.fireAnnouncementComplete(status);
            }
        }
    }

    static interface VersionMinter {
        /**
         * Create a new state version.<p>
         *
         * State versions should be ascending -- later states have greater versions.<p>
         *
         * @return a new state version
         */
        long mint();
    }

    public static interface Populator {
        void populate(HollowProducer.WriteState newState) throws Exception;
    }

    public static interface WriteState {
        int add(Object o);

        HollowObjectMapper getObjectMapper();

        HollowWriteStateEngine getStateEngine();

        ReadState getPriorState();

        long getVersion();
    }
    
    public static interface ReadState {
        public long getVersion();

        public HollowReadStateEngine getStateEngine();
    }
    
    
    public static interface BlobStager {
        /**
         * Returns a blob with which a {@code HollowProducer} will write a snapshot for the version specified.<p>
         *
         * The producer will pass the returned blob back to this publisher when calling {@link Publisher#publish(Blob, Map)}.
         *
         * @param version the blob version
         *
         * @return a {@link HollowProducer.Blob} representing a snapshot for the {@code version}
         */
        public HollowProducer.Blob openSnapshot(long version);
        
        /**
         * Returns a blob with which a {@code HollowProducer} will write a forward delta from the version specified to
         * the version specified, i.e. {@code fromVersion => toVersion}.<p>
         *
         * The producer will pass the returned blob back to this publisher when calling {@link Publisher#publish(Blob, Map)}.
         *
         * In the delta chain {@code fromVersion} is the older version such that {@code fromVersion < toVersion}.
         *
         * @param fromVersion the data state this delta will transition from
         * @param toVersion the data state this delta will transition to
         *
         * @return a {@link HollowProducer.Blob} representing a snapshot for the {@code version}
         */
        public HollowProducer.Blob openDelta(long fromVersion, long toVersion);
        
        /**
         * Returns a blob with which a {@code HollowProducer} will write a reverse delta from the version specified to
         * the version specified, i.e. {@code fromVersion <= toVersion}.<p>
         *
         * The producer will pass the returned blob back to this publisher when calling {@link Publisher#publish(Blob, Map)}.
         *
         * In the delta chain {@code fromVersion} is the older version such that {@code fromVersion < toVersion}.
         *
         * @param fromVersion version in the delta chain immediately after {@code toVersion}
         * @param toVersion version in the delta chain immediately before {@code fromVersion}
         *
         * @return a {@link HollowProducer.Blob} representing a snapshot for the {@code version}
         */
        public HollowProducer.Blob openReverseDelta(long fromVersion, long toVersion);
    }
    
    public static interface BlobCompressor {
        public static final BlobCompressor NO_COMPRESSION = new BlobCompressor() {
            public OutputStream compress(OutputStream os) { return os; }

            public InputStream decompress(InputStream is) { return is; }
        };

        /**
         * This method provides an opportunity to wrap the OutputStream used to write the blob (e.g. with a GZIPOutputStream).
         */
        public OutputStream compress(OutputStream is);
        
        /**
         * This method provides an opportunity to wrap the InputStream used to write the blob (e.g. with a GZIPInputStream).
         */
        public InputStream decompress(InputStream is);
    }

    

    public static interface Publisher {

        /**
         * Publish the blob specified to this publisher's blobstore.<p>
         *
         * It is guaranteed that {@code blob} was created by calling one of
         * {@link Publisher#openSnapshot(long)}, {@link Publisher#openDelta(long,long)}, or
         * {@link Publisher#openReverseDelta(long,long)} on this publisher.
         *
         * @param blob the blob to publish
         */
        public abstract void publish(HollowProducer.Blob blob);

    }

    public static abstract class Blob {

        protected final long fromVersion;
        protected final long toVersion;
        protected final Blob.Type type;


        protected Blob(long fromVersion, long toVersion, Blob.Type type) {
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
            this.type = type;
        }

        protected abstract void write(HollowBlobWriter writer) throws IOException;

        public abstract InputStream newInputStream() throws IOException;

        public abstract void cleanup();

        public File getFile() {
            throw new UnsupportedOperationException("File is not available");
        }

        public Type getType() {
            return this.type;
        }

        public long getFromVersion() {
            return this.fromVersion;
        }

        public long getToVersion() {
            return this.toVersion;
        }

        /**
         * Hollow blob types are {@code SNAPSHOT}, {@code DELTA} and {@code REVERSE_DELTA}.
         */
        public static enum Type {
            SNAPSHOT("snapshot"),
            DELTA("delta"),
            REVERSE_DELTA("reversedelta");

            public final String prefix;

            Type(String prefix) {
                this.prefix = prefix;
            }
        }
    }

    public static interface Validator {
        void validate(HollowProducer.ReadState readState);
    
        @SuppressWarnings("serial")
        public static class ValidationException extends RuntimeException {
            private List<Throwable> individualFailures;
            
            public ValidationException() {
                super();
            }
            
            public ValidationException(String msg) {
                super(msg);
            }
            
            public ValidationException(String msg, Throwable cause) {
                super(msg, cause);
            }
            
            public void setIndividualFailures(List<Throwable> individualFailures) {
                this.individualFailures = individualFailures;
            }
            
            public List<Throwable> getIndividualFailures() {
                return individualFailures;
            }
        }
    }

    public static interface Announcer {
        public void announce(long stateVersion);
    }

    private static final class Artifacts {
        Blob snapshot = null;
        Blob delta = null;
        Blob reverseDelta = null;
        
        boolean cleanupCalled;
        boolean snapshotPublishComplete;

        synchronized void cleanup() {
            cleanupCalled = true;
            
            cleanupSnapshot();
            
            if(delta != null) {
                delta.cleanup();
                delta = null;
            }
            if(reverseDelta != null) {
                reverseDelta.cleanup();
                reverseDelta = null;
            }
        }
        
        synchronized void markSnapshotPublishComplete() {
            snapshotPublishComplete = true;
            
            cleanupSnapshot();
        }
        
        private void cleanupSnapshot() {
            if(cleanupCalled && snapshotPublishComplete && snapshot != null) {
                snapshot.cleanup();
                snapshot = null;
            }
        }

        boolean hasDelta() {
            return delta != null;
        }

        public boolean hasReverseDelta() {
            return reverseDelta != null;
        }
    }
    
    public static HollowProducer.Builder withPublisher(HollowProducer.Publisher publisher) {
        Builder builder = new Builder();
        return builder.withPublisher(publisher);
    }
    
    public static class Builder {
        private BlobStager stager;
        private BlobCompressor compressor;
        private File stagingDir;
        private Publisher publisher;
        private Announcer announcer;
        private List<Validator> validators = new ArrayList<Validator>();
        private List<HollowProducerListener> listeners = new ArrayList<HollowProducerListener>();
        private VersionMinter versionMinter = new VersionMinterWithCounter();
        private Executor snapshotPublishExecutor = null;
        private int numStatesBetweenSnapshots = 0;
        private long targetMaxTypeShardSize = DEFAULT_TARGET_MAX_TYPE_SHARD_SIZE;
        
        public Builder withBlobStager(HollowProducer.BlobStager stager) {
            this.stager = stager;
            return this;
        }
        
        public Builder withBlobCompressor(HollowProducer.BlobCompressor compressor) {
            this.compressor = compressor;
            return this;
        }
        
        public Builder withBlobStagingDir(File stagingDir) {
            this.stagingDir = stagingDir;
            return this;
        }
        
        public Builder withPublisher(HollowProducer.Publisher publisher) {
            this.publisher = publisher; 
            return this;
        }
        
        public Builder withAnnouncer(HollowProducer.Announcer announcer) {
            this.announcer = announcer;
            return this;
        }
        
        public Builder withValidator(HollowProducer.Validator validator) {
            this.validators.add(validator);
            return this;
        }
        
        public Builder withValidators(HollowProducer.Validator... validators) {
            for(Validator validator : validators)
                this.validators.add(validator);
            return this;
        }
        
        public Builder withListener(HollowProducerListener listener) {
            this.listeners.add(listener);
            return this;
        }
        
        public Builder withListeners(HollowProducerListener... listeners) {
            for(HollowProducerListener listener : listeners)
                this.listeners.add(listener);
            return this;
        }
        
        public Builder withVersionMinter(HollowProducer.VersionMinter versionMinter) {
            this.versionMinter = versionMinter;
            return this;
        }
        
        public Builder withSnapshotPublishExecutor(Executor executor) {
            this.snapshotPublishExecutor = executor;
            return this;
        }
        
        public Builder withNumStatesBetweenSnapshots(int numStatesBetweenSnapshots) {
            this.numStatesBetweenSnapshots = numStatesBetweenSnapshots;
            return this;
        }
        
        public Builder withTargetMaxTypeShardSize(long targetMaxTypeShardSize) {
            this.targetMaxTypeShardSize = targetMaxTypeShardSize;
            return this;
        }
        
        public HollowProducer build() {
            if(stager != null && compressor != null)
                throw new IllegalArgumentException("Both a custom BlobStager and BlobCompressor were specified -- please specify only one of these.");
            if(stager != null && stagingDir != null)
                throw new IllegalArgumentException("Both a custom BlobStager and a staging directory were specified -- please specify only one of these.");
            
            BlobStager stager = this.stager;
            if(stager == null) {
                BlobCompressor compressor = this.compressor != null ? this.compressor : BlobCompressor.NO_COMPRESSION;
                File stagingDir = this.stagingDir != null ? this.stagingDir : new File(System.getProperty("java.io.tmpdir"));
                stager = new HollowFilesystemBlobStager(stagingDir, compressor);
            }
            
            return new HollowProducer(stager, publisher, announcer, validators, listeners, versionMinter, snapshotPublishExecutor, numStatesBetweenSnapshots, targetMaxTypeShardSize);
        }
    }
    
}
