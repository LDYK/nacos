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

package com.alibaba.nacos.naming.healthcheck.heartbeat;

import com.alibaba.nacos.common.task.AbstractExecuteTask;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.core.v2.client.impl.IpPortBasedClient;
import com.alibaba.nacos.naming.core.v2.pojo.HealthCheckInstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.healthcheck.NacosHealthCheckTask;
import com.alibaba.nacos.naming.misc.GlobalConfig;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.sys.utils.ApplicationUtils;

import java.util.Collection;

/**
 * Client beat check task of service for version 2.x.
 *
 * @author nkorange
 */

// 基于IPPort的Client对象中，客户端服务如果是临时实例（ephemeral=true），那么就会创建这个健康检查任务。
// 执行流程
//    1、从客户端的所有服务发布（注册）集合中取出对应的客户端服务；
//    2、基于拦截器的模式，对每个拦截执行InstanceBeatCheckTask对象的检查，默认情况下，有2个实例心跳检查器InstanceBeatChecker
//      1)、UnhealthyInstanceChecker，它的检查机制是：如果当前服务实例心跳检查超过15s（默认），那么
//          a、设置服务实例health=false
//          b、发布服务变更事件：ServiceEvent.ServiceChangedEvent
//          c、发布客户端变更事件：ClientEvent.ClientChangedEvent
//      2)、ExpiredInstanceChecker，它的检查机制是：如果当前服务实例心跳检查超过30s（默认），那么
//          a、从服务端的该服务的Client对象中实例集合删除该服务实例；
//          b、发布服务注销事件：ClientOperationEvent.ClientDeregisterServiceEvent；
//          c、发布服务实例元数据变更事件：MetadataEvent.InstanceMetadataEvent

public class ClientBeatCheckTaskV2 extends AbstractExecuteTask implements BeatCheckTask, NacosHealthCheckTask {

    // 客户端对象
    private final IpPortBasedClient client;

    //就是 client.getResponsibleId
    private final String taskId;

    // 任务拦截器链
    private final InstanceBeatCheckTaskInterceptorChain interceptorChain;
    
    public ClientBeatCheckTaskV2(IpPortBasedClient client) {
        this.client = client;
        this.taskId = client.getResponsibleId();
        this.interceptorChain = InstanceBeatCheckTaskInterceptorChain.getInstance();
    }
    
    public GlobalConfig getGlobalConfig() {
        return ApplicationUtils.getBean(GlobalConfig.class);
    }
    
    @Override
    public String taskKey() {
        return KeyBuilder.buildServiceMetaKey(client.getClientId(), String.valueOf(client.isEphemeral()));
    }
    
    @Override
    public String getTaskId() {
        return taskId;
    }

    // 心跳检查
    // 执行心跳检查任务是在ClientBeatCheckTaskV2 里面嵌入一个新的InstanceBeatCheckTask任务
    // 真正的心跳检查逻辑是在InstanceBeatCheckTask上的拦截器都执行完毕后
    // 再执行InstanceBeatCheckTask的passIntercept方法完成一次心跳检查
    @Override
    public void doHealthCheck() {
        try {
            // 轮询该客户端发布的所有服务列表
            Collection<Service> services = client.getAllPublishedService();
            for (Service each : services) {
                // 获取服务下的实例信息
                HealthCheckInstancePublishInfo instance = (HealthCheckInstancePublishInfo) client
                        .getInstancePublishInfo(each);
                // 拦截器链处理完InstanceBeatCheckTask后执行
                // InstanceBeatCheckTask.passIntercept()方法
                interceptorChain.doInterceptor(new InstanceBeatCheckTask(client, each, instance));
            }
        } catch (Exception e) {
            Loggers.SRV_LOG.warn("Exception while processing client beat time out.", e);
        }
    }

    // 定时任务的执行入口，实现Runnable方法
    @Override
    public void run() {
        doHealthCheck();
    }
    
    @Override
    public void passIntercept() {
        doHealthCheck();
    }
    
    @Override
    public void afterIntercept() {
    }
}
