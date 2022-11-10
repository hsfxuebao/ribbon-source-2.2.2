/*
*
* Copyright 2013 Netflix, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/
package com.netflix.loadbalancer;

import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** 
 * Rule that use the average/percentile response times
 * to assign dynamic "weights" per Server which is then used in 
 * the "Weighted Round Robin" fashion. 
 * <p>
 * The basic idea for weighted round robin has been obtained from JCS
 * The implementation for choosing the endpoint from the list of endpoints
 * is as follows:Let's assume 4 endpoints:A(wt=10), B(wt=30), C(wt=40), 
 * D(wt=20). 
 * <p>
 * Using the Random API, generate a random number between 1 and10+30+40+20.
 * Let's assume that the above list is randomized. Based on the weights, we
 * have intervals as follows:
 * <p>
 * 1-----10 (A's weight)
 * <br>
 * 11----40 (A's weight + B's weight)
 * <br>
 * 41----80 (A's weight + B's weight + C's weight)
 * <br>
 * 81----100(A's weight + B's weight + C's weight + C's weight)
 * <p>
 * Here's the psuedo code for deciding where to send the request:
 * <p>
 * if (random_number between 1 &amp; 10) {send request to A;}
 * <br>
 * else if (random_number between 11 &amp; 40) {send request to B;}
 * <br>
 * else if (random_number between 41 &amp; 80) {send request to C;}
 * <br>
 * else if (random_number between 81 &amp; 100) {send request to D;}
 * <p>
 * When there is not enough statistics gathered for the servers, this rule
 * will fall back to use {@link RoundRobinRule}. 
 * @author stonse
 */
public class WeightedResponseTimeRule extends RoundRobinRule {

    public static final IClientConfigKey<Integer> WEIGHT_TASK_TIMER_INTERVAL_CONFIG_KEY = new IClientConfigKey<Integer>() {
        @Override
        public String key() {
            return "ServerWeightTaskTimerInterval";
        }
        
        @Override
        public String toString() {
            return key();
        }

        @Override
        public Class<Integer> type() {
            return Integer.class;
        }
    };
    
    public static final int DEFAULT_TIMER_INTERVAL = 30 * 1000;
    
    private int serverWeightTaskTimerInterval = DEFAULT_TIMER_INTERVAL;

    private static final Logger logger = LoggerFactory.getLogger(WeightedResponseTimeRule.class);
    
    // holds the accumulated weight from index 0 to current index
    // for example, element at index 2 holds the sum of weight of servers from 0 to 2
    private volatile List<Double> accumulatedWeights = new ArrayList<Double>();
    

    private final Random random = new Random();

    protected Timer serverWeightTimer = null;

    protected AtomicBoolean serverWeightAssignmentInProgress = new AtomicBoolean(false);

    String name = "unknown";

    public WeightedResponseTimeRule() {
        super();
    }

    public WeightedResponseTimeRule(ILoadBalancer lb) {
        super(lb);
    }
    
    @Override
    public void setLoadBalancer(ILoadBalancer lb) {
        super.setLoadBalancer(lb);
        if (lb instanceof BaseLoadBalancer) {
            name = ((BaseLoadBalancer) lb).getName();
        }
        initialize(lb);
    }

    void initialize(ILoadBalancer lb) {        
        if (serverWeightTimer != null) {
            serverWeightTimer.cancel();
        }
        serverWeightTimer = new Timer("NFLoadBalancer-serverWeightTimer-"
                + name, true);
        serverWeightTimer.schedule(new DynamicServerWeightTask(), 0,
                serverWeightTaskTimerInterval);
        // do a initial run
        ServerWeight sw = new ServerWeight();
        sw.maintainWeights();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                logger
                        .info("Stopping NFLoadBalancer-serverWeightTimer-"
                                + name);
                serverWeightTimer.cancel();
            }
        }));
    }

    public void shutdown() {
        if (serverWeightTimer != null) {
            logger.info("Stopping NFLoadBalancer-serverWeightTimer-" + name);
            serverWeightTimer.cancel();
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE")
    @Override
    public Server choose(ILoadBalancer lb, Object key) {
        if (lb == null) {
            return null;
        }
        Server server = null;

        while (server == null) {
            // get hold of the current reference in case it is changed from the other thread
            // accumulatedWeights 里面封装的是已经计算完毕权重的所有服务器，具体在 ServerWeight类中
            List<Double> currentWeights = accumulatedWeights;
            if (Thread.interrupted()) {
                return null;
            }
            List<Server> allList = lb.getAllServers();

            int serverCount = allList.size();

            if (serverCount == 0) {
                return null;
            }

            int serverIndex = 0;

            // last one in the list is the sum of all weights
            // 取出已经计算完权重的服务器列表中的最后一个权重,见下面解释，最后一个权重为 当前所有权重之和
            double maxTotalWeight = currentWeights.size() == 0 ? 0 : currentWeights.get(currentWeights.size() - 1); 
            // No server has been hit yet and total weight is not initialized
            // fallback to use round robin
            // 如果没有命中任何一个服务器或者是服务器列表权重还没有被初始化
            // 那么就使用 默认的 RoundRobinRule 算法重新进行选择
            if (maxTotalWeight < 0.001d) {
                server =  super.choose(getLoadBalancer(), key);
                if(server == null) {
                    return server;
                }
            } else {
                // generate a random weight between 0 (inclusive) to maxTotalWeight (exclusive)
                // 生成一个随机权重  0 <= randomWeight < maxTotalWeight
                double randomWeight = random.nextDouble() * maxTotalWeight;
                // pick the server index based on the randomIndex
                // 看当前的 randomWeight 在哪个区间，那么该区间对应的服务器即为被选中
                int n = 0;
                for (Double d : currentWeights) {
                    if (d >= randomWeight) {
                        serverIndex = n;
                        break;
                    } else {
                        n++;
                    }
                }

                server = allList.get(serverIndex);
            }

            if (server == null) {
                /* Transient. */
                Thread.yield();
                continue;
            }

            if (server.isAlive()) {
                return (server);
            }

            // Next.
            server = null;
        }
        return server;
    }

    class DynamicServerWeightTask extends TimerTask {
        public void run() {
            ServerWeight serverWeight = new ServerWeight();
            try {
                serverWeight.maintainWeights();
            } catch (Exception e) {
                logger.error("Error running DynamicServerWeightTask for {}", name, e);
            }
        }
    }

    //进行服务器的权重设置
    class ServerWeight {

        public void maintainWeights() {
            ILoadBalancer lb = getLoadBalancer();
            if (lb == null) {
                return;
            }
            
            if (!serverWeightAssignmentInProgress.compareAndSet(false,  true))  {
                return; 
            }
            
            try {
                logger.info("Weight adjusting job started");
                AbstractLoadBalancer nlb = (AbstractLoadBalancer) lb;
                //在AbstractLoadBalancer中维护了一个服务器列表，里面有当前服务器的统计信息
                LoadBalancerStats stats = nlb.getLoadBalancerStats();
                if (stats == null) {
                    // no statistics, nothing to do
                    // 如果没有统计信息，返回
                    return;
                }
                //循环所有服务器，将所有服务器的平均响应时间 相加
                double totalResponseTime = 0;
                // find maximal 95% response time
                for (Server server : nlb.getAllServers()) {
                    // this will automatically load the stats if not in cache
                    // 取出某个服务器的统计信息
                    ServerStats ss = stats.getSingleServerStat(server);
                    totalResponseTime += ss.getResponseTimeAvg();
                }
                // weight for each server is (sum of responseTime of all servers - responseTime)
                // so that the longer the response time, the less the weight and the less likely to be chosen
                // 计算权重的方式是： 权重 =  totalResponseTime - 该服务器的响应时间
                // 即响应时间越长的服务器，权重就会越小，所以被选择的机会就越小
                Double weightSoFar = 0.0;
                
                // create new list and hot swap the reference
                // 这个for循环就是按照上述方法来计算每个服务器的权重
                List<Double> finalWeights = new ArrayList<Double>();
                for (Server server : nlb.getAllServers()) {
                    ServerStats ss = stats.getSingleServerStat(server);
                    double weight = totalResponseTime - ss.getResponseTimeAvg();
                    //这里的值，相当于是一个区间段，起始是0.0，往后每一个数都比前面大当前的weight
                    //eg:0.0--5---10---15 ,那么最后一个数就是当前所有权重的总和
                    weightSoFar += weight;
                    finalWeights.add(weightSoFar);   
                }
                setWeights(finalWeights);
            } catch (Exception e) {
                logger.error("Error calculating server weights", e);
            } finally {
                serverWeightAssignmentInProgress.set(false);
            }

        }
    }

    void setWeights(List<Double> weights) {
        this.accumulatedWeights = weights;
    }
    
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        super.initWithNiwsConfig(clientConfig);
        serverWeightTaskTimerInterval = clientConfig.get(WEIGHT_TASK_TIMER_INTERVAL_CONFIG_KEY, DEFAULT_TIMER_INTERVAL);
    }

}
