/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.mdsal.dom.broker;

import org.opendaylight.mdsal.dom.spi.store.DOMStoreReadTransaction;

import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.mdsal.common.api.ReadFailedException;
import org.opendaylight.mdsal.dom.api.DOMDataReadOnlyTransaction;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;
import java.util.Map;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;

/**
 * Read Only Transaction, which is composed of several
 * {@link DOMStoreReadTransaction} transactions. Subtransaction is selected by
 * {@link LogicalDatastoreType} type parameter in
 * {@link #read(LogicalDatastoreType, YangInstanceIdentifier)}.
 */
class DOMForwardedReadOnlyTransaction extends
        AbstractDOMForwardedCompositeTransaction<LogicalDatastoreType, DOMStoreReadTransaction> implements
        DOMDataReadOnlyTransaction {

    protected DOMForwardedReadOnlyTransaction(final Object identifier,
            final Map<LogicalDatastoreType, DOMStoreReadTransaction> backingTxs) {
        super(identifier, backingTxs);
    }

    @Override
    public CheckedFuture<Optional<NormalizedNode<?,?>>, ReadFailedException> read(
            final LogicalDatastoreType store, final YangInstanceIdentifier path) {
        return getSubtransaction(store).read(path);
    }

    @Override
    public CheckedFuture<Boolean, ReadFailedException> exists(
        final LogicalDatastoreType store,
        final YangInstanceIdentifier path) {
        return getSubtransaction(store).exists(path);
    }

    @Override
    public void close() {
        closeSubtransactions();
    }
}
