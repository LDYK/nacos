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

package com.alibaba.nacos.naming.core.v2.index;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.naming.core.v2.ServiceManager;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManager;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManagerDelegate;
import com.alibaba.nacos.naming.core.v2.metadata.InstanceMetadata;
import com.alibaba.nacos.naming.core.v2.metadata.NamingMetadataManager;
import com.alibaba.nacos.naming.core.v2.pojo.BatchInstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.utils.InstanceUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 *
 * 根据service存取对应的ServiceInfo以及service下的集群列表
 *
 * Service storage.
 *
 * @author xiweng.yy
 */
@Component
public class ServiceStorage {
    
    private final ClientServiceIndexesManager serviceIndexesManager;
    
    private final ClientManager clientManager;
    
    private final SwitchDomain switchDomain;
    
    private final NamingMetadataManager metadataManager;

    // 存放服务及对应的服务详细信息
    private final ConcurrentMap<Service, ServiceInfo> serviceDataIndexes;

    // 存放服务及对应拥有的集群集合（一个服务的不同实例可以设置不同的cluster）；
    private final ConcurrentMap<Service, Set<String>> serviceClusterIndex;
    
    public ServiceStorage(ClientServiceIndexesManager serviceIndexesManager, ClientManagerDelegate clientManager,
            SwitchDomain switchDomain, NamingMetadataManager metadataManager) {
        this.serviceIndexesManager = serviceIndexesManager;
        this.clientManager = clientManager;
        this.switchDomain = switchDomain;
        this.metadataManager = metadataManager;
        this.serviceDataIndexes = new ConcurrentHashMap<>();
        this.serviceClusterIndex = new ConcurrentHashMap<>();
    }

    //服务的集群列表
    public Set<String> getClusters(Service service) {
        return serviceClusterIndex.getOrDefault(service, new HashSet<>());
    }


    // 如果集合serviceDataIndexes中存在，那么直接获取返回
    // 如果集合serviceDataIndexes中不存在，从ClientServiceIndexesManager中对应的服务的客户端集合中查询对应服务的ClientId
    // 然后根据clientId找到对应的Client（根据上文的ClientManager介绍，不同实例对应不同的ClientManager，比如EphemeralIpPortClientManager、ConnectionBasedClientManager和PersistentIpPortClientManager）
    // 然后从对应的Client中查询出对应Client发布（注册）的服务实例，最后组建要查询的服务实例集合返回
    // 在查询服务实例信息时，如果服务实例对应有metadata，那么就从NamingMetadataManager查找元数据信息作为实例的metadata返回；
    public ServiceInfo getData(Service service) {
        return serviceDataIndexes.containsKey(service) ? serviceDataIndexes.get(service) : getPushData(service);
    }

    //如果服务管理器中没有这个服务就通过ServiceManager创建并返回一个空的serviceInfo
    public ServiceInfo getPushData(Service service) {
        ServiceInfo result = emptyServiceInfo(service);
        if (!ServiceManager.getInstance().containSingleton(service)) {
            return result;
        }
        Service singleton = ServiceManager.getInstance().getSingleton(service);
        result.setHosts(getAllInstancesFromIndex(singleton));
        serviceDataIndexes.put(singleton, result);
        return result;
    }

    //分别从2个map中移除service记录
    public void removeData(Service service) {
        serviceDataIndexes.remove(service);
        serviceClusterIndex.remove(service);
    }

    //根据service中的属性创建新的ServiceInfo
    private ServiceInfo emptyServiceInfo(Service service) {
        ServiceInfo result = new ServiceInfo();
        result.setName(service.getName());
        result.setGroupName(service.getGroup());
        result.setLastRefTime(System.currentTimeMillis());
        result.setCacheMillis(switchDomain.getDefaultPushCacheMillis());
        return result;
    }
    
    private List<Instance> getAllInstancesFromIndex(Service service) {
        Set<Instance> result = new HashSet<>();
        Set<String> clusters = new HashSet<>();
        //轮询发布该服务的所有clientId
        for (String each : serviceIndexesManager.getAllClientsRegisteredService(service)) {
            //根据clientId查询对应发布的实例信息
            Optional<InstancePublishInfo> instancePublishInfo = getInstanceInfo(each, service);
            if (instancePublishInfo.isPresent()) {
                InstancePublishInfo publishInfo = instancePublishInfo.get();
                //If it is a BatchInstancePublishInfo type, it will be processed manually and added to the instance list
                if (publishInfo instanceof BatchInstancePublishInfo) {
                    BatchInstancePublishInfo batchInstancePublishInfo = (BatchInstancePublishInfo) publishInfo;
                    List<Instance> batchInstance = parseBatchInstance(service, batchInstancePublishInfo, clusters);
                    result.addAll(batchInstance);
                } else {
                    Instance instance = parseInstance(service, instancePublishInfo.get());
                    result.add(instance);
                    clusters.add(instance.getClusterName());
                }
            }
        }
        // cache clusters of this service
        serviceClusterIndex.put(service, clusters);
        return new LinkedList<>(result);
    }
    
    /**
     * Parse batch instance.
     * @param service service
     * @param batchInstancePublishInfo batchInstancePublishInfo
     * @return batch instance list
     */
    private List<Instance> parseBatchInstance(Service service, BatchInstancePublishInfo batchInstancePublishInfo, Set<String> clusters) {
        List<Instance> resultInstanceList = new ArrayList<>();
        List<InstancePublishInfo> instancePublishInfos = batchInstancePublishInfo.getInstancePublishInfos();
        for (InstancePublishInfo instancePublishInfo : instancePublishInfos) {
            Instance instance = parseInstance(service, instancePublishInfo);
            resultInstanceList.add(instance);
            clusters.add(instance.getClusterName());
        }
        return resultInstanceList;
    }

    //根据clientId从客户端管理器中查询Client
    //client查询服务的所有发布信息类型InstancePublishInfo(跟Instance属性差异不大)
    private Optional<InstancePublishInfo> getInstanceInfo(String clientId, Service service) {
        Client client = clientManager.getClient(clientId);
        if (null == client) {
            return Optional.empty();
        }
        return Optional.ofNullable(client.getInstancePublishInfo(service));
    }

    //解析InstancePublishInfo 成 Instance
    private Instance parseInstance(Service service, InstancePublishInfo instanceInfo) {
        //属性赋值
        Instance result = InstanceUtil.parseToApiInstance(service, instanceInfo);
        //查询实例的元数据并更新Instance里面的元数据
        Optional<InstanceMetadata> metadata = metadataManager
                .getInstanceMetadata(service, instanceInfo.getMetadataId());
        metadata.ifPresent(instanceMetadata -> InstanceUtil.updateInstanceMetadata(result, instanceMetadata));
        return result;
    }
}
