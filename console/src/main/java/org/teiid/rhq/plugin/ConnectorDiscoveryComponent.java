/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.rhq.plugin;

import java.util.Collection;

import org.teiid.rhq.comm.Component;
import org.teiid.rhq.comm.Connection;
import org.teiid.rhq.comm.ConnectionConstants;
import org.teiid.rhq.comm.ConnectionException;


/**
 * Discovery component used to discover the monitored connector bindings
 * 
 */
public class ConnectorDiscoveryComponent extends NodeChildrenDiscoveryComponent {
	
	 Collection<Component> getComponents(Connection conn, Facet parent) throws ConnectionException {
		 return conn.discoverComponents(ConnectionConstants.ComponentType.Runtime.Connector.TYPE, parent.getComponentIdentifier());
	 }
	

}