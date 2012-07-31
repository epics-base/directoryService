/**
 * -*-java-*- CfClient is a simple client of the cfService (an EPICS V4
 * service).
 */
package org.epics.directory.client;

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
 * CfClient is a simple command line client of the CfService; CfService is intended to
 * get data out of the ChannelFinder directory service, via EPICS V4.
 *
 * @author Ralph Lange <Ralph.Lange@gmx.de>
 *
 */
public class CfClient {
    // Issue application level debugging messages if true.

    private static final boolean DEBUG = false;
    // Set an identifying name for your client, used by the service. Does not have to
    // be unique in an installation, just a free format string.
    private static final String CLIENT_NAME = "ChannelFinder client";
    // These literals must agree with the XML Database of the service in question. In this
    // case perfTestService. See perfTestService.xml.
    private static final String OBJECTIVE_SERVICE_NAME = "cf";  // Name of service to contact.
    private static final String SERVICE_ARGUMENTS_FIELDNAME = "arguments";
    // Name of field holding
    // arguments in the service's interface xml db.
    private static final String ENTITY_ARGNAME = "query";          // Name of argument holding what the user asked for.   
    private static final String PARAMS_ARGNAME = "parameters";     // Name of an argument supplied, but not used by
    // the cfService. 
    // cfClient expects to get returned a PVStructure which conforms to the 
    // definition of an NTTable. As such, the PVStructure's first field should 
    // be called "normativeType" and have value "NTTable".
    private static String TYPE_FIELD_NAME = "NTType";
    private static String NTTABLE_TYPE_NAME = "NTTable";
    // Error exit codes
    private static final int NOTNORMATIVETYPE = 1;
    private static final int NOTNTTABLETYPE = 2;
    private static final int NODATARETURNED = 3;
    private static final int NOARGS = 4;
    public static final int OWNERS_WANTED = 1;
    public static final int NOOWNERS_WANTED = 2;
    private static final int _OWNERS_ARGN = 1;                   // The index of the no/owners arg if given.
    private static final String _OWNERS_WANTED = "owners";       // Arg _OWNERS_ARGN must be this for owners printout.
    private static final String _OWNERS_NOTWANTED = "noowners";  // Arg _OWNERS_ARGN, if given, = this for no owners printout.
    private static int _owners = NOOWNERS_WANTED;
    private static int _style = NamedValuesFormatter.STYLE_COLUMNS;

    /**
     * Main of cfService client takes 2 arguments: - the query string that the
     * server side will understand - the flag that specifies ownership to be
     * included in the answer
     *
     * @param args args[0] must be a query that the server will understand
     */
    public static void main(String[] args) {
        PVStructure pvResult = null;  // The data that will come back from the server.

        parseArguments(args);

        // Start PVAccess. Instantiate private class that handles callbacks and look for arguments
        org.epics.pvaccess.ClientFactory.start();
        Client client = new Client();

        PVDataCreate pvDataCreate = PVDataFactory.getPVDataCreate();
        FieldCreate fieldCreate = FieldFactory.getFieldCreate();
        PVField[] fields = new PVField[2];
        fields[0] = pvDataCreate.createPVField(fieldCreate.createScalar(ScalarType.pvString));
        fields[1] = pvDataCreate.createPVField(fieldCreate.createScalar(ScalarType.pvString));
        String[] l = new String[2];
        l[0] = ENTITY_ARGNAME;
        l[1] = PARAMS_ARGNAME;
        PVStructure pvArguments = pvDataCreate.createPVStructure(l, fields);

        // Make connection to service

        client.connect(OBJECTIVE_SERVICE_NAME);

        _dbg("main(): following client connect, pvarguments = "
                + pvArguments.toString());

        // Retrieve interface (i.e., an API for setting the arguments of the service)
        //
        PVString pvQuery = pvArguments.getStringField(ENTITY_ARGNAME);
        PVString pvParams = pvArguments.getStringField(PARAMS_ARGNAME);

        // Update arguments to the service with what we got at the command line, like "swissfel:allmagnetnames"
        //
        if (args.length <= 0) {
            System.err.println("No query specified; exiting.");
            System.exit(NOARGS);
        }
        pvQuery.put(args[0]);

        // TODO: There are presently no record parameters supported (ie, parameters 
        // member of the rdbService xml record is not used). This would be used if for instance 
        // supporting SQL "define" parameter replacement (eg &1,&2 etc), or any other
        // runtime modification you wanted to make to the way the RDB query is formed or processed.	

        // Execute the service request for data subject to the arguments constructed above. 
        //
        try {
            pvResult = client.request(pvArguments);
        } catch (Exception ex) {
            if (ex.getMessage() != null) {
                System.err.println(ex.getMessage());
            }
            System.exit(NODATARETURNED);
        }

        // Print the results if we have any.
        //
        if (pvResult == null) {
            System.err.println("Internal Error in " + OBJECTIVE_SERVICE_NAME
                    + ". Server returned null top level result but no Exception");
            System.exit(NODATARETURNED);
        } else {
            PVString normativetypeField = pvResult.getStringField(TYPE_FIELD_NAME);

            if (normativetypeField == null) {
                System.err.println("Unable to get data: unexpected data structure returned from "
                        + OBJECTIVE_SERVICE_NAME + ". Expected normative type member, "
                        + "but normative type not found in returned datum.");
                System.err.println(pvResult);
                System.exit(NOTNORMATIVETYPE);
            }
            String type = normativetypeField.get();
            if (type.compareTo(NTTABLE_TYPE_NAME) != 0) {
                System.err.println("Unable to get data: unexpected data structure returned from "
                        + OBJECTIVE_SERVICE_NAME + ".");
                System.err.println("Expected normativetype member value " + NTTABLE_TYPE_NAME
                        + " but found type = " + type);
                System.exit(NOTNTTABLETYPE);
            }

            /*
             * OK, so we know we got back an NTTable. Now we have to decode it
             * and display it as a table. The datum pvResult that came back from
             * the rdbService should be of EPICS V4 normative type NTTable. That
             * is, pvResult is a PVStructure whose specific shape conforms to
             * the definition NTTable
             * (http://epics-pvdata.sourceforge.net/normative_types_specification
             * .html). Therefore it can be unpacked assuming such structure (a
             * PVStructure containing a PVStructure containing a number of
             * arrays of potentially different type). Further, we know that
             * rdbService only returns columns of type PVDoubleArray,
             * PVLongArray, PVByteArray, or PVStringArray. We use the
             * introspection interface of PVStructure to determine which of
             * these types is each <array> member of the NTTable.
             */

            // skip past the first field, that was the normative type specifier.
            int N_dataFields = pvResult.getNumberFields() - 1;

            PVField[] pvFields = pvResult.getPVFields();
            if (N_dataFields <= 0 || pvFields.length <= 0) {
                System.err.println("No data fields returned from " + OBJECTIVE_SERVICE_NAME + ".");
                System.exit(NODATARETURNED);
            }

            // To print the contents of the NTTable PVstructure, make a NamedValues system
            // from its data. A NamedValues object allows us to personify the table
            // as 2 vectors: a 1-D vector <String> of column headings, and a 2-d vector <String>
            // of values - the table of data. Then use the formatting provisions of 
            // NamedValues to print that data in a familiar looking table format. 
            //
            NamedValues namedValues = new NamedValues();
            for (PVField pvFielde : pvFields) {
                // Get the label attached to the field. This will be the column name from the ResultSet
                // of the SQL SELECT query.
                String fieldName = pvFielde.getFieldName();

                // Skip past the meta-data field named "normativeType"
                if (fieldName.compareTo(TYPE_FIELD_NAME) == 0) {
                    continue;
                }

                if (pvFielde.getField().getType() == Type.scalarArray) {
                    ScalarArray scalarArray = (ScalarArray) pvFielde.getField();
                    if (scalarArray.getElementType() == ScalarType.pvDouble) {
                        PVDoubleArray pvDoubleArray = (PVDoubleArray) pvFielde;
                        namedValues.add(fieldName, GetHelper.getDoubleVector(pvDoubleArray));
                    } else if (scalarArray.getElementType() == ScalarType.pvLong) {
                        PVLongArray pvLongArray = (PVLongArray) pvFielde;
                        namedValues.add(fieldName, GetHelper.getLongVector(pvLongArray));
                    } else if (scalarArray.getElementType() == ScalarType.pvByte) {
                        PVByteArray pvByteArray = (PVByteArray) pvFielde;
                        namedValues.add(fieldName, GetHelper.getByteVector(pvByteArray));
                    } else if (scalarArray.getElementType() == ScalarType.pvString) {
                        PVStringArray pvStringArray = (PVStringArray) pvFielde;
                        namedValues.add(fieldName, GetHelper.getStringVector(pvStringArray));
                    } else if (scalarArray.getElementType() == ScalarType.pvBoolean) {
                        PVBooleanArray pvStringArray = (PVBooleanArray) pvFielde;
                        namedValues.add(fieldName, GetHelper.getBooleanVector(pvStringArray));
                    } else {
                        System.err.println("Unexpected array type returned from " + OBJECTIVE_SERVICE_NAME + ".");
                        System.err.println("Only pvData scalarArray types pvDouble, pvLong, pvByte or pvString expected");
                    }
                } else {
                    System.err.println("Unexpected non-array field returned from " + OBJECTIVE_SERVICE_NAME + ".");
                    System.err.println("Field named " + fieldName + " is not of scalarArray type, "
                            + "and so can not be interpretted as a data column.");
                }
            }


            // Set up a printout formatter for our NamedValues system, and give it our 
            // constructed namedValues (the data that came 
            // back from the server, but recast from a PVStructure to a NamedValues). Then
            // ask the formatter to print it - result is a table printed to System.out.
            //
            NamedValuesFormatter formatter =
                    NamedValuesFormatter.create(_style);
//            formatter.setWhetherDisplayLabels(_labels_wanted);
            formatter.assignNamedValues(namedValues);
            formatter.display(System.out);
        }
        _dbg(pvResult.toString());

        // Clean up
        client.destroy();
        org.epics.pvaccess.ClientFactory.stop();

        _dbg("DEBUG: main(): Completed Successfully");
        System.exit(0);
    }

    /**
     * Client is an example implementor of ServiceClientRequester. A client side
     * of EPICS V4 rpc support must implement a static class of
     * ServiceClientImplemetor. This class forms the interface between your
     * functional client side code, and the callbacks required by the client
     * side of pvData and the RPC support.
     */
    private static class Client implements ServiceClientRequester {

        private ServiceClient serviceClient = null;
        private PVStructure pvResult = null;

        /**
         * Connect and wait until connected
         *
         * @param objectiveServiceName The recordName of the service to which to
         * connect.
         * @return The pvStructure of the arguments expected by the service; the
         * client "sends arguments" by filling in the elements of this returned
         * structure.
         */
        void connect(String objectiveServiceName) {
            _dbg("connect() entered");

            // Service name argument must match recordName in XML database exactly, 
            // including case.
            try {
                serviceClient = ServiceClientFactory.create(objectiveServiceName, this);
                // Connect with 5.0s timeout. Increase for slow services. 
                serviceClient.waitConnect(5.0);
            } catch (Exception ex) {
                System.err.println(this.getClass().getName() + " received Exception " + ex.getClass()
                        + " with message \"" + ex.getMessage() + "\"");
                System.err.println("Unable to contact " + OBJECTIVE_SERVICE_NAME + ". Exiting");
                System.exit(1);
            }

            _dbg("connect() exits");
        }

        /**
         * Cleanup client side resource. At least call super serviceClient
         * destroy.
         */
        void destroy() {
            serviceClient.destroy();
        }

        /**
         * Send a request and wait until done.
         *
         * The service will be issued a "sendRequest" by this method invocation,
         * which is its queue to "process the record." Processing the record
         * amounts to examining the bitset on the server side, processing, and
         * returning a PVStructure holding the returned data.
         *
         * It's important that this request method sets the bits of the bitset
         * to indicate which arguments (inside the PVArguments PVStructure)
         * should be examined by the server for possible changes in value. Since
         * it's a cheap operation, and this is a demo, we just set those bits
         * every time. But an optimized client would only actively reset bits in
         * the bitset if the had changed value since the last sendRequest.
         *
         * @return PVStructure the data returned by the service for this call.
         */
        PVStructure request(PVStructure pvArguments) {
            _dbg("request() entered");

            // Actually execute the request for data on the server.
            serviceClient.sendRequest(pvArguments);
            serviceClient.waitRequest();

            _dbg("Request() exits with pvResult=" + pvResult.toString());
            return pvResult;
        }

        /**
         * connectResult verifies connection and gets the interface of the
         * specific server you specified in ServiceClientFactory.create.
         *
         * You, the client side programmer must supply (aka Override) this
         * method definition in your implementation of ServiceClientRequester;
         * but client side of pvData calls it, you don't call it directly.
         *
         * @see
         * org.epics.pvservice.client.ServiceClientRequester#connectResult(org.epics.pvdata.pv.Status,
         * org.epics.pvdata.pv.PVStructure, org.epics.pvdata.misc.BitSet)
         */
        @Override
        public void connectResult(Status status) {
            if (!status.isOK()) {
                throw new RuntimeException("Connection error: " + status.getMessage());
            }

            return;
        }

        /**
         * requestResult receives the data from the server.
         *
         * You, the client side programmer must supply (aka Override) this
         * method definition in your implementation of ServiceClientRequester;
         * but client side of pvData calls it, you don't call it directly.
         *
         * @see
         * org.epics.pvService.client.ServiceClientRequester#requestResult(org.epics.pvData.pv.Status,
         * org.epics.pvData.pv.PVStructure)
         */
        @Override
        public void requestResult(Status status, PVStructure pvResult) {
            if (!status.isOK()) {
                // throw new RuntimeException("Request error: " + status.getMessage());
                System.err.println(OBJECTIVE_SERVICE_NAME + " returned status " + status.getType().toString()
                        + " with message: " + status.getMessage());
                this.pvResult = null;
            } else {
                this.pvResult = pvResult;
            }
            return;
        }

        /**
         * The message method is called back by the service to acquire the name
         * of client. Right! It's that clever: you can get and print diagnostic
         * messages from a server while it's processing your request, not just
         * an exit status. Neatto eh.
         *
         * You, the client side programmer must supply (aka Override) this
         * method definition in your implementation of ServiceClientRequester;
         * but the client side of pvData calls it, not your client side code
         * directly.
         *
         * @see org.epics.pvData.pv.Requester#getRequesterName()
         */
        @Override
        public String getRequesterName() {
            return CLIENT_NAME;
        }

        /**
         * The message method is called back by the service to issue messages
         * while it is processing requests for you. Right! It's that clever: you
         * can get and print diagnostic messages from a server while it's
         * processing your request, not just an exit status. Neatto eh.
         *
         * You, the client side programmer must supply this method; but the
         * server calls it, not your client side code.
         *
         * @see org.epics.pvData.pv.Requester#message(java.lang.String,
         * org.epics.pvData.pv.MessageType)
         */
        @Override
        public void message(String message, MessageType messageType) {
            System.out.printf("Message from %s %s: %s", OBJECTIVE_SERVICE_NAME,
                    messageType.toString(), message);
        }
    } // end class Client

    /**
     * parse command line arguments to see what we're going to get and how to
     * print it.
     *
     * @param args the command line arguments
     */
    private static void parseArguments(String[] args) {
        if (args.length > _OWNERS_ARGN && args[_OWNERS_ARGN] != null) {
            if (_OWNERS_WANTED.equals(args[_OWNERS_ARGN])) {
                _owners = OWNERS_WANTED;
            } else if (_OWNERS_NOTWANTED.equals(args[_OWNERS_ARGN])) {
                _owners = NOOWNERS_WANTED;
            } else {
                System.err.println("Unexpected value of owners argument; it must be given as " + _OWNERS_WANTED
                        + " or " + _OWNERS_NOTWANTED);
            }
        }
    }

    /**
     * Just a programming utility for debugging; if static DEBUG is true, then
     * the argument will be printed to stderr, and not otherwise.
     *
     * Helps you put debug messages in code in a simple way.
     *
     * @param debug_message
     */
    private static void _dbg(String debug_message) {
        if (DEBUG) {
            System.err.println("DEBUG: " + debug_message);
        }
    }
} // end class CfClient
