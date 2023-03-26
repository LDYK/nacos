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

package com.alibaba.nacos.common.remote.client;

import com.alibaba.nacos.api.remote.Requester;

/**
 * connection on client side.
 *
 * @author liuzunfei
 * @version $Id: Connection.java, v 0.1 2020年08月09日 1:32 PM liuzunfei Exp $
 */

// 定义客户端连接的抽象定义，实现接口Requester
// Connection的实例类默认只有GrpcConnection，代表一个Grpc连接对象，负责Grpc方式的客户端连接请求和响应处理。

@SuppressWarnings("PMD.AbstractClassShouldStartWithAbstractNamingRule")
public abstract class Connection implements Requester {

    // 连接唯一标识
    private String connectionId;

    // 连接是否丢弃，为true当前连接的连接事件不会处理
    private boolean abandon = false;

    // 当前客户端连接对应的服务端信息（ip+port）
    protected RpcClient.ServerInfo serverInfo;
    
    public Connection(RpcClient.ServerInfo serverInfo) {
        this.serverInfo = serverInfo;
    }
    
    public String getConnectionId() {
        return connectionId;
    }
    
    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }
    
    /**
     * Getter method for property <tt>abandon</tt>.
     *
     * @return property value of abandon
     */
    public boolean isAbandon() {
        return abandon;
    }
    
    /**
     * Setter method for property <tt>abandon</tt>. connection event will be ignored if connection is abandoned.
     *
     * @param abandon value to be assigned to property abandon
     */
    public void setAbandon(boolean abandon) {
        this.abandon = abandon;
    }
    
}
