/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.client.loadbalancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for blocking client-side load balancing. 这个组件在META=INF/spring.factory中
 * 会在springboot 项目 启动的时候创建
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Will Tran
 * @author Gang Li
 * @author Olga Maciaszek-Sharma
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RestTemplate.class)
@ConditionalOnBean(LoadBalancerClient.class)
@EnableConfigurationProperties(LoadBalancerProperties.class)
public class LoadBalancerAutoConfiguration {

	@LoadBalanced
	@Autowired(required = false)
	private List<RestTemplate> restTemplates = Collections.emptyList();

	@Autowired(required = false)
	private List<LoadBalancerRequestTransformer> transformers = Collections.emptyList();

	/**
	 * 申明的这个bean实现了 SmartInitializingSingleton 这个接口
	 * 在SpringBean的生命周期中 当所有Bean都实例化完成之后调用
	 * 在这里会获得所有的RestTemplateCustomizer RestTemplate的定制器，
	 * 对容器中的所有的 RestTemplate进行定制 这里的定制器就是上面创建的定制器
	 * 是会对restTemplate增加负载均衡的拦截器添加进去
	 *
	 * @param restTemplateCustomizers
	 * @return
	 */
	@Bean
	public SmartInitializingSingleton loadBalancedRestTemplateInitializerDeprecated(
			final ObjectProvider<List<RestTemplateCustomizer>> restTemplateCustomizers) {

		//获取可用的定制器 对获取到的RestTemplate进行定制
		//这是一个匿名的类 这是不用 lambda之前的代码
//		List<RestTemplateCustomizer> available = restTemplateCustomizers.getIfAvailable();
//		new SmartInitializingSingleton() {
//			@Override
//			public void afterSingletonsInstantiated() {
//				for (RestTemplate restTemplate : LoadBalancerAutoConfiguration.this.restTemplates) {
//					for (RestTemplateCustomizer customizer :available) {
//						customizer.customize(restTemplate);
//					}
//				}
//			}
//		};
		return () -> restTemplateCustomizers.ifAvailable(customizers -> {
			for (RestTemplate restTemplate : LoadBalancerAutoConfiguration.this.restTemplates) {
				for (RestTemplateCustomizer customizer : customizers) {
					customizer.customize(restTemplate);
				}
			}
		});
	}

	@Bean
	@ConditionalOnMissingBean
	public LoadBalancerRequestFactory loadBalancerRequestFactory(
			LoadBalancerClient loadBalancerClient) {
		return new LoadBalancerRequestFactory(loadBalancerClient, this.transformers);
	}

	@Configuration(proxyBeanMethods = false)
	@Conditional(RetryMissingOrDisabledCondition.class)
	static class LoadBalancerInterceptorConfig {

		/**
		 * 增加负载均衡的拦截器组件 让下面的restTemplate的定制器组件使用
		 * @param loadBalancerClient
		 * @param requestFactory
		 * @return
		 */
		@Bean
		public LoadBalancerInterceptor loadBalancerInterceptor(
				LoadBalancerClient loadBalancerClient,
				LoadBalancerRequestFactory requestFactory) {
			return new LoadBalancerInterceptor(loadBalancerClient, requestFactory);
		}

		/**
		 * 增加一个RestTemplate的定制器组件
		 * @param loadBalancerInterceptor
		 * @return
		 */
		@Bean
		@ConditionalOnMissingBean
		public RestTemplateCustomizer restTemplateCustomizer(
				final LoadBalancerInterceptor loadBalancerInterceptor) {
//			//创建restTemplate的定制器
//				设置对restTemplate的定制方法（向restTemplate拦截器中添加上面创建的拦截器）
//			new RestTemplateCustomizer() {
//				@Override
//				public void customize(RestTemplate restTemplate) {
//					restTemplate.getInterceptors().add(loadBalancerInterceptor);
//				}
//			};
			/**
			 * lambda表达式一下没看懂 改成原来的方式写一下
			 * 就是将外部传入RestTemplate 增加一个负载均衡的拦截器
			 */
			return new RestTemplateCustomizer() {
				@Override
				public void customize(RestTemplate restTemplate) {
					List<ClientHttpRequestInterceptor> list = new ArrayList<>(
							restTemplate.getInterceptors());
					list.add(loadBalancerInterceptor);
					restTemplate.setInterceptors(list);
				}
			};
		}

	}

	private static class RetryMissingOrDisabledCondition extends AnyNestedCondition {

		RetryMissingOrDisabledCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnMissingClass("org.springframework.retry.support.RetryTemplate")
		static class RetryTemplateMissing {

		}

		@ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", havingValue = "false")
		static class RetryDisabled {

		}

	}

	/**
	 * Auto configuration for retry mechanism.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RetryTemplate.class)
	public static class RetryAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public LoadBalancedRetryFactory loadBalancedRetryFactory() {
			return new LoadBalancedRetryFactory() {
			};
		}

	}

	/**
	 * Auto configuration for retry intercepting mechanism.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RetryTemplate.class)
	@ConditionalOnBean(ReactiveLoadBalancer.Factory.class)
	@ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", matchIfMissing = true)
	public static class RetryInterceptorAutoConfiguration {

		/**
		 * lb的拦截器
		 * @param loadBalancerClient
		 * @param properties
		 * @param requestFactory
		 * @param loadBalancedRetryFactory
		 * @param loadBalancerFactory
		 * @return
		 */
		@Bean
		@ConditionalOnMissingBean
		public RetryLoadBalancerInterceptor loadBalancerInterceptor(
				LoadBalancerClient loadBalancerClient,
				LoadBalancerProperties properties, LoadBalancerRequestFactory requestFactory,
				LoadBalancedRetryFactory loadBalancedRetryFactory,
				ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) {
			return new RetryLoadBalancerInterceptor(loadBalancerClient, properties, requestFactory,
					loadBalancedRetryFactory, loadBalancerFactory);
		}

		/**
		 * RestTemplate的定制器
		 * @param loadBalancerInterceptor
		 * @return
		 */
		@Bean
		@ConditionalOnMissingBean
		public RestTemplateCustomizer restTemplateCustomizer(
				final RetryLoadBalancerInterceptor loadBalancerInterceptor) {
//			//创建restTemplate的定制器
//				设置对restTemplate的定制方法（向restTemplate拦截器中添加上面创建的拦截器）
//			new RestTemplateCustomizer() {
//				@Override
//				public void customize(RestTemplate restTemplate) {
//					restTemplate.getInterceptors().add(loadBalancerInterceptor);
//				}
//			};
			return restTemplate -> {
				List<ClientHttpRequestInterceptor> list = new ArrayList<>(
						restTemplate.getInterceptors());
				list.add(loadBalancerInterceptor);
				restTemplate.setInterceptors(list);
			};
		}

	}

}
