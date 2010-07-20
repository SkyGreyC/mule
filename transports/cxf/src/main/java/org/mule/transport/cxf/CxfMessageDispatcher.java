/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.cxf;

import org.mule.DefaultMuleMessage;
import org.mule.api.MuleEvent;
import org.mule.api.MuleMessage;
import org.mule.api.config.MuleProperties;
import org.mule.api.endpoint.EndpointURI;
import org.mule.api.endpoint.MalformedEndpointException;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.api.transformer.TransformerException;
import org.mule.api.transport.PropertyScope;
import org.mule.message.DefaultExceptionPayload;
import org.mule.transport.AbstractMessageDispatcher;
import org.mule.transport.NullPayload;
import org.mule.transport.cxf.security.WebServiceSecurityException;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.http.HttpConstants;
import org.mule.transport.soap.SoapConstants;
import org.mule.util.StringUtils;
import org.mule.util.TemplateParser;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.activation.DataHandler;
import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Holder;

import org.apache.commons.httpclient.HttpException;
import org.apache.cxf.binding.soap.SoapBindingConstants;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.ClientImpl;
import org.apache.cxf.service.model.BindingOperationInfo;

/**
 * The CxfMessageDispatcher is used for making Soap client requests to remote
 * services.
 */
public class CxfMessageDispatcher extends AbstractMessageDispatcher
{

    private static final String URI_REGEX = "cxf:\\[(.+?)\\]:(.+?)/\\[(.+?)\\]:(.+?)";
    Pattern URI_PATTERN = Pattern.compile(URI_REGEX);

    protected final CxfConnector connector;
    protected ClientWrapper wrapper;
    private final TemplateParser soapActionTemplateParser = TemplateParser.createMuleStyleParser();
    protected final CxfPayloadToArguments payloadToArguments;

    public CxfMessageDispatcher(OutboundEndpoint endpoint) throws MalformedEndpointException
    {
        super(endpoint);
        this.connector = (CxfConnector) endpoint.getConnector();
        payloadToArguments = CxfPayloadToArguments.getPayloadToArgumentsForEndpoint(endpoint);
    }

    /*
    We need a way to associate an endpoint with a specific CXF service and operation, and the most sensible way to
    accomplish that is to overload URI syntax:

    cxf:[service_URI]:service_localname/[ep_URI]:ep_localname

    And the map method to operation
     */
    @Override
    protected void doConnect() throws Exception
    {
        wrapper = new ClientWrapper(connector.getCxfBus(), endpoint);
    }

    @Override
    protected void doDisconnect() throws Exception
    {
        // nothing to do
    }

    @Override
    protected void doDispose()
    {
        // nothing to do
    }

    @Override
    public void passivate()
    {
        // MULE-4899: cleans up client's request and response context to avoid a memory leak.
        try
        {
            super.passivate();
        }
        finally
        {
            Map<String, Object> requestContext = wrapper.client.getRequestContext();
            requestContext.clear();
            Map<String, Object> responseContext = wrapper.client.getResponseContext();
            responseContext.clear();
        }
    }

    protected Object[] getArgs(MuleEvent event) throws TransformerException
    {
        Object payload;
        
        if (wrapper.isApplyTransformersToProtocol())
        {
            payload = event.getMessage().getPayload();
        }
        else 
        {
            payload = event.transformMessage();
        }
        
        if (wrapper.isProxy())
        {
            return new Object[] { event.getMessage() };
        }
        
        Object[] args = payloadToArguments.payloadToArrayOfArguments(payload);

        MuleMessage message = event.getMessage();
        Set<?> attachmentNames = message.getAttachmentNames();
        if (attachmentNames != null && !attachmentNames.isEmpty())
        {
            List<DataHandler> attachments = new ArrayList<DataHandler>();
            for (Iterator<?> i = attachmentNames.iterator(); i.hasNext();)
            {
                attachments.add(message.getAttachment((String)i.next()));
            }
            List<Object> temp = new ArrayList<Object>(Arrays.asList(args));
            temp.add(attachments.toArray(new DataHandler[0]));
            args = temp.toArray();
        }

        if (args.length == 0)
        {
            return null;
        }
        return args;
    }

    @Override
    protected MuleMessage doSend(MuleEvent event) throws Exception
    {
        ((ClientImpl)wrapper.getClient()).setSynchronousTimeout(event.getTimeout());
        
        MuleMessage res;
        if (!wrapper.isClientProxyAvailable())
        {
            res = doSendWithClient(event);
        }
        else
        {
            res = doSendWithProxy(event);
        }

        return res;
    }

    protected MuleMessage doSendWithProxy(MuleEvent event) throws Exception
    {
        Method method = wrapper.getMethod(event);

        Map<String, Object> props = getInovcationProperties(event);
        
        Holder<MuleMessage> holder = new Holder<MuleMessage>();
        props.put("holder", holder); 
        // Set custom soap action if set on the event or endpoint
        String soapAction = event.getMessage().getOutboundProperty(SoapConstants.SOAP_ACTION_PROPERTY);
        if (soapAction != null)
        {
            soapAction = parseSoapAction(soapAction, new QName(method.getName()), event);
            props.put(SoapBindingConstants.SOAP_ACTION, soapAction);
        }
        
        BindingProvider bp = wrapper.getClientProxy();
        bp.getRequestContext().putAll(props);
        
        Object response;
        try
        {
            response = method.invoke(wrapper.getClientProxy(), getArgs(event));
        }
        catch (InvocationTargetException e)
        {
            Throwable ex = e.getTargetException();

            if (ex != null && ex.getMessage().contains("Security"))
            {
                throw new WebServiceSecurityException(event, e);
            }
            else
            {
                throw e;
            }
        }        
        
        // TODO: handle holders
        MuleMessage muleRes = holder.value;
        return buildResponseMessage(muleRes, new Object[] { response });
    }

    protected MuleMessage doSendWithClient(MuleEvent event) throws Exception
    {
        BindingOperationInfo bop = wrapper.getOperation(event);
        
        Map<String, Object> props = getInovcationProperties(event);
        
        // Holds the response from the transport
        Holder<MuleMessage> holder = new Holder<MuleMessage>();
        props.put("holder", holder); 
        
        // Set custom soap action if set on the event or endpoint
        final MuleMessage message = event.getMessage();
        String soapAction = message.getOutboundProperty(SoapConstants.SOAP_ACTION_PROPERTY);
        if (soapAction != null)
        {
            soapAction = parseSoapAction(soapAction, bop.getName(), event);
            props.put(SoapBindingConstants.SOAP_ACTION, soapAction);
            message.setOutboundProperty(SoapConstants.SOAP_ACTION_PROPERTY, soapAction);
        }
        
        Map<String, Object> ctx = new HashMap<String, Object>();
        ctx.put(Client.REQUEST_CONTEXT, props); 
        ctx.put(Client.RESPONSE_CONTEXT, props); 
        
        // Set Custom Headers on the client
        // populate only from invocation and outbound scopes
        addToHeaders(props, message, PropertyScope.INVOCATION);
        addToHeaders(props, message, PropertyScope.OUTBOUND);

        Object[] response = wrapper.getClient().invoke(bop, getArgs(event), ctx);

        MuleMessage muleRes = holder.value;
        return buildResponseMessage(muleRes, response);
    }

    protected void addToHeaders(Map<String, Object> props, MuleMessage message, PropertyScope scope)
    {
        for (String name : message.getPropertyNames(scope))
        {
            String header = message.getProperty(name, scope);
            if ((header != null) && (!header.startsWith("MULE")))
            {
                props.put(name, header);
            }
        }
    }

    private Map<String, Object> getInovcationProperties(MuleEvent event)
    {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(MuleProperties.MULE_EVENT_PROPERTY, event); 
        EndpointURI uri = endpoint.getEndpointURI();
        if (uri.getUser() != null) 
        {
            props.put(BindingProvider.USERNAME_PROPERTY, uri.getUser());
            props.put(BindingProvider.PASSWORD_PROPERTY, uri.getPassword());
        }
        return props;
    }

    protected MuleMessage buildResponseMessage(MuleMessage transportResponse, Object[] response) 
    {
        // One way dispatches over an async transport result in this
        if (transportResponse == null) 
        {
            return new DefaultMuleMessage(NullPayload.getInstance(), connector.getMuleContext());
        }
        
        // Otherwise we may have a response!
        MuleMessage result = null;
        if (response != null && response.length <= 1)
        {
            if (response.length == 1)
            {
                result = new DefaultMuleMessage(response[0], transportResponse, connector.getMuleContext());
            }
        }
        else
        {
            result = new DefaultMuleMessage(response, transportResponse, connector.getMuleContext());
        }

        String statusCode = transportResponse.getOutboundProperty(HttpConnector.HTTP_STATUS_PROPERTY);
        if (statusCode != null && Integer.parseInt(statusCode) != HttpConstants.SC_OK)
        {
            String payload;
            try
            {
                payload = transportResponse.getPayloadAsString();
            }
            catch (Exception e)
            {
                payload = "Invalid status code: " + statusCode;
            }
            result.setExceptionPayload(new DefaultExceptionPayload(new HttpException(payload)));
        }

        return result;
    }
    
    @Override
    protected void doDispatch(MuleEvent event) throws Exception
    {
        doSend(event);
    }

    public String parseSoapAction(String soapAction, QName method, MuleEvent event)
    {
        EndpointURI endpointURI = event.getEndpoint().getEndpointURI();
        Map<String, String> properties = new HashMap<String, String>();
        MuleMessage msg = event.getMessage();
        // propagate only invocation- and outbound-scoped properties
        for (String name : event.getMessage().getPropertyNames(PropertyScope.INVOCATION))
        {
            final String value = msg.getInvocationProperty(name, StringUtils.EMPTY);
            properties.put(name, value);
        }
        for (String name : event.getMessage().getPropertyNames(PropertyScope.OUTBOUND))
        {
            final String value = msg.getOutboundProperty(name, StringUtils.EMPTY);
            properties.put(name, value);
        }

        for (Iterator<?> iterator = msg.getPropertyNames().iterator(); iterator.hasNext();)
        {
            String propertyKey = (String)iterator.next();
            properties.put(propertyKey, msg.getProperty(propertyKey).toString());
        }
        properties.put(MuleProperties.MULE_METHOD_PROPERTY, method.getLocalPart());
        properties.put("methodNamespace", method.getNamespaceURI());
        properties.put("address", endpointURI.getAddress());
        properties.put("scheme", endpointURI.getScheme());
        properties.put("host", endpointURI.getHost());
        properties.put("port", String.valueOf(endpointURI.getPort()));
        properties.put("path", endpointURI.getPath());
        properties.put("hostInfo", endpointURI.getScheme()
                                   + "://"
                                   + endpointURI.getHost()
                                   + (endpointURI.getPort() > -1
                                                   ? ":" + String.valueOf(endpointURI.getPort()) : ""));
        if (event.getFlowConstruct() != null)
        {
            properties.put("serviceName", event.getFlowConstruct().getName());
        }

        soapAction = soapActionTemplateParser.parse(properties, soapAction);

        if (logger.isDebugEnabled())
        {
            logger.debug("SoapAction for this call is: " + soapAction);
        }

        return soapAction;
    }
}


