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

package com.alibaba.nacos.naming.remote.udp;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.remote.PushCallBack;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.monitor.MetricsMonitor;
import com.alibaba.nacos.naming.push.v2.NoRequiredRetryException;
import com.alibaba.nacos.naming.constants.Constants;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Udp socket connector to send upd data and listen ack if necessary.
 *
 * @author xiweng.yy
 */
// UDP连接，负责发送udp数据和监听响应返回
@Component
public class UdpConnector {
    
    private final ConcurrentMap<String, AckEntry> ackMap;
    
    private final ConcurrentMap<String, PushCallBack> callbackMap;
    
    private final DatagramSocket udpSocket;
    
    private volatile boolean running = true;
    
    public UdpConnector() throws SocketException {
        this.ackMap = new ConcurrentHashMap<>();
        this.callbackMap = new ConcurrentHashMap<>();
        this.udpSocket = new DatagramSocket();
        GlobalExecutor.scheduleUdpReceiver(new UdpReceiver());
    }
    
    public void shutdown() {
        running = false;
    }
    
    public boolean containAck(String ackId) {
        return ackMap.containsKey(ackId);
    }
    
    /**
     * Sync send data once.
     *
     * @param ackEntry ack entry
     * @throws NacosException nacos exception during sending
     */
    public void sendData(AckEntry ackEntry) throws NacosException {
        // 直接使用UDP的DatagramSocket发送DatagramPacket数据报文
        if (null == ackEntry) {
            return;
        }
        try {
            MetricsMonitor.incrementPush();
            doSend(ackEntry.getOrigin());
        } catch (IOException e) {
            MetricsMonitor.incrementFailPush();
            throw new NacosException(NacosException.SERVER_ERROR, "[NACOS-PUSH] push data with exception: ", e);
        }
    }
    
    /**
     * Send Data with {@link PushCallBack}.
     *
     * @param ackEntry     ack entry
     * @param pushCallBack push callback
     */
    public void sendDataWithCallback(AckEntry ackEntry, PushCallBack pushCallBack) {
        // UdpAsyncSender线程，用于异步发送UDP数据，并在发生异常时执行回调处理，而且在UdpRetrySender线程里面，10s后再检测是否收到回应，在没有回应时，重新发送（默认重试1次）
        if (null == ackEntry) {
            return;
        }
        GlobalExecutor.scheduleUdpSender(new UdpAsyncSender(ackEntry, pushCallBack), 0L, TimeUnit.MILLISECONDS);
    }
    
    private void doSend(DatagramPacket packet) throws IOException {
        if (!udpSocket.isClosed()) {
            udpSocket.send(packet);
        }
    }
    
    private void callbackSuccess(String ackKey) {
        PushCallBack pushCallBack = callbackMap.remove(ackKey);
        if (null != pushCallBack) {
            pushCallBack.onSuccess();
        }
    }
    
    private void callbackFailed(String ackKey, Throwable exception) {
        PushCallBack pushCallBack = callbackMap.remove(ackKey);
        if (null != pushCallBack) {
            pushCallBack.onFail(exception);
        }
    }
    
    private class UdpAsyncSender implements Runnable {
        
        private final AckEntry ackEntry;
        
        private final PushCallBack callBack;
        
        public UdpAsyncSender(AckEntry ackEntry, PushCallBack callBack) {
            this.ackEntry = ackEntry;
            this.callBack = callBack;
        }
        
        @Override
        public void run() {
            try {
                // 存放pushCallback
                callbackMap.put(ackEntry.getKey(), callBack);
                // 存放回应ackEntry
                ackMap.put(ackEntry.getKey(), ackEntry);
                Loggers.PUSH.info("send udp packet: " + ackEntry.getKey());
                ackEntry.increaseRetryTime();
                // DatagramSocket
                doSend(ackEntry.getOrigin());
                // 默认18s之后执行UdpRetrySender（在没有收到回应时重试）
                GlobalExecutor.scheduleRetransmitter(new UdpRetrySender(ackEntry), Constants.ACK_TIMEOUT_NANOS,
                        TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                ackMap.remove(ackEntry.getKey());
                callbackMap.remove(ackEntry.getKey());
                // 异常执行回调函数
                callBack.onFail(e);
            }
        }
    }
    
    private class UdpRetrySender implements Runnable {
        
        private final AckEntry ackEntry;
        
        public UdpRetrySender(AckEntry ackEntry) {
            this.ackEntry = ackEntry;
        }
        
        @Override
        public void run() {
            // Received ack, no need to retry
            if (!containAck(ackEntry.getKey())) {
                return;
            }
            // Match max retry, push failed.
            // 默认1次
            if (ackEntry.getRetryTimes() > Constants.UDP_MAX_RETRY_TIMES) {
                Loggers.PUSH.warn("max re-push times reached, retry times {}, key: {}", ackEntry.getRetryTimes(),
                        ackEntry.getKey());
                ackMap.remove(ackEntry.getKey());
                callbackFailed(ackEntry.getKey(), new NoRequiredRetryException());
                return;
            }
            Loggers.PUSH.info("retry to push data, key: " + ackEntry.getKey());
            try {
                ackEntry.increaseRetryTime();
                doSend(ackEntry.getOrigin());
                GlobalExecutor.scheduleRetransmitter(this, Constants.ACK_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                callbackFailed(ackEntry.getKey(), e);
                ackMap.remove(ackEntry.getKey());
            }
        }
    }
    
    private class UdpReceiver implements Runnable {
        
        @Override
        public void run() {
            while (running) {
                byte[] buffer = new byte[1024 * 64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    udpSocket.receive(packet);
                    String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    AckPacket ackPacket = JacksonUtils.toObj(json, AckPacket.class);
                    InetSocketAddress socketAddress = (InetSocketAddress) packet.getSocketAddress();
                    String ip = socketAddress.getAddress().getHostAddress();
                    int port = socketAddress.getPort();
                    // 数据发送回应与服务器接收时间之差大于10s
                    if (System.nanoTime() - ackPacket.lastRefTime > Constants.ACK_TIMEOUT_NANOS) {
                        Loggers.PUSH.warn("ack takes too long from {} ack json: {}", packet.getSocketAddress(), json);
                    }
                    String ackKey = AckEntry.getAckKey(ip, port, ackPacket.lastRefTime);
                    AckEntry ackEntry = ackMap.remove(ackKey);
                    if (ackEntry == null) {
                        throw new IllegalStateException(
                                "unable to find ackEntry for key: " + ackKey + ", ack json: " + json);
                    }
                    // 请求成功后，执行callback，默认只有记录数据指标（支持扩展）
                    callbackSuccess(ackKey);
                } catch (Throwable e) {
                    Loggers.PUSH.error("[NACOS-PUSH] error while receiving ack data", e);
                }
            }
        }
        
    }
}
