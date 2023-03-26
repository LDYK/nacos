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

import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.pojo.Subscriber;

import java.util.Collection;

/**
 * Nacos naming client.
 *
 * <p>The abstract concept of the client stored by on the server of Nacos naming module. It is used to store which
 * services the client has published and subscribed.
 *
 * @author xiweng.yy
 */
// 服务提供者（Provider）、消费者（Consumer）都属于Client
// 服务端（Server：注册中心）
// 服务提供者和服务消费者属于临时节点 集群节点 属于非临时节点

// 客户端管理
// 1、Client：通过接口Client及相关的抽象类和实现类对客户端进行管理
//    1)、AbstractClient：客户端与服务端创建连接之后，在服务端由继承AbstractClient的具体实例来维护
//        a、ConnectionBasedClient：基于TCP的客户端定义，继承抽象类AbstractClient
//        b、IpPortBasedClient：基于IP+Port的客户端定义，继承抽象类AbstractClient
//        c、
//        d、
//    2)、ClientFactory：负责定义创建Client对象
//        a、ConnectionBasedClientFactory：负责创建ConnectionBasedClient工厂对象，类型名为default，负责创建ConnectionBasedClient，表示创建基于Grpc连接的客户端
//        b、EphemeralIpPortClientFactory：负责创建IpPortBasedClient工厂对象，类型名为ephemeralIpPort，负责创建IpPortBasedClient，表示创建基于IP地址连接的客户端（临时服务实例）
//        c、PersistentIpPortClientFactory：负责创建IpPortBasedClient工厂对象，类型名为persistentIpPort，负责创建IpPortBasedClient，表示创建基于IP地址连接的客户端（持久化服务实例）
// 2、Metadata：管理客户端服务元数据，元数据信息随着服务实例的存在而存在，如果服务或者实例不存在，那么对应的元数据信息也会被移除
//    1)、ClusterMetadata：集群元数据信息，维护服务集群健康检查用
//    2)、ExpiredMetadata：过期元数据信息，维护过期的服务元数据；过期的元数据信息会被删除
//    3)、ServiceMetadata：服务元数据信息
//    4)、InstanceMetadata：实例元数据
// 3、健康检查：Nacos服务端，维持着客户端的健康检查机制，在健康检查不满足条件时，对客户端进行不检查设置或者删除
//    1)、HealthCheckTaskV2：在基于IPPort的Client对象中，客户端服务如果是非临时实例（ephemeral=false），那么就会创建这个健康检查任务
//    2)、ClientBeatCheckTaskV2：在基于IPPort的Client对象中，客户端服务如果是临时实例（ephemeral=true），那么就会创建这个健康检查任务
// 4、ClientManager：负责管理客户端，存储所有的客户端信息，以及客户端的crud功能，以及提供定时任务清理过期的客户端
//    1)、ConnectionBasedClientManager：对客户端服务基于RPCclient的服务管理。同时继承抽象类ClientConnectionEventListener，实现连接在建立和断开时的事件处理
//    2)、EphemeralIpPortClientManager：对客户端服务基于IP Port模式的临时服务管理
//    3)、PersistentIpPortClientManager：对客户端服务基于持久化模式的服务管理，同EphemeralIpPortClientManager类似
// 5、客户端存储：针对临时服务实例，客户端服务注册、服务订阅相关的信息会在Nacos服务端进行存储
//    1)、ClientServiceIndexesManager：Index管理，存储服务以及订阅该服务的相关信息，也是一个订阅者
//    2)、ServiceStorage：在Nacos服务端，服务及对应服务实例等相关信息就是存放在类ServiceStorage里面


// ClientManagerDelegate：统一客户端管理器的代理类


public interface Client {
    
    /**
     * Get the unique id of current client.
     *
     * @return id of client
     */
    String getClientId();
    
    /**
     * Whether is ephemeral of current client.
     *
     * @return true if client is ephemeral, otherwise false
     */
    boolean isEphemeral();
    
    /**
     * Set the last time for updating current client as current time.
     */
    void setLastUpdatedTime();
    
    /**
     * Get the last time for updating current client.
     *
     * @return last time for updating
     */
    long getLastUpdatedTime();
    
    /**
     * Add a new instance for service for current client.
     *
     * @param service             publish service
     * @param instancePublishInfo instance
     * @return true if add successfully, otherwise false
     */
    boolean addServiceInstance(Service service, InstancePublishInfo instancePublishInfo);
    
    /**
     * Remove service instance from client.
     *
     * @param service service of instance
     * @return instance info if exist, otherwise {@code null}
     */
    InstancePublishInfo removeServiceInstance(Service service);
    
    /**
     * Get instance info of service from client.
     *
     * @param service service of instance
     * @return instance info
     */
    InstancePublishInfo getInstancePublishInfo(Service service);
    
    /**
     * Get all published service of current client.
     *
     * @return published services
     */
    Collection<Service> getAllPublishedService();
    
    /**
     * Add a new subscriber for target service.
     *
     * @param service    subscribe service
     * @param subscriber subscriber
     * @return true if add successfully, otherwise false
     */
    boolean addServiceSubscriber(Service service, Subscriber subscriber);
    
    /**
     * Remove subscriber for service.
     *
     * @param service service of subscriber
     * @return true if remove successfully, otherwise false
     */
    boolean removeServiceSubscriber(Service service);
    
    /**
     * Get subscriber of service from client.
     *
     * @param service service of subscriber
     * @return subscriber
     */
    Subscriber getSubscriber(Service service);
    
    /**
     * Get all subscribe service of current client.
     *
     * @return subscribe services
     */
    Collection<Service> getAllSubscribeService();
    
    /**
     * Generate sync data.
     *
     * @return sync data
     */
    ClientSyncData generateSyncData();
    
    /**
     * Whether current client is expired.
     *
     * @param currentTime unified current timestamp
     * @return true if client has expired, otherwise false
     */
    boolean isExpire(long currentTime);
    
    /**
     * Release current client and release resources if neccessary.
     */
    void release();
    
    /**
     * Recalculate client revision and get its value.
     * @return recalculated revision value
     */
    long recalculateRevision();
    
    /**
     * Get client revision.
     * @return current revision without recalculation
     */
    long getRevision();
    
    /**
     * Set client revision.
     * @param revision revision of this client to update
     */
    void setRevision(long revision);
    
}
