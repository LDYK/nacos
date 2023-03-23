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

package com.alibaba.nacos.naming.core.v2.metadata;

import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.SmartSubscriber;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.naming.core.v2.ServiceManager;
import com.alibaba.nacos.naming.core.v2.event.client.ClientEvent;
import com.alibaba.nacos.naming.core.v2.event.metadata.MetadataEvent;
import com.alibaba.nacos.naming.core.v2.event.publisher.NamingEventPublisherFactory;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Nacos naming metadata manager.
 *
 * @author xiweng.yy
 */
// 在服务端，这些元数据的管理，是交给单例类NamingMetadataManager管理。
// 客户端与服务端失联之后，就会通过NamingMetadataManager订阅处理，将服务元数据信息加入到过期元数据信息集合中，这些过期元数据信息的清理就交给ExpiredMetadataCleaner处理。
// 该类也是一个订阅者实例，继承了抽象类SmartSubscriber，表示可以订阅多个事件:
//   1）MetadataEvent.InstanceMetadataEvent
//   2）MetadataEvent.ServiceMetadataEvent
//   3）ClientEvent.ClientDisconnectEvent
// NamingMetadataManager的主要作用就是负责查询、维护服务的服务元数据信息、实例元数据信息和过期元数据信息，同时作为订阅者，对上面3个事件进行事件的处理
// NamingMetadataOperateService 用于Node节点之间元数据信息一致性的处理
@Component
public class NamingMetadataManager extends SmartSubscriber {
    
    private final Set<ExpiredMetadataInfo> expiredMetadataInfos;
    
    private ConcurrentMap<Service, ServiceMetadata> serviceMetadataMap;
    
    private ConcurrentMap<Service, ConcurrentMap<String, InstanceMetadata>> instanceMetadataMap;
    
    private static final int INITIAL_CAPACITY = 1;
    
    public NamingMetadataManager() {
        serviceMetadataMap = new ConcurrentHashMap<>(1 << 10);
        instanceMetadataMap = new ConcurrentHashMap<>(1 << 10);
        expiredMetadataInfos = new ConcurrentHashSet<>();
        NotifyCenter.registerSubscriber(this, NamingEventPublisherFactory.getInstance());
    }
    
    /**
     * Whether contain service metadata for {@link Service}.
     *
     * @param service service
     * @return true if contain service metadata, otherwise false
     */
    public boolean containServiceMetadata(Service service) {
        return serviceMetadataMap.containsKey(service);
    }
    
    /**
     * Whether instance metadata for instance of {@link Service}.
     *
     * @param service    service
     * @param metadataId instance metadata id
     * @return true if contain instance metadata, otherwise false
     */
    public boolean containInstanceMetadata(Service service, String metadataId) {
        return instanceMetadataMap.containsKey(service) && instanceMetadataMap.get(service).containsKey(metadataId);
    }
    
    /**
     * Get service metadata for {@link Service}, which is the original metadata object.
     *
     * <p>This method should use only query, can't modify metadata.
     *
     * @param service service
     * @return service metadata
     */
    public Optional<ServiceMetadata> getServiceMetadata(Service service) {
        return Optional.ofNullable(serviceMetadataMap.get(service));
    }
    
    /**
     * Get instance metadata for instance of {@link Service}, which is the original metadata object.
     *
     * <p>This method should use only query, can't modify metadata.
     *
     * @param service    service
     * @param metadataId instance metadata id
     * @return instance metadata
     */
    public Optional<InstanceMetadata> getInstanceMetadata(Service service, String metadataId) {
        ConcurrentMap<String, InstanceMetadata> instanceMetadataMapForService = instanceMetadataMap.get(service);
        if (null == instanceMetadataMapForService) {
            return Optional.empty();
        }
        return Optional.ofNullable(instanceMetadataMapForService.get(metadataId));
    }
    
    /**
     * Update service metadata.
     *
     * @param service         service
     * @param serviceMetadata new service metadata
     */
    public void updateServiceMetadata(Service service, ServiceMetadata serviceMetadata) {
        service.incrementRevision();
        serviceMetadataMap.put(service, serviceMetadata);
    }
    
    /**
     * Update instance metadata.
     *
     * @param service          service
     * @param metadataId       instance metadata id
     * @param instanceMetadata new instance metadata
     */
    public void updateInstanceMetadata(Service service, String metadataId, InstanceMetadata instanceMetadata) {
        if (!instanceMetadataMap.containsKey(service)) {
            instanceMetadataMap.putIfAbsent(service, new ConcurrentHashMap<>(INITIAL_CAPACITY));
        }
        instanceMetadataMap.get(service).put(metadataId, instanceMetadata);
    }
    
    /**
     * Remove service metadata.
     *
     * @param service service
     */
    public void removeServiceMetadata(Service service) {
        serviceMetadataMap.remove(service);
        expiredMetadataInfos.remove(ExpiredMetadataInfo.newExpiredServiceMetadata(service));
    }
    
    /**
     * Remove instance metadata.
     *
     * @param service    service
     * @param metadataId instance metadata id
     */
    public void removeInstanceMetadata(Service service, String metadataId) {
        ConcurrentMap<String, InstanceMetadata> instanceMetadataMapForService = instanceMetadataMap.get(service);
        instanceMetadataMapForService.remove(metadataId);
        if (instanceMetadataMapForService.isEmpty()) {
            serviceMetadataMap.remove(service);
        }
        expiredMetadataInfos.remove(ExpiredMetadataInfo.newExpiredInstanceMetadata(service, metadataId));
    }
    
    /**
     * Get service metadata snapshot.
     *
     * @return service metadata snapshot
     */
    public Map<Service, ServiceMetadata> getServiceMetadataSnapshot() {
        ConcurrentMap<Service, ServiceMetadata> result = new ConcurrentHashMap<>(serviceMetadataMap.size());
        result.putAll(serviceMetadataMap);
        return result;
    }
    
    /**
     * Get instance metadata snapshot.
     *
     * @return service metadata snapshot
     */
    public Map<Service, ConcurrentMap<String, InstanceMetadata>> getInstanceMetadataSnapshot() {
        ConcurrentMap<Service, ConcurrentMap<String, InstanceMetadata>> result = new ConcurrentHashMap<>(
                instanceMetadataMap.size());
        result.putAll(instanceMetadataMap);
        return result;
    }
    
    /**
     * Load service metadata snapshot.
     *
     * <p>Service metadata need load back the service.
     *
     * @param snapshot snapshot
     */
    public void loadServiceMetadataSnapshot(ConcurrentMap<Service, ServiceMetadata> snapshot) {
        for (Service each : snapshot.keySet()) {
            ServiceManager.getInstance().getSingleton(each);
        }
        ConcurrentMap<Service, ServiceMetadata> oldSnapshot = serviceMetadataMap;
        serviceMetadataMap = snapshot;
        oldSnapshot.clear();
    }
    
    /**
     * Load instance metadata snapshot.
     *
     * @param snapshot snapshot
     */
    public void loadInstanceMetadataSnapshot(ConcurrentMap<Service, ConcurrentMap<String, InstanceMetadata>> snapshot) {
        ConcurrentMap<Service, ConcurrentMap<String, InstanceMetadata>> oldSnapshot = instanceMetadataMap;
        instanceMetadataMap = snapshot;
        oldSnapshot.clear();
    }
    
    public Set<ExpiredMetadataInfo> getExpiredMetadataInfos() {
        return expiredMetadataInfos;
    }
    
    @Override
    public List<Class<? extends Event>> subscribeTypes() {
        List<Class<? extends Event>> result = new LinkedList<>();
        result.add(MetadataEvent.InstanceMetadataEvent.class);
        result.add(MetadataEvent.ServiceMetadataEvent.class);
        result.add(ClientEvent.ClientDisconnectEvent.class);
        return result;
    }
    
    @Override
    public void onEvent(Event event) {
        if (event instanceof MetadataEvent.InstanceMetadataEvent) {
            handleInstanceMetadataEvent((MetadataEvent.InstanceMetadataEvent) event);
        } else if (event instanceof MetadataEvent.ServiceMetadataEvent) {
            handleServiceMetadataEvent((MetadataEvent.ServiceMetadataEvent) event);
        } else {
            handleClientDisconnectEvent((ClientEvent.ClientDisconnectEvent) event);
        }
    }

    // 客户端与服务端失联，将客户端对应的实例加入到过期元数据（ExpiredMetadataInfo）集合，等待删除
    private void handleClientDisconnectEvent(ClientEvent.ClientDisconnectEvent event) {
        for (Service each : event.getClient().getAllPublishedService()) {
            String metadataId = event.getClient().getInstancePublishInfo(each).getMetadataId();
            if (containInstanceMetadata(each, metadataId)) {
                updateExpiredInfo(true, ExpiredMetadataInfo.newExpiredInstanceMetadata(each, metadataId));
            }
        }
    }
    
    private void handleServiceMetadataEvent(MetadataEvent.ServiceMetadataEvent event) {
        Service service = event.getService();
        if (containServiceMetadata(service)) {
            updateExpiredInfo(event.isExpired(), ExpiredMetadataInfo.newExpiredServiceMetadata(service));
        }
    }

    // 接收到服务元数据信息时间后
    // 如果是expired放入过期元数据集合等待删除
    // 如果是expired为false，表示有的服务重新注册，从过期元数据中删除
    private void handleInstanceMetadataEvent(MetadataEvent.InstanceMetadataEvent event) {
        Service service = event.getService();
        String metadataId = event.getMetadataId();
        if (containInstanceMetadata(service, metadataId)) {
            // 如果是Expired放入过期元数据
            updateExpiredInfo(event.isExpired(),
                    ExpiredMetadataInfo.newExpiredInstanceMetadata(event.getService(), event.getMetadataId()));
        }
    }

    // 如果是expired为true，放入过期元数据集合等待删除
    // 如果是expired为false，表示有的服务重新注册，从过期元数据中删除
    private void updateExpiredInfo(boolean expired, ExpiredMetadataInfo expiredMetadataInfo) {
        if (expired) {
            expiredMetadataInfos.add(expiredMetadataInfo);
        } else {
            expiredMetadataInfos.remove(expiredMetadataInfo);
        }
    }
}
