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
import com.alibaba.nacos.naming.constants.ClientConstants;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.client.ClientAttributes;
import com.alibaba.nacos.naming.core.v2.client.impl.IpPortBasedClient;
import com.alibaba.nacos.naming.core.v2.client.manager.impl.ConnectionBasedClientManager;
import com.alibaba.nacos.naming.core.v2.client.manager.impl.EphemeralIpPortClientManager;
import com.alibaba.nacos.naming.core.v2.client.manager.impl.PersistentIpPortClientManager;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;

/**
 * Client manager delegate.
 * 统一的客户端管理器代理类
 * @author xiweng.yy
 */
@DependsOn({"clientServiceIndexesManager", "namingMetadataManager"})
@Component("clientManager")
public class ClientManagerDelegate implements ClientManager {
    
    private final ConnectionBasedClientManager connectionBasedClientManager;

    // 临时节点的客户端管理器
    private final EphemeralIpPortClientManager ephemeralIpPortClientManager;

    // 持久化节点的客户端管理器
    private final PersistentIpPortClientManager persistentIpPortClientManager;
    
    public ClientManagerDelegate(ConnectionBasedClientManager connectionBasedClientManager,
            EphemeralIpPortClientManager ephemeralIpPortClientManager,
            PersistentIpPortClientManager persistentIpPortClientManager) {
        this.connectionBasedClientManager = connectionBasedClientManager;
        this.ephemeralIpPortClientManager = ephemeralIpPortClientManager;
        this.persistentIpPortClientManager = persistentIpPortClientManager;
    }

    // 新客户端接入
    @Override
    public boolean clientConnected(String clientId, ClientAttributes attributes) {
        return getClientManagerById(clientId).clientConnected(clientId, attributes);
    }

    // 新客户端接入
    @Override
    public boolean clientConnected(Client client) {
        return getClientManagerById(client.getClientId()).clientConnected(client);
    }
    
    @Override
    public boolean syncClientConnected(String clientId, ClientAttributes attributes) {
        return getClientManagerById(clientId).syncClientConnected(clientId, attributes);
    }

    // 客户端下线
    @Override
    public boolean clientDisconnected(String clientId) {
        return getClientManagerById(clientId).clientDisconnected(clientId);
    }

    // 查找客户端
    @Override
    public Client getClient(String clientId) {
        return getClientManagerById(clientId).getClient(clientId);
    }

    // 判断客户端是否存在
    @Override
    public boolean contains(String clientId) {
        return connectionBasedClientManager.contains(clientId) || ephemeralIpPortClientManager.contains(clientId)
                || persistentIpPortClientManager.contains(clientId);
    }

    // 获取所有客户端列表（临时、持久化）
    @Override
    public Collection<String> allClientId() {
        Collection<String> result = new HashSet<>();
        result.addAll(connectionBasedClientManager.allClientId());
        result.addAll(ephemeralIpPortClientManager.allClientId());
        result.addAll(persistentIpPortClientManager.allClientId());
        return result;
    }

    // 该客户端的健康检查等是否有当前服务器负责
    @Override
    public boolean isResponsibleClient(Client client) {
        return getClientManagerById(client.getClientId()).isResponsibleClient(client);
    }

    // 客户端的版本等校验
    @Override
    public boolean verifyClient(DistroClientVerifyInfo verifyData) {
        return getClientManagerById(verifyData.getClientId()).verifyClient(verifyData);
    }

    // 根据clientId的格式选择不同的ClientManager
    private ClientManager getClientManagerById(String clientId) {
        if (isConnectionBasedClient(clientId)) {
            return connectionBasedClientManager;
        }
        // clientId以false结尾的是ephemeralIpPortClientManager，否则是persistentIpPortClientManager
        return clientId.endsWith(ClientConstants.PERSISTENT_SUFFIX) ? persistentIpPortClientManager : ephemeralIpPortClientManager;
    }

    // clientId不包含#服务的客户端
    private boolean isConnectionBasedClient(String clientId) {
        return !clientId.contains(IpPortBasedClient.ID_DELIMITER);
    }
}
