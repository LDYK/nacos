/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.client.naming.core;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosLoadException;
import com.alibaba.nacos.client.env.NacosClientProperties;
import com.alibaba.nacos.client.naming.event.ServerListChangedEvent;
import com.alibaba.nacos.client.naming.remote.http.NamingHttpClientManager;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.client.naming.utils.InitUtils;
import com.alibaba.nacos.client.naming.utils.NamingHttpUtil;
import com.alibaba.nacos.common.executor.NameThreadFactory;
import com.alibaba.nacos.common.http.HttpRestResult;
import com.alibaba.nacos.common.http.client.NacosRestTemplate;
import com.alibaba.nacos.common.http.param.Header;
import com.alibaba.nacos.common.http.param.Query;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.remote.client.ServerListFactory;
import com.alibaba.nacos.common.utils.IoUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import com.alibaba.nacos.common.utils.ThreadUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;
import static com.alibaba.nacos.common.constant.RequestUrlConstants.HTTP_PREFIX;

/**
 * Server list manager.
 *
 * @author xiweng.yy
 */
public class ServerListManager implements ServerListFactory, Closeable {
    
    private final NacosRestTemplate nacosRestTemplate = NamingHttpClientManager.getInstance().getNacosRestTemplate();

    //隔多久拉取一次远程服务列表
    private final long refreshServerListInternal = TimeUnit.SECONDS.toMillis(30);

    // 环境信息：dev、test、product等
    // namespace -> service -> group -> cluster -> instance
    private final String namespace;
    
    private final AtomicInteger currentIndex = new AtomicInteger();

    //配置文件中配置的serverList
    private final List<String> serverList = new ArrayList<>();

    //从远程服务为拉取的nacos server 列表
    private volatile List<String> serversFromEndpoint = new ArrayList<>();

    // 定时拉取的线程池
    private ScheduledExecutorService refreshServerListExecutor;

    //配置中维护的远程拉取serverList的地址
    private String endpoint;

    // 只配置了一个nacos Server的地址
    private String nacosDomain;

    //最近一次拉取serverList时间
    private long lastServerListRefreshTime = 0L;
    
    public ServerListManager(Properties properties) {
        this(NacosClientProperties.PROTOTYPE.derive(properties), null);
    }
    
    public ServerListManager(NacosClientProperties properties, String namespace) {
        this.namespace = namespace;
        initServerAddr(properties);
        if (!serverList.isEmpty()) {
            currentIndex.set(new Random().nextInt(serverList.size()));
        }
        if (serverList.isEmpty() && StringUtils.isEmpty(endpoint)) {
            throw new NacosLoadException("serverList is empty,please check configuration");
        }
    }

    /**
     * 1、属性配置 2、提供查询的端点地址
     * 一般小集群或者服务的ip不怎么改变的情况可以直接本地化配置，
     * 如果集群数量比较大或者ip会变化就只能用第二种方式
     * 从远程服务拉取服务列表是通过一个定时任务没隔30s更新一次
     **/
    private void initServerAddr(NacosClientProperties properties) {
        this.endpoint = InitUtils.initEndpoint(properties);
        // 如果endpoint已配置
        // endpoint: 拉取远程服务列表的ip:port
        if (StringUtils.isNotEmpty(endpoint)) {
            this.serversFromEndpoint = getServerListFromEndpoint();
            // 创建定时任务线程池，核心线程为 1
            refreshServerListExecutor = new ScheduledThreadPoolExecutor(1,
                    new NameThreadFactory("com.alibaba.nacos.client.naming.server.list.refresher"));
            // 定时任务每隔30s执行一次refreshServerListIfNeed方法
            refreshServerListExecutor
                    .scheduleWithFixedDelay(this::refreshServerListIfNeed, 0, refreshServerListInternal,
                            TimeUnit.MILLISECONDS);
        } else {
            // 从配置中获取列表
            String serverListFromProps = properties.getProperty(PropertyKeyConst.SERVER_ADDR);
            if (StringUtils.isNotEmpty(serverListFromProps)) {
                // serverList: 从本地配置"serverAddr"中解析，并赋值给serverList
                this.serverList.addAll(Arrays.asList(serverListFromProps.split(",")));
                // 如果只有一个服务端则赋值给nacosDomain
                if (this.serverList.size() == 1) {
                    this.nacosDomain = serverListFromProps;
                }
            }
        }
    }

    // 远程获取配置列表
    private List<String> getServerListFromEndpoint() {
        try {
            // endpoint配置中维护
            String urlString = HTTP_PREFIX + endpoint + "/nacos/serverlist";
            Header header = NamingHttpUtil.builderHeader();
            Query query = StringUtils.isNotBlank(namespace)
                    ? Query.newInstance().addParam("namespace", namespace)
                    : Query.EMPTY;
            HttpRestResult<String> restResult = nacosRestTemplate.get(urlString, header, query, String.class);
            if (!restResult.ok()) {
                throw new IOException(
                        "Error while requesting: " + urlString + "'. Server returned: " + restResult.getCode());
            }
            String content = restResult.getData();
            List<String> list = new ArrayList<>();
            for (String line : IoUtils.readLines(new StringReader(content))) {
                if (!line.trim().isEmpty()) {
                    list.add(line.trim());
                }
            }
            return list;
        } catch (Exception e) {
            NAMING_LOGGER.error("[SERVER-LIST] failed to update server list.", e);
        }
        return null;
    }
    
    private void refreshServerListIfNeed() {
        try {
            //如果本地已经设置了serverList 不从远程拉取serverList
            if (!CollectionUtils.isEmpty(serverList)) {
                NAMING_LOGGER.debug("server list provided by user: " + serverList);
                return;
            }
            //间隔时间未到
            if (System.currentTimeMillis() - lastServerListRefreshTime < refreshServerListInternal) {
                return;
            }
            // 从endpoint属性提供的地址拉取serverList
            List<String> list = getServerListFromEndpoint();
            if (CollectionUtils.isEmpty(list)) {
                throw new Exception("Can not acquire Nacos list");
            }
            // 如果之前拉取的服务列表跟本次拉取不同，则更新
            if (null == serversFromEndpoint || !CollectionUtils.isEqualCollection(list, serversFromEndpoint)) {
                NAMING_LOGGER.info("[SERVER-LIST] server list is updated: " + list);
                serversFromEndpoint = list;
                // 更新服务列表获取时间
                lastServerListRefreshTime = System.currentTimeMillis();
                // 发布服务列表变更事件
                NotifyCenter.publishEvent(new ServerListChangedEvent());
            }
        } catch (Throwable e) {
            NAMING_LOGGER.warn("failed to update server list", e);
        }
    }
    
    public boolean isDomain() {
        return StringUtils.isNotBlank(nacosDomain);
    }
    
    public String getNacosDomain() {
        return nacosDomain;
    }
    
    @Override
    public List<String> getServerList() {
        return serverList.isEmpty() ? serversFromEndpoint : serverList;
    }
    
    @Override
    public String genNextServer() {
        int index = currentIndex.incrementAndGet() % getServerList().size();
        return getServerList().get(index);
    }
    
    @Override
    public String getCurrentServer() {
        return getServerList().get(currentIndex.get() % getServerList().size());
    }
    
    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        if (null != refreshServerListExecutor) {
            ThreadUtils.shutdownThreadPool(refreshServerListExecutor, NAMING_LOGGER);
        }
        NamingHttpClientManager.getInstance().shutdown();
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }
}
