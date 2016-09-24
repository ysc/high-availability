package org.apdplat.service.api;

import org.apdplat.service.configration.ConfManager;
import org.apdplat.service.configration.ConfTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by ysc on 7/6/16.
 */
public class JedisAPI  implements Observer {
    private static final Logger LOGGER = LoggerFactory.getLogger(JedisAPI.class);

    private List<JedisPool> availablePools = new CopyOnWriteArrayList<>();
    private List<JedisPool> unavailablePools = new CopyOnWriteArrayList<>();
    private AtomicInteger totalCallTimes = new AtomicInteger();
    private Map<String, AtomicInteger> callSuccessHistories = new ConcurrentHashMap<>();
    private Map<String, AtomicInteger> callFailureHistories = new ConcurrentHashMap<>();
    private Map<String, String> poolToServer = new ConcurrentHashMap<>();
    private static final String DETECT_KEY = "redis_ha_detector";

    private ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

    private JedisAPI() {
        // 注册观察者
        ConfManager.addObserver(this);
        // 定时检查不可用服务是否已经恢复
        scheduledExecutorService.scheduleAtFixedRate(()->validUnavailablePools(),
                ConfTools.getInt("unavailable.urls.schedule.initial.delay.seconds", 60),
                ConfTools.getInt("unavailable.urls.schedule.period.seconds", 60),
                TimeUnit.SECONDS);
        // 初始化服务
        init(ConfTools.get("redis.servers"));
    }

    private void init(String servers){
        if(servers == null || "".equals(servers.trim())){
            LOGGER.error("配置文件中没有指定REDIS服务地址");
            return;
        }
        try {
            availablePools.forEach(jedisPool -> jedisPool.close());
            availablePools.clear();
            unavailablePools.forEach(jedisPool -> jedisPool.close());
            unavailablePools.clear();
            poolToServer.clear();
        }catch (Throwable e){
            LOGGER.error("清理REDIS连接池失败", e);
        }
        for(String server : servers.split(",")){
            String[] attr = server.trim().split(":");
            JedisPool pool = initPool(attr[0].trim(), Integer.parseInt(attr[1].trim()), attr[2].trim());
            if(pool != null){
                poolToServer.put(pool.toString(), attr[0]+":"+attr[1]);
            }
        }
    }

    /**
     * 配置发生变化, 重新初始化服务
     * @param o
     * @param arg
     */
    @Override
    public void update(Observable o, Object arg) {
        LOGGER.info("收到配置文件已经发生变化的通知");
        init(ConfTools.get("redis.servers"));
    }

    public void close() {
        scheduledExecutorService.shutdownNow();
    }

    public String getStatus() {
        StringBuilder status = new StringBuilder();
        status.append("总获取REDIS连接次数: ").append(totalCallTimes.get()).append(" 次\n");
        if(!availablePools.isEmpty()){
            status.append("可用REDIS连接池: \n");
            int i=1;
            for(JedisPool pool : availablePools){
                status.append("\t").append(i++).append(". ").append(poolToServer.get(pool.toString()))
                        .append("\t")
                        .append("getMaxBorrowWaitTimeMillis: ").append(pool.getMaxBorrowWaitTimeMillis()).append(",\t")
                        .append("MeanBorrowWaitTimeMillis: ").append(pool.getMeanBorrowWaitTimeMillis()).append(",\t")
                        .append("NumActive: ").append(pool.getNumActive()).append(",\t")
                        .append("NumIdle: ").append(pool.getNumIdle()).append(",\t")
                        .append("NumWaiters: ").append(pool.getNumWaiters())
                        .append("\n");
            }
        }
        if(!unavailablePools.isEmpty()) {
            status.append("不可用REDIS连接池: \n");
            int i = 1;
            for (JedisPool pool : unavailablePools) {
                status.append("\t").append(i++).append(". ").append(poolToServer.get(pool.toString()))
                        .append("\t")
                        .append("getMaxBorrowWaitTimeMillis: ").append(pool.getMaxBorrowWaitTimeMillis()).append(",\t")
                        .append("MeanBorrowWaitTimeMillis: ").append(pool.getMeanBorrowWaitTimeMillis()).append(",\t")
                        .append("NumActive: ").append(pool.getNumActive()).append(",\t")
                        .append("NumIdle: ").append(pool.getNumIdle()).append(",\t")
                        .append("NumWaiters: ").append(pool.getNumWaiters())
                        .append("\n");
            }
        }
        if(!callSuccessHistories.isEmpty()){
            status.append("REDIS连接获取成功次数统计: \n");
            int i=1;
            for(String server : callSuccessHistories.keySet()){
                status.append("\t").append(i++).append(". ").append(server).append(" -->  ").append(callSuccessHistories.get(server).get()).append("\n");
            }
        }
        if(!callFailureHistories.isEmpty()){
            status.append("REDIS连接获取失败次数统计: \n");
            int i=1;
            for(String server : callFailureHistories.keySet()){
                status.append("\t").append(i++).append(". ").append(server).append(" -->  ").append(callFailureHistories.get(server).get()).append("\n");
            }
        }

        return status.toString();
    }

    /**
     * 如果所有REDIS服务都不可用, 则返回null
     * 返回连接用完后必须要关闭, 调用close方法
     * @return
     */
    public Jedis getJedis() {
        if(availablePools.isEmpty()){
            validUnavailablePools();
        }
        while (!availablePools.isEmpty()){
            JedisPool pool = null;
            try{
                int index = totalCallTimes.incrementAndGet() % availablePools.size();
                pool = availablePools.get(index);
                Jedis jedis = pool.getResource();
                callSuccess(pool);
                return jedis;
            }catch (Throwable ex){
                poolUnavailable(pool);
                callFailure(pool);
                LOGGER.error("获取REDIS连接出错: ", ex);
            }
        }
        LOGGER.error("没有可用的REDIS连接!");
        return null;
    }

    /**
     * 当getInstance方法第一次被调用的时候，它第一次读取
     * JedisAPIHolder.INSTANCE，导致JedisAPIHolder类得到初始化；而这个类在装载并被初始化的时候，会初始化它的静
     * 态域，从而创建JedisAPI的实例，由于是静态的域，因此只会在虚拟机装载类的时候初始化一次，并由虚拟机来保证它的线程安全性。
     * 这个模式的优势在于，getInstance方法并没有被同步，并且只是执行一个域的访问，因此延迟初始化并没有增加任何访问成本。
     */
    public static JedisAPI getInstance() {
        return JedisAPIHolder.INSTANCE;
    }

    /**
     * 类级的内部类，也就是静态的成员式内部类，该内部类的实例与外部类的实例 没有绑定关系，而且只有被调用到时才会装载，从而实现了延迟加载。
     */
    private static class JedisAPIHolder {
        /**
         * 静态初始化器，由JVM来保证线程安全
         */
        private static final JedisAPI INSTANCE = new JedisAPI();
    }

    private void callSuccess(JedisPool pool){
        callOnce(callSuccessHistories, pool);
    }

    private void callFailure(JedisPool pool){
        callOnce(callFailureHistories, pool);
    }

    private void callOnce(Map<String, AtomicInteger> histories, JedisPool pool){
        String server = poolToServer.get(pool.toString());
        if(server == null){
            return;
        }
        histories.putIfAbsent(server, new AtomicInteger());
        histories.get(server).incrementAndGet();
    }

    private void validUnavailablePools(){
        for(JedisPool pool : unavailablePools){
            if(isAvailable(pool)){
                poolAvailable(pool);
            }
        }
        if(ConfTools.getBoolean("status.log.enabled", true)){
            LOGGER.info("\n{}", getStatus());
        }
    }

    private boolean isAvailable(JedisPool pool){
        try{
            Jedis jedis = pool.getResource();
            String value = String.valueOf(System.currentTimeMillis());
            jedis.set(DETECT_KEY, value);
            String actualValue = jedis.get(DETECT_KEY);
            jedis.close();
            if(value.equals(actualValue)) {
                return true;
            }
        }catch (Throwable e){
            //
        }
        return false;
    }

    private void poolUnavailable(JedisPool pool){
        availablePools.remove(pool);
        if(!unavailablePools.contains(pool)) {
            unavailablePools.add(pool);
        }
    }

    private void poolAvailable(JedisPool pool){
        if(!availablePools.contains(pool)) {
            availablePools.add(pool);
        }
        unavailablePools.remove(pool);
    }

    private JedisPool initPool(String ip, int port, String password){
        JedisPoolConfig config = new JedisPoolConfig();
        // 连接耗尽时是否阻塞, false报异常, ture阻塞直到超时, 默认true
        config.setBlockWhenExhausted(ConfTools.getBoolean("redis.pool.blockWhenExhausted", false));

        // 设置的逐出策略类名, 默认DefaultEvictionPolicy(当连接超过最大空闲时间,或连接数超过最大空闲连接数)
        config.setEvictionPolicyClassName("org.apache.commons.pool2.impl.DefaultEvictionPolicy");

        // 是否启用pool的jmx管理功能
        config.setJmxEnabled(ConfTools.getBoolean("redis.pool.jmxEnabled", false));

        // 是否启用后进先出
        config.setLifo(ConfTools.getBoolean("redis.pool.lifo", true));

        // 最大空闲连接数
        config.setMaxIdle(ConfTools.getInt("redis.pool.maxIdle", 8));

        // 最大连接数
        config.setMaxTotal(ConfTools.getInt("redis.pool.maxTotal", 8));

        // 获取连接时的最大等待毫秒数(如果设置为阻塞时BlockWhenExhausted), 如果超时就抛异常, 小于零: 阻塞不确定的时间
        config.setMaxWaitMillis(ConfTools.getInt("redis.pool.maxWaitMillis", 500));

        // 逐出连接的最小空闲时间 默认1800 000 毫秒(30分钟)
        config.setMinEvictableIdleTimeMillis(ConfTools.getInt("redis.pool.minEvictableIdleTimeMillis", 1800000));

        // 最小空闲连接数
        config.setMinIdle(ConfTools.getInt("redis.pool.minIdle", 0));

        // 每次逐出检查时 逐出的最大数目 如果为负数就是 : 1/abs(n), 默认3
        config.setNumTestsPerEvictionRun(ConfTools.getInt("redis.pool.numTestsPerEvictionRun", 3));

        // 对象空闲多久后逐出, 当空闲时间>该值 且 空闲连接>最大空闲数
        // 时直接逐出,不再根据MinEvictableIdleTimeMillis判断 (默认逐出策略)
        config.setSoftMinEvictableIdleTimeMillis(ConfTools.getInt("redis.pool.softMinEvictableIdleTimeMillis", 1800000));

        // 在获取连接的时候检查有效性
        config.setTestOnBorrow(ConfTools.getBoolean("redis.pool.testOnBorrow", true));

        // 在空闲时检查有效性
        config.setTestWhileIdle(ConfTools.getBoolean("redis.pool.testWhileIdle", true));

        // 逐出扫描的时间间隔(毫秒) 如果为负数, 则不运行逐出线程
        config.setTimeBetweenEvictionRunsMillis(ConfTools.getInt("redis.pool.timeBetweenEvictionRunsMillis", -1));
        try {
            /**
             * 如果你遇到 java.net.SocketTimeoutException: Read timed out
             * exception的异常信息 请尝试在构造JedisPool的时候设置自己的超时值.
             * JedisPool默认的超时时间是2秒(单位毫秒)
             */
            JedisPool pool = new JedisPool(config, ip, port, ConfTools.getInt("redis.pool.readTimeoutMillis", 2000), password);
            if(!availablePools.contains(pool)) {
                availablePools.add(pool);
            }
            return pool;
        } catch (Exception e) {
            LOGGER.error("构造REDIS连接池失败", e);
        }
        return null;
    }

    public static void main(String[] args) throws Exception{
        while(true){
            Jedis jedis = null;
            try{
                jedis = JedisAPI.getInstance().getJedis();
                if(jedis != null) {
                    jedis.set(DETECT_KEY, String.valueOf(System.currentTimeMillis()));
                    System.out.println(jedis.get(DETECT_KEY));
                    System.out.println("REDIS服务状态:\n" + JedisAPI.getInstance().getStatus());
                }
            }finally {
                if(jedis != null){
                    jedis.close();
                }
            }
            Thread.sleep(1000);
        }
    }
}
