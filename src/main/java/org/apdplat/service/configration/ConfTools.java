package org.apdplat.service.configration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配置工具
 *
 * 用法说明:
 *         在类路径下面的 conf.txt 文件中指定本机调试开发环境使用的值
 *         在类路径下面的 conf.production.txt 文件中指定生产环境中使用的值, 这个值会覆盖conf.txt中的同KEY的值, 开发阶段, 这个文件为空位注释掉指定的值
 *         可以用系统属性覆盖以上两个文件的配置
 *         以 # 号开始的行为注释
 *         K 和 V 之间用 = 号连接
 * Created by ysc on 7/5/16.
 */
public class ConfTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfTools.class);

    private static final Map<String, String> CONF = new HashMap<>();

    public static void set(String key, String value){
        CONF.put(key, value);
    }
    public static boolean getBoolean(String key, boolean defaultValue){
        String value = CONF.get(key) == null ? Boolean.valueOf(defaultValue).toString() : CONF.get(key);
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug("获取配置项：" + key + "=" + value);
        }
        return value.contains("true");
    }
    public static boolean getBoolean(String key){
        return getBoolean(key, false);
    }
    public static int getInt(String key, int defaultValue){
        int value = CONF.get(key) == null ? defaultValue : Integer.parseInt(CONF.get(key).trim());
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug("获取配置项：" + key + "=" + value);
        }
        return value;
    }
    public static int getInt(String key){
        return getInt(key, -1);
    }
    public static String get(String key, String defaultValue){
        String value = CONF.get(key) == null ? defaultValue : CONF.get(key);
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug("获取配置项：" + key + "=" + value);
        }
        return value;
    }
    public static String get(String key){
        String value = CONF.get(key);
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug("获取配置项：" + key + "=" + value);
        }
        return value;
    }
    static{
        reload();
    }
    /**
     * 重新加载配置文件
     */
    public static void reload(){
        CONF.clear();
        LOGGER.info("开始加载配置文件");
        long start = System.currentTimeMillis();
        loadConf("conf/conf.txt");
        loadConf("conf/conf.production.txt");
        checkSystemProperties();
        long cost = System.currentTimeMillis() - start;
        LOGGER.info("配置文件加载完毕，耗时" + cost + " 毫秒，配置项数目：" + CONF.size());
        LOGGER.info("配置信息：");
        AtomicInteger i = new AtomicInteger();
        for(String key : CONF.keySet()){
            LOGGER.info(i.incrementAndGet() + "、" + key + "=" + CONF.get(key));
        }
    }
    /**
     * 强制覆盖默认配置
     * @param confFile 配置文件
     */
    public static void forceOverride(File confFile) {
        try(InputStream in = new FileInputStream(confFile)){
            LOGGER.info("使用配置文件 {} 强制覆盖默认配置", confFile.getAbsolutePath());
            loadConf(in);
        } catch (Exception ex) {
            LOGGER.error("强制覆盖默认配置失败：", ex);
        }
        int i=1;
        for(String key : CONF.keySet()){
            LOGGER.info((i++)+"、"+key+"="+ CONF.get(key));
        }
    }
    /**
     * 强制覆盖默认配置
     * @param confFile 配置文件路径
     */
    public static void forceOverride(String confFile) {
        File file = new File(confFile);
        try(InputStream in = new FileInputStream(file)){
            LOGGER.info("使用配置文件 {} 强制覆盖默认配置", file.getAbsolutePath());
            loadConf(in);
        } catch (Exception ex) {
            LOGGER.error("强制覆盖默认配置失败：", ex);
        }
        int i=1;
        for(String key : CONF.keySet()){
            LOGGER.info((i++)+"、"+key+"="+ CONF.get(key));
        }
    }
    /**
     * 加载配置文件
     * @param confFile 类路径下的配置文件
     */
    private static void loadConf(String confFile) {
        InputStream in = ConfTools.class.getClassLoader().getResourceAsStream(confFile);
        if(in == null){
            LOGGER.info("未找到配置文件："+confFile);
            return;
        }
        LOGGER.info("加载配置文件："+confFile);
        loadConf(in);
    }
    /**
     * 加载配置文件
     * @param in 文件输入流
     */
    private static void loadConf(InputStream in) {
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(in, "utf-8"))){
            String line;
            while((line = reader.readLine()) != null){
                line = line.trim();
                if("".equals(line) || line.startsWith("#")){
                    continue;
                }
                int index = line.indexOf("=");
                if(index==-1){
                    LOGGER.error("错误的配置："+line);
                    continue;
                }
                //有K V
                if(index>0 && line.length()>index+1) {
                    String key = line.substring(0, index).trim();
                    String value = line.substring(index + 1, line.length()).trim();
                    CONF.put(key, value);
                }
                //有K无V
                else if(index>0 && line.length()==index+1) {
                    String key = line.substring(0, index).trim();
                    CONF.put(key, "");
                }else{
                    LOGGER.error("错误的配置："+line);
                }
            }
        } catch (IOException ex) {
            System.err.println("配置文件加载失败:"+ex.getMessage());
            throw new RuntimeException(ex);
        }
    }
    /**
     * 使用系统属性覆盖配置文件
     */
    private static void checkSystemProperties() {
        for(String key : CONF.keySet()){
            String value = System.getProperty(key);
            if(value != null){
                CONF.put(key, value);
                LOGGER.info("系统属性覆盖默认配置："+key+"="+value);
            }
        }
    }
    public static void main(String[] args){
        System.out.println(get("search.api.server.ips"));
    }
}