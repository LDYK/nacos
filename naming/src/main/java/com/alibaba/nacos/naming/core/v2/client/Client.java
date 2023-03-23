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
// 服务提供者（Provider）、消费者（Consumer）都属于Client，通过接口Client及相关的抽象类和实现类对客户端进行管理
// 服务端（Server：注册中心）
// 服务提供者和服务消费者属于临时节点 集群节点 属于非临时节点
// Client 相关内容主要包括几大核心类：
// 1、ClientManager：存储所有的客户端信息 以及客户端的crud功能 以及提供定时任务清理过期的客户端
// 2、ClientManagerDelegate：统一客户端管理器的代理类
// 3、EphemeralIpPortClientManager：管理临时节点的客户端管理器
// 4、IpPortBasedClient：基于ip和端口的客户端，继承抽象类AbstractClient
// 5、ConnectionBasedClient：基于TCP的客户端定义，继承抽象类AbstractClient
// 6、ClientFactory负责定义创建Client对象
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
