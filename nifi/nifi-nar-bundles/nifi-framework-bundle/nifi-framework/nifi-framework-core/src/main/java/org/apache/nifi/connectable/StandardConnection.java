/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.connectable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.nifi.controller.FlowFileQueue;
import org.apache.nifi.controller.ProcessScheduler;
import org.apache.nifi.controller.StandardFlowFileQueue;
import org.apache.nifi.controller.repository.FlowFileRecord;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.processor.FlowFileFilter;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.util.NiFiProperties;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * Models a connection between connectable components. A connection may contain one or more relationships that map the source component to the destination component.
 */
public final class StandardConnection implements Connection {

    private final String id;
    private final AtomicReference<ProcessGroup> processGroup;
    private final AtomicReference<String> name;
    private final AtomicReference<List<Position>> bendPoints;
    private final Connectable source;
    private final AtomicReference<Connectable> destination;
    private final AtomicReference<Collection<Relationship>> relationships;
    private final StandardFlowFileQueue flowFileQueue;
    private final AtomicInteger labelIndex = new AtomicInteger(1);
    private final AtomicLong zIndex = new AtomicLong(0L);
    private final ProcessScheduler scheduler;
    private final int hashCode;

    private StandardConnection(final Builder builder) {
        id = builder.id;
        name = new AtomicReference<>(builder.name);
        bendPoints = new AtomicReference<>(Collections.unmodifiableList(new ArrayList<>(builder.bendPoints)));
        processGroup = new AtomicReference<>(builder.processGroup);
        source = builder.source;
        destination = new AtomicReference<>(builder.destination);
        relationships = new AtomicReference<>(Collections.unmodifiableCollection(builder.relationships));
        scheduler = builder.scheduler;
        flowFileQueue = new StandardFlowFileQueue(id, this, scheduler, NiFiProperties.getInstance().getQueueSwapThreshold());
        hashCode = new HashCodeBuilder(7, 67).append(id).toHashCode();
    }

    @Override
    public ProcessGroup getProcessGroup() {
        return processGroup.get();
    }

    @Override
    public String getIdentifier() {
        return id;
    }

    @Override
    public String getName() {
        return name.get();
    }

    @Override
    public void setName(final String name) {
        this.name.set(name);
    }

    @Override
    public List<Position> getBendPoints() {
        return bendPoints.get();
    }

    @Override
    public void setBendPoints(final List<Position> position) {
        this.bendPoints.set(Collections.unmodifiableList(new ArrayList<>(position)));
    }

    @Override
    public int getLabelIndex() {
        return labelIndex.get();
    }

    @Override
    public void setLabelIndex(final int labelIndex) {
        this.labelIndex.set(labelIndex);
    }

    @Override
    public long getZIndex() {
        return zIndex.get();
    }

    @Override
    public void setZIndex(final long zIndex) {
        this.zIndex.set(zIndex);
    }

    @Override
    public Connectable getSource() {
        return source;
    }

    @Override
    public Connectable getDestination() {
        return destination.get();
    }

    @Override
    public Collection<Relationship> getRelationships() {
        return relationships.get();
    }

    @Override
    public FlowFileQueue getFlowFileQueue() {
        return flowFileQueue;
    }

    @Override
    public void setProcessGroup(final ProcessGroup newGroup) {
        final ProcessGroup currentGroup = this.processGroup.get();
        try {
            this.processGroup.set(newGroup);
        } catch (final RuntimeException e) {
            this.processGroup.set(currentGroup);
            throw e;
        }
    }

    @Override
    public void setRelationships(final Collection<Relationship> newRelationships) {
        final Collection<Relationship> currentRelationships = relationships.get();
        if (currentRelationships.equals(newRelationships)) {
            return;
        }

        if (getSource().isRunning()) {
            throw new IllegalStateException("Cannot update the relationships for Connection because the source of the Connection is running");
        }

        try {
            this.relationships.set(new ArrayList<>(newRelationships));
            getSource().updateConnection(this);
        } catch (final RuntimeException e) {
            this.relationships.set(currentRelationships);
            throw e;
        }
    }

    @Override
    public void setDestination(final Connectable newDestination) {
        final Connectable previousDestination = destination.get();
        if (previousDestination.equals(newDestination)) {
            return;
        }

        if (previousDestination.isRunning() && !(previousDestination instanceof Funnel || previousDestination instanceof LocalPort)) {
            throw new IllegalStateException("Cannot change destination of Connection because the current destination is running");
        }

        if (getFlowFileQueue().getUnacknowledgedQueueSize().getObjectCount() > 0) {
            throw new IllegalStateException("Cannot change destination of Connection because FlowFiles from this Connection are currently held by " + previousDestination);
        }

        try {
            previousDestination.removeConnection(this);
            this.destination.set(newDestination);
            getSource().updateConnection(this);

            newDestination.addConnection(this);
            scheduler.registerEvent(newDestination);
        } catch (final RuntimeException e) {
            this.destination.set(previousDestination);
            throw e;
        }
    }

    @Override
    public void lock() {
        flowFileQueue.lock();
    }

    @Override
    public void unlock() {
        flowFileQueue.unlock();
    }

    @Override
    public List<FlowFileRecord> poll(final FlowFileFilter filter, final Set<FlowFileRecord> expiredRecords) {
        return flowFileQueue.poll(filter, expiredRecords);
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof Connection)) {
            return false;
        }
        final Connection con = (Connection) other;
        return new EqualsBuilder().append(id, con.getIdentifier()).isEquals();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "Connection[ID=" + id + ",Name=" + name.get() + ",Source=" + getSource() + ",Destination=" + getDestination() + ",Relationships=" + getRelationships();
    }

    /**
     * Gives this Connection ownership of the given FlowFile and allows the Connection to hold on to the FlowFile but NOT provide the FlowFile to consumers. This allows us to ensure that the
     * Connection is not deleted during the middle of a Session commit.
     *
     * @param flowFile to add
     */
    @Override
    public void enqueue(final FlowFileRecord flowFile) {
        flowFileQueue.put(flowFile);
    }

    @Override
    public void enqueue(final Collection<FlowFileRecord> flowFiles) {
        flowFileQueue.putAll(flowFiles);
    }

    public static class Builder {

        private final ProcessScheduler scheduler;

        private String id = UUID.randomUUID().toString();
        private String name;
        private List<Position> bendPoints = new ArrayList<>();
        private ProcessGroup processGroup;
        private Connectable source;
        private Connectable destination;
        private Collection<Relationship> relationships;

        public Builder(final ProcessScheduler scheduler) {
            this.scheduler = scheduler;
        }

        public Builder id(final String id) {
            this.id = id;
            return this;
        }

        public Builder source(final Connectable source) {
            this.source = source;
            return this;
        }

        public Builder processGroup(final ProcessGroup group) {
            this.processGroup = group;
            return this;
        }

        public Builder destination(final Connectable destination) {
            this.destination = destination;
            return this;
        }

        public Builder relationships(final Collection<Relationship> relationships) {
            this.relationships = new ArrayList<>(relationships);
            return this;
        }

        public Builder name(final String name) {
            this.name = name;
            return this;
        }

        public Builder bendPoints(final List<Position> bendPoints) {
            this.bendPoints.clear();
            this.bendPoints.addAll(bendPoints);
            return this;
        }

        public Builder addBendPoint(final Position bendPoint) {
            bendPoints.add(bendPoint);
            return this;
        }

        public StandardConnection build() {
            if (source == null) {
                throw new IllegalStateException("Cannot build a Connection without a Source");
            }
            if (destination == null) {
                throw new IllegalStateException("Cannot build a Connection without a Destination");
            }

            if (relationships == null) {
                relationships = new ArrayList<>();
            }

            if (relationships.isEmpty()) {
                // ensure relationships have been specified for processors, otherwise the anonymous relationship is used
                if (source.getConnectableType() == ConnectableType.PROCESSOR) {
                    throw new IllegalStateException("Cannot build a Connection without any relationships");
                }
                relationships.add(Relationship.ANONYMOUS);
            }

            return new StandardConnection(this);
        }
    }

    @Override
    public void verifyCanUpdate() {
        // StandardConnection can always be updated
    }

    @Override
    public void verifyCanDelete() {
        if (!flowFileQueue.isEmpty()) {
            throw new IllegalStateException("Queue not empty for " + this);
        }

        if (source.isRunning()) {
            if (!ConnectableType.FUNNEL.equals(source.getConnectableType())) {
                throw new IllegalStateException("Source of Connection (" + source + ") is running");
            }
        }
    }
}
