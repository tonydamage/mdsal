/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.mdsal.dom.broker.osgi;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.opendaylight.mdsal.dom.api.DOMSchemaService;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.concepts.Registration;
import org.opendaylight.yangtools.util.ListenerRegistry;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaContextListener;
import org.opendaylight.yangtools.yang.model.api.SchemaContextProvider;
import org.opendaylight.yangtools.yang.parser.repo.URLSchemaContextResolver;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OsgiBundleScanningSchemaService implements SchemaContextProvider, DOMSchemaService, ServiceTrackerCustomizer<SchemaContextListener, SchemaContextListener>, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OsgiBundleScanningSchemaService.class);

    private final ListenerRegistry<SchemaContextListener> listeners = new ListenerRegistry<>();
    private final URLSchemaContextResolver contextResolver = URLSchemaContextResolver.create("global-bundle");
    private final BundleScanner scanner = new BundleScanner();
    private final BundleContext context;

    private ServiceTracker<SchemaContextListener, SchemaContextListener> listenerTracker;
    private BundleTracker<Iterable<Registration>> bundleTracker;
    private boolean starting = true;
    private static OsgiBundleScanningSchemaService instance;

    private OsgiBundleScanningSchemaService(final BundleContext context) {
        this.context = Preconditions.checkNotNull(context);
    }

    public synchronized static OsgiBundleScanningSchemaService createInstance(final BundleContext ctx) {
        Preconditions.checkState(instance == null);
        instance = new OsgiBundleScanningSchemaService(ctx);
        instance.start();
        return instance;
    }

    public synchronized static OsgiBundleScanningSchemaService getInstance() {
        Preconditions.checkState(instance != null, "Global Instance was not instantiated");
        return instance;
    }

    @VisibleForTesting
    public static synchronized void destroyInstance() {
        try {
            instance.close();
        } finally {
            instance = null;
        }
    }

    public BundleContext getContext() {
        return context;
    }

    public void start() {
        checkState(context != null);
        LOG.debug("start() starting");

        listenerTracker = new ServiceTracker<>(context, SchemaContextListener.class, OsgiBundleScanningSchemaService.this);
        bundleTracker = new BundleTracker<>(context, Bundle.RESOLVED | Bundle.STARTING |
                Bundle.STOPPING | Bundle.ACTIVE, scanner);
        bundleTracker.open();

        LOG.debug("BundleTracker.open() complete");

        listenerTracker.open();
        starting = false;
        tryToUpdateSchemaContext();

        LOG.debug("start() complete");
    }

    @Override
    public SchemaContext getSchemaContext() {
        return getGlobalContext();
    }

    @Override
    public SchemaContext getGlobalContext() {
        return contextResolver.getSchemaContext().orNull();
    }

    @Override
    public SchemaContext getSessionContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized ListenerRegistration<SchemaContextListener> registerSchemaContextListener(final SchemaContextListener listener) {
        final Optional<SchemaContext> potentialCtx = contextResolver.getSchemaContext();
        if(potentialCtx.isPresent()) {
            listener.onGlobalContextUpdated(potentialCtx.get());
        }
        return listeners.register(listener);
    }

    @Override
    public void close() {
        if (bundleTracker != null) {
            bundleTracker.close();
        }
        if (listenerTracker != null) {
            listenerTracker.close();
        }

        for (final ListenerRegistration<SchemaContextListener> l : listeners.getListeners()) {
            l.close();
        }
    }

    private synchronized void updateContext(final SchemaContext snapshot) {
        final Object[] services = listenerTracker.getServices();
        for (final ListenerRegistration<SchemaContextListener> listener : listeners) {
            try {
                listener.getInstance().onGlobalContextUpdated(snapshot);
            } catch (final Exception e) {
                LOG.error("Exception occured during invoking listener", e);
            }
        }
        if (services != null) {
            for (final Object rawListener : services) {
                final SchemaContextListener listener = (SchemaContextListener) rawListener;
                try {
                    listener.onGlobalContextUpdated(snapshot);
                } catch (final Exception e) {
                    LOG.error("Exception occured during invoking listener {}", listener, e);
                }
            }
        }
    }

    private class BundleScanner implements BundleTrackerCustomizer<Iterable<Registration>> {
        @Override
        public Iterable<Registration> addingBundle(final Bundle bundle, final BundleEvent event) {

            if (bundle.getBundleId() == 0) {
                return Collections.emptyList();
            }

            final Enumeration<URL> enumeration = bundle.findEntries("META-INF/yang", "*.yang", false);
            if (enumeration == null) {
                return Collections.emptyList();
            }

            final List<Registration> urls = new ArrayList<>();
            while (enumeration.hasMoreElements()) {
                final URL u = enumeration.nextElement();
                try {
                    urls.add(contextResolver.registerSource(u));
                    LOG.debug("Registered {}", u);
                } catch (final Exception e) {
                    LOG.warn("Failed to register {}, ignoring it", e);
                }
            }

            if (!urls.isEmpty()) {
                LOG.debug("Loaded {} new URLs from bundle {}, attempting to rebuild schema context",
                        urls.size(), bundle.getSymbolicName());
                tryToUpdateSchemaContext();
            }

            return ImmutableList.copyOf(urls);
        }

        @Override
        public void modifiedBundle(final Bundle bundle, final BundleEvent event, final Iterable<Registration> object) {
        }

        /**
         * If removing YANG files makes yang store inconsistent, method
         * {@link #getYangStoreSnapshot()} will throw exception. There is no
         * rollback.
         */

        @Override
        public synchronized void removedBundle(final Bundle bundle, final BundleEvent event, final Iterable<Registration> urls) {
            for (final Registration url : urls) {
                try {
                    url.close();
                } catch (final Exception e) {
                    LOG.warn("Failed do unregister URL {}, proceeding", url, e);
                }
            }

            final int numUrls = Iterables.size(urls);
            if(numUrls > 0 ) {
                if(LOG.isDebugEnabled()) {
                    LOG.debug("removedBundle: {}, state: {}, # urls: {}", bundle.getSymbolicName(), bundle.getState(), numUrls);
                }

                tryToUpdateSchemaContext();
            }
        }
    }

    @Override
    public synchronized SchemaContextListener addingService(final ServiceReference<SchemaContextListener> reference) {

        final SchemaContextListener listener = context.getService(reference);
        final SchemaContext _ctxContext = getGlobalContext();
        if (getContext() != null && _ctxContext != null) {
            listener.onGlobalContextUpdated(_ctxContext);
        }
        return listener;
    }

    public synchronized void tryToUpdateSchemaContext() {
        if (starting) {
            return;
        }
        final Optional<SchemaContext> schema = contextResolver.getSchemaContext();
        if(schema.isPresent()) {
            if(LOG.isDebugEnabled()) {
                LOG.debug("Got new SchemaContext: # of modules {}", schema.get().getAllModuleIdentifiers().size());
            }

            updateContext(schema.get());
        }
    }

    @Override
    public void modifiedService(final ServiceReference<SchemaContextListener> reference, final SchemaContextListener service) {
        // NOOP
    }

    @Override
    public void removedService(final ServiceReference<SchemaContextListener> reference, final SchemaContextListener service) {
        context.ungetService(reference);
    }
}
