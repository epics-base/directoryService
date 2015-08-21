/**
 * CFConnector accesses the ChannelFinder web service,
 * taking its arguments and returning the web service results as pvData.
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

import gov.bnl.channelfinder.api.Channel;
import gov.bnl.channelfinder.api.ChannelFinder;
import gov.bnl.channelfinder.api.ChannelFinderClient;
import gov.bnl.channelfinder.api.ChannelUtil;
import gov.bnl.channelfinder.api.Property;
import gov.bnl.channelfinder.api.Tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.epics.pvdata.factory.FieldFactory;
import org.epics.pvdata.factory.PVDataFactory;
import org.epics.pvdata.pv.FieldBuilder;
import org.epics.pvdata.pv.FieldCreate;
import org.epics.pvdata.pv.PVBooleanArray;
import org.epics.pvdata.pv.PVDataCreate;
import org.epics.pvdata.pv.PVField;
import org.epics.pvdata.pv.PVString;
import org.epics.pvdata.pv.PVStringArray;
import org.epics.pvdata.pv.PVStructure;
import org.epics.pvdata.pv.ScalarArray;
import org.epics.pvdata.pv.ScalarType;

/**
 * CFConnector retrieves data from the ChannelFinder web service.
 *
 * CFConnector expects arguments of the following form:
 * <pre>
 *     string query      - The query for which to get data from the relational database,
 *                          eg "SwissFEL:alldevices"
 *     string parameters  - No parameters are supported by cfService at present. When
 *                          functionality like text replacement is added, this argument
 *                          will be used.
 * </pre>
 *
 * It returns a PVStructure of normative type NTTable.
 *
 * @author Ralph Lange (Ralph.Lange@gmx.de)
 *
 */

class ChannelComparator implements Comparator<Channel> {

    private static List<String> sortProperties;

    ChannelComparator(List<String> prop) {
        sortProperties = prop;
    }

    @Override
    public int compare(Channel c1, Channel c2) {
        for (String prop : sortProperties) {
            Property p1 = c1.getProperty(prop);
            Property p2 = c2.getProperty(prop);

            if (p1 == null) {
                if (p2 != null) {
                    return -1;
                }
            } else {
                if (p2 == null) {
                    return 1;
                } else {
                    int cmp = p1.getValue().compareTo(p2.getValue());
                    if (cmp != 0) {
                        return cmp;
                    }
                }
            }
        }
        return 0;
    }
}

public class CFConnector {

    private static final boolean DEBUG = false; // Print debug info
    private static ChannelFinderClient cfClient = null;
    private static final FieldCreate fieldCreate = FieldFactory.getFieldCreate();
    private static final PVDataCreate pvDataCreate = PVDataFactory.getPVDataCreate();

    private void connect() {
        cfClient = ChannelFinder.getClient();
        if (cfClient != null) {
            _dbg("Successfully created ChannelFinder web service client");
        } else {
            throw new IllegalStateException("Unable to create ChannelFinder web service client");
        }
    }

    private List<String> removeDoubles(List<String> l) {
        Set<String> s = new LinkedHashSet<String>(l);
        return new ArrayList<String>(s);
    }
    
    /**
     * getData performs a query on the ChannelFinder directory service.
     * 
     * @param args pvData structure holding the arguments
     * @return NTTable structure with the results
     */
    public PVStructure getData(PVStructure args) {
        PVString pvStringArg;
        String query;
        List<String> show = null;
        boolean useShowFilter = false;
        List<String> sort = null;
        boolean showOwner = false;
        
        if (cfClient == null) {
            connect();
        }
        
        // Parsing arguments
        
        pvStringArg = args.getStringField("query");
        if (pvStringArg == null) {
            throw new IllegalArgumentException("No query in argument list");
        }
        query = pvStringArg.get();
        _dbg("Got request, query=" + query);
        
        pvStringArg = args.getStringField("show");
        if (pvStringArg != null) {
            show = new ArrayList<String>(Arrays.asList(pvStringArg.get().split(",")));
            show.add("channel");
            show = removeDoubles(show);
            useShowFilter = true;
            _dbg("  Arg show=" + show.toString());
        }
        
        pvStringArg = args.getStringField("sort");
        if (pvStringArg != null) {
            sort = new ArrayList<String>(Arrays.asList(pvStringArg.get().split(",")));
            sort = removeDoubles(sort);
            _dbg("  Arg sort=" + sort.toString());
        }
        
        pvStringArg = args.getStringField("owner");
        if (pvStringArg != null) {
            showOwner = true;
            _dbg("  Arg owner");
        }
        
        Collection<Channel> channels;
        List<String> properties;
        List<String> tags;
        int nChan;

        HashMap<String, String[]> propColumns = new HashMap<String, String[]>();
        HashMap<String, boolean[]> tagColumns = new HashMap<String, boolean[]>();
        String[] chanColumn;
        String[] ownerColumn;

        /* Do the ChannelFinder query */
        channels = cfClient.find(query);
        
        if (channels != null) {
            if (sort != null) {
                ChannelComparator comp = new ChannelComparator(sort);
                ArrayList<Channel> sortedCh = new ArrayList<Channel>(channels);
                Collections.sort(sortedCh, comp);
                channels = sortedCh;
            }
            nChan = channels.size();
            properties = new ArrayList<String>(
                            ChannelUtil.getPropertyNames(channels));
            tags = new ArrayList<String>(ChannelUtil.getAllTagNames(channels));
            _dbg("ChannelFinder returned " + nChan + " channels with "
                    + properties.size() + " properties and " + tags.size() + " tags");
        } else {
            nChan = 0;
            _dbg("ChannelFinder returned no channels");
            properties = Collections.emptyList();
            tags = Collections.emptyList();
        }

        /* Filter out no-show columns */
        if (useShowFilter) {
            properties.retainAll(show);
            tags.retainAll(show);
            _dbg("After no-show filtering remain " + properties.size() + " properties and " + tags.size() + " tags");
        }
        
        /* Create the empty columns data arrays */
        chanColumn  = new String[nChan];
        ownerColumn = new String[nChan];
        for (String prop : properties) {
            propColumns.put(prop, new String[nChan]);
        }
        for (String tag : tags) {
            tagColumns.put(tag, new boolean[nChan]);
        }
        int noCols = 1 + properties.size() + tags.size();  // channel properties tags
        if (showOwner) {
            noCols++;
        }
        _dbg("Reply contains " + noCols + " columns");

        /* Loop through the channels, setting the appropriate fields in the column data */
        int i = 0;
        for (Channel chan : channels) {
            chanColumn[i] = chan.getName();
            if (showOwner) {
                ownerColumn[i] = chan.getOwner();
            }
            for (Property prop : chan.getProperties()) {
                String s = prop.getName();
                if (!useShowFilter || properties.contains(s)) {
                    String[] col = propColumns.get(s);
                    col[i] = prop.getValue();
                }
            }
            for (Tag tag : chan.getTags()) {
                String s = tag.getName();
                if (!useShowFilter || tags.contains(s)) {
                    boolean[] col = tagColumns.get(s);
                    col[i] = true;
                }
            }
            i++;
        }

        /* Create the labels */
        List<String> labels = new ArrayList<String>(noCols);
        
        /* Create the value fields */
        List<Object> valueFieldsData = new ArrayList<Object>();
        
        /* Construct the return data NTTable */
        FieldBuilder topBuilder = fieldCreate.createFieldBuilder();
        topBuilder.setId("epics:nt/NTTable:1.0");
        topBuilder.addArray("labels", ScalarType.pvString);
        
        FieldBuilder valueNestedBuilder = topBuilder.addNestedStructure("value");

        ScalarArray stringColumnField = fieldCreate.createScalarArray(ScalarType.pvString);
        ScalarArray booleanColumnField = fieldCreate.createScalarArray(ScalarType.pvBoolean);
        
        Integer col = 0;
        
        /* Add channel column */
        if (nChan > 0) {
            valueNestedBuilder.add("c"+col.toString(), stringColumnField);
            valueFieldsData.add(chanColumn);
            col++;
            labels.add("channel");
        }

        /* Add owner column */
        if (showOwner && nChan > 0) {
            valueNestedBuilder.add("c"+col.toString(), stringColumnField);
            valueFieldsData.add(ownerColumn);
            col++;
            labels.add("@owner");
        }

        /* Add properties columns */
        if (useShowFilter) {
            for (String prop : show) {
                if (properties.contains(prop)) {
                    valueNestedBuilder.add("c"+col.toString(), stringColumnField);
                    valueFieldsData.add(propColumns.get(prop));
                    col++;
                    labels.add(prop);
                }
            }
        } else {
            for (String prop : properties) {
                valueNestedBuilder.add("c"+col.toString(), stringColumnField);
                valueFieldsData.add(propColumns.get(prop));
                col++;
                labels.add(prop);
            }
        }

        /* Add tags columns */
        if (useShowFilter) {
            for (String tag : show) {
                if (tags.contains(tag)) {
                    valueNestedBuilder.add("c"+col.toString(), booleanColumnField);
                    valueFieldsData.add(tagColumns.get(tag));
                    col++;
                    labels.add(tag);
                }
            }
        } else {
            for (String tag : tags) {
                valueNestedBuilder.add("c"+col.toString(), booleanColumnField);
                valueFieldsData.add(tagColumns.get(tag));
                col++;
                labels.add(tag);
            }
        }

        valueNestedBuilder.endNested();
        
        PVStructure pvTop = pvDataCreate.createPVStructure(topBuilder.createStructure());
        PVStructure pvValue = pvTop.getStructureField("value");
        int ix = 0;
        for (PVField pvField : pvValue.getPVFields())
        {
        	if (pvField instanceof PVStringArray)
        	{
        		String[] val = (String[])valueFieldsData.get(ix);
        		((PVStringArray)pvField).put(0, val.length, val, 0);
        	}
        	else if (pvField instanceof PVBooleanArray)
        	{
        		boolean[] val = (boolean[])valueFieldsData.get(ix);
        		((PVBooleanArray)pvField).put(0, val.length, val, 0);
        	}
        	else
        		throw new RuntimeException("unsupported column type: " + pvField.getField());
        	ix++;
        }
        
        PVStringArray labelsArray = (PVStringArray) pvTop.getScalarArrayField("labels", ScalarType.pvString);
        labelsArray.put(0, noCols, labels.toArray(new String[0]), 0);
        
        _dbg("Returned data:\n" + pvTop);
        return pvTop;
    }

    private static void _dbg(String debug_message) {
        if (DEBUG) {
            System.err.println("DEBUG (" + CFConnector.class.getSimpleName() + "): " + debug_message);
        }
    }
}
