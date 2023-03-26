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

package com.alibaba.nacos.naming.core.v2.service.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.naming.core.v2.ServiceManager;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManager;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManagerDelegate;
import com.alibaba.nacos.naming.core.v2.event.client.ClientOperationEvent;
import com.alibaba.nacos.naming.core.v2.event.metadata.MetadataEvent;
import com.alibaba.nacos.naming.core.v2.pojo.BatchInstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.core.v2.service.ClientOperationService;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.pojo.Subscriber;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Operation service for ephemeral clients and services.
 *
 * @author xiweng.yy
 */
@Component("ephemeralClientOperationService")
public class EphemeralClientOperationServiceImpl implements ClientOperationService {
    
    private final ClientManager clientManager;
    
    public EphemeralClientOperationServiceImpl(ClientManagerDelegate clientManager) {
        this.clientManager = clientManager;
    }
    
    @Override
    public void registerInstance(Service service, Instance instance, String clientId) throws NacosException {
        //先检查该服务是否在服务管理器中注册过，没有就注册
        NamingUtils.checkInstanceIsLegal(instance);
    
        Service singleton = ServiceManager.getInstance().getSingleton(service);
        if (!singleton.isEphemeral()) {
            throw new NacosRuntimeException(NacosException.INVALID_PARAM,
                    String.format("Current service %s is persistent service, can't register ephemeral instance.",
                            singleton.getGroupedServiceName()));
        }
        //获取之前注册过的IpPortBasedClient对象
        Client client = clientManager.getClient(clientId);
        // 校验客户端是否合法
        if (!clientIsLegal(client, clientId)) {
            return;
        }
        // 构建服务实例发布对象InstancePublishInfo
        //注意包括ip port healthy 3个属性 是instance的属性的子集
        InstancePublishInfo instanceInfo = getPublishInfo(instance);
        client.addServiceInstance(singleton, instanceInfo);
        client.setLastUpdatedTime();
        client.recalculateRevision();
        // 发布客户端注册事件，触发更新 publisherIndexes（保存 service => clientId 的 Map<Service, Set<String>>，即哪些客户端注册了这个服务的索引），同时也触发一个 ServiceChangedEvent，该事件负责向监听该服务的客户端进行推送
        NotifyCenter.publishEvent(new ClientOperationEvent.ClientRegisterServiceEvent(singleton, clientId));
        // 发布处理元数据事件，Nacos 在 2.0 中将元数据与基础数据拆分开，分为不同的处理流程
        NotifyCenter.publishEvent(new MetadataEvent.InstanceMetadataEvent(singleton, instanceInfo.getMetadataId(), false));
    }
    
    @Override
    public void batchRegisterInstance(Service service, List<Instance> instances, String clientId) {
        // 交给服务管理器管理服务
        Service singleton = ServiceManager.getInstance().getSingleton(service);
        if (!singleton.isEphemeral()) {
            throw new NacosRuntimeException(NacosException.INVALID_PARAM,
                    String.format("Current service %s is persistent service, can't batch register ephemeral instance.",
                            singleton.getGroupedServiceName()));
        }
        // 从客户端管理器拿到客户端
        Client client = clientManager.getClient(clientId);
        if (!clientIsLegal(client, clientId)) {
            return;
        }
        BatchInstancePublishInfo batchInstancePublishInfo = new BatchInstancePublishInfo();
        List<InstancePublishInfo> resultList = new ArrayList<>();
        for (Instance instance : instances) {
            InstancePublishInfo instanceInfo = getPublishInfo(instance);
            resultList.add(instanceInfo);
        }
        batchInstancePublishInfo.setInstancePublishInfos(resultList);
        client.addServiceInstance(singleton, batchInstancePublishInfo);
        client.setLastUpdatedTime();
        NotifyCenter.publishEvent(new ClientOperationEvent.ClientRegisterServiceEvent(singleton, clientId));
        NotifyCenter.publishEvent(
                new MetadataEvent.InstanceMetadataEvent(singleton, batchInstancePublishInfo.getMetadataId(), false));
    }
    
    @Override
    public void deregisterInstance(Service service, Instance instance, String clientId) {
        // 第二次校验服务管理器中是否存在该服务
        //在EphemeralClientOperationServiceImpl服务注册逻辑里第一行代码就是交给服务管理器管理服务
        if (!ServiceManager.getInstance().containSingleton(service)) {
            Loggers.SRV_LOG.warn("remove instance from non-exist service: {}", service);
            return;
        }
        // 从服务管理器获取服务
        Service singleton = ServiceManager.getInstance().getSingleton(service);
        // 从客户端管理器获取客户端
        Client client = clientManager.getClient(clientId);
        // 客户端合法性校验
        if (!clientIsLegal(client, clientId)) {
            return;
        }
        // 从客户端移除服务
        InstancePublishInfo removedInstance = client.removeServiceInstance(singleton);
        //设置最近更新时间
        client.setLastUpdatedTime();
        client.recalculateRevision();
        if (null != removedInstance) {
            // 发布客户端下线事件
            // 从ClientServiceIndexesManager 列表表删除服务下该client的发布信息 和 服务元数据变更事件
            NotifyCenter.publishEvent(new ClientOperationEvent.ClientDeregisterServiceEvent(singleton, clientId));
            //把该实例的元数据过期 参考 NamingMetadataManager
            NotifyCenter.publishEvent(
                    new MetadataEvent.InstanceMetadataEvent(singleton, removedInstance.getMetadataId(), true));
        }
    }
    
    @Override
    public void subscribeService(Service service, Subscriber subscriber, String clientId) {
        //首先从服务管理器中去查询服务 查询不到 就用当前的
        Service singleton = ServiceManager.getInstance().getSingletonIfExist(service).orElse(service);
        //从客户端管理器获取客户端对象
        Client client = clientManager.getClient(clientId);
        //校验逻辑很简单 1、是否为空 2、是否是临时节点
        if (!clientIsLegal(client, clientId)) {
            return;
        }
        //客户端自己为服务的订阅者
        client.addServiceSubscriber(singleton, subscriber);
        client.setLastUpdatedTime();
        //发布客户端订阅事件
        NotifyCenter.publishEvent(new ClientOperationEvent.ClientSubscribeServiceEvent(singleton, clientId));
    }
    
    @Override
    public void unsubscribeService(Service service, Subscriber subscriber, String clientId) {
        Service singleton = ServiceManager.getInstance().getSingletonIfExist(service).orElse(service);
        Client client = clientManager.getClient(clientId);
        if (!clientIsLegal(client, clientId)) {
            return;
        }
        client.removeServiceSubscriber(singleton);
        client.setLastUpdatedTime();
        NotifyCenter.publishEvent(new ClientOperationEvent.ClientUnsubscribeServiceEvent(singleton, clientId));
    }
    
    private boolean clientIsLegal(Client client, String clientId) {
        if (client == null) {
            Loggers.SRV_LOG.warn("Client connection {} already disconnect", clientId);
            return false;
        }
        if (!client.isEphemeral()) {
            Loggers.SRV_LOG.warn("Client connection {} type is not ephemeral", clientId);
            return false;
        }
        return true;
    }
}
