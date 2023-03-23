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

package com.alibaba.nacos.naming.core.v2.client.factory;

import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.client.ClientAttributes;

/**
 * Client factory.
 *
 * @author xiweng.yy
 */
// ClientFactory负责定义创建Client对象
// 1、ConnectionBasedClientFactory 负责创建ConnectionBasedClient工厂对象，类型名为default，负责创建ConnectionBasedClient，表示创建基于Grpc连接的客户端
// 2、EphemeralIpPortClientFactory 负责创建IpPortBasedClient工厂对象，类型名为ephemeralIpPort，负责创建IpPortBasedClient，表示创建基于IP地址连接的客户端（临时服务实例）
// 3、PersistentIpPortClientFactory 负责创建IpPortBasedClient工厂对象，类型名为persistentIpPort，负责创建IpPortBasedClient，表示创建基于IP地址连接的客户端（持久化服务实例）
public interface ClientFactory<C extends Client> {
    
    /**
     * Get the type of client this factory can build.
     *
     * @return client type
     */
    // 返回当前Client对象的一个标识，就是ClientConstants常量
    String getType();
    
    /**
     * Build a new {@link Client}.
     *
     * @param clientId client id
     * @param attributes client attributes
     * @return new {@link Client} implementation
     */
    // 创建Client实例对象（本地的）
    C newClient(String clientId, ClientAttributes attributes);
    
    /**
     * Build a new {@link Client} synced from other server node.
     *
     * @param clientId   client id
     * @param attributes client attributes
     * @return new sync {@link Client} implementation
     */
    // 负责创建Client实例对象（其他Nacos节点同步过来的）
    C newSyncedClient(String clientId, ClientAttributes attributes);
}
