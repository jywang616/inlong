/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sdk.sort.impl;

import com.google.gson.Gson;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.inlong.sdk.sort.api.ClientContext;
import org.apache.inlong.sdk.sort.api.QueryConsumeConfig;
import org.apache.inlong.sdk.sort.entity.CacheZone;
import org.apache.inlong.sdk.sort.entity.CacheZoneCluster;
import org.apache.inlong.sdk.sort.entity.CacheZoneConfig;
import org.apache.inlong.sdk.sort.entity.ConsumeConfig;
import org.apache.inlong.sdk.sort.entity.InLongTopic;
import org.apache.inlong.sdk.sort.entity.ManagerResponse;
import org.apache.inlong.sdk.sort.entity.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryConsumeConfigImpl implements QueryConsumeConfig {

    private static final int NOUPDATE_VALUE = 1;
    private static final int UPDATE_VALUE = 0;
    private static final int REQ_PARAMS_ERROR = -101;
    private final Logger logger = LoggerFactory.getLogger(QueryConsumeConfigImpl.class);
    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    private final ClientContext clientContext;
    private String md5 = "";

    private Map<String, List<InLongTopic>> subscribedTopic = new HashMap<>();

    public QueryConsumeConfigImpl(ClientContext clientContext) {
        this.clientContext = clientContext;
    }

    private String getRequestUrlWithParam() {
        return clientContext.getConfig().getManagerApiUrl() + "?sortClusterName=" + clientContext.getConfig()
                .getSortClusterName() + "&sortTaskId=" + clientContext.getConfig().getSortTaskId() + "&md5=" + md5
                + "&apiVersion=" + clientContext.getConfig().getManagerApiVersion();
    }

    // HTTP GET
    private ManagerResponse doGetRequest() throws Exception {
        ManagerResponse managerResponse;
        HttpGet request = getHttpGet();

        try (CloseableHttpResponse response = httpClient.execute(request)) {

            logger.debug("response status:{}", response.getStatusLine().toString());

            HttpEntity entity = response.getEntity();
            Header headers = entity.getContentType();
            logger.debug("response headers:{}", headers);

            String result = EntityUtils.toString(entity);
            logger.debug("response String result:{}", result);
            try {
                managerResponse = new Gson().fromJson(result, ManagerResponse.class);
                return managerResponse;
            } catch (Exception e) {
                logger.error("parse json to ManagerResponse error:{}", e.getMessage(), e);
                e.printStackTrace();
            }

        }
        return null;
    }

    private HttpGet getHttpGet() {
        HttpGet request = new HttpGet(getRequestUrlWithParam());
        // add request headers
        request.addHeader("custom-key", "inlong-readapi");
        request.addHeader(HttpHeaders.USER_AGENT, "Googlebot");
        return request;
    }

    /**
     * get new sortTask conf from inlong manager
     */
    public void reload() {
        logger.debug("start to reload sort task config.");
        try {
            ManagerResponse managerResponse = doGetRequest();
            if (managerResponse == null) {
                logger.info("## reload managerResponse == null");
                return;
            }
            if (handleSortTaskConfResult(managerResponse, managerResponse.getErrCode())) {
                return;
            }
        } catch (Throwable e) {
            String msg = MessageFormat
                    .format("Fail to reload atta configuration in {0} error:{1}.", getRequestUrlWithParam(),
                            e.getMessage());
            logger.error(msg, e);
        }
        logger.debug("end to reload manager config.");
    }

    /**
     * handle request response
     *
     * UPDATE_VALUE = 0; conf update
     * NOUPDATE_VALUE = 1; conf no update, md5 is same
     * REQ_PARAMS_ERROR = -101; request params error
     * FAIL = -1; common error
     *
     * @param response ManagerResponse
     * @param respCodeValue int
     * @return true/false
     */
    private boolean handleSortTaskConfResult(ManagerResponse response, int respCodeValue) throws Exception {
        switch (respCodeValue) {
            case NOUPDATE_VALUE:
                logger.debug("manager conf noupdate");
                return true;
            case UPDATE_VALUE:
                logger.info("manager conf update");
                clientContext.getStatManager().getStatistics(clientContext.getConfig().getSortTaskId())
                        .addManagerConfChangedTimes(1);
                this.md5 = response.getMd5();
                updateSortTaskConf(response);
                break;
            case REQ_PARAMS_ERROR:
                logger.error("return code error:{}", respCodeValue);
                clientContext.getStatManager().getStatistics(clientContext.getConfig().getSortTaskId())
                        .addRequestManagerParamErrorTimes(1);
                break;
            default:
                logger.error("return code error:{}", respCodeValue);
                clientContext.getStatManager().getStatistics(clientContext.getConfig().getSortTaskId())
                        .addRequestManagerCommonErrorTimes(1);
                return true;
        }
        return false;
    }

    private void updateSortTaskConf(ManagerResponse response) {
        CacheZoneConfig cacheZoneConfig = response.getData();
        Map<String, List<InLongTopic>> newGroupTopicsMap = new HashMap<>();
        for (Map.Entry<String, CacheZone> entry : cacheZoneConfig.getCacheZones().entrySet()) {
            String sortId = entry.getKey();
            CacheZone cacheZone = entry.getValue();

            List<InLongTopic> topics = newGroupTopicsMap.computeIfAbsent(sortId, k -> new ArrayList<>());

            CacheZoneCluster cacheZoneCluster = new CacheZoneCluster(cacheZone.getZoneName(),
                    cacheZone.getServiceUrl(), cacheZone.getAuthentication());
            for (Topic topicInfo : cacheZone.getTopics()) {
                InLongTopic topic = new InLongTopic();
                topic.setInLongCluster(cacheZoneCluster);
                topic.setTopic(topicInfo.getTopic());
                topic.setTopicType(cacheZone.getZoneType());
                topics.add(topic);
            }
        }

        this.subscribedTopic = newGroupTopicsMap;
    }

    /**
     * query ConsumeConfig
     *
     * @param sortTaskId String
     * @return ConsumeConfig
     */
    @Override
    public ConsumeConfig queryCurrentConsumeConfig(String sortTaskId) {
        reload();
        return new ConsumeConfig(subscribedTopic.get(sortTaskId));
    }
}
