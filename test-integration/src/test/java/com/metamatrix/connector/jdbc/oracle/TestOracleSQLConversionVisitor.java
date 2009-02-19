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

package com.metamatrix.connector.jdbc.oracle;

import java.util.Map;
import java.util.Properties;

import junit.framework.TestCase;

import com.metamatrix.cdk.CommandBuilder;
import com.metamatrix.cdk.api.EnvironmentUtility;
import com.metamatrix.cdk.api.TranslationUtility;
import com.metamatrix.common.types.DataTypeManager;
import com.metamatrix.connector.api.ConnectorException;
import com.metamatrix.connector.api.ExecutionContext;
import com.metamatrix.connector.jdbc.JDBCPropertyNames;
import com.metamatrix.connector.jdbc.extension.SQLConversionVisitor;
import com.metamatrix.connector.jdbc.extension.TranslatedCommand;
import com.metamatrix.connector.jdbc.util.FunctionReplacementVisitor;
import com.metamatrix.connector.language.ICommand;
import com.metamatrix.connector.metadata.runtime.RuntimeMetadata;
import com.metamatrix.core.util.UnitTestUtil;
import com.metamatrix.dqp.internal.datamgr.impl.ConnectorEnvironmentImpl;
import com.metamatrix.dqp.internal.datamgr.impl.ExecutionContextImpl;
import com.metamatrix.dqp.internal.datamgr.impl.FakeExecutionContextImpl;
import com.metamatrix.dqp.internal.datamgr.metadata.MetadataFactory;
import com.metamatrix.dqp.internal.datamgr.metadata.RuntimeMetadataImpl;
import com.metamatrix.query.metadata.QueryMetadataInterface;
import com.metamatrix.query.unittest.FakeMetadataFacade;
import com.metamatrix.query.unittest.FakeMetadataFactory;
import com.metamatrix.query.unittest.FakeMetadataObject;
import com.metamatrix.query.unittest.FakeMetadataStore;

/**
 */
public class TestOracleSQLConversionVisitor extends TestCase {
    private static Map MODIFIERS;
    private static ExecutionContext EMPTY_CONTEXT = new FakeExecutionContextImpl();
    
    static {
        OracleSQLTranslator trans = new OracleSQLTranslator();
        
        try {
            trans.initialize(new ConnectorEnvironmentImpl(new Properties(), null, null));
        } catch(ConnectorException e) {
            e.printStackTrace();
        }
        
        MODIFIERS = trans.getFunctionModifiers();
        
        ExtractFunctionModifier extractMod = new ExtractFunctionModifier ("month"); //$NON-NLS-1$
        MODIFIERS.put("extract", extractMod);  //$NON-NLS-1$
    }

    /**
     * Constructor for TestOracleSQLConversionVisitor.
     * @param name
     */
    public TestOracleSQLConversionVisitor(String name) {
        super(name);
    }

    private String getTestVDB() {
        return UnitTestUtil.getTestDataPath() + "/PartsSupplierOracle.vdb"; //$NON-NLS-1$
    }
    
    private void helpTestVisitor(String vdb, String input, Map modifiers, String dbmsTimeZone, String expectedOutput) throws ConnectorException {
        helpTestVisitor(vdb, input, modifiers, EMPTY_CONTEXT, dbmsTimeZone, expectedOutput, false);
    }

    private void helpTestVisitor(String vdb, String input, Map modifiers, String dbmsTimeZone, String expectedOutput, boolean correctNaming) throws ConnectorException {
        helpTestVisitor(vdb, input, modifiers, EMPTY_CONTEXT, dbmsTimeZone, expectedOutput, correctNaming);
    }

    private void helpTestVisitor(String vdb, String input, Map modifiers, ExecutionContext context, String dbmsTimeZone, String expectedOutput, boolean correctNaming) throws ConnectorException {
        // Convert from sql to objects
        TranslationUtility util = new TranslationUtility(vdb);
        ICommand obj =  util.parseCommand(input, correctNaming, true);        
		this.helpTestVisitor(obj, util.createRuntimeMetadata(), modifiers, context, dbmsTimeZone, expectedOutput);
    }

    /** Helper method takes a QueryMetadataInterface impl instead of a VDB filename 
     * @throws ConnectorException 
     */
    private void helpTestVisitor(QueryMetadataInterface metadata, String input, Map modifiers, ExecutionContext context, String dbmsTimeZone, String expectedOutput) throws ConnectorException {
        // Convert from sql to objects
        CommandBuilder commandBuilder = new CommandBuilder(metadata);
        ICommand obj = commandBuilder.getCommand(input);
        RuntimeMetadata runtimeMetadata = new RuntimeMetadataImpl(new MetadataFactory(metadata));
		this.helpTestVisitor(obj, runtimeMetadata, modifiers, context, dbmsTimeZone, expectedOutput);
    }
    
    private void helpTestVisitor(ICommand obj, RuntimeMetadata metadata, Map modifiers, ExecutionContext context, String dbmsTimeZone, String expectedOutput) throws ConnectorException {

        
        // Apply function replacement
        FunctionReplacementVisitor funcVisitor = new FunctionReplacementVisitor(modifiers);
        OracleSQLTranslator translator = new OracleSQLTranslator();
        Properties p = new Properties();
        if (dbmsTimeZone != null) {
        	p.setProperty(JDBCPropertyNames.DATABASE_TIME_ZONE, dbmsTimeZone);
        }
        translator.initialize(EnvironmentUtility.createEnvironment(p, false));
        // Convert back to SQL
        SQLConversionVisitor sqlVisitor = new SQLConversionVisitor(translator);      
        sqlVisitor.setExecutionContext(context);
        TranslatedCommand tc = new TranslatedCommand(context, translator, sqlVisitor, funcVisitor);
        tc.translateCommand(obj);
        
        // Check stuff
        assertEquals("Did not get correct sql", expectedOutput, tc.getSql());             //$NON-NLS-1$
    }
    
    /**
     * case 3905
     * as of 5.6 this is handled by the rewriter, but it's possible
     * that we may bring back source queries with expressions in the group by clause
     */
    public void defer_testFunctionsInGroupBy() throws Exception {
        String input = "SELECT substring(PART_NAME, 2, 1) FROM PARTS Group By substring(PART_NAME, 2, 1)"; //$NON-NLS-1$
        String output = "SELECT substr(PARTS.PART_NAME, 2, 1) FROM PARTS GROUP BY substr(PARTS.PART_NAME, 2, 1)"; //$NON-NLS-1$
        
        helpTestVisitor(getTestVDB(),
                        input, 
                        MODIFIERS, null,
                        output);
    }
    
    /** defect 21775 */
    public void testDateStuff() throws Exception {
        String input = "SELECT ((CASE WHEN month(datevalue) < 10 THEN ('0' || convert(month(datevalue), string)) ELSE convert(month(datevalue), string) END || CASE WHEN dayofmonth(datevalue) < 10 THEN ('0' || convert(dayofmonth(datevalue), string)) ELSE convert(dayofmonth(datevalue), string) END) || convert(year(datevalue), string)), SUM(intkey) FROM bqt1.SMALLA GROUP BY datevalue"; //$NON-NLS-1$
        String output = "SELECT CASE WHEN (CASE WHEN (CASE WHEN EXTRACT(MONTH FROM SmallA.DateValue) < 10 THEN CASE WHEN to_char(EXTRACT(MONTH FROM SmallA.DateValue)) IS NULL THEN NULL ELSE concat('0', to_char(EXTRACT(MONTH FROM SmallA.DateValue))) END ELSE to_char(EXTRACT(MONTH FROM SmallA.DateValue)) END IS NULL) OR (CASE WHEN EXTRACT(DAY FROM SmallA.DateValue) < 10 THEN CASE WHEN to_char(EXTRACT(DAY FROM SmallA.DateValue)) IS NULL THEN NULL ELSE concat('0', to_char(EXTRACT(DAY FROM SmallA.DateValue))) END ELSE to_char(EXTRACT(DAY FROM SmallA.DateValue)) END IS NULL) THEN NULL ELSE concat(CASE WHEN EXTRACT(MONTH FROM SmallA.DateValue) < 10 THEN CASE WHEN to_char(EXTRACT(MONTH FROM SmallA.DateValue)) IS NULL THEN NULL ELSE concat('0', to_char(EXTRACT(MONTH FROM SmallA.DateValue))) END ELSE to_char(EXTRACT(MONTH FROM SmallA.DateValue)) END, CASE WHEN EXTRACT(DAY FROM SmallA.DateValue) < 10 THEN CASE WHEN to_char(EXTRACT(DAY FROM SmallA.DateValue)) IS NULL THEN NULL ELSE concat('0', to_char(EXTRACT(DAY FROM SmallA.DateValue))) END ELSE to_char(EXTRACT(DAY FROM SmallA.DateValue)) END) END IS NULL) OR (to_char(EXTRACT(YEAR FROM SmallA.DateValue)) IS NULL) THEN NULL ELSE concat(CASE WHEN (CASE WHEN EXTRACT(MONTH FROM SmallA.DateValue) < 10 THEN CASE WHEN to_char(EXTRACT(MONTH FROM SmallA.DateValue)) IS NULL THEN NULL ELSE concat('0', to_char(EXTRACT(MONTH FROM SmallA.DateValue))) END ELSE to_char(EXTRACT(MONTH FROM SmallA.DateValue)) END IS NULL) OR (CASE WHEN EXTRACT(DAY FROM SmallA.DateValue) < 10 THEN CASE WHEN to_char(EXTRACT(DAY FROM SmallA.DateValue)) IS NULL THEN NULL ELSE concat('0', to_char(EXTRACT(DAY FROM SmallA.DateValue))) END ELSE to_char(EXTRACT(DAY FROM SmallA.DateValue)) END IS NULL) THEN NULL ELSE concat(CASE WHEN EXTRACT(MONTH FROM SmallA.DateValue) < 10 THEN CASE WHEN to_char(EXTRACT(MONTH FROM SmallA.DateValue)) IS NULL THEN NULL ELSE concat('0', to_char(EXTRACT(MONTH FROM SmallA.DateValue))) END ELSE to_char(EXTRACT(MONTH FROM SmallA.DateValue)) END, CASE WHEN EXTRACT(DAY FROM SmallA.DateValue) < 10 THEN CASE WHEN to_char(EXTRACT(DAY FROM SmallA.DateValue)) IS NULL THEN NULL ELSE concat('0', to_char(EXTRACT(DAY FROM SmallA.DateValue))) END ELSE to_char(EXTRACT(DAY FROM SmallA.DateValue)) END) END, to_char(EXTRACT(YEAR FROM SmallA.DateValue))) END, SUM(SmallA.IntKey) FROM SmallA GROUP BY SmallA.DateValue"; //$NON-NLS-1$
        
        helpTestVisitor(FakeMetadataFactory.exampleBQTCached(),
                        input, 
                        MODIFIERS, EMPTY_CONTEXT, null,
                        output);
    }
    
    /**
     * defect 21775
     * as of 5.6 this is handled by the rewriter, but it's possible
     * that we may bring back source queries with expressions in the group by clause
     */

    public void defer_testDateStuffGroupBy() throws Exception {
        String input = "SELECT ((CASE WHEN month(datevalue) < 10 THEN ('0' || convert(month(datevalue), string)) ELSE convert(month(datevalue), string) END || CASE WHEN dayofmonth(datevalue) < 10 THEN ('0' || convert(dayofmonth(datevalue), string)) ELSE convert(dayofmonth(datevalue), string) END) || convert(year(datevalue), string)), SUM(intkey) FROM bqt1.SMALLA GROUP BY month(datevalue), dayofmonth(datevalue), year(datevalue)"; //$NON-NLS-1$
        String output = "SELECT ((CASE WHEN EXTRACT(MONTH FROM SmallA.DateValue) < 10 THEN ('0' || to_char(EXTRACT(MONTH FROM SmallA.DateValue))) ELSE to_char(EXTRACT(MONTH FROM SmallA.DateValue)) END || CASE WHEN TO_NUMBER(TO_CHAR(SmallA.DateValue, 'DD')) < 10 THEN ('0' || to_char(TO_NUMBER(TO_CHAR(SmallA.DateValue, 'DD')))) ELSE to_char(TO_NUMBER(TO_CHAR(SmallA.DateValue, 'DD'))) END) || to_char(EXTRACT(YEAR FROM SmallA.DateValue))), SUM(SmallA.IntKey) FROM SmallA GROUP BY EXTRACT(MONTH FROM SmallA.DateValue), TO_NUMBER(TO_CHAR(SmallA.DateValue, 'DD')), EXTRACT(YEAR FROM SmallA.DateValue)"; //$NON-NLS-1$
        
        helpTestVisitor(FakeMetadataFactory.exampleBQTCached(),
                input, 
                MODIFIERS, EMPTY_CONTEXT, null,
                output);
    }
    
    public void testCharFunction() throws Exception {
        String input = "SELECT char(CONVERT(PART_ID, INTEGER)) FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT chr(to_number(PARTS.PART_ID)) FROM PARTS"; //$NON-NLS-1$
        
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }    

    public void testLcaseFunction() throws Exception {
        String input = "SELECT lcase(PART_NAME) FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT lower(PARTS.PART_NAME) FROM PARTS"; //$NON-NLS-1$
    
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testUcaseFunction() throws Exception {
        String input = "SELECT ucase(PART_NAME) FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT upper(PARTS.PART_NAME) FROM PARTS"; //$NON-NLS-1$
    
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testIfnullFunction() throws Exception {
        String input = "SELECT ifnull(PART_NAME, 'x') FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT nvl(PARTS.PART_NAME, 'x') FROM PARTS"; //$NON-NLS-1$
    
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testLogFunction() throws Exception {
        String input = "SELECT log(CONVERT(PART_ID, INTEGER)) FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT ln(to_number(PARTS.PART_ID)) FROM PARTS"; //$NON-NLS-1$
    
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testLog10Function() throws Exception {
        String input = "SELECT log10(CONVERT(PART_ID, INTEGER)) FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT log(10, to_number(PARTS.PART_ID)) FROM PARTS"; //$NON-NLS-1$
    
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testConvertFunctionInteger() throws Exception {
        String input = "SELECT convert(PARTS.PART_ID, integer) FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT to_number(PARTS.PART_ID) FROM PARTS"; //$NON-NLS-1$
    
    
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testConvertFunctionChar() throws Exception {
        String input = "SELECT convert(PARTS.PART_ID, char) FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT PARTS.PART_ID FROM PARTS"; //$NON-NLS-1$
    
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testConvertFunctionBoolean() throws Exception {
        String input = "SELECT convert(PARTS.PART_ID, boolean) FROM PARTS"; //$NON-NLS-1$
        //String output = "SELECT PARTS.PART_ID FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT decode(PARTS.PART_ID, 'true', 1, 'false', 0) FROM PARTS"; //$NON-NLS-1$
        
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testConvertFunctionDate() throws Exception {
        String input = "SELECT convert(PARTS.PART_ID, date) FROM PARTS"; //$NON-NLS-1$
        //String output = "SELECT PARTS.PART_ID FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT to_date(PARTS.PART_ID, 'YYYY-MM-DD') FROM PARTS";  //$NON-NLS-1$
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testConvertFunctionTime() throws Exception {
        String input = "SELECT convert(PARTS.PART_ID, time) FROM PARTS"; //$NON-NLS-1$
        //String output = "SELECT PARTS.PART_ID FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT to_date(('1970-01-01 ' || to_char(PARTS.PART_ID, 'HH24:MI:SS')), 'YYYY-MM-DD HH24:MI:SS') FROM PARTS"; //$NON-NLS-1$ 
    
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testConvertFunctionTimestamp() throws Exception {
        String input = "SELECT convert(PARTS.PART_ID, timestamp) FROM PARTS"; //$NON-NLS-1$
        //String output = "SELECT PARTS.PART_ID FROM PARTS"; //$NON-NLS-1$
        String output = "SELECT to_timestamp(PARTS.PART_ID, 'YYYY-MM-DD HH24:MI:SS.FF') FROM PARTS"; //$NON-NLS-1$
               
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }
    
    public void testExtractFunctionTimestamp() throws Exception {
        String input = "SELECT month(TIMESTAMPVALUE) FROM BQT1.Smalla"; //$NON-NLS-1$
        String output = "SELECT EXTRACT(MONTH FROM SmallA.TimestampValue) FROM SmallA"; //$NON-NLS-1$
               
        helpTestVisitor(FakeMetadataFactory.exampleBQTCached(),
                input, 
                MODIFIERS, EMPTY_CONTEXT, null,
                output);
    }

    public void testAliasedGroup() throws Exception {
        helpTestVisitor(getTestVDB(),
            "select y.part_name from parts as y", //$NON-NLS-1$
            MODIFIERS,
            null,
            "SELECT y.PART_NAME FROM PARTS y"); //$NON-NLS-1$
    }
    
    public void testDateLiteral() throws Exception {
        helpTestVisitor(getTestVDB(),
            "select {d'2002-12-31'} FROM parts", //$NON-NLS-1$
            MODIFIERS,
            null,
            "SELECT {d'2002-12-31'} FROM PARTS"); //$NON-NLS-1$
    }

    public void testTimeLiteral() throws Exception {
        helpTestVisitor(getTestVDB(),
            "select {t'13:59:59'} FROM parts", //$NON-NLS-1$
            MODIFIERS,
            null,
            "SELECT {ts'1970-01-01 13:59:59'} FROM PARTS"); //$NON-NLS-1$
    }

    public void testTimestampLiteral() throws Exception {
        helpTestVisitor(getTestVDB(),
            "select {ts'2002-12-31 13:59:59'} FROM parts", //$NON-NLS-1$
            MODIFIERS,
            null,
            "SELECT {ts'2002-12-31 13:59:59.0'} FROM PARTS"); //$NON-NLS-1$
    }

    public void testUnionOrderByWithThreeBranches() throws Exception {
        helpTestVisitor(getTestVDB(),
                        "select part_id id FROM parts UNION ALL select part_name FROM parts UNION ALL select part_id FROM parts ORDER BY id", //$NON-NLS-1$
                        MODIFIERS,
                        null,
                        "(SELECT g_2.PART_ID AS c_0 FROM PARTS g_2 UNION ALL SELECT g_1.PART_NAME AS c_0 FROM PARTS g_1) UNION ALL SELECT g_0.PART_ID AS c_0 FROM PARTS g_0 ORDER BY c_0", true); //$NON-NLS-1$
    }
    
    public void testUnionOrderBy() throws Exception {
        helpTestVisitor(getTestVDB(),
                        "select part_id FROM parts UNION ALL select part_name FROM parts ORDER BY part_id", //$NON-NLS-1$
                        MODIFIERS,
                        null,
                        "SELECT g_1.PART_ID AS c_0 FROM PARTS g_1 UNION ALL SELECT g_0.PART_NAME AS c_0 FROM PARTS g_0 ORDER BY c_0", true); //$NON-NLS-1$
    }

    public void testUnionOrderBy2() throws Exception {
        helpTestVisitor(getTestVDB(),
                        "select part_id as p FROM parts UNION ALL select part_name FROM parts ORDER BY p", //$NON-NLS-1$
                        MODIFIERS,
                        null,
                        "SELECT PARTS.PART_ID AS p FROM PARTS UNION ALL SELECT PARTS.PART_NAME FROM PARTS ORDER BY p"); //$NON-NLS-1$
    }

    public void testUpdateWithFunction() throws Exception {
        String input = "UPDATE bqt1.smalla SET intkey = intkey + 1"; //$NON-NLS-1$
        String output = "UPDATE SmallA SET IntKey = (SmallA.IntKey + 1)"; //$NON-NLS-1$
        
        helpTestVisitor(FakeMetadataFactory.exampleBQTCached(),
                input, 
                MODIFIERS, EMPTY_CONTEXT, null,
                output);
    }
    

    /**
     * Oracle's DUAL table is a pseudo-table; element names cannot be 
     * fully qualified since the table doesn't really exist nor contain
     * any columns.  But this requires modeling the DUAL table in 
     * MM as if it were a real physical table, and also modeling any
     * columns in the table.  Case 3742
     * 
     * @since 4.3
     */
    public void testDUAL() throws Exception {
        String input = "SELECT something FROM DUAL"; //$NON-NLS-1$
        String output = "SELECT something FROM DUAL"; //$NON-NLS-1$
               
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);
    }

    /**
     * Test Oracle's rownum pseudo-column.  Not a real column, so it can't
     * be fully-qualified with a table name.  MM requires this column to be
     * modeled in any table which the user wants to use rownum with.
     * Case 3739
     * 
     * @since 4.3
     */
    public void testROWNUM() throws Exception {
        String input = "SELECT part_name, rownum FROM parts"; //$NON-NLS-1$
        String output = "SELECT PARTS.PART_NAME, ROWNUM FROM PARTS"; //$NON-NLS-1$
               
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);        
    }
    
    /**
     * Test Oracle's rownum pseudo-column.  Not a real column, so it can't
     * be fully-qualified with a table name.  MM requires this column to be
     * modeled in any table which the user wants to use rownum with.  Case 3739
     * 
     * @since 4.3
     */
    public void testROWNUM2() throws Exception {
        String input = "SELECT part_name FROM parts where rownum < 100"; //$NON-NLS-1$
        String output = "SELECT PARTS.PART_NAME FROM PARTS WHERE ROWNUM < 100"; //$NON-NLS-1$
               
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            null,
            output);        
    }    
    
    /**
     * Case 3744.  Test that an Oracle-specific db hint, delivered as a String via command
     * payload, is added to the translated SQL.
     * 
     * @since 4.3
     */
    public void testOracleCommentPayload() throws Exception {
        String input = "SELECT part_name, rownum FROM parts"; //$NON-NLS-1$
        String output = "SELECT /*+ ALL_ROWS */ PARTS.PART_NAME, ROWNUM FROM PARTS"; //$NON-NLS-1$
               
        String hint = "/*+ ALL_ROWS */"; //$NON-NLS-1$
        ExecutionContext context = new ExecutionContextImpl(null, null, null, null, hint, null, "", null, null, null, false); //$NON-NLS-1$
        
        helpTestVisitor(getTestVDB(),
            input, 
            MODIFIERS,
            context,
            null,
            output, false);        
    }
    
    /**
     * reproducing this case relies on the name in source for the table being different from
     * the name
     */
    public void testCase3845() throws Exception {
        
        String input = "SELECT (DoubleNum * 1.0) FROM BQT1.Smalla"; //$NON-NLS-1$
        String output = "SELECT (SmallishA.DoubleNum * 1.0) FROM SmallishA"; //$NON-NLS-1$

        FakeMetadataFacade metadata = exampleCase3845();

        helpTestVisitor(metadata, input, MODIFIERS, EMPTY_CONTEXT, null, output);
    }
    
    /** create fake BQT metadata to test this case, name in source is important */
    private FakeMetadataFacade exampleCase3845() { 
        // Create models
        FakeMetadataObject bqt1 = FakeMetadataFactory.createPhysicalModel("BQT1"); //$NON-NLS-1$
        FakeMetadataObject bqt1SmallA = FakeMetadataFactory.createPhysicalGroup("BQT1.SmallA", bqt1); //$NON-NLS-1$
        bqt1SmallA.putProperty(FakeMetadataObject.Props.NAME_IN_SOURCE, "SmallishA");//$NON-NLS-1$
        FakeMetadataObject doubleNum = FakeMetadataFactory.createElement("BQT1.SmallA.DoubleNum", bqt1SmallA, DataTypeManager.DefaultDataTypes.DOUBLE, 0); //$NON-NLS-1$

        FakeMetadataStore store = new FakeMetadataStore();
        store.addObject(bqt1);
        store.addObject(bqt1SmallA);
        store.addObject(doubleNum);
        return new FakeMetadataFacade(store);
    }

	public void helpTestVisitor(String vdb, String input, String expectedOutput) throws ConnectorException {
		helpTestVisitor(vdb, input, MODIFIERS, null, expectedOutput);
	}

    public void testRowLimit2() throws Exception {
        String input = "select intkey from bqt1.smalla limit 100"; //$NON-NLS-1$
        String output = "SELECT * FROM (SELECT SmallA.IntKey FROM SmallA) WHERE ROWNUM <= 100"; //$NON-NLS-1$
               
        helpTestVisitor(FakeMetadataFactory.exampleBQTCached(),
                input, 
                MODIFIERS, EMPTY_CONTEXT, null,
                output);        
    }
    
    public void testRowLimit3() throws Exception {
        String input = "select intkey from bqt1.smalla limit 50, 100"; //$NON-NLS-1$
        String output = "SELECT * FROM (SELECT VIEW_FOR_LIMIT.*, ROWNUM ROWNUM_ FROM (SELECT SmallA.IntKey FROM SmallA) VIEW_FOR_LIMIT WHERE ROWNUM <= 100) WHERE ROWNUM_ > 50"; //$NON-NLS-1$
               
        helpTestVisitor(FakeMetadataFactory.exampleBQTCached(),
                input, 
                MODIFIERS, EMPTY_CONTEXT, null,
                output);        
    }
            
    public void testLimitWithNestedInlineView() throws Exception {
        String input = "select max(intkey), stringkey from (select intkey, stringkey from bqt1.smalla order by intkey limit 100) x group by intkey"; //$NON-NLS-1$
        String output = "SELECT MAX(x.intkey), x.stringkey FROM (SELECT * FROM (SELECT SmallA.IntKey, SmallA.StringKey FROM SmallA ORDER BY intkey) WHERE ROWNUM <= 100) x GROUP BY x.intkey"; //$NON-NLS-1$
               
        helpTestVisitor(FakeMetadataFactory.exampleBQTCached(),
                input, 
                MODIFIERS, EMPTY_CONTEXT, null,
                output);        
    }
    
    public void testExceptAsMinus() throws Exception {
        String input = "select intkey, intnum from bqt1.smalla except select intnum, intkey from bqt1.smallb"; //$NON-NLS-1$
        String output = "SELECT SmallA.IntKey, SmallA.IntNum FROM SmallA MINUS SELECT SmallB.IntNum, SmallB.IntKey FROM SmallB"; //$NON-NLS-1$
               
        helpTestVisitor(FakeMetadataFactory.exampleBQTCached(),
                input, 
                MODIFIERS, EMPTY_CONTEXT, null,
                output);        
    }

}
