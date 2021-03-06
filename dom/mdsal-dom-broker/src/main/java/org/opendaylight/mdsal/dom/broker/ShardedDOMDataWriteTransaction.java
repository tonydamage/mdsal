/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.mdsal.dom.broker;

import org.opendaylight.mdsal.dom.spi.store.DOMStoreThreePhaseCommitCohort;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreWriteTransaction;

import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.mdsal.common.api.TransactionCommitFailedException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import org.opendaylight.mdsal.dom.api.DOMDataTreeIdentifier;
import org.opendaylight.mdsal.dom.api.DOMDataWriteTransaction;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NotThreadSafe
final class ShardedDOMDataWriteTransaction implements DOMDataWriteTransaction {
    private static final Logger LOG = LoggerFactory.getLogger(ShardedDOMDataWriteTransaction.class);
    private static final AtomicLong COUNTER = new AtomicLong();
    private final Map<DOMDataTreeIdentifier, DOMStoreWriteTransaction> idToTransaction;
    private final ShardedDOMDataTreeProducer producer;
    private final String identifier;
    @GuardedBy("this")
    private boolean closed =  false;

    ShardedDOMDataWriteTransaction(final ShardedDOMDataTreeProducer producer, final Map<DOMDataTreeIdentifier, DOMStoreWriteTransaction> idToTransaction) {
        this.producer = Preconditions.checkNotNull(producer);
        this.idToTransaction = Preconditions.checkNotNull(idToTransaction);
        this.identifier = "SHARDED-DOM-" + COUNTER.getAndIncrement();
    }

    // FIXME: use atomic operations
    @GuardedBy("this")
    private DOMStoreWriteTransaction lookup(final LogicalDatastoreType store, final YangInstanceIdentifier path) {
        final DOMDataTreeIdentifier id = new DOMDataTreeIdentifier(store, path);

        for (final Entry<DOMDataTreeIdentifier, DOMStoreWriteTransaction> e : idToTransaction.entrySet()) {
            if (e.getKey().contains(id)) {
                return e.getValue();
            }
        }

        throw new IllegalArgumentException(String.format("Path %s is not acessible from transaction %s", id, this));
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public synchronized boolean cancel() {
        if (closed) {
            return false;
        }

        LOG.debug("Cancelling transaction {}", identifier);
        for (final DOMStoreWriteTransaction tx : ImmutableSet.copyOf(idToTransaction.values())) {
            tx.close();
        }

        closed = true;
        producer.cancelTransaction(this);
        return true;
    }

    @Override
    public synchronized CheckedFuture<Void, TransactionCommitFailedException> submit() {
        Preconditions.checkState(!closed, "Transaction %s is already closed", identifier);

        final Set<DOMStoreWriteTransaction> txns = ImmutableSet.copyOf(idToTransaction.values());
        final List<DOMStoreThreePhaseCommitCohort> cohorts = new ArrayList<>(txns.size());
        for (final DOMStoreWriteTransaction tx : txns) {
            cohorts.add(tx.ready());
        }

        try {
            return Futures.immediateCheckedFuture(new CommitCoordinationTask(this, cohorts, null).call());
        } catch (final TransactionCommitFailedException e) {
            return Futures.immediateFailedCheckedFuture(e);
        }
    }

    @Override
    public synchronized void delete(final LogicalDatastoreType store, final YangInstanceIdentifier path) {
        lookup(store, path).delete(path);
    }

    @Override
    public synchronized void put(final LogicalDatastoreType store, final YangInstanceIdentifier path, final NormalizedNode<?, ?> data) {
        lookup(store, path).write(path, data);
    }

    @Override
    public synchronized void merge(final LogicalDatastoreType store, final YangInstanceIdentifier path, final NormalizedNode<?, ?> data) {
        lookup(store, path).merge(path, data);
    }
}
