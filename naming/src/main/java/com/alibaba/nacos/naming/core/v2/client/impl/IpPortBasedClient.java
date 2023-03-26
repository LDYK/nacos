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
import com.alibaba.nacos.naming.core.v2.pojo.HealthCheckInstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.healthcheck.HealthCheckReactor;
import com.alibaba.nacos.naming.healthcheck.heartbeat.ClientBeatCheckTaskV2;
import com.alibaba.nacos.naming.healthcheck.v2.HealthCheckTaskV2;
import com.alibaba.nacos.naming.misc.ClientConfig;
import com.alibaba.nacos.naming.monitor.MetricsMonitor;

import java.util.Collection;

/**
 * 基于ip和端口的客户端比如服务提供者和发布者，UDP协议负责处理基于IpPortBasedClient的客户端的数据推送。
 *
 * Nacos naming client based ip and port.
 *
 * <p>The client is bind to the ip and port users registered. It's a abstract content to simulate the tcp session
 * client.
 *
 * @author xiweng.yy
 */
public class IpPortBasedClient extends AbstractClient {
    
    public static final String ID_DELIMITER = "#";

    // 格式 ip:port#true
    private final String clientId;
    
    private final boolean ephemeral;

    // 用于计算哪台服务器负责该client的参数，默认responsibleId的值就是从clientId中取#之前的字符串
    private final String responsibleId;

    // 定时检查临时节点发起的心跳，是一个线程
    private ClientBeatCheckTaskV2 beatCheckTask;

    // 定时主动检查非临时节点比如长链接的节点[集群服务]的健康检查，是一个线程
    private HealthCheckTaskV2 healthCheckTaskV2;

    public IpPortBasedClient(String clientId, boolean ephemeral) {
        this(clientId, ephemeral, null);
    }
    
    public IpPortBasedClient(String clientId, boolean ephemeral, Long revision) {
        super(revision);
        this.ephemeral = ephemeral;
        this.clientId = clientId;
        this.responsibleId = getResponsibleTagFromId();
    }

    // 截取clientId的ip:port部分作为responsebleId
    private String getResponsibleTagFromId() {
        int index = clientId.indexOf(IpPortBasedClient.ID_DELIMITER);
        return clientId.substring(0, index);
    }

    //静态方法生成clientId address(ip:port)
    public static String getClientId(String address, boolean ephemeral) {
        return address + ID_DELIMITER + ephemeral;
    }
    
    @Override
    public String getClientId() {
        return clientId;
    }
    
    @Override
    public boolean isEphemeral() {
        return ephemeral;
    }
    
    public String getResponsibleId() {
        return responsibleId;
    }

    // 客户端添加服务发布信息
    // 调用父类addServiceInstance方法前对参数InstancePublishInfo调用parseToHealthCheckInstance进行类型转化，转化为HealthCheckInstancePublishInfo
    @Override
    public boolean addServiceInstance(Service service, InstancePublishInfo instancePublishInfo) {
        return super.addServiceInstance(service, parseToHealthCheckInstance(instancePublishInfo));
    }
    // 同时满足下面的条件该方法返回true
    //  1）ephemeral=true，默认就是true
    //  2）所有客户端注册的服务集合publishers是空（也就是没有客户端注册信息）；
    //  3）当前时间与客户端最近更新时间之差大于客户端过期时间（默认3m）；
    @Override
    public boolean isExpire(long currentTime) {
        return isEphemeral() && getAllPublishedService().isEmpty() && currentTime - getLastUpdatedTime() > ClientConfig
                .getInstance().getClientExpiredTime();
    }

    // 服务的所有发布者
    public Collection<InstancePublishInfo> getAllInstancePublishInfo() {
        return publishers.values();
    }

    // 释放资源，取消定时任务
    @Override
    public void release() {
        super.release();
        if (ephemeral) {
            HealthCheckReactor.cancelCheck(beatCheckTask);
        } else {
            healthCheckTaskV2.setCancelled(true);
        }
    }



    /**
     * 包装 instancePublishInfo 对象成一个HealthCheckInstancePublishInfo  对象
     * 区别在于 后者包括所有的前者的属性之外 还包括了属性：lastHeartBeatTime[最近的心跳时间] 和 HealthCheckStatus
     * 包括最近健康检查时间 成功失败状态 及次数统计
     **/
    private HealthCheckInstancePublishInfo parseToHealthCheckInstance(InstancePublishInfo instancePublishInfo) {
        HealthCheckInstancePublishInfo result;
        if (instancePublishInfo instanceof HealthCheckInstancePublishInfo) {
            result = (HealthCheckInstancePublishInfo) instancePublishInfo;
        } else {
            result = new HealthCheckInstancePublishInfo();
            result.setIp(instancePublishInfo.getIp());
            result.setPort(instancePublishInfo.getPort());
            result.setHealthy(instancePublishInfo.isHealthy());
            result.setCluster(instancePublishInfo.getCluster());
            result.setExtendDatum(instancePublishInfo.getExtendDatum());
        }
        if (!ephemeral) {
            // 持久化节点因为主动发起健康检查所以需要初始化HealthCheckStatus对象
            result.initHealthCheck();
        }
        return result;
    }
    
    /**
     * Init client.
     * 针对临时客户端，创建任务ClientBeatCheckTaskV2，交给线程池管理，间隔5s秒执行；所以对于默认情况下，是通过这里的Client心跳检查来验证客户端是否健康的。
     * 针对非临时客户端，创建任务HealthCheckTaskV2，交给线程池管理，延迟2s+(0~5s一个随机数)时间，这个时间首次使用后，后面的间隔时长都是一致的。
     */
    public void init() {
        if (ephemeral) {
            // 创建临时节点的心跳检查任务并加入定时任务执行
            beatCheckTask = new ClientBeatCheckTaskV2(this);
            // 临时节点心跳检查：第一次执行5秒后，后面间隔5秒执行一次
            HealthCheckReactor.scheduleCheck(beatCheckTask);
        } else {
            // 创建持久化节点的心跳检查任务并加入定时任务执行
            healthCheckTaskV2 = new HealthCheckTaskV2(this);
            // 健康检查：第一次执行5秒后，后面间隔5秒执行一次
            HealthCheckReactor.scheduleCheck(healthCheckTaskV2);
        }
    }
    
    /**
     * Purely put instance into service without publish events.
     */
    public void putServiceInstance(Service service, InstancePublishInfo instance) {
        if (null == publishers.put(service, parseToHealthCheckInstance(instance))) {
            MetricsMonitor.incrementInstanceCount();
        }
    }
}
