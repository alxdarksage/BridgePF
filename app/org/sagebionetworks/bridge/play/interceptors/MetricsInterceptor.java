package org.sagebionetworks.bridge.play.interceptors;

import static org.apache.http.HttpHeaders.USER_AGENT;
import static org.sagebionetworks.bridge.BridgeConstants.X_FORWARDED_FOR_HEADER;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.models.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Result;

@Component("metricsInterceptor")
public class MetricsInterceptor implements MethodInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(MetricsInterceptor.class);

    @Override
    public Object invoke(MethodInvocation method) throws Throwable {
        RequestContext context = BridgeUtils.getRequestContext();
        if (context == null || context == RequestContext.NULL_INSTANCE) {
            throw new IllegalStateException("The interceptors are in the wrong order (no requestId for metrics)");
        }
        final Request request = Http.Context.current().request();
        Metrics metrics = context.getMetrics(); 
        metrics.setMethod(request.method());
        metrics.setUri(request.path());
        metrics.setProtocol(request.version());
        metrics.setRemoteAddress(RequestUtils.header(request, X_FORWARDED_FOR_HEADER, request.remoteAddress()));
        metrics.setUserAgent(RequestUtils.header(request, USER_AGENT, null));

        try {
            final Result result = (Result)method.proceed();
            metrics.setStatus(result.status());
            return result;
        } finally {
            metrics.end();
            logger.info(metrics.toJsonString());
        }
    }

}
