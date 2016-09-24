package org.apdplat.service.impl;

import org.apdplat.service.api.SearchAPI;
import org.apdplat.service.configration.ConfManager;
import org.apdplat.service.configration.ConfTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by ysc on 7/5/16.
 */
public class SearchAPIImpl implements SearchAPI, Observer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchAPIImpl.class);

    private volatile int timeout = ConfTools.getInt("search.api.timeout.seconds", 1)*1000;

    private List<String> availableUrls = new CopyOnWriteArrayList<>();
    private List<String> unavailableUrls = new CopyOnWriteArrayList<>();
    private AtomicInteger totalCallTimes = new AtomicInteger();
    private Map<String, AtomicInteger> callSuccessHistories = new ConcurrentHashMap<>();
    private Map<String, AtomicInteger> callFailureHistories = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

    public SearchAPIImpl(){
        // 注册观察者
        ConfManager.addObserver(this);
        // 定时检查不可用服务是否已经恢复
        scheduledExecutorService.scheduleAtFixedRate(()->validUnavailableUrls(),
                ConfTools.getInt("unavailable.schedule.initial.delay.seconds", 60),
                ConfTools.getInt("unavailable.schedule.period.seconds", 60),
                TimeUnit.SECONDS);
        // 初始化服务
        init();
    }

    @Override
    public void close() {
        scheduledExecutorService.shutdownNow();
    }

    @Override
    public String getStatus() {
        StringBuilder status = new StringBuilder();
        status.append("超时时间: ").append(timeout).append(" 毫秒\n")
              .append("总调用次数: ").append(totalCallTimes.get()).append(" 次\n");
        if(!availableUrls.isEmpty()){
            status.append("可用搜索服务URL: \n");
            int i=1;
            for(String url : availableUrls){
                status.append("\t").append(i++).append(". ").append(url).append("\n");
            }
        }
        if(!unavailableUrls.isEmpty()) {
            status.append("不可用搜索服务URL: \n");
            int i = 1;
            for (String url : unavailableUrls) {
                status.append("\t").append(i++).append(". ").append(url).append("\n");
            }
        }
        if(!callSuccessHistories.isEmpty()){
            status.append("搜索服务URL调用成功次数统计: \n");
            int i=1;
            for(String url : callSuccessHistories.keySet()){
                status.append("\t").append(i++).append(". ").append(url).append(" -->  ").append(callSuccessHistories.get(url).get()).append("\n");
            }
        }
        if(!callFailureHistories.isEmpty()){
            status.append("搜索服务URL调用失败次数统计: \n");
            int i=1;
            for(String url : callFailureHistories.keySet()){
                status.append("\t").append(i++).append(". ").append(url).append(" -->  ").append(callFailureHistories.get(url).get()).append("\n");
            }
        }

        return status.toString();
    }

    public Map<String, AtomicInteger> getCallSuccessHistories(){
        return Collections.unmodifiableMap(callSuccessHistories);
    }

    public Map<String, AtomicInteger> getCallFailureHistories(){
        return Collections.unmodifiableMap(callFailureHistories);
    }

    public int getTimeout(){
        return timeout;
    }

    public int getTotalCallTimes(){
        return totalCallTimes.get();
    }

    public List<String> getAvailableUrls() {
        return Collections.unmodifiableList(availableUrls);
    }

    public List<String> getUnavailableUrls() {
        return Collections.unmodifiableList(unavailableUrls);
    }

    /**
     * 配置发生变化, 重新加载提供搜索服务的URL
     * @param o
     * @param arg
     */
    @Override
    public void update(Observable o, Object arg) {
        LOGGER.info("收到配置文件已经发生变化的通知");
        // 重新初始化
        init();
    }

    public void init() {
        timeout = ConfTools.getInt("search.api.timeout.seconds", 1)*1000;
        LOGGER.info("搜索服务调用超时时间: {} 秒", timeout);
        String urls = ConfTools.get("search.api.server.urls");
        if(urls == null){
            LOGGER.error("配置文件中没有指定提供搜索服务的URL");
            return;
        }
        availableUrls.clear();
        unavailableUrls.clear();
        for(String url : urls.split(",")){
            url = url.trim();
            LOGGER.info("增加提供搜索服务的URL: {}", url);
            if(!availableUrls.contains(url)) {
                availableUrls.add(url);
            }
        }
        if(!availableUrls.isEmpty()){
            LOGGER.info("提供搜索服务的URL({}):", availableUrls.size());
            int i=1;
            for(String url : availableUrls) {
                LOGGER.info("\t{}. {}", i++, url);
            }
        }
    }

    private void validUnavailableUrls(){
        for(String url : unavailableUrls){
            if(isAvailable(url)){
                urlAvailable(url);
            }
        }
        if(ConfTools.getBoolean("status.log.enabled", true)){
            LOGGER.info("\n{}", getStatus());
        }
    }

    private boolean isAvailable(String url){
        try{
            String result = get(url);
            if(result != null
                    && result.contains("id")
                    && result.contains("name")
                    && result.contains("type")){
                return true;
            }
        }catch (Throwable e){
            //
        }
        return false;
    }

    @Override
    public String search(String keyword, int topN) {
        if(availableUrls.isEmpty()){
            validUnavailableUrls();
        }
        while (!availableUrls.isEmpty()){
            String url = null;
            try{
                int index = totalCallTimes.incrementAndGet() % availableUrls.size();
                url = availableUrls.get(index);
                String result = get(url+"?kw="+keyword+"&topN="+topN);
                callSuccess(url);
                return result;
            }catch (Throwable ex){
                urlUnavailable(url);
                callFailure(url);
                LOGGER.error("异常信息: ", ex);
                LOGGER.error("调用搜索服务失败, keyword: {}, topN: {}, url: {}", keyword, topN, url);
            }
        }
        LOGGER.error("搜索服务没有可以使用的URL!");
        return "[]";
    }

    private void callSuccess(String url){
        callSuccessHistories.putIfAbsent(url, new AtomicInteger());
        callSuccessHistories.get(url).incrementAndGet();
    }
    private void callFailure(String url){
        callFailureHistories.putIfAbsent(url, new AtomicInteger());
        callFailureHistories.get(url).incrementAndGet();
    }
    private void urlUnavailable(String url){
        availableUrls.remove(url);
        if(!unavailableUrls.contains(url)) {
            unavailableUrls.add(url);
        }
    }
    private void urlAvailable(String url){
        if(!availableUrls.contains(url)) {
            availableUrls.add(url);
        }
        unavailableUrls.remove(url);
    }
    private String get(String url) throws Exception{
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        connection.setRequestMethod("GET");
        connection.setReadTimeout(timeout);
        connection.connect();

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();

        String line = null;
        while ((line = reader.readLine()) != null){
            response.append(line + "\n");
        }
        return response.toString();
    }
}
