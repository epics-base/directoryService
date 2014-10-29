/**
 * DSClient is a simple command line client of DSService.
 */

package org.epics.directory;

/*
 * #%L
 * directoryService - Java
 * %%
 * Copyright (C) 2012 EPICS
 * %%
 * Copyright (C) 2012 Helmholtz-Zentrum Berlin fuer Materialien und Energie GmbH
 * All rights reserved. Use is subject to license terms.
 * #L%
 */

import java.util.ArrayList;
import java.util.List;
import org.epics.pvaccess.ClientFactory;
import org.epics.pvaccess.client.rpc.RPCClient;
import org.epics.pvaccess.client.rpc.RPCClientFactory;
import org.epics.pvaccess.client.rpc.RPCClientRequester;
import org.epics.pvdata.factory.FieldFactory;
import org.epics.pvdata.factory.PVDataFactory;
import org.epics.pvdata.pv.*;
import org.epics.pvdata.util.namedValues.NamedValues;
import org.epics.pvdata.util.namedValues.NamedValuesFormatter;
import org.epics.pvdata.util.pvDataHelper.GetHelper;

/**
 * DSClient is a simple command line client of the DSService.
 * 
 * DSService gets data out of the ChannelFinder directory service.
 *
 * @author Ralph Lange (Ralph.Lange@gmx.de)
 *
 */

public class DSClient {
    private static final boolean DEBUG = false; // Print debug info
    private static final String CLIENT_NAME = "DirectoryService Client";
    private static final String SERVICE_NAME = "ds";

    private static final String LABELS_FIELD = "labels";
    
    private static final FieldCreate fieldCreate = FieldFactory.getFieldCreate();
    
    private static final double timeout = 5.0;

    /**
     * main()
     * 
     * @param args args[0] must be a query that the server will understand
     */
    public static void main(String[] args) {
        PVStructure pvResult = null;
        List<String> arguments = new ArrayList<String>();
        List<String> values = new ArrayList<String>();
        boolean printLabels = true;

        if (args.length <= 0) {
            System.err.println("No query specified; exiting.");
            System.exit(-1);
        }

        /* Start PVAccess client and connect */
        ClientFactory.start();
        Client client = new Client();

        client.connect(SERVICE_NAME);
        _dbg("client connected");

        // Parse command line
        
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
                _dbg("token " + i + ": " + s);
            if (s.equals("owner")) {
                _dbg("-> found owner ");
                arguments.add("owner");
                values.add("1");
            } else if (s.startsWith("sort=")) {
                _dbg("-> found sort " + s.split("=", -1)[1]);
                arguments.add("sort");
                values.add(s.split("=", -1)[1]);
            } else if (s.equals("nolabels")) {
                printLabels = false;
            } else if (s.startsWith("show=")) {
                _dbg("-> found show " + s.split("=", -1)[1]);
                arguments.add("show");
                values.add(s.split("=", -1)[1]);
            } else if (s.startsWith("query=")) {
                _dbg("-> found qual. query " + s.split("=", -1)[1]);
                arguments.add("query");
                values.add(s.split("=", -1)[1]);
            } else if (i == 0) {
                _dbg("-> found query " + s);
                arguments.add("query");
                values.add(s);
            } else {
                System.err.println("Unrecognized argument '" + s + "'; exiting.");
                System.exit(-1);
            }
        }

        // Set up NTURI request structure (args are string fields inside the query structure)
        int nArgs = arguments.size();

        String[] a = arguments.toArray(new String[nArgs]);
        String[] v = values.toArray(new String[values.size()]);
        _dbg("nArgs " + nArgs + " (" + a + ")");
        
        Field[] f = new Field[nArgs];
        for (int i = 0; i < nArgs; i++) {
            f[i] = fieldCreate.createScalar(ScalarType.pvString);
        }
        
        Structure queryStructure = fieldCreate.createStructure(a, f);

        Structure uriStructure =
                fieldCreate.createStructure("epics:nt/NTURI:1.0",
                new String[]{"path", "query"},
                new Field[]{fieldCreate.createScalar(ScalarType.pvString),
                    queryStructure});

        // Fill in values for path and query (argument list)
        PVStructure request = PVDataFactory.getPVDataCreate().
                createPVStructure(uriStructure);
        request.getStringField("path").put(SERVICE_NAME);
        PVStructure query = request.getStructureField("query");
        for (int i = 0; i < nArgs; i++) {
            query.getStringField(a[i]).put(v[i]);
        }
        _dbg("request = " + request);

        try {
            pvResult = client.request(query);
        } catch (Exception e) {
            if (e.getMessage() != null) {
                System.err.println(e.getMessage());
            }
            System.exit(-1);
        }

        if (!pvResult.getStructure().getID().startsWith("epics:nt/NTTable:1.")) {
            System.err.println("Unexpected data structure returned from "
                    + SERVICE_NAME + ": Expected epics:nt/NTTable:1.x, got "
                    + pvResult.getStructure().getID());
            System.exit(-1);
        }
        
        PVStructure pvValueStructure = pvResult.getStructureField("value");
        if (pvValueStructure == null) {
            System.err.println("NTTable returned from "+ SERVICE_NAME
                    + "does not have a value");
            System.exit(-1);
        }
        PVField[] pvValue = pvValueStructure.getPVFields();

        /* Fill the returned table into a NamedValues structure for printout */
        
        NamedValues namedValues = new NamedValues();

        int i = 0;
        for (String columnName : GetHelper.getStringVector((PVStringArray) pvResult.getScalarArrayField(LABELS_FIELD, ScalarType.pvString))) {
            ScalarArray scalarArray = (ScalarArray) pvValue[i].getField();
            if (scalarArray.getElementType() == ScalarType.pvDouble) {
                namedValues.add(columnName, GetHelper.getDoubleVector((PVDoubleArray) pvValue[i]));
            } else if (scalarArray.getElementType() == ScalarType.pvString) {
                namedValues.add(columnName, GetHelper.getStringVector((PVStringArray) pvValue[i]));
            } else if (scalarArray.getElementType() == ScalarType.pvBoolean) {
                namedValues.add(columnName, GetHelper.getBooleanVector((PVBooleanArray) pvValue[i]));
            } else {
                System.err.println("Value array " + i + " called " + pvValue[i].getFieldName()
                        + " from " + SERVICE_NAME + " has unexpected type.\n"
                        + "Only pvDouble, pvString, pvBoolean supported");
            }
            i++;
        }

        /* Set up a printout formatter for our NamedValues system */
        NamedValuesFormatter formatter =
                NamedValuesFormatter.create(NamedValuesFormatter.STYLE_COLUMNS);
        formatter.setWhetherDisplayLabels(printLabels);
        formatter.assignNamedValues(namedValues);
        formatter.display(System.out);

        _dbg(pvResult.toString());

        client.destroy();
        org.epics.pvaccess.ClientFactory.stop();

        _dbg("result printed, pvAccess stopped, exiting");
        System.exit(0);
    }

    /**
     * Client is an implementation of RPCClientRequester.
     * 
     * This is the interface between your functional client side code,
     * and the callbacks required by the client side of pvData and the RPC support
     */
    private static class Client implements RPCClientRequester {

        private RPCClient serviceClient = null;
        private PVStructure pvResult = null;

        /**
         * Connect and wait until connected.
         *
         * @param serviceName name of the service to connect to
         */
        void connect(String serviceName) {
            try {
                serviceClient = RPCClientFactory.create(serviceName, this);
                serviceClient.waitConnect(5.0);
            } catch (Exception ex) {
                System.err.println(this.getClass().getName() + " received Exception " + ex.getClass()
                        + " with message \"" + ex.getMessage() + "\"");
                System.err.println("Unable to contact " + SERVICE_NAME + ". Exiting");
                System.exit(1);
            }
            _dbg("connected to " + SERVICE_NAME);
        }

        /**
         * Cleanup client side resources.
         */
        void destroy() {
            serviceClient.destroy();
        }

        /**
         * Send a request and wait until done.
         *
         * @returns PVStructure data returned by the service
         */
        PVStructure request(PVStructure pvArguments) {
            _dbg("Sending request");

            // Actually execute the request for data on the server.
            serviceClient.sendRequest(pvArguments);
            serviceClient.waitResponse(timeout);

            _dbg("Request exits with pvResult =\n" + pvResult.toString());
            return pvResult;
        }

        /**
         * connectResult verifies the connection.
         *
         * @see
         * org.epics.pvaccess.client.rpc.RPCClientRequester#connectResult(org.epics.pvdata.pv.Status)
         */
        @Override
	public void connectResult(RPCClient client, Status status) {
            if (!status.isOK()) {
                throw new RuntimeException("Connection error: " + status.getMessage());
            }
            return;
        }

        /**
         * requestResult receives data from the server.
         *
         * @see
         * org.epics.pvService.client.RPCClientRequester#requestResult(org.epics.pvdata.pv.Status,
         * org.epics.pvdata.pv.PVStructure)
         */
        @Override
	public void requestResult(RPCClient client, Status status, PVStructure pvResult) {
            if (!status.isOK()) {
                // throw new RuntimeException("Request error: " + status.getMessage());
                System.err.println(SERVICE_NAME + " returned status " + status.getType().toString()
                        + " with message: " + status.getMessage());
                this.pvResult = null;
            } else {
                this.pvResult = pvResult;
            }
        }

        /**
         * Called back by the service to acquire the client name.
         *
         * @see org.epics.pvData.pv.Requester#getRequesterName()
         */
        @Override
        public String getRequesterName() {
            return CLIENT_NAME;
        }

        /**
         * Called back by the service to issue messages
         * while it is processing requests for you.
         *
         * @see org.epics.pvData.pv.Requester#message(java.lang.String,
         * org.epics.pvdata.pv.MessageType)
         */
        @Override
        public void message(String message, MessageType messageType) {
            System.out.printf("Message from %s %s: %s", SERVICE_NAME,
                    messageType.toString(), message);
        }
    }

    private static void _dbg(String debug_message) {
        if (DEBUG) {
            System.err.println("DEBUG (" + DSClient.class.getSimpleName() + "): " + debug_message);
        }
    }
}
