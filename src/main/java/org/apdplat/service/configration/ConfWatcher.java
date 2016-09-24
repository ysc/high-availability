package org.apdplat.service.configration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by ysc on 7/5/16.
 */
public class ConfWatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfWatcher.class);

    private static WatchService watchService = null;
    private static Map<WatchKey, Path> directories = null;
    private static ExecutorService EXECUTOR_SERVICE = null;
    /**
     * 开始配置文件监控（启动一个新的线程）
     */
    public static void startWatch(String conf) {
        startWatch(Paths.get(conf));
    }
    public static void startWatch(Path conf) {
        if(watchService != null || directories != null || EXECUTOR_SERVICE != null){
            LOGGER.info("配置文件监控服务已被启用, 不需要重复启动, 忽略本次请求");
            return;
        }
        LOGGER.info("启用配置文件监控服务");
        try {
            watchService = FileSystems.getDefault().newWatchService();
            directories = new HashMap<>();
            EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
            registerConf(conf);
            EXECUTOR_SERVICE.submit(()->watch(conf));
        } catch (IOException ex) {
            LOGGER.error("监控配置文件失败", ex);
            return;
        }
        LOGGER.info("监控配置文件成功");
    }
    /**
     * 停止配置文件监控
     */
    public static void stopWatch(){
        if(watchService != null){
            try {
                watchService.close();
            } catch (IOException e) {
                LOGGER.error("关闭监控服务出错", e);
            }
            watchService = null;
        }
        if(EXECUTOR_SERVICE != null) {
            EXECUTOR_SERVICE.shutdownNow();
            EXECUTOR_SERVICE = null;
        }
        if(directories != null){
            directories.clear();
            directories = null;
        }
    }
    private static void watch(Path conf){
        try {
            while (true) {
                final WatchKey key = watchService.take();
                if(key == null){
                    continue;
                }
                for (WatchEvent<?> watchEvent : key.pollEvents()) {
                    final WatchEvent.Kind<?> kind = watchEvent.kind();
                    //忽略无效事件
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    final WatchEvent<Path> watchEventPath = (WatchEvent<Path>) watchEvent;
                    //path是相对路径（相对于监控目录）
                    final Path contextPath = watchEventPath.context();
                    LOGGER.debug("contextPath: {}", contextPath);
                    //获取监控文件
                    final Path directoryPath = directories.get(key);
                    LOGGER.debug("directoryPath: {}", directoryPath);
                    //得到绝对路径
                    final Path absolutePath = directoryPath.resolve(contextPath);
                    LOGGER.debug("absolutePath: {}", absolutePath);
                    LOGGER.debug("kind: {}", kind);
                    //判断事件类别
                    switch (kind.name()) {
                        case "ENTRY_MODIFY":
                            LOGGER.info("配置文件发生变化：{}", absolutePath);
                            if(absolutePath.toString().endsWith("conf.txt")){
                                // 加载conf.txt
                                ConfTools.forceOverride(absolutePath.toFile());
                                // 加载conf.production.txt
                                ConfTools.forceOverride(absolutePath.toString().replace("conf.txt", "conf.production.txt"));
                                // 通知观察者
                                LOGGER.info("通知观察者配置文件已经发生变化");
                                ConfManager.notifyObservers();
                            }
                            if(absolutePath.toString().endsWith("conf.production.txt")){
                                // 加载conf.production.txt
                                ConfTools.forceOverride(absolutePath.toFile());
                                // 通知观察者
                                LOGGER.info("通知观察者配置文件已经发生变化");
                                ConfManager.notifyObservers();
                            }
                            break;
                    }
                }
                boolean valid = key.reset();
                if (!valid) {
                    LOGGER.info("停止监控配置文件："+directories.get(key));
                    directories.remove(key);
                    if (directories.isEmpty()) {
                        LOGGER.error("退出监控");
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.info("监控配置文件线程退出");
        } finally{
            startWatch(conf);
        }
    }
    private static void registerConf(Path path) throws IOException {
        LOGGER.debug("开始监控配置文件:" + path);
        WatchKey key = path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        directories.put(key, path);
    }
    public static void main(String[] args) {
        Path conf = Paths.get(ConfWatcher.class.getResource("/conf/").getPath());
        ConfWatcher.startWatch(conf);
    }
}
