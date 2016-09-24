### 保障服务的持续高可用、高性能及负载均衡

    高可用: 服务多副本
    高性能: 超时限制
    负载均衡: 环形队列
    
    已经实现的功能:
    1. HTTP调用方式的搜索服务
    2. REDIS访问

### 在WEB项目中的使用方法
    
    1. 编译依赖:
    
        git clone https://github.com/ysc/high-availability.git
        cd high-availability
        mvn install

    2. 在 pom.xml 中指定以下依赖:
    
        <dependency>
            <groupId>org.apdplat.service</groupId>
            <artifactId>high-availability</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
   
    3. 在 src/main/resources 目录下新建目录 conf, 然后在 conf 目录下新建文件 conf.txt, 加入如下配置项:
    
        #search api depends on these servers
        search.api.server.urls=http://192.168.0.100:8080/search.jsp, http://192.168.0.101:8080/search.jsp
        #timeout config to guarantee response time
        search.api.timeout.seconds=1
        #whether output status to log in every unavailable urls valid process
        status.log.enabled=true
        #unavailable schedule initial delay seconds
        unavailable.schedule.initial.delay.seconds=60
        #unavailable schedule period seconds
        unavailable.schedule.period.seconds=60
        #redis servers
        redis.servers=192.168.0.102:6379:b01cbe1209a545a7cdb, 192.168.0.103:6379:b01cbe1209a545a7cdb
                
        redis.pool.blockWhenExhausted=false
        redis.pool.jmxEnabled=false
        redis.pool.lifo=true
        redis.pool.maxIdle=500
        redis.pool.maxTotal=500
        redis.pool.maxWaitMillis=500
        redis.pool.minEvictableIdleTimeMillis=1800000
        redis.pool.minIdle=0
        redis.pool.numTestsPerEvictionRun=3
        redis.pool.softMinEvictableIdleTimeMillis=1800000
        redis.pool.testOnBorrow=true
        redis.pool.testWhileIdle=true
        redis.pool.timeBetweenEvictionRunsMillis=-1
        redis.pool.readTimeoutMillis=2000

    4. 在 web.xml 中配置一个监听器:
    
        <listener>
            <listener-class>org.apdplat.data.visualization.container.HighAvailabilityListener</listener-class>
        </listener>
        
    5. 监听器 HighAvailabilityListener.java 类的代码如下:
    
        public class HighAvailabilityListener  implements ServletContextListener {
            private static final Logger LOGGER = LoggerFactory.getLogger(HighAvailabilityListener.class);
        
            @Override
            public void contextInitialized(ServletContextEvent sce) {
                String conf = sce.getServletContext().getRealPath("/WEB-INF/classes/conf/");
                LOGGER.info("启动搜索服务, 监控配置目录: {}", conf);
                ConfWatcher.startWatch(conf);
                sce.getServletContext().setAttribute("SearchAPI", new SearchAPIImpl());
            }
        
            @Override
            public void contextDestroyed(ServletContextEvent sce) {
                LOGGER.info("停止搜索服务");
                SearchAPI searchAPI = (SearchAPI)sce.getServletContext().getAttribute("SearchAPI");
                if(searchAPI != null){
                    searchAPI.close();
                }
                LOGGER.info("停止监控配置目录");
                ConfWatcher.stopWatch();
            }
        }
        
    6. 在 jsp 中调用搜索服务:
    
        SearchAPI searchAPI = (SearchAPI) application.getAttribute("SearchAPI");
        if(searchAPI == null) {
            out.println("搜索服务未启动");
            return;
        }
        String keyword = request.getParameter("kw") == null ? "CCTV-1" : request.getParameter("kw");
        int topN = 5;
        try{
            topN = Integer.parseInt(request.getParameter("topN"));
        }catch (Exception e){
            //
        }
        String result = null;
        long start = System.currentTimeMillis();
        result = searchAPI.search(keyword, topN);
        String cost = Utils.getTimeDes(System.currentTimeMillis()-start);
        // 如果想知道搜索服务的状态
        String status = searchAPI.getStatus().replace("\n", "<br/>").replace("\t", "&nbsp; &nbsp; &nbsp; &nbsp; ");
    
    7. 获取REDIS连接:
        
        String DETECT_KEY = "redis_ha_detector";
        
        Jedis jedis = null;
        try{
            jedis = JedisAPI.getInstance().getJedis();
            // 如果所有REDIS服务都不可用, 则返回null
            if(jedis != null) {
                jedis.set(DETECT_KEY, String.valueOf(System.currentTimeMillis()));
                System.out.println(jedis.get(DETECT_KEY));
                System.out.println("REDIS服务状态:\n" + JedisAPI.getInstance().getStatus());
            }
        }finally {
            if(jedis != null){
                // 返回连接用完后必须要关闭, 调用close方法
                jedis.close();
            }
        }    
    
### 在非WEB项目中的使用方法

    1. 编译依赖:
    
        git clone https://github.com/ysc/high-availability.git
        cd high-availability
        mvn install

    2. 在 pom.xml 中指定以下依赖:
    
        <dependency>
            <groupId>org.apdplat.service</groupId>
            <artifactId>high-availability</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
   
    3. 新建目录 conf, 然后在 conf 目录下新建文件 conf.txt, 加入如下配置项:
        
        #search api depends on these servers
        search.api.server.urls=http://192.168.0.100:8080/search.jsp, http://192.168.0.101:8080/search.jsp
        #timeout config to guarantee response time
        search.api.timeout.seconds=1
        #whether output status to log in every unavailable urls valid process
        status.log.enabled=true
        #unavailable schedule initial delay seconds
        unavailable.schedule.initial.delay.seconds=60
        #unavailable schedule period seconds
        unavailable.schedule.period.seconds=60
        #redis servers
        redis.servers=192.168.0.102:6379:b01cbe1209a545a7cdb, 192.168.0.103:6379:b01cbe1209a545a7cdb
        
        redis.pool.blockWhenExhausted=false
        redis.pool.jmxEnabled=false
        redis.pool.lifo=true
        redis.pool.maxIdle=500
        redis.pool.maxTotal=500
        redis.pool.maxWaitMillis=500
        redis.pool.minEvictableIdleTimeMillis=1800000
        redis.pool.minIdle=0
        redis.pool.numTestsPerEvictionRun=3
        redis.pool.softMinEvictableIdleTimeMillis=1800000
        redis.pool.testOnBorrow=true
        redis.pool.testWhileIdle=true
        redis.pool.timeBetweenEvictionRunsMillis=-1
        redis.pool.readTimeoutMillis=2000

    4. 将conf目录加入classpath:
    
        java -cp conf:xxx-1.0-SNAPSHOT-jar-with-dependencies.jar 
        
    5. 启动搜索服务并监控配置目录的代码如下:
    
        Path conf = Paths.get(ConfWatcher.class.getResource("/conf/").getPath());
        LOGGER.info("启动搜索服务, 监控配置目录: {}", conf);
        ConfWatcher.startWatch(conf);
        SearchAPI searchAPI = new SearchAPIImpl();
        
    6. 调用搜索服务:
    
        String keyword = "CCTV-1";
        int topN = 5;
        
        String result = null;
        long start = System.currentTimeMillis();
        result = searchAPI.search(keyword, topN);
        String cost = Utils.getTimeDes(System.currentTimeMillis()-start);
        
        // 如果想知道搜索服务的状态
        String status = searchAPI.getStatus();
    
    7. 获取REDIS连接:
    
        String DETECT_KEY = "redis_ha_detector";
        
        Jedis jedis = null;
        try{
            jedis = JedisAPI.getInstance().getJedis();
            // 如果所有REDIS服务都不可用, 则返回null
            if(jedis != null) {
                jedis.set(DETECT_KEY, String.valueOf(System.currentTimeMillis()));
                System.out.println(jedis.get(DETECT_KEY));
                System.out.println("REDIS服务状态:\n" + JedisAPI.getInstance().getStatus());
            }
        }finally {
            if(jedis != null){
                // 返回连接用完后必须要关闭, 调用close方法
                jedis.close();
            }
        }

### 目录结构

    .
    ├── README.md
    ├── pom.xml
    └── src
        └── main
            ├── java
            │   └── org
            │       └── apdplat
            │           └── service
            │               ├── api
            │               │   ├── JedisAPI.java
            │               │   └── SearchAPI.java
            │               ├── configration
            │               │   ├── ConfManager.java
            │               │   ├── ConfTools.java
            │               │   └── ConfWatcher.java
            │               ├── impl
            │               │   └── SearchAPIImpl.java
            │               └── utils
            │                   └── TimeUtils.java
            └── resources
                └── conf
                    ├── conf.production.txt
                    └── conf.txt
    
    12 directories, 11 files
