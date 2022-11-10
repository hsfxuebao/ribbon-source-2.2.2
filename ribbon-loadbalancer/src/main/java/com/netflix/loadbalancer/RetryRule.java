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

/**
 * Given that
 * {@link IRule} can be cascaded, this {@link RetryRule} class allows adding a retry logic to an existing Rule.
 * 
 * @author stonse
 * 
 */
public class RetryRule extends AbstractLoadBalancerRule {
	// 使用的轮询的规则
	IRule subRule = new RoundRobinRule();
	// 重试时间500毫秒
	long maxRetryMillis = 500;

	public RetryRule() {
	}

	public RetryRule(IRule subRule) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	}

	public RetryRule(IRule subRule, long maxRetryMillis) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
		this.maxRetryMillis = (maxRetryMillis > 0) ? maxRetryMillis : 500;
	}

	public void setRule(IRule subRule) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	}

	public IRule getRule() {
		return subRule;
	}

	// 设置重试时间
	public void setMaxRetryMillis(long maxRetryMillis) {
		if (maxRetryMillis > 0) {
			this.maxRetryMillis = maxRetryMillis;
		} else {
			this.maxRetryMillis = 500;
		}
	}

	public long getMaxRetryMillis() {
		return maxRetryMillis;
	}

	
	
	@Override
	public void setLoadBalancer(ILoadBalancer lb) {		
		super.setLoadBalancer(lb);
		subRule.setLoadBalancer(lb);
	}

	/*
	 * Loop if necessary. Note that the time CAN be exceeded depending on the
	 * subRule, because we're not spawning additional threads and returning
	 * early.
	 */
	public Server choose(ILoadBalancer lb, Object key) {
		long requestTime = System.currentTimeMillis();
		//超时时间为 当前时间+500 ms
		long deadline = requestTime + maxRetryMillis;

		Server answer = null;
		//默认的策略是 RoundRobinRule
		answer = subRule.choose(key);

		//如果默认策略选出的服务器为空，或者该服务器状态为不存活并且当前时间还在超时时间内
		if (((answer == null) || (!answer.isAlive()))
				&& (System.currentTimeMillis() < deadline)) {

			InterruptTask task = new InterruptTask(deadline
					- System.currentTimeMillis());
			// 只要满足条件，一直重试
			while (!Thread.interrupted()) {
				answer = subRule.choose(key);

				if (((answer == null) || (!answer.isAlive()))
						&& (System.currentTimeMillis() < deadline)) {
					/* pause and retry hoping it's transient */
					Thread.yield();
				} else {
					break;
				}
			}

			task.cancel();
		}

		//如果在最大超时时间内仍未能选出可用的服务器那就返回空
		if ((answer == null) || (!answer.isAlive())) {
			return null;
		} else {
			return answer;
		}
	}

	@Override
	public Server choose(Object key) {
		return choose(getLoadBalancer(), key);
	}

	@Override
	public void initWithNiwsConfig(IClientConfig clientConfig) {
	}
}
