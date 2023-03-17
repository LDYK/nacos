package com.netflix.loadbalancer;


public class DynamicServerListLoadBalancer {
   ...
    //构造负载据衡器
    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing
            ping,ServerList<T> serverList, ServerListFilter<T> filter,
                                         ServerListUpdater serverListUpdater) {
        super(clientConfig, rule, ping);
        // 这个就是我们上面的NacosServerList对象
        // NacosServerList实现了ribbon的ServerList接口
        this.serverListImpl = serverList;
        //服务过滤器
        this.filter = filter;
        //服务更新器
        this.serverListUpdater = serverListUpdater;
        ...
        //其他部分初始化
        restOfInit(clientConfig);
    }
   ...

    void restOfInit(IClientConfig clientConfig) {
        boolean primeConnection = this.isEnablePrimingConnections();
        // turn this off to avoid duplicated asynchronous priming done in
        BaseLoadBalancer.setServerList()
        this.setEnablePrimingConnections(false);
        //该方法会启动定时任务执行更新
        enableAndInitLearnNewServersFeature();

        updateListOfServers();
        if (primeConnection && this.getPrimeConnections() != null) {
            this.getPrimeConnections()
                    .primeConnections(getReachableServers());
        }
        this.setEnablePrimingConnections(primeConnection);

    }

    //开始启动定时更新
    public void enableAndInitLearnNewServersFeature() {
        serverListUpdater.start(updateAction);
    }

    //30s 执行一次定时任务
    @Override
    public synchronized void start(final UpdateAction updateAction) {
        if (isActive.compareAndSet(false, true)) {
            final Runnable wrapperRunnable = new Runnable() {
                @Override
                public void run() {
                    //任务执行取消
                    if (!isActive.get()) {
                        if (scheduledFuture != null) {
                            scheduledFuture.cancel(true);
                        }
                        return;
                    }
                    try {
                        //执行更新
                        updateAction.doUpdate();
                        lastUpdated = System.currentTimeMillis();
                    }
                      ...
                }
            };

            scheduledFuture = getRefreshExecutor().scheduleWithFixedDelay(
                    wrapperRunnable,
                    initialDelayMs, // 默认 1s
                    refreshIntervalMs,// 默认 30s
                    TimeUnit.MILLISECONDS
            );
        }
         ...
    }

    //服务列表更新操作
    protected final ServerListUpdater.UpdateAction updateAction = new S
    erverListUpdater.UpdateAction() {
        @Override
        public void doUpdate() {
            updateListOfServers();
        }
    };
}