/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.providers.apache;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseHeaders;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;

import java.net.URI;

/**
 * A class that represent the HTTP headers.
 */
public class ApacheResponseHeaders extends HttpResponseHeaders {

    private final HttpMethodBase method;
    private final FluentCaseInsensitiveStringsMap headers;

    public ApacheResponseHeaders(URI uri, HttpMethodBase method, AsyncHttpProvider<HttpClient> provider) {
        super(uri, provider, false);
        this.method = method;
        headers = computerHeaders();
    }

    private FluentCaseInsensitiveStringsMap computerHeaders() {
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();

        Header[] uh = method.getResponseHeaders();

        for (Header e : uh) {
            if (e.getName() != null) {
                h.add(e.getName(), e.getValue());
            }
        }

        uh = method.getResponseFooters();
        for (Header e : uh) {
            if (e.getName() != null) {
                h.add(e.getName(), e.getValue());
            }
        }

        return h;
    }

    /**
     * Return the HTTP header
     *
     * @return an {@link com.ning.http.client.FluentCaseInsensitiveStringsMap}
     */
    @Override
    public FluentCaseInsensitiveStringsMap getHeaders() {
        return headers;
    }
}