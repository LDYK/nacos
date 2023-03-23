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

package com.alibaba.nacos.naming.core.v2.client.impl;

import com.alibaba.nacos.naming.core.v2.client.AbstractClient;
import com.alibaba.nacos.naming.misc.ClientConfig;

/**
 * Nacos naming client based on tcp session.
 *
 * <p>The client is bind to the tcp session. When the tcp session disconnect, the client should be clean.
 *
 * @author xiweng.yy
 */

// 基于TCP的客户端定义，继承抽象类AbstractClient
public class ConnectionBasedClient extends AbstractClient {

    // 连接唯一表示
    private final String connectionId;
    
    /**
     * {@code true} means this client is directly connect to current server. {@code false} means this client is synced
     * from other server.
     */
    // 表示当前Client是否是直接连接到当前Nacos服务的，如果是false则表示是从其他节点同步而来；
    private final boolean isNative;
    
    /**
     * Only has meaning when {@code isNative} is false, which means that the last time verify from source server.
     */
    // 只在isNative=true时有意义，表示客户端最近一次续约（代表最近一次有效连接）时间；
    private volatile long lastRenewTime;
    
    public ConnectionBasedClient(String connectionId, boolean isNative, Long revision) {
        super(revision);
        this.connectionId = connectionId;
        this.isNative = isNative;
        lastRenewTime = getLastUpdatedTime();
    }
    
    @Override
    public String getClientId() {
        return connectionId;
    }
    
    @Override
    public boolean isEphemeral() {
        return true;
    }
    
    public boolean isNative() {
        return isNative;
    }
    
    public long getLastRenewTime() {
        return lastRenewTime;
    }
    
    public void setLastRenewTime() {
        this.lastRenewTime = System.currentTimeMillis();
    }

    // 满足下面的条件该方法返回true：
    //  1）非isNative客户端，则说明是否过期是针对同步而来的客户端；
    //  2）当前时间与上次续约时间（lastRenewTime）时间间隔大于客户端过期时间（默认3m）；
    @Override
    public boolean isExpire(long currentTime) {
        return !isNative() && currentTime - getLastRenewTime() > ClientConfig.getInstance().getClientExpiredTime();
    }
    
    @Override
    public long recalculateRevision() {
        return revision.addAndGet(1);
    }
}
