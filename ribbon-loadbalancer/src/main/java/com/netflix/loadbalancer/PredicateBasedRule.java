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

import com.google.common.base.Optional;

/**
 * A rule which delegates the server filtering logic to an instance of {@link AbstractServerPredicate}.
 * After filtering, a server is returned from filtered list in a round robin fashion.
 * 
 * 
 * @author awang
 *
 */
//抽象策略，继承自ClientConfigEnabledRoundRobinRule
//基于Predicate的策略
//Predicateshi Google Guava Collection工具对集合进行过滤的条件接口
public abstract class PredicateBasedRule extends ClientConfigEnabledRoundRobinRule {
   
    /**
     * Method that provides an instance of {@link AbstractServerPredicate} to be used by this class.
     * 
     */
    //定义了一个抽象函数来获取AbstractServerPredicate
    public abstract AbstractServerPredicate getPredicate();
        
    /**
     * Get a server by calling {@link AbstractServerPredicate#chooseRandomlyAfterFiltering(java.util.List, Object)}.
     * The performance for this method is O(n) where n is number of servers to be filtered.
     */
    @Override
    public Server choose(Object key) {
        ILoadBalancer lb = getLoadBalancer();
        //通过AbstractServerPredicate的chooseRoundRobinAfterFiltering函数来选出具体的服务实例
        //AbstractServerPredicate的子类实现的Predicate逻辑来过滤一部分服务实例
        //然后在以轮询的方式从过滤后的实例中选出一个
        Optional<Server> server = getPredicate().chooseRoundRobinAfterFiltering(lb.getAllServers(), key);
        if (server.isPresent()) {
            return server.get();
        } else {
            return null;
        }       
    }
}
