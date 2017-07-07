/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud.autoscaling;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringJoiner;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple HTTP callback that POSTs event data to a URL.
 * <p>URL, payload and headers may contain property substitution patterns, with the following properties available:
 * <ul>
 *   <li>config.* - listener configuration</li>
 *   <li>event.* - event properties</li>
 *   <li>stage - current stage of event processing</li>
 *   <li>actionName - optional current action name</li>
 *   <li>context.* - optional {@link ActionContext} properties</li>
 *   <li>error - optional error string (from {@link Throwable#toString()})</li>
 *   <li>message - optional message</li>
 * </ul>
 * </p>
 * The following listener configuration is supported:
 * <ul>
 *   <li>url - a URL template</li>
 *   <li>payload - optional payload template. If absent a JSON map of all properties listed above will be used.</li>
 *   <li>contentType - optional payload content type. If absent then <code>application/json</code> will be used.</li>
 *   <li>header.* - optional header template(s). The name of the property without "header." prefix defines the literal header name.</li>
 * </ul>
 */
public class HttpTriggerListener extends TriggerListenerBase {
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private HttpClient httpClient;
  private String urlTemplate;
  private String payloadTemplate;
  private String contentType;
  private Map<String, String> headerTemplates = new HashMap<>();

  @Override
  public void init(CoreContainer coreContainer, AutoScalingConfig.TriggerListenerConfig config) {
    super.init(coreContainer, config);
    httpClient = coreContainer.getUpdateShardHandler().getHttpClient();
    urlTemplate = (String)config.properties.get("url");
    payloadTemplate = (String)config.properties.get("payload");
    contentType = (String)config.properties.get("contentType");
    config.properties.forEach((k, v) -> {
      if (k.startsWith("header.")) {
        headerTemplates.put(k.substring(7), String.valueOf(v));
      }
    });
  }

  @Override
  public void onEvent(TriggerEvent event, AutoScaling.EventProcessorStage stage, String actionName, ActionContext context, Throwable error, String message) {
    Properties properties = new Properties();
    properties.setProperty("stage", stage.toString());
    // if configuration used "actionName" but we're in a non-action related stage then PropertiesUtil will
    // throws an exception on missing value - so replace it with an empty string
    if (actionName == null) {
      actionName = "";
    }
    properties.setProperty("actionName", actionName);
    if (context != null) {
      context.getProperties().forEach((k, v) -> {
        properties.setProperty("context." + k, String.valueOf(v));
      });
    }
    if (error != null) {
      properties.setProperty("error", error.toString());
    } else {
      properties.setProperty("error", "");
    }
    if (message != null) {
      properties.setProperty("message", message);
    } else {
      properties.setProperty("message", "");
    }
    // add event properties
    properties.setProperty("event.id", event.getId());
    properties.setProperty("event.source", event.getSource());
    properties.setProperty("event.eventTime", String.valueOf(event.eventTime));
    properties.setProperty("event.eventType", event.getEventType().toString());
    event.getProperties().forEach((k, v) -> {
      properties.setProperty("event.properties." + k, String.valueOf(v));
    });
    // add config properties
    properties.setProperty("config.name", config.name);
    properties.setProperty("config.trigger", config.trigger);
    properties.setProperty("config.listenerClass", config.listenerClass);
    properties.setProperty("config.beforeActions", String.join(",", config.beforeActions));
    properties.setProperty("config.afterActions", String.join(",", config.afterActions));
    StringJoiner joiner = new StringJoiner(",");
    config.stages.forEach(s -> joiner.add(s.toString()));
    properties.setProperty("config.stages", joiner.toString());
    config.properties.forEach((k, v) -> {
      properties.setProperty("config.properties." + k, String.valueOf(v));
    });
    String url = PropertiesUtil.substituteProperty(urlTemplate, properties);
    String payload;
    String type;
    if (payloadTemplate != null) {
      payload = PropertiesUtil.substituteProperty(payloadTemplate, properties);
      if (contentType != null) {
        type = contentType;
      } else {
        type = "application/json";
      }
    } else {
      payload = Utils.toJSONString(properties);
      type = "application/json";
    }
    HttpPost post = new HttpPost(url);
    HttpEntity entity = new StringEntity(payload, "UTF-8");
    headerTemplates.forEach((k, v) -> {
      String headerVal = PropertiesUtil.substituteProperty(v, properties);
      if (!headerVal.isEmpty()) {
        post.addHeader(k, headerVal);
      }
    });
    post.setEntity(entity);
    post.setHeader("Content-Type", type);
    org.apache.http.client.config.RequestConfig.Builder requestConfigBuilder = HttpClientUtil.createDefaultRequestConfigBuilder();
//    if (soTimeout != null) {
//      requestConfigBuilder.setSocketTimeout(soTimeout);
//    }
//    if (connectionTimeout != null) {
//      requestConfigBuilder.setConnectTimeout(connectionTimeout);
//    }
//    if (followRedirects != null) {
//      requestConfigBuilder.setRedirectsEnabled(followRedirects);
//    }

    post.setConfig(requestConfigBuilder.build());
    try {
      HttpClientContext httpClientRequestContext = HttpClientUtil.createNewHttpClientRequestContext();
      HttpResponse rsp = httpClient.execute(post, httpClientRequestContext);
      int statusCode = rsp.getStatusLine().getStatusCode();
      if (statusCode != 200) {
        LOG.warn("Error sending request for event " + event + ", HTTP response: " + rsp.toString());
      }
      HttpEntity responseEntity = rsp.getEntity();
      Utils.consumeFully(responseEntity);
    } catch (IOException e) {
      LOG.warn("Exception sending request for event " + event, e);
    }
  }
}
