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

package com.alibaba.nacos.naming.core.v2.client;

import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.naming.core.v2.event.client.ClientEvent;
import com.alibaba.nacos.naming.core.v2.pojo.BatchInstanceData;
import com.alibaba.nacos.naming.core.v2.pojo.BatchInstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.monitor.MetricsMonitor;
import com.alibaba.nacos.naming.pojo.Subscriber;
import com.alibaba.nacos.naming.utils.DistroUtils;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.alibaba.nacos.naming.constants.ClientConstants.REVISION;

/**
 * Abstract implementation of {@code Client}.
 *
 * @author xiweng.yy
 */
public abstract class AbstractClient implements Client {

    // 存储客户端服务及对应的实例注册信息
    protected final ConcurrentHashMap<Service, InstancePublishInfo> publishers = new ConcurrentHashMap<>(16, 0.75f, 1);

    // 存储客户端服务及对应的订阅信息
    protected final ConcurrentHashMap<Service, Subscriber> subscribers = new ConcurrentHashMap<>(16, 0.75f, 1);
    
    protected volatile long lastUpdatedTime;
    
    protected final AtomicLong revision;
    
    protected ClientAttributes attributes;
    
    public AbstractClient(Long revision) {
        lastUpdatedTime = System.currentTimeMillis();
        this.revision = new AtomicLong(revision == null ? 0 : revision);
    }
    
    @Override
    public void setLastUpdatedTime() {
        this.lastUpdatedTime = System.currentTimeMillis();
    }
    
    @Override
    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    // 将服务添加到集合publishers，首次添加时，在指标数据中进行实例数+1操作
    @Override
    public boolean addServiceInstance(Service service, InstancePublishInfo instancePublishInfo) {
        //  客户端务发布服务实例信息
        if (null == publishers.put(service, instancePublishInfo)) {
            if (instancePublishInfo instanceof BatchInstancePublishInfo) {
                MetricsMonitor.incrementIpCountWithBatchRegister(instancePublishInfo);
            } else {
                MetricsMonitor.incrementInstanceCount();
            }
        }
        //统一事件中心：客户端变化事件
        NotifyCenter.publishEvent(new ClientEvent.ClientChangedEvent(this));
        Loggers.SRV_LOG.info("Client change for service {}, {}", service, getClientId());
        return true;
    }

    // 将服务从集合publishers删除，并在指标数据中进行实例数-1操作
    @Override
    public InstancePublishInfo removeServiceInstance(Service service) {
        InstancePublishInfo result = publishers.remove(service);
        if (null != result) {
            if (result instanceof BatchInstancePublishInfo) {
                // 批量移除，注册实例减掉 -1 * list.size()
                MetricsMonitor.decrementIpCountWithBatchRegister(result);
            } else {
                // 注册客户端数量-1
                MetricsMonitor.decrementInstanceCount();
            }
            NotifyCenter.publishEvent(new ClientEvent.ClientChangedEvent(this));
        }
        Loggers.SRV_LOG.info("Client remove for service {}, {}", service, getClientId());
        return result;
    }
    
    @Override
    public InstancePublishInfo getInstancePublishInfo(Service service) {
        return publishers.get(service);
    }
    
    @Override
    public Collection<Service> getAllPublishedService() {
        return publishers.keySet();
    }

    //该客户端的subscribers订阅者记录里面加一个记录
    @Override
    public boolean addServiceSubscriber(Service service, Subscriber subscriber) {
        if (null == subscribers.put(service, subscriber)) {
            MetricsMonitor.incrementSubscribeCount();
        }
        return true;
    }
    
    @Override
    public boolean removeServiceSubscriber(Service service) {
        if (null != subscribers.remove(service)) {
            MetricsMonitor.decrementSubscribeCount();
        }
        return true;
    }
    
    @Override
    public Subscriber getSubscriber(Service service) {
        return subscribers.get(service);
    }
    
    @Override
    public Collection<Service> getAllSubscribeService() {
        return subscribers.keySet();
    }

    // 同步数据的生成只要是为了Nacos节点之间数据一致性目的
    // 生成的数据主要包含了所有客户端服务的注册信息及服务所拥有的namespace、group、instance等
    @Override
    public ClientSyncData generateSyncData() {
        List<String> namespaces = new LinkedList<>();
        List<String> groupNames = new LinkedList<>();
        List<String> serviceNames = new LinkedList<>();
    
        List<String> batchNamespaces = new LinkedList<>();
        List<String> batchGroupNames = new LinkedList<>();
        List<String> batchServiceNames = new LinkedList<>();
        
        List<InstancePublishInfo> instances = new LinkedList<>();
        List<BatchInstancePublishInfo> batchInstancePublishInfos = new LinkedList<>();
        BatchInstanceData  batchInstanceData = new BatchInstanceData();
        for (Map.Entry<Service, InstancePublishInfo> entry : publishers.entrySet()) {
            InstancePublishInfo instancePublishInfo = entry.getValue();
            if (instancePublishInfo instanceof BatchInstancePublishInfo) {
                BatchInstancePublishInfo batchInstance = (BatchInstancePublishInfo) instancePublishInfo;
                batchInstancePublishInfos.add(batchInstance);
                buildBatchInstanceData(batchInstanceData, batchNamespaces, batchGroupNames, batchServiceNames, entry);
                batchInstanceData.setBatchInstancePublishInfos(batchInstancePublishInfos);
            } else {
                namespaces.add(entry.getKey().getNamespace());
                groupNames.add(entry.getKey().getGroup());
                serviceNames.add(entry.getKey().getName());
                instances.add(entry.getValue());
            }
        }
        ClientSyncData data = new ClientSyncData(getClientId(), namespaces, groupNames, serviceNames, instances, batchInstanceData);
        data.getAttributes().addClientAttribute(REVISION, getRevision());
        return data;
    }
    
    private static BatchInstanceData buildBatchInstanceData(BatchInstanceData  batchInstanceData, List<String> batchNamespaces,
            List<String> batchGroupNames, List<String> batchServiceNames, Map.Entry<Service, InstancePublishInfo> entry) {
        batchNamespaces.add(entry.getKey().getNamespace());
        batchGroupNames.add(entry.getKey().getGroup());
        batchServiceNames.add(entry.getKey().getName());
        
        batchInstanceData.setNamespaces(batchNamespaces);
        batchInstanceData.setGroupNames(batchGroupNames);
        batchInstanceData.setServiceNames(batchServiceNames);
        return batchInstanceData;
    }
    
    @Override
    public void release() {
        Collection<InstancePublishInfo> instancePublishInfos = publishers.values();
        for (InstancePublishInfo instancePublishInfo : instancePublishInfos) {
            if (instancePublishInfo instanceof BatchInstancePublishInfo) {
                MetricsMonitor.decrementIpCountWithBatchRegister(instancePublishInfo);
            } else {
                MetricsMonitor.getIpCountMonitor().decrementAndGet();
            }
        }
        MetricsMonitor.getSubscriberCount().addAndGet(-1 * subscribers.size());
    }
    
    @Override
    public long recalculateRevision() {
        int hash = DistroUtils.hash(this);
        revision.set(hash);
        return hash;
    }
    
    @Override
    public long getRevision() {
        return revision.get();
    }
    
    @Override
    public void setRevision(long revision) {
        this.revision.set(revision);
    }
    
    /**
     * get client attributes.
     */
    public ClientAttributes getClientAttributes() {
        return attributes;
    }
    
    public void setAttributes(ClientAttributes attributes) {
        this.attributes = attributes;
    }
}
