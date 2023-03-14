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

package com.alibaba.nacos.naming.core.v2.client.manager.impl;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.naming.consistency.ephemeral.distro.v2.DistroClientVerifyInfo;
import com.alibaba.nacos.naming.constants.ClientConstants;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.client.ClientAttributes;
import com.alibaba.nacos.naming.core.v2.client.factory.ClientFactory;
import com.alibaba.nacos.naming.core.v2.client.factory.ClientFactoryHolder;
import com.alibaba.nacos.naming.core.v2.client.impl.IpPortBasedClient;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManager;
import com.alibaba.nacos.naming.core.v2.event.client.ClientEvent;
import com.alibaba.nacos.naming.healthcheck.heartbeat.ClientBeatUpdateTask;
import com.alibaba.nacos.naming.misc.ClientConfig;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.NamingExecuteTaskDispatcher;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * The manager of {@code IpPortBasedClient} and ephemeral.
 *
 * @author xiweng.yy
 */

// @DependsOn注解：表示EphemeralIpPortClientManager的实例化依赖clientServiceIndexesManager的实例化，虽然EphemeralIpPortClientManager并没持有clientServiceIndexesManager对象
// 所谓的Bean可以是任何Bean：包括@Bean、@Component、@Configuration等一切形式
@DependsOn("clientServiceIndexesManager")
@Component("ephemeralIpPortClientManager")
public class EphemeralIpPortClientManager implements ClientManager {

    // 以clientId为key的Client缓存，clientId格式：ip:port#ephemeral
    private final ConcurrentMap<String, IpPortBasedClient> clients = new ConcurrentHashMap<>();

    // 提供管理Nacos Server服务器集群功能
    private final DistroMapper distroMapper;

    // IpPortBasedClient的工厂类
    private final ClientFactory<IpPortBasedClient> clientFactory;
    
    public EphemeralIpPortClientManager(DistroMapper distroMapper, SwitchDomain switchDomain) {
        this.distroMapper = distroMapper;
        // 过期客户端清理任务 5s 执行一次 ExpiredClientCleaner
        GlobalExecutor.scheduleExpiredClientCleaner(new ExpiredClientCleaner(this, switchDomain), 0,
                Constants.DEFAULT_HEART_BEAT_INTERVAL, TimeUnit.MILLISECONDS);
        //根据[ClientConstants.EPHEMERAL_IP_PORT]类型去工厂类获取相应的clientFactory
        clientFactory = ClientFactoryHolder.getInstance().findClientFactory(ClientConstants.EPHEMERAL_IP_PORT);
    }

    // 通过工厂类创建新的客户端
    @Override
    public boolean clientConnected(String clientId, ClientAttributes attributes) {
        return clientConnected(clientFactory.newClient(clientId, attributes));
    }

    // 初始化客户端 并缓存到map中
    @Override
    public boolean clientConnected(final Client client) {
        clients.computeIfAbsent(client.getClientId(), s -> {
            Loggers.SRV_LOG.info("Client connection {} connect", client.getClientId());
            IpPortBasedClient ipPortBasedClient = (IpPortBasedClient) client;
            // 初始化客户端，并创建心跳检查任务
            ipPortBasedClient.init();
            return ipPortBasedClient;
        });
        return true;
    }
    
    @Override
    public boolean syncClientConnected(String clientId, ClientAttributes attributes) {
        return clientConnected(clientFactory.newSyncedClient(clientId, attributes));
    }

    // 从缓存中移除client 并发布 ClientEvent.ClientDisconnectEvent 事件到统一事件中心
    @Override
    public boolean clientDisconnected(String clientId) {
        Loggers.SRV_LOG.info("Client connection {} disconnect, remove instances and subscribers", clientId);
        IpPortBasedClient client = clients.remove(clientId);
        if (null == client) {
            return true;
        }
        // 发布断开连接事件
        NotifyCenter.publishEvent(new ClientEvent.ClientDisconnectEvent(client, isResponsibleClient(client)));
        // 资源释放
        client.release();
        return true;
    }
    
    @Override
    public Client getClient(String clientId) {
        return clients.get(clientId);
    }
    
    @Override
    public boolean contains(String clientId) {
        return clients.containsKey(clientId);
    }
    
    @Override
    public Collection<String> allClientId() {
        return clients.keySet();
    }


    @Override
    public boolean isResponsibleClient(Client client) {
        if (client instanceof IpPortBasedClient) {
            return distroMapper.responsible(((IpPortBasedClient) client).getResponsibleId());
        }
        return false;
    }

    //该方法在服务器集群间交互的时候用到 服务注册取消注册用不到目前
    @Override
    public boolean verifyClient(DistroClientVerifyInfo verifyData) {
        String clientId = verifyData.getClientId();
        IpPortBasedClient client = clients.get(clientId);
        if (null != client) {
            // remote node of old version will always verify with zero revision
            if (0 == verifyData.getRevision() || client.getRevision() == verifyData.getRevision()) {
                NamingExecuteTaskDispatcher.getInstance()
                        .dispatchAndExecuteTask(clientId, new ClientBeatUpdateTask(client));
                return true;
            } else {
                Loggers.DISTRO.info("[DISTRO-VERIFY-FAILED] IpPortBasedClient[{}] revision local={}, remote={}",
                        client.getClientId(), client.getRevision(), verifyData.getRevision());
            }
        }
        return false;
    }


    /**
     * 该类的逻辑定期检查是否是一个过期的客户端 过期的含义满足下面任一个
     * 1、超时还没有发布服务 且 超时还没有订阅服务
     * 2、超过了客户端的过期事件
     */
    private static class ExpiredClientCleaner implements Runnable {
        
        private final EphemeralIpPortClientManager clientManager;
        
        private final SwitchDomain switchDomain;
        
        public ExpiredClientCleaner(EphemeralIpPortClientManager clientManager, SwitchDomain switchDomain) {
            this.clientManager = clientManager;
            this.switchDomain = switchDomain;
        }
        
        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            for (String each : clientManager.allClientId()) {
                IpPortBasedClient client = (IpPortBasedClient) clientManager.getClient(each);
                if (null != client && isExpireClient(currentTime, client)) {
                    clientManager.clientDisconnected(each);
                }
            }
        }

        // 判断是否超时
        private boolean isExpireClient(long currentTime, IpPortBasedClient client) {
            long noUpdatedTime = currentTime - client.getLastUpdatedTime();
            // 临时节点 && 30s未发布 && 10s未订阅
            return client.isEphemeral() && (
                    isExpirePublishedClient(noUpdatedTime, client) && isExpireSubscriberClient(noUpdatedTime, client)
                            || noUpdatedTime > ClientConfig.getInstance().getClientExpiredTime());
        }
        
        private boolean isExpirePublishedClient(long noUpdatedTime, IpPortBasedClient client) {
            return client.getAllPublishedService().isEmpty() && noUpdatedTime > Constants.DEFAULT_IP_DELETE_TIMEOUT;
        }
        
        private boolean isExpireSubscriberClient(long noUpdatedTime, IpPortBasedClient client) {
            return client.getAllSubscribeService().isEmpty() || noUpdatedTime > switchDomain.getDefaultPushCacheMillis();
        }
    }
}
