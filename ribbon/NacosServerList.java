/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.nacos.ribbon;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.AbstractServerList;

/**
 * 该类负责加载serverId 下的实例列表
 * AbstractServerList实现了ribbon的ServerList接口
 * NacosServer实现了ribbon的Server接口
 */
public class NacosServerList extends AbstractServerList<NacosServer> {

    private NacosDiscoveryProperties discoveryProperties;

    private String serviceId;

    public NacosServerList(NacosDiscoveryProperties discoveryProperties) {
        this.discoveryProperties = discoveryProperties;
    }

    //查询服务列表
    @Override
    public List<NacosServer> getInitialListOfServers() {
        return getServers();
    }

    //查询服务列表
    @Override
    public List<NacosServer> getUpdatedListOfServers() {
        return getServers();
    }

    //调用NacosNamingService查询服务实例列表
    private List<NacosServer> getServers() {
        try {
            String group = discoveryProperties.getGroup();
            List<Instance> instances = discoveryProperties.namingServiceInstance()
                    .selectInstances(serviceId, group, true);
            //把Instantce 包装成loadbalancer 能识别的Server对象
            return instancesToServerList(instances);
        }
        catch (Exception e) {
            throw new IllegalStateException(
                    "Can not get service instances from nacos, serviceId=" + serviceId,
                    e);
        }
    }

    //把Instantce 包装成loadbalancer 能识别的Server对象
    private List<NacosServer> instancesToServerList(List<Instance> instances) {
        List<NacosServer> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(instances)) {
            return result;
        }
        for (Instance instance : instances) {
            result.add(new NacosServer(instance));
        }

        return result;
    }

    public String getServiceId() {
        return serviceId;
    }

    @Override
    public void initWithNiwsConfig(IClientConfig iClientConfig) {
        //从clientConfig 中获取当前服务的id
        this.serviceId = iClientConfig.getClientName();
    }

}
