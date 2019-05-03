package org.sagebionetworks.bridge.play.interceptors;

import org.sagebionetworks.bridge.BridgeConstants;

import play.mvc.Http.Request;

public final class RequestUtils {

    private RequestUtils() {
    }

    public static String getSessionToken(final Request request) {
        return header(request, BridgeConstants.SESSION_TOKEN_HEADER, null);
    }

    public static String header(final Request request, final String name, final String defaultVal) {
        final String value = request.getHeader(name);
        return (value != null) ? value : defaultVal;
    }
}
