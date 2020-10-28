/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.server;

import com.intuit.karate.FileUtils;
import com.intuit.karate.StringUtils;
import com.intuit.karate.graal.JsArray;
import com.intuit.karate.graal.JsList;
import com.intuit.karate.graal.JsValue;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pthomas3
 */
public class Response implements ProxyObject {

    private static final Logger logger = LoggerFactory.getLogger(Response.class);

    private static final String BODY = "body";
    private static final String STATUS = "status";
    private static final String HEADER = "header";
    private static final String HEADERS = "headers";
    private static final String HEADER_ENTRIES = "headerEntries";
    private static final String DATA_TYPE = "dataType";

    private static final String[] KEYS = new String[]{STATUS, HEADER, HEADERS, BODY, DATA_TYPE, HEADER_ENTRIES};
    private static final Set<String> KEY_SET = new HashSet(Arrays.asList(KEYS));
    private static final JsArray KEY_ARRAY = new JsArray(KEYS);

    private int status;
    private Map<String, List<String>> headers;
    private byte[] body;

    private ResourceType resourceType;
    private int delay;

    public Response(int status) {
        this.status = status;
    }

    public Response(int status, Map<String, List<String>> headers, byte[] body) {
        this(status, headers, body, null);
    }

    public Response(int status, Map<String, List<String>> headers, byte[] body, ResourceType resourceType) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.resourceType = resourceType;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public Map<String, Map> getCookies() {
        List<String> values = getHeaderValues(HttpConstants.HDR_SET_COOKIE);
        if (values == null) {
            return null;
        }
        Map<String, Map> map = new HashMap();
        for (String value : values) {
            Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(value);
            for (Cookie cookie : cookies) {
                map.put(cookie.name(), Cookies.toMap(cookie));
            }
        }
        return map;
    }

    public void setHeaders(Map<String, List<String>> headers) {
        this.headers = headers;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public String getBodyAsString() {
        return body == null ? null : FileUtils.toString(body);
    }

    public Object getBodyConverted() {
        ResourceType rt = getResourceType(); // derive if needed
        if (rt != null && rt.isBinary()) {
            return body;
        }
        try {
            return JsValue.fromBytes(body);
        } catch (Exception e) {
            logger.trace("failed to auto-convert response: {}", e);
            return getBodyAsString();
        }
    }

    public ResourceType getResourceType() {
        if (resourceType == null) {
            String contentType = getContentType();
            if (contentType != null) {
                resourceType = ResourceType.fromContentType(contentType);
            }
        }
        return resourceType;
    }

    public void setResourceType(ResourceType resourceType) {
        this.resourceType = resourceType;
    }

    public List<String> getHeaderValues(String name) { // TOTO optimize
        return StringUtils.getIgnoreKeyCase(headers, name);
    }

    public String getHeader(String name) {
        List<String> values = getHeaderValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public String getContentType() {
        return getHeader(HttpConstants.HDR_CONTENT_TYPE);
    }

    public void setHeader(String name, List<String> values) {
        if (headers == null) {
            headers = new HashMap();
        }
        headers.put(name, values);
    }

    public void setHeader(String name, String... values) {
        setHeader(name, Arrays.asList(values));
    }

    private static String toString(Object o) {
        return o == null ? null : o.toString();
    }

    private final VarArgsFunction HEADER_FUNCTION = args -> {
        if (args.length == 1) {
            return getHeader(toString(args[0]));
        } else {
            setHeader(toString(args[0]), toString(args[1]));
            return Response.this;
        }
    };

    private static final String KEY = "key";
    private static final String VALUE = "value";

    private final Supplier HEADER_ENTRIES_FUNCTION = () -> {
        if (headers == null) {
            return JsList.EMPTY;
        }
        List list = new ArrayList(headers.size());
        headers.forEach((k, v) -> {
            if (v == null || v.isEmpty()) {
                // continue
            } else {
                Map map = new HashMap(2);
                map.put(KEY, k);
                map.put(VALUE, v.get(0));
                list.add(map);
            }
        });
        return JsValue.fromJava(list);
    };

    @Override
    public Object getMember(String key) {
        switch (key) {
            case STATUS:
                return status;
            case HEADER:
                return HEADER_FUNCTION;
            case HEADERS:
                return JsValue.fromJava(headers);
            case BODY:
                return getBodyConverted();
            case DATA_TYPE:
                ResourceType rt = getResourceType();
                if (rt == null || rt == ResourceType.NONE) {
                    return null;
                }
                return rt.name().toLowerCase();
            case HEADER_ENTRIES:
                return HEADER_ENTRIES_FUNCTION;
            default:
                logger.warn("no such property on response object: {}", key);
                return null;
        }
    }

    @Override
    public Object getMemberKeys() {
        return KEY_ARRAY;
    }

    @Override
    public boolean hasMember(String key) {
        return KEY_SET.contains(key);
    }

    @Override
    public void putMember(String key, Value value) {
        switch (key) {
            case BODY:
                body = JsValue.toBytes(value);
                break;
            case STATUS:
                status = value.asInt();
                break;
            case HEADERS:
                headers = (Map) JsValue.toJava(value);
                break;
            default:
                logger.warn("put not supported on response object: {} - {}", key, value);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[status: ").append(status);
        if (resourceType != null && resourceType != ResourceType.NONE) {
            sb.append(", type: ").append(resourceType);
        }
        if (body != null) {
            sb.append(", length: ").append(body.length);
        }
        if (headers != null) {
            sb.append(", headers: ").append(headers);
        }
        sb.append(']');
        return sb.toString();
    }

}