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

package com.alibaba.nacos.naming.core.v2.client.manager;

import com.alibaba.nacos.naming.consistency.ephemeral.distro.v2.DistroClientVerifyInfo;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.client.ClientAttributes;

import java.util.Collection;

/**
 * The manager of {@code Client} Nacos naming client.
 *
 * @author xiweng.yy
 */
// ClientManager负责管理客户端，存储所有的客户端信息，以及客户端的crud功能，以及提供定时任务清理过期的客户端
// 1、ConnectionBasedClientManager：对客户端服务基于RPCclient的服务管理。同时继承抽象类ClientConnectionEventListener，实现连接在建立和断开时的事件处理。
// 2、EphemeralIpPortClientManager：对客户端服务基于IP Port模式的临时服务管理。
// 3、PersistentIpPortClientManager：对客户端服务基于持久化模式的服务管理，同EphemeralIpPortClientManager类似


// 服务提供者和服务消费者以及其他的集群节点都属于Client
// 服务提供者和服务消费者属于临时节点，集群节点属于非临时节点
// Client 相关内容主要包括几大核心类：
// 1、ClientManager 存储所有的客户端信息 以及客户端的crud功能 以及提供定时任务清理过期的客户端
// 2、ClientManagerDelegate：统一客户端管理器的代理类
// 3、EphemeralIpPortClientManager：管理临时节点的客户端管理器
// 4、IpPortBasedClient：基于ip和端口的客户端
public interface ClientManager {
    
    /**
     * 创建一个新的客户端并创建心跳检查任务
     * New client connected.
     *
     * @param clientId new client id
     * @param attributes client attributes, which can help create client
     * @return true if add successfully, otherwise false
     */
    boolean clientConnected(String clientId, ClientAttributes attributes);
    
    /**
     * 创建一个新的客户端并创建心跳检查任务
     * New client connected.
     *
     * @param client new client
     * @return true if add successfully, otherwise false
     */
    boolean clientConnected(Client client);
    
    /**
     * New sync client connected.
     *
     * @param clientId   synced client id
     * @param attributes client sync attributes, which can help create sync client
     * @return true if add successfully, otherwise false
     */
    boolean syncClientConnected(String clientId, ClientAttributes attributes);
    
    /**
     * Client disconnected.
     *
     * @param clientId client id
     * @return true if remove successfully, otherwise false
     */
    boolean clientDisconnected(String clientId);
    
    /**
     * Get client by id.
     *
     * @param clientId client id
     * @return client
     */
    Client getClient(String clientId);
    
    /**
     * Whether the client id exists.
     *
     * @param clientId client id
     * @return client
     */
    boolean contains(final String clientId);
    
    /**
     * All client id.
     *
     * @return collection of client id
     */
    Collection<String> allClientId();
    
    /**
     * 在一个集群里面判断该客户端健康检查是不是由当前的服务器负责
     * Whether the client is responsible by current server.
     *
     * @param client client
     * @return true if responsible, otherwise false
     */
    boolean isResponsibleClient(Client client);
    
    /**
     * verify client.
     *
     * @param verifyData verify data from remote responsible server
     * @return true if client is valid, otherwise is false.
     */
    boolean verifyClient(DistroClientVerifyInfo verifyData);
}
