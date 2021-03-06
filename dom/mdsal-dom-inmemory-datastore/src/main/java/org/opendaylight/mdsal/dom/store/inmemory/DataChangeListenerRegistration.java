/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.mdsal.dom.store.inmemory;

import org.opendaylight.mdsal.common.api.AsyncDataChangeListener;
import org.opendaylight.mdsal.common.api.AsyncDataBroker.DataChangeScope;

import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;

public interface DataChangeListenerRegistration<L extends AsyncDataChangeListener<YangInstanceIdentifier, NormalizedNode<?, ?>>> extends ListenerRegistration<L> {
    @Override
    L getInstance();

    YangInstanceIdentifier getPath();

    DataChangeScope getScope();
}
