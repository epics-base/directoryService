/**
 * DSClient is a simple command line client of DSService.
 */

package org.epics.directory;

import org.epics.pvaccess.ClientFactory;
import org.epics.pvdata.factory.FieldFactory;
import org.epics.pvdata.factory.PVDataFactory;
import org.epics.pvdata.pv.*;
import org.epics.pvdata.util.namedValues.NamedValues;
import org.epics.pvdata.util.namedValues.NamedValuesFormatter;
import org.epics.pvdata.util.pvDataHelper.GetHelper;
import org.epics.pvservice.rpc.ServiceClient;
import org.epics.pvservice.rpc.ServiceClientFactory;
import org.epics.pvservice.rpc.ServiceClientRequester;

/**
 * DSClient is a simple command line client of the DSService.
 * 
 * DSService gets data out of the ChannelFinder directory service.
 *
 * @author Ralph Lange <Ralph.Lange@gmx.de>
 *
 */

public class DSClient {
    private static final boolean DEBUG = false; // Print debug info
    private static final String CLIENT_NAME = "DirectoryService Client";
    private static final String SERVICE_NAME = "ds";

    private static final String LABELS_FIELD = "labels";
    
    private static final FieldCreate fieldCreate = FieldFactory.getFieldCreate();
    private static final PVDataCreate pvDataCreate = PVDataFactory.getPVDataCreate();

    /**
     * main()
     * 
     * @param args args[0] must be a query that the server will understand
     */
    public static void main(String[] args) {
        PVStructure pvResult = null;

        /* Start PVAccess client and connect */
        ClientFactory.start();
        Client client = new Client();

        client.connect(SERVICE_NAME);
        _dbg("client connected");

        PVField[] fields = new PVField[1];
        fields[0] = pvDataCreate.createPVField(fieldCreate.createScalar(ScalarType.pvString));
        String[] l = new String[1];
        l[0] = "query";
        PVStructure pvArguments = pvDataCreate.createPVStructure(l, fields);
        _dbg("pvArguments = " + pvArguments);

        // Retrieve interface (i.e., an API for setting the arguments of the service)
        //
        PVString pvQuery = pvArguments.getStringField("query");

        // Update arguments to the service with what we got at the command line, like "swissfel:allmagnetnames"
        //
        if (args.length <= 0) {
            System.err.println("No query specified; exiting.");
            System.exit(-1);
        }
        pvQuery.put(args[0]);

        try {
            pvResult = client.request(pvArguments);
        } catch (Exception e) {
            if (e.getMessage() != null) {
                System.err.println(e.getMessage());
            }
            System.exit(-1);
        }

        if (!pvResult.getStructure().getID().equals("NTTable")) {
            System.err.println("Unexpected data structure returned from "
                    + SERVICE_NAME + ": Expected NTTable, got "
                    + pvResult.getStructure().getID());
            System.exit(-1);
        }

        /* TODO: here we should validate the NTTable we got */
        
        PVField[] pvFields = pvResult.getPVFields();

        /* Fill the returned table into a NamedValues structure for printout */
        
        NamedValues namedValues = new NamedValues();

        int i = 0;
        for (String columnName : GetHelper.getStringVector((PVStringArray) pvResult.getScalarArrayField(LABELS_FIELD, ScalarType.pvString))) {
            /* We assume the first labels.size() arrays in the result are our columns */
            while (pvFields[i].getField().getType() != Type.scalarArray
                    || pvFields[i].getFieldName().equals(LABELS_FIELD)) {
                i++;
            }
            ScalarArray scalarArray = (ScalarArray) pvFields[i].getField();
            if (scalarArray.getElementType() == ScalarType.pvDouble) {
                namedValues.add(columnName, GetHelper.getDoubleVector((PVDoubleArray) pvFields[i]));
            } else if (scalarArray.getElementType() == ScalarType.pvString) {
                namedValues.add(columnName, GetHelper.getStringVector((PVStringArray) pvFields[i]));
            } else if (scalarArray.getElementType() == ScalarType.pvBoolean) {
                namedValues.add(columnName, GetHelper.getBooleanVector((PVBooleanArray) pvFields[i]));
            } else {
                System.err.println("Array " + pvFields[i].getFieldName() + " from " + SERVICE_NAME + " has unexpected type.\n"
                        + "Only pvDouble, pvString, pvBoolean supported");
            }
            i++;
        }

        /* Set up a printout formatter for our NamedValues system */
        NamedValuesFormatter formatter =
                NamedValuesFormatter.create(NamedValuesFormatter.STYLE_COLUMNS);
        formatter.assignNamedValues(namedValues);
        formatter.display(System.out);

        _dbg(pvResult.toString());

        client.destroy();
        org.epics.pvaccess.ClientFactory.stop();

        _dbg("result printed, pvAccess stopped, exiting");
        System.exit(0);
    }

    /**
     * Client is an implementation of ServiceClientRequester.
     * 
     * This is the interface between your functional client side code,
     * and the callbacks required by the client side of pvData and the RPC support
     */
    private static class Client implements ServiceClientRequester {

        private ServiceClient serviceClient = null;
        private PVStructure pvResult = null;

        /**
         * Connect and wait until connected.
         *
         * @param serviceName name of the service to connect to
         */
        void connect(String serviceName) {
            try {
                serviceClient = ServiceClientFactory.create(serviceName, this);
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
            serviceClient.waitRequest();

            _dbg("Request exits with pvResult =\n" + pvResult.toString());
            return pvResult;
        }

        /**
         * connectResult verifies the connection.
         *
         * @see
         * org.epics.pvservice.client.ServiceClientRequester#connectResult(org.epics.pvdata.pv.Status)
         */
        @Override
        public void connectResult(Status status) {
            if (!status.isOK()) {
                throw new RuntimeException("Connection error: " + status.getMessage());
            }
            return;
        }

        /**
         * requestResult receives data from the server.
         *
         * @see
         * org.epics.pvService.client.ServiceClientRequester#requestResult(org.epics.pvdata.pv.Status,
         * org.epics.pvdata.pv.PVStructure)
         */
        @Override
        public void requestResult(Status status, PVStructure pvResult) {
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
