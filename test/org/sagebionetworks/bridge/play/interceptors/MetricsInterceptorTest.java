package org.sagebionetworks.bridge.play.interceptors;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.Test;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.models.Metrics;
import org.sagebionetworks.bridge.play.interceptors.MetricsInterceptor;

import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Http.Context;
import play.mvc.Http.Request;

public class MetricsInterceptorTest {

    @Test
    public void testInitMetrics() throws Throwable {
        // Mock request
        final Request mockRequest = mock(Request.class);
        when(mockRequest.method()).thenReturn("POST");
        when(mockRequest.path()).thenReturn("/v3/participants");
        when(mockRequest.version()).thenReturn("HTTP/1.1");
        
        TestUtils.mockPlay().withRequest(mockRequest)
            .withHeader("X-Request-Id", "12345")
            .withHeader("X-Forwarded-For", "1.2.3.4")
            .withHeader("User-Agent", "ifeng 6").mock();
        
        // Mock context
        final Context mockContext = mock(Context.class);
        when(mockContext.request()).thenReturn(mockRequest);
        Http.Context.current = new ThreadLocal<Context>(){
            @Override
            protected Context initialValue() {
                return mockContext;
            }
        };
        
        // Fix the id that's assigned
        BridgeUtils.setRequestContext(new RequestContext.Builder().withRequestId("12345").build());
        
        // Test
        MetricsInterceptor interceptor = new MetricsInterceptor();
        
        Result mockResult = mock(Result.class);
        when(mockResult.status()).thenReturn(200);
        
        MethodInvocation mockInvocation = mock(MethodInvocation.class);
        when(mockInvocation.proceed()).thenReturn(mockResult);
        
        interceptor.invoke(mockInvocation);
        
        Metrics metrics = BridgeUtils.getRequestContext().getMetrics();
        assertNotNull(metrics);
        String json = metrics.toJsonString();
        assertNotNull(json);
        assertTrue(json.contains("\"version\":1"));
        assertTrue(json.contains("\"start\":"));
        assertTrue(json.contains("\"request_id\":\"12345\""));
        assertTrue(json.contains("\"method\":\"POST\""));
        assertTrue(json.contains("\"uri\":\"/v3/participants\""));
        assertTrue(json.contains("\"protocol\":\"HTTP/1.1\""));
        assertTrue(json.contains("\"remote_address\":\"1.2.3.4\""));
        assertTrue(json.contains("\"user_agent\":\"ifeng 6\""));
        assertTrue(json.contains("\"status\":200"));
    }
}
