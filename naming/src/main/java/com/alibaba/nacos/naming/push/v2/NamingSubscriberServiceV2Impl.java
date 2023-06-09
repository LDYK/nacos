/*
 * Copyright 1999-2020 Alibaba Group Holding Ltd.
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

package com.alibaba.nacos.naming.push.v2;

import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.SmartSubscriber;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManager;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManagerDelegate;
import com.alibaba.nacos.naming.core.v2.event.publisher.NamingEventPublisherFactory;
import com.alibaba.nacos.naming.core.v2.event.service.ServiceEvent;
import com.alibaba.nacos.naming.core.v2.index.ClientServiceIndexesManager;
import com.alibaba.nacos.naming.core.v2.index.ServiceStorage;
import com.alibaba.nacos.naming.core.v2.metadata.NamingMetadataManager;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.monitor.MetricsMonitor;
import com.alibaba.nacos.naming.pojo.Subscriber;
import com.alibaba.nacos.naming.push.NamingSubscriberService;
import com.alibaba.nacos.naming.push.v2.executor.PushExecutorDelegate;
import com.alibaba.nacos.naming.push.v2.task.PushDelayTask;
import com.alibaba.nacos.naming.push.v2.task.PushDelayTaskExecuteEngine;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Naming subscriber service for v2.x.
 *
 * @author xiweng.yy
 */

// 订阅处理服务变更事件、服务订阅事件

@org.springframework.stereotype.Service
public class NamingSubscriberServiceV2Impl extends SmartSubscriber implements NamingSubscriberService {
    
    private static final int PARALLEL_SIZE = 100;

    // 客户端管理器回顾一下前面讲到的 ClientManagerDelegate 和 EphemeralIpPortClientManager 管理所有的客户端及客户端的管理功能
    private final ClientManager clientManager;

    // ClientServiceIndexesManager管理所有的服务订阅者和发布者信息
    private final ClientServiceIndexesManager indexesManager;

    //这是Nacos推送机制 主要有udp 和 rpc长连接
    private final PushDelayTaskExecuteEngine delayTaskEngine;
    
    public NamingSubscriberServiceV2Impl(ClientManagerDelegate clientManager,
            ClientServiceIndexesManager indexesManager, ServiceStorage serviceStorage,
            NamingMetadataManager metadataManager, PushExecutorDelegate pushExecutor, SwitchDomain switchDomain) {
        this.clientManager = clientManager;
        this.indexesManager = indexesManager;
        // 延迟任务执行器，默认100ms执行一次
        this.delayTaskEngine = new PushDelayTaskExecuteEngine(clientManager, indexesManager, serviceStorage,
                metadataManager, pushExecutor, switchDomain);
        NotifyCenter.registerSubscriber(this, NamingEventPublisherFactory.getInstance());
    }
    
    @Override
    public Collection<Subscriber> getSubscribers(String namespaceId, String serviceName) {
        //从serviceName中获取groupName
        String serviceNameWithoutGroup = NamingUtils.getServiceName(serviceName);
        String groupName = NamingUtils.getGroupName(serviceName);
        //根据命名空间 组名 不在组名的服务名构建service
        Service service = Service.newService(namespaceId, groupName, serviceNameWithoutGroup);
        //找到服务的所有订阅者
        return getSubscribers(service);
    }

    @Override
    public Collection<Subscriber> getSubscribers(Service service) {
        Collection<Subscriber> result = new HashSet<>();
        //客户端id格式 ip:port#(是否是临时节点的标志true|false)
        //indexesManager.getAllClientsSubscribeService 查询订阅该服务的所有客户端集合
        for (String each : indexesManager.getAllClientsSubscribeService(service)) {
            // 循环每个客户端，取出每个客户端的订阅信息
            result.add(clientManager.getClient(each).getSubscriber(service));
        }
        return result;
    }

    // 查询指定namespaceId和服务名下，订阅该服务的订阅信息集合
    @Override
    public Collection<Subscriber> getFuzzySubscribers(String namespaceId, String serviceName) {
        Collection<Subscriber> result = new HashSet<>();
        Stream<Service> serviceStream = getServiceStream();
        String serviceNamePattern = NamingUtils.getServiceName(serviceName);
        String groupNamePattern = NamingUtils.getGroupName(serviceName);
        serviceStream.filter(service -> service.getNamespace().equals(namespaceId) && service.getName()
                .contains(serviceNamePattern) && service.getGroup().contains(groupNamePattern))
                .forEach(service -> result.addAll(getSubscribers(service)));
        return result;
    }

    //根据服务名和组名模糊查找所有匹配条件的service 列表下的订阅者
    @Override
    public Collection<Subscriber> getFuzzySubscribers(Service service) {
        return getFuzzySubscribers(service.getNamespace(), service.getGroupedServiceName());
    }

    //订阅两类事件
    //1、服务变更 和 服务订阅事件
    @Override
    public List<Class<? extends Event>> subscribeTypes() {
        List<Class<? extends Event>> result = new LinkedList<>();
        result.add(ServiceEvent.ServiceChangedEvent.class);
        result.add(ServiceEvent.ServiceSubscribedEvent.class);
        return result;
    }
    
    @Override
    public void onEvent(Event event) {
        //  ServiceChangedEvent 事件和 ServiceSubscribedEvent 事件唯一的区别是
        //  ServiceChangedEvent 通知所有的订阅者
        //  ServiceSubscribedEvent 只推送给其中一个订阅者
        if (event instanceof ServiceEvent.ServiceChangedEvent) {
            // 服务变更会推送给所有的订阅者
            ServiceEvent.ServiceChangedEvent serviceChangedEvent = (ServiceEvent.ServiceChangedEvent) event;
            Service service = serviceChangedEvent.getService();
            // 创建推送延迟任务PushDelayTask，默认设置的延迟时间500ms
            // 加入到延迟任务执行器 PushDelayTaskExecuteEngine（父类NacosDelayTaskExecuteEngine）的延迟任务队列
            // 延迟任务执行器 PushDelayTaskExecuteEngine 的执行线程是 ProcessRunnable，默认间隔100ms执行一次
            delayTaskEngine.addTask(service, new PushDelayTask(service, PushConfig.getInstance().getPushTaskDelay()));
            MetricsMonitor.incrementServiceChangeCount(service.getNamespace(), service.getGroup(), service.getName());
        } else if (event instanceof ServiceEvent.ServiceSubscribedEvent) {
            // If service is subscribed by one client, only push this client.
            // 如果服务被一个客户端订阅，则只推送该客户端。
            ServiceEvent.ServiceSubscribedEvent subscribedEvent = (ServiceEvent.ServiceSubscribedEvent) event;
            Service service = subscribedEvent.getService();
            delayTaskEngine.addTask(service, new PushDelayTask(service, PushConfig.getInstance().getPushTaskDelay(),
                    subscribedEvent.getClientId()));
        }
    }

    //如果所有的服务大于100 就用并发流否则使用单流
    //PARALLEL_SIZE  ：100
    private Stream<Service> getServiceStream() {
        Collection<Service> services = indexesManager.getSubscribedService();
        return services.size() > PARALLEL_SIZE ? services.parallelStream() : services.stream();
    }
    
    public int getPushPendingTaskCount() {
        return delayTaskEngine.size();
    }
}
