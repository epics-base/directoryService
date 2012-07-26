/*
 * Copyright (c) 2012 Helmholtz-Zentrum Berlin für Materialien und Energie GmbH
 * All rights reserved. Use is subject to license terms and conditions.
 */
/**
 * cfService defines classes for the server side of an EPICS V4 service for
 * accessing the ChannelFinder web service.
 */
package cfService;

import gov.bnl.channelfinder.api.ChannelFinder;
import gov.bnl.channelfinder.api.ChannelFinderClient;
import gov.bnl.channelfinder.api.ChannelUtil;
import gov.bnl.channelfinder.api.Channel;
import gov.bnl.channelfinder.api.Property;
import gov.bnl.channelfinder.api.Tag;
import java.util.*;
import org.epics.pvaccess.client.ChannelRPCRequester;
import org.epics.pvioc.database.PVRecord;
import org.epics.pvioc.pvAccess.RPCServer;
import org.epics.pvdata.factory.FieldFactory;
import org.epics.pvdata.factory.PVDataFactory;
import org.epics.pvdata.factory.StatusFactory;
import org.epics.pvdata.pv.Status.StatusType;
import org.epics.pvdata.pv.*;
import org.epics.pvdata.factory.FieldFactory;
import org.epics.pvdata.factory.PVDataFactory;
import org.epics.pvdata.factory.StatusFactory;
import org.epics.pvdata.pv.Field;
import org.epics.pvdata.pv.FieldCreate;
import org.epics.pvdata.pv.PVBooleanArray;
import org.epics.pvdata.pv.PVDataCreate;
import org.epics.pvdata.pv.PVField;
import org.epics.pvdata.pv.PVString;
import org.epics.pvdata.pv.PVStringArray;
import org.epics.pvdata.pv.PVStructure;
import org.epics.pvdata.pv.ScalarArray;
import org.epics.pvdata.pv.ScalarType;
import org.epics.pvdata.pv.Status;
import org.epics.pvdata.pv.Status.StatusType;
import org.epics.pvdata.pv.StatusCreate;
import org.epics.pvioc.database.PVRecord;
import org.epics.pvioc.pvAccess.RPCServer;

/**
 * CfServiceFactory implements an EPICS v4 service for retrieving data from the
 * ChannelFinder web service.
 *
 * In the EPICS v4 services framework, each service is implemented by creating a
 * class with the signature defined by [TODO: where in fact?]. CfServiceFactory
 * is the required factory Class for the ChannelFinder service.
 *
 * As written, CfService expects arguments of the following form:
 * <pre>
 *     string query      - The query for which to get data from the relational database,
 *                          eg "SwissFEL:alldevices"
 *     string parameters  - No parameters are supported by cfService at present. When
 *                          functionality like text replacement is added, this argument
 *                          will be used.
 * </pre> This form is not required by the EPICS v4 RPC framework, but is shared
 * by the 3 services I've written so far because it's a pattern that seems to
 * fit many use cases; you ask a service for a named thing, subject to
 * parameters. It's also the pattern at the heart of URLs, so it'll be easy to
 * expose EPICS V4 services as Web Services.
 *
 * These must be defined in the EPICS V4 XML database definition file of the
 * service (cfService.xml) and this class must expect and process these in
 * accordance with the XML file. Note the XML db is part of EPICS V4, and has
 * nothing to do with the relational database that will be accessed. The XML db
 * is part of the required EPICS V4 infrastructure of any EPICS V4 RPC type
 * service, this particular service accesses a relational database like Oracle.
 *
 * The service returns results as a PVStructure of normative type NTTable (as
 * NTTable was defined at the time of writing, it was in flux, as the idea was
 * being driven by this project).
 *
 * @author Ralph Lange <Ralph.Lange@gmx.de>
 *
 */
public class CfServiceFactory {

    // Define factory to create an instance of this service
    public static RPCServer create() {
        return new RPCServerImpl();
    }
    // Create Status codes used by this service
    //
    private static final boolean DEBUG = true; // Whether to print debugging
    // info.
    private static final StatusCreate statusCreate = StatusFactory.getStatusCreate();
    private static final Status okStatus = statusCreate.getStatusOK();
    private static final Status missingRequiredArgumentStatus = statusCreate.createStatus(StatusType.ERROR,
            "Missing required argument", null);
    private static final FieldCreate fieldCreate = FieldFactory.getFieldCreate();
    private static final PVDataCreate pvDataCreate = PVDataFactory.getPVDataCreate();

    private static class RPCServerImpl implements RPCServer {

        private ChannelRPCRequester channelRPCRequester;
        private String query; // String in pvEntity.
        /*
         * Declare elements of the interface between the pvIOC server side and
         * this server. Why the SuppressWarnings? Because these elements are
         * populated pvIOC and READ FROM in this class, which Eclipse
         * erroneously interprets as unused, so we override that warning.
         */
        @SuppressWarnings("unused")
        private PVStructure m_pvRequest;      // ??
        @SuppressWarnings("unused")           // m_pvParameters is not used in this example.
        private PVString m_pvParameters;      // Would be used to send qualifying information about the 
        // query, but in fact it's not used in this server.
        private PVString m_pvQuery;          // The name of the SQL query to run against Oracle
        // TODO: Externalize and protect access username/passwords and strings
        // TODO: Convert connection string to java Property.
        private static final String SERVICE_NAME = "cfService";
        //
        private static ChannelFinderClient m_CfClient = null; // ChannelFinder client

        // Send message of status.
        // TODO: Expand this to differentially handling the 3 endpoints: 1)
        // user's client, 2) global message log, 3) local stderr.
        private void msg(String message) {
            // TODO: Make these go back to the client. Following line causes Connection Error
            // channelRPCRequester.message(message, MessageType.error);
            System.err.println(SERVICE_NAME + ": " + message);
        }

        private void msgl(String message) {
            msg(message);
            System.err.println(SERVICE_NAME + ": " + message);
        }

        private void msgl(Throwable throwable, String message) {
            msg(throwable.toString() + " " + message);
            System.err.println(SERVICE_NAME + ": " + throwable.toString() + ": " + message);
        }

        /**
         * Initialize for an acquisition.
         *
         * RPCServerImpl is called by the pvIOC framework at server start and on
         * each service request, so if you want to execute initializations only
         * once, you have to check if it's already done. Note, in this service
         * example, we use a pattern where the initialization is done on server
         * startup, and the important part (getConnection) can be redone at any
         * time if the connection to the backend rdb goes bad.
         */
        RPCServerImpl() {
            msg("Entering Impl");
            if (m_CfClient == null) {
                init();
            }
        }

        /**
         * init gets ChannelFinder connection
         */
        private void init() {
            msg("Entering init");
            m_CfClient = ChannelFinder.getClient();
            if (m_CfClient != null) {
                msg("Successfully created ChannelFinder web service client");
            } else {
                msg("Unable to create ChannelFinder web service client");
            }
            msg("After init");
        }

        /*
         * We have to override destroy. (non-Javadoc)
         *
         * @see org.epics.ioc.pvAccess.RPCServer#destroy()
         */
        @Override
        public void destroy() {
        }

        /**
         *
         *
         * @see
         * org.epics.ioc.pvAccess.RPCServer#initialize(org.epics.ca.client.Channel
         * , org.epics.ioc.database.PVRecord,
         * org.epics.ca.client.ChannelRPCRequester,
         * org.epics.pvData.pv.PVStructure, org.epics.pvData.misc.BitSet,
         * org.epics.pvData.pv.PVStructure)
         *
         * @param channel The channel that is requesting the service.
         * @param pvRecord The record that is being serviced.
         * @param channelRPCRequester The client that is requesting the service.
         * @param pvArgument The structure for the argument data that will be
         * passed from the client
         * @param bitSet The bitSet that shows which fields in pvArgument have
         * changed value
         * @param pvRequest The client's request structure - the agreement
         * between client and server.
         */
        @Override
        public Status initialize(org.epics.pvaccess.client.Channel channel, PVRecord pvRecord,
                ChannelRPCRequester channelRPCRequester, PVStructure pvRequest) {
            if (DEBUG) {
                msg("intialize() entered.");
            }

            Status status = okStatus;
            this.channelRPCRequester = channelRPCRequester;
            m_pvRequest = pvRequest;

            if (DEBUG) {
                msg("intialize() leaving: status=" + status.toString());
            }
            return status;
        }

        /**
         * Construct and return the requested archive data.
         */
        @Override
        public void request(PVStructure pvArguments) {
            // Retrieve the arguments (query and parameters). Only the "query"
            // argument is required for cfService at this time. That's the
            // ChannelFinder query string, as defined in the ChannelFinder docs.
            // The "parameters" argument is not supplied by cfClient.
            // It is only in the cfService xml record db because 
            // a this early stage in EPICS v4 it looks like a good idea to make all
            // services xml look identical, so all server code can be cloned.
            //
            m_pvQuery = pvArguments.getStringField("query");
            if (m_pvQuery == null) {
                channelRPCRequester.requestDone(missingRequiredArgumentStatus, null);
            }
            query = m_pvQuery.get();

            // m_pvParameters is not used, so we don't check it.
            m_pvParameters = pvArguments.getStringField("parameters");

            // Construct the return data structure "pvTop."
            // The data structure we return here is a pre-release example
            // of the idea of normative types. This pvTop is self identifying that
            // it is a "normativeType" and that it is specifically a NTTable normative
            // type. In this way, the client side can check that it got a structure it
            // understands. The client knows to look for structures declaring themselves
            // to be normative type NTTable in their first element. The data payload
            // follows the declaration, in another PVStructure called Result.
            //
            Field normativeType = fieldCreate.createScalar(ScalarType.pvString);
            PVField[] t = new PVField[1];
            t[0] = pvDataCreate.createPVField(normativeType);
            PVString x = (PVString) t[0];
            x.put("NTTable");
            String[] l = new String[1];
            l[0] = "NTType";
            PVStructure pvTop = pvDataCreate.createPVStructure(l, t);

            // All gone well, so, pass the pvTop introspection interface and the 
            // query string to getData, which will populate the pvTop for us with
            // the data in Oracle.
            //
            getData(query, pvTop);

            // Return the data from ChannelFinder, in the pvTop, to the client.
            _dbg("pvTop = " + pvTop);
            channelRPCRequester.requestDone(okStatus, pvTop);
        }

        private void getData(String query, PVStructure pvTop) {
            Collection<Channel> channels;
            List<String> properties;
            List<String> tags;
            int nChan;

            HashMap<String, String[]> propColumns = new HashMap<String, String[]>();
            HashMap<String, boolean[]> tagColumns = new HashMap<String, boolean[]>();
            String[] chanColumn;

            ArrayList<String> labels = new ArrayList<String>();

            // Do the ChannelFinder query
            channels = m_CfClient.find(query);
            if (channels != null) {
                nChan = channels.size();
                properties = new ArrayList<String>(
                                ChannelUtil.getPropertyNames(channels));
                tags = new ArrayList<String>(ChannelUtil.getAllTagNames(channels));
            } else {
                nChan = 0;
                properties = Collections.emptyList();
                tags = Collections.emptyList();
            }

            // Create the columns
            chanColumn = new String[nChan];
            for (String prop : properties) {
                propColumns.put(prop, new String[nChan]);
            }
            for (String tag : tags) {
                tagColumns.put(tag, new boolean[nChan]);
            }

            // Loop through the channels, setting the appropriate fields in the columns
            int i = 0;
            for (Channel chan : channels) {
                chanColumn[i] = chan.getName();
                for (Property prop : chan.getProperties()) {
                    String[] col = propColumns.get(prop.getName());
                    col[i] = prop.getValue();
                }
                for (Tag tag : chan.getTags()) {
                    boolean[] col = tagColumns.get(tag.getName());
                    col[i] = true;
                }
                i++;
            }

            // Add the channel name column to top pvData structure
            ScalarArray colField = fieldCreate.createScalarArray(ScalarType.pvString);
            PVStringArray valuesArray = (PVStringArray) pvDataCreate.createPVScalarArray(colField);
            valuesArray.put(0, i, chanColumn, 0);
            pvTop.appendPVField("channel", valuesArray);

            // Add the property columns to top pvData structure
            for (String prop : properties) {
                colField = fieldCreate.createScalarArray(ScalarType.pvString);
                valuesArray = (PVStringArray) pvDataCreate.createPVScalarArray(colField);
                valuesArray.put(0, i, propColumns.get(prop), 0);
                pvTop.appendPVField(prop, valuesArray);
            }

            // Add the tag columns to top pvData structure
            for (String tag : tags) {
                colField = fieldCreate.createScalarArray(ScalarType.pvBoolean);
                PVBooleanArray tagsArray = (PVBooleanArray) pvDataCreate.createPVScalarArray(colField);
                tagsArray.put(0, i, tagColumns.get(tag), 0);
                pvTop.appendPVField(tag, tagsArray);
            }
        }

        @SuppressWarnings("unused")
        private Status parseParameters() {
            // There are no parameters recognized by the cfService, so
            // always return okStatus.
            //
            return okStatus;
        }

        void _dbg(String debug_message) {
            if (DEBUG) {
                System.err.println(debug_message);
            }
        }
    }
}
