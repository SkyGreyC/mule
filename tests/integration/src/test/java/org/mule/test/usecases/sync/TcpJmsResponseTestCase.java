/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.test.usecases.sync;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;
import org.mule.api.MuleMessage;
import org.mule.module.client.MuleClient;
import org.mule.tck.AbstractServiceAndFlowTestCase;

public class TcpJmsResponseTestCase extends AbstractServiceAndFlowTestCase
{

    public TcpJmsResponseTestCase(ConfigVariant variant, String configResources)
    {
        super(variant, configResources);

    }

    @Parameters
    public static Collection<Object[]> parameters()
    {
        return Arrays.asList(new Object[][]{
            {ConfigVariant.SERVICE, "org/mule/test/usecases/sync/tcp-jms-response-service.xml"},
            {ConfigVariant.FLOW, "org/mule/test/usecases/sync/tcp-jms-response-flow.xml"}});
    }

    @Test
    public void testSyncResponse() throws Exception
    {
        MuleClient client = new MuleClient(muleContext);
        MuleMessage message = client.send("tcp://localhost:4444", "request", null);
        assertNotNull(message);
        assertEquals("Received: request", message.getPayloadAsString());
    }
}
