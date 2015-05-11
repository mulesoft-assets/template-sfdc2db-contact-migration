/**
 * Mule Anypoint Template
 *
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 */

package org.mule.templates.integration;

import static org.mule.templates.builders.SfdcObjectBuilder.aContact;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleException;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.processor.chain.InterceptingChainLifecycleWrapper;
import org.mule.processor.chain.SubflowInterceptingChainLifecycleWrapper;
import org.mule.templates.builders.SfdcObjectBuilder;
import org.mule.templates.db.MySQLDbCreator;
import org.mule.util.UUID;

import com.mulesoft.module.batch.BatchTestHelper;
import com.sforce.soap.partner.SaveResult;

/**
 * The objective of this class is validating the correct behavior of the flows
 * for this Mule Anypoint Template
 * 
 */
@SuppressWarnings("unchecked")
public class BusinessLogicIT extends AbstractTemplateTestCase {

	private static final String INBOUND_FLOW_NAME = "triggerFlow";
	private static final String ANYPOINT_TEMPLATE_NAME = "sfdc2db-contact-migration";
	private static final int TIMEOUT_MILLIS = 60000;

	private static List<String> contactsCreatedInSFDC = new ArrayList<String>();
	private static SubflowInterceptingChainLifecycleWrapper deleteContactFromSFDCFlow;
	
	private static final String PATH_TO_TEST_PROPERTIES = "./src/test/resources/mule.test.properties";
	private static final String PATH_TO_SQL_SCRIPT = "src/main/resources/contact.sql";
	private static final String DATABASE_NAME = "SFDC2DBContactMigration" + new Long(new Date().getTime()).toString();
	private static final MySQLDbCreator DBCREATOR = new MySQLDbCreator(DATABASE_NAME, PATH_TO_SQL_SCRIPT, PATH_TO_TEST_PROPERTIES);
	private String name = "";
	
	private SubflowInterceptingChainLifecycleWrapper retrieveContactFromDatabaseFlow;
	private SubflowInterceptingChainLifecycleWrapper createContactInSFDCFlow;
	private BatchTestHelper batchTestHelper;
	private Map<String, Object> contact;
	
	@BeforeClass
	public static void beforeTestClass() {
		System.setProperty("page.size", "1000");
		System.setProperty("db.jdbcUrl", DBCREATOR.getDatabaseUrlWithName());
		System.setProperty("account.sync.policy", "syncAccount");
		DateTimeFormatter df = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");		
		System.setProperty("migration.date", df.print(new Date().getTime() - 1000 * 60 * 60 * 24));
	}
	
	@Before
	public void setUp() throws Exception {
		getAndInitializeFlows();
		DBCREATOR.setUpDatabase();
		batchTestHelper = new BatchTestHelper(muleContext);
		
		name = ANYPOINT_TEMPLATE_NAME + "_" + UUID.getUUID();
		// Build test contacts
		SfdcObjectBuilder contactBuilder = aContact()
				.with("Phone", "00123456789")
				.with("LastName", name)
				.with("Email",
						ANYPOINT_TEMPLATE_NAME + "-"
								+ System.currentTimeMillis()
								+ "@mail.com");
		contact = contactBuilder.build();
		contactsCreatedInSFDC.add(createTestContactsInSfdcSandbox(contactBuilder.build(), createContactInSFDCFlow));
	}

	@AfterClass
	public static void shutDown() {
		System.clearProperty("polling.frequency");
		System.clearProperty("watermark.default.expression");
		System.clearProperty("account.sync.policy");
		
	}

	@After
	public void tearDown() throws MuleException, Exception {
		cleanUpSandboxesByRemovingTestContacts();
		DBCREATOR.tearDownDataBase();
	}

	private void getAndInitializeFlows() throws InitialisationException {
		// Flow for creating contacts in SFDC instance
		retrieveContactFromDatabaseFlow = getSubFlow("retrieveContactFromDatabaseFlow");
		retrieveContactFromDatabaseFlow.initialise();

		// Flow for creating contacts in SFDC instance
		createContactInSFDCFlow = getSubFlow("createContactInSFDCFlow");
		createContactInSFDCFlow.initialise();
		
		// Flow for deleting contacts in SFDC instance
		deleteContactFromSFDCFlow = getSubFlow("deleteContactFromSFDCFlow");
		deleteContactFromSFDCFlow.initialise();

	}

	private static void cleanUpSandboxesByRemovingTestContacts()
			throws MuleException, Exception {
		final List<String> idList = new ArrayList<String>();
		for (String contact : contactsCreatedInSFDC) {
			idList.add(contact);
		}
		deleteContactFromSFDCFlow.process(getTestEvent(idList,
				MessageExchangePattern.REQUEST_RESPONSE));		
	}

	@Test
	public void testMainFlow() throws MuleException, Exception {
		runFlow(INBOUND_FLOW_NAME);
		executeWaitAndAssertBatchJob(INBOUND_FLOW_NAME);
		
		Map<String, Object> contactInDb = new HashMap<String, Object>();
		contactInDb.put("Name", name);
		Map<String, Object> response = (Map<String, Object>)retrieveContactFromDatabaseFlow.process(getTestEvent(contactInDb, MessageExchangePattern.REQUEST_RESPONSE)).getMessage().getPayload();
		Assert.assertNotNull(response.get("Name"));
		Assert.assertTrue(response.get("Name").equals(contact.get("LastName")));
		Assert.assertTrue(response.get("Phone").equals(contact.get("Phone")));
		Assert.assertTrue(response.get("Email").equals(contact.get("Email")));		
		
	}
	

	private String createTestContactsInSfdcSandbox(Map<String, Object> contact,
			InterceptingChainLifecycleWrapper createContactFlow)
			throws MuleException, Exception {
		List<Map<String, Object>> salesforceContacts = new ArrayList<Map<String, Object>>();
		salesforceContacts.add(contact);

		final List<SaveResult> payloadAfterExecution = (List<SaveResult>) createContactFlow
				.process(
						getTestEvent(salesforceContacts,
								MessageExchangePattern.REQUEST_RESPONSE))
				.getMessage().getPayload();
		return payloadAfterExecution.get(0).getId();
	}

	private void executeWaitAndAssertBatchJob(String flowConstructName)
			throws Exception {

		// Execute synchronization
		runSchedulersOnce(flowConstructName);

		// Wait for the batch job execution to finish
		batchTestHelper.awaitJobTermination(TIMEOUT_MILLIS * 1000, 500);
		batchTestHelper.assertJobWasSuccessful();
	}
	

}
