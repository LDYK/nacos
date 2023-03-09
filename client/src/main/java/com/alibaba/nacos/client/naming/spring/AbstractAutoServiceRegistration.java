package com.alibaba.nacos.client.naming.spring;

/**
 *

 调用链：spring.factory —> NacosServiceRegistryAutoConfiguration —> NacosAutoServiceRegistration —> AbstractAutoServiceRegistration —> NacosServiceRegistry —> NacosNamingService

 public class AbstractAutoServiceRegistration{

 //web服务器在初始化时唤醒该方法
    public void bind(WebServerInitializedEvent event) {
        ApplicationContext context = event.getApplicationContext();
        ...
        //如果当前的端口号是0就用服务启动的端口比如8080
        this.port.compareAndSet(0, event.getWebServer().getPort());
        //调用启动发放
        this.start();
    }


    public void start() {

        // 如果服务如果已注册过则跳过
        if (!this.running.get()) {

            //发布注册前事件
            this.context.publishEvent(
                    new InstancePreRegisteredEvent(this, getRegistration()));

            // 服务注册入口
            register();

            if (shouldRegisterManagement()) {
                registerManagement();
            }

            //发布注册后事件
            this.context.publishEvent(
                    new InstanceRegisteredEvent<>(this, getConfiguration()));

            //设置服务注册状态为已注册
            this.running.compareAndSet(false, true);
        }

    }

    // 服务注册接着调用NacosServiceRegistry 的注册方法
    protected void register() {
        this.serviceRegistry.register(getRegistration());
    }

 }

 */
