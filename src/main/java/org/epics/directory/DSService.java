/**
 * cfService defines classes for the server side of an EPICS V4 service for
 * accessing the ChannelFinder web service.
 */

package org.epics.directory;

/*
 * #%L
 * directoryService - Java
 * %%
 * Copyright (C) 2012 EPICS
 * %%
 * Copyright (C) 2012 Helmholtz-Zentrum Berlin für Materialien und Energie GmbH
 * All rights reserved. Use is subject to license terms.
 * #L%
 */

import org.epics.pvaccess.CAException;
import org.epics.pvaccess.client.ChannelRPCRequester;
import org.epics.pvaccess.server.rpc.RPCRequestException;
import org.epics.pvaccess.server.rpc.RPCServer;
import org.epics.pvaccess.server.rpc.RPCService;
import org.epics.pvdata.pv.PVStructure;
import org.epics.pvdata.pv.Status;

/**
 * DSService implements an EPICS V4 RPC service for retrieving data
 * from a directory service.
 *
 * As written, DSService expects arguments of the following form:
 * <pre>
 *     string query       - The query which is used to get data from the
 *                          directory service, e.g. "SR:C01-MG:G06*"
 * </pre>
 * 
 * The service returns results as a PVStructure of normative type NTTable.
 *
 * @author Ralph Lange <Ralph.Lange@gmx.de>
 *
 */

public class DSService {

//    private static final StatusCreate statusCreate = StatusFactory.getStatusCreate();
//    private static final Status okStatus = statusCreate.getStatusOK();
//    private static final Status missingRequiredArgumentStatus = statusCreate.createStatus(StatusType.ERROR,
//            "Missing required argument", null);
    private static final String SERVICE_NAME = "ds";

    private static class DSServiceImpl implements RPCService {

        private ChannelRPCRequester channelRPCRequester;
        private static CFConnector dsConnector = new CFConnector();
        
        /**
         * Execute the RPC request using the directory service connector
         */
        @Override
        public PVStructure request(PVStructure args) throws RPCRequestException {
            try {
                return dsConnector.getData(args);
            } catch (Exception e) {
                throw new RPCRequestException(Status.StatusType.FATAL, e.getMessage());
            }
        }
    }
    
    /**
     * main runs the DSService.
     * 
     * @throws CAException pvAccess exception
     */
    public static void main(String[] args) throws CAException {

        RPCServer server = new RPCServer();

        server.registerService(SERVICE_NAME, new DSServiceImpl());

        server.printInfo();
        server.run(0);
    }
}
