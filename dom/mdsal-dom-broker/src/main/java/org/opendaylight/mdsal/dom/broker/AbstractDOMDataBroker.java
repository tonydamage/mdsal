/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.mdsal.dom.broker;

import static com.google.common.base.Preconditions.checkState;

import org.opendaylight.mdsal.dom.spi.store.DOMStore;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreTransactionChain;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreTreeChangePublisher;

import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.mdsal.common.api.TransactionChainListener;
import org.opendaylight.mdsal.dom.api.DOMDataBroker;
import org.opendaylight.mdsal.dom.api.DOMDataBrokerExtension;
import org.opendaylight.mdsal.dom.api.DOMDataChangeListener;
import org.opendaylight.mdsal.dom.api.DOMDataTreeChangeListener;
import org.opendaylight.mdsal.dom.api.DOMDataTreeChangeService;
import org.opendaylight.mdsal.dom.api.DOMDataTreeIdentifier;
import org.opendaylight.mdsal.dom.api.DOMTransactionChain;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractDOMDataBroker extends AbstractDOMForwardedTransactionFactory<DOMStore> implements DOMDataBroker, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractDOMDataBroker.class);

    private final AtomicLong txNum = new AtomicLong();
    private final AtomicLong chainNum = new AtomicLong();
    private final Map<Class<? extends DOMDataBrokerExtension>, DOMDataBrokerExtension> extensions;
    private volatile AutoCloseable closeable;

    protected AbstractDOMDataBroker(final Map<LogicalDatastoreType, DOMStore> datastores) {
        super(datastores);

        boolean treeChange = true;
        for (DOMStore ds : datastores.values()) {
            if (!(ds instanceof DOMStoreTreeChangePublisher)) {
                treeChange = false;
                break;
            }
        }

        if (treeChange) {
            extensions = ImmutableMap.<Class<? extends DOMDataBrokerExtension>, DOMDataBrokerExtension>of(DOMDataTreeChangeService.class, new DOMDataTreeChangeService() {
                @Override
                public <L extends DOMDataTreeChangeListener> ListenerRegistration<L> registerDataTreeChangeListener(final DOMDataTreeIdentifier treeId, final L listener) {
                    DOMStore publisher = getTxFactories().get(treeId.getDatastoreType());
                    checkState(publisher != null, "Requested logical data store is not available.");

                    return ((DOMStoreTreeChangePublisher)publisher).registerTreeChangeListener(treeId.getRootIdentifier(), listener);
                }
            });
        } else {
            extensions = Collections.emptyMap();
        }
    }

    public void setCloseable(final AutoCloseable closeable) {
        this.closeable = closeable;
    }

    @Override
    public void close() {
        super.close();

        if(closeable != null) {
            try {
                closeable.close();
            } catch(Exception e) {
                LOG.debug("Error closing instance", e);
            }
        }
    }

    @Override
    protected Object newTransactionIdentifier() {
        return "DOM-" + txNum.getAndIncrement();
    }

    @Override
    public ListenerRegistration<DOMDataChangeListener> registerDataChangeListener(final LogicalDatastoreType store,
            final YangInstanceIdentifier path, final DOMDataChangeListener listener, final DataChangeScope triggeringScope) {

        DOMStore potentialStore = getTxFactories().get(store);
        checkState(potentialStore != null, "Requested logical data store is not available.");
        return potentialStore.registerChangeListener(path, listener, triggeringScope);
    }

    @Override
    public Map<Class<? extends DOMDataBrokerExtension>, DOMDataBrokerExtension> getSupportedExtensions() {
        return extensions;
    }

    @Override
    public DOMTransactionChain createTransactionChain(final TransactionChainListener listener) {
        checkNotClosed();

        final Map<LogicalDatastoreType, DOMStoreTransactionChain> backingChains = new EnumMap<>(LogicalDatastoreType.class);
        for (Entry<LogicalDatastoreType, DOMStore> entry : getTxFactories().entrySet()) {
            backingChains.put(entry.getKey(), entry.getValue().createTransactionChain());
        }

        final long chainId = chainNum.getAndIncrement();
        LOG.debug("Transactoin chain {} created with listener {}, backing store chains {}", chainId, listener,
                backingChains);
        return new DOMDataBrokerTransactionChainImpl(chainId, backingChains, this, listener);
    }
}
