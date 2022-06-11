package org.apache.eventmesh.webhook.admin;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.utils.MD5Utils;
import com.alibaba.nacos.shaded.io.grpc.netty.shaded.io.netty.util.internal.StringUtil;
import org.apache.eventmesh.common.utils.JsonUtils;
import org.apache.eventmesh.webhook.api.ManufacturerObject;
import org.apache.eventmesh.webhook.api.WebHookConfig;
import org.apache.eventmesh.webhook.api.WebHookConfigOperation;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.eventmesh.webhook.api.WebHookOperationConstant.*;

public class NacosWebHookConfigOperation implements WebHookConfigOperation{

	private static final Logger logger = LoggerFactory.getLogger(NacosWebHookConfigOperation.class);



	private ConfigService configService;

	public NacosWebHookConfigOperation(Properties properties) throws NacosException {
		configService = ConfigFactory.createConfigService(properties);

		String manufacturers= configService.getConfig(MANUFACTURERS_DATA_ID, "webhook", TIMEOUT_MS);
		if (manufacturers == null) {
			configService.publishConfig(MANUFACTURERS_DATA_ID, "webhook", JsonUtils.serialize(new ManufacturerObject()), ConfigType.JSON.getType());
		}

	}

	@Override
	public Integer insertWebHookConfig(WebHookConfig webHookConfig) {
		Boolean result = false;
		String manufacturerName = webHookConfig.getManufacturerName();
		try {
			// 判断配置是否已存在
			if (configService.getConfig(getWebHookConfigDataId(webHookConfig), getManuGroupId(webHookConfig), TIMEOUT_MS) != null) {
				logger.error("insertWebHookConfig failed, config has existed");
				return 0;
			}
			// 厂商名作为groupId
			result = configService.publishConfig(getWebHookConfigDataId(webHookConfig), getManuGroupId(webHookConfig), JsonUtils.serialize(webHookConfig), ConfigType.JSON.getType());
		} catch (NacosException e) {
			logger.error("insertWebHookConfig failed", e);
			return 0;
		}
		if (result) {
			//更新集合
			try {
				ManufacturerObject manufacturerObject = getManufacturersInfo();
				manufacturerObject.addManufacturer(manufacturerName);
				manufacturerObject.getManufacturerEvents(manufacturerName).add(getWebHookConfigDataId(webHookConfig));
				configService.publishConfig(MANUFACTURERS_DATA_ID, "webhook", JsonUtils.serialize(manufacturerObject), ConfigType.JSON.getType());
			} catch (NacosException e) {
				logger.error("update manufacturersInfo error", e);
			}
		}
		return result ? 1 : 0;
	}

	@Override
	public Integer updateWebHookConfig(WebHookConfig webHookConfig) {
		Boolean result = false;
		try {
			// 判断配置是否存在
			if (configService.getConfig(getWebHookConfigDataId(webHookConfig), getManuGroupId(webHookConfig), TIMEOUT_MS) == null) {
				logger.error("updateWebHookConfig failed, config is not existed");
				return 0;
			}
			result = configService.publishConfig(getWebHookConfigDataId(webHookConfig), getManuGroupId(webHookConfig), JsonUtils.serialize(webHookConfig), ConfigType.JSON.getType());
		} catch (NacosException e) {
			logger.error("updateWebHookConfig failed", e);
		}
		return result ? 1 : 0;
	}

	@Override
	public Integer deleteWebHookConfig(WebHookConfig webHookConfig) {
		Boolean result = false;
		String manufacturerName = webHookConfig.getManufacturerName();
		try {
			// 厂商名作为groupId，厂商事件名作为dataId
			result = configService.removeConfig(getWebHookConfigDataId(webHookConfig), getManuGroupId(webHookConfig));
		} catch (NacosException e) {
			logger.error("deleteWebHookConfig failed", e);
		}
		if (result) {
			//更新集合
			try {
				ManufacturerObject manufacturerObject = getManufacturersInfo();
				manufacturerObject.getManufacturerEvents(manufacturerName).remove(getWebHookConfigDataId(webHookConfig));
				configService.publishConfig(MANUFACTURERS_DATA_ID, "webhook", JsonUtils.serialize(manufacturerObject), ConfigType.JSON.getType());
			} catch (NacosException e) {
				logger.error("update manufacturersInfo error", e);
			}
		}
		return result ? 1 : 0;
	}

	@Override
	public WebHookConfig queryWebHookConfigById(WebHookConfig webHookConfig) {
		try {
			String content = configService.getConfig(getWebHookConfigDataId(webHookConfig), getManuGroupId(webHookConfig), TIMEOUT_MS);
			return JsonUtils.deserialize(content, WebHookConfig.class);
		} catch (NacosException e) {
			logger.error("queryWebHookConfigById failed", e);
		}
		return null;
	}

	@Override
	public List<WebHookConfig> queryWebHookConfigByManufacturer(WebHookConfig webHookConfig, Integer pageNum,
			Integer pageSize) {
		List<WebHookConfig> webHookConfigs = new ArrayList<>();
		String manufacturerName = webHookConfig.getManufacturerName();
		// 查出厂商的所有事件名称
		try {
			ManufacturerObject manufacturerObject = getManufacturersInfo();
			List<String> manufacturerEvents = manufacturerObject.getManufacturerEvents(manufacturerName);
			int startIndex = (pageNum-1)*pageSize, endIndex = pageNum*pageSize-1;
			if (manufacturerEvents.size() > startIndex) {
				for (int i=startIndex; i<endIndex && i<manufacturerEvents.size(); i++) {
					//由于nacos API无提供批量获取配置接口，只能遍历查询
					String content = configService.getConfig(manufacturerEvents.get(i)+ DATA_ID_EXTENSION, getManuGroupId(webHookConfig), TIMEOUT_MS);
					webHookConfigs.add(JsonUtils.deserialize(content, WebHookConfig.class));
				}
			}
		} catch (NacosException e) {
			logger.error("queryWebHookConfigByManufacturer failed", e);
		}
		return webHookConfigs;
	}

	/**
	 * 由于nacos配置名不可包含特殊字符，使用callbackPath的MD5值作为dataId
	 * @param webHookConfig
	 * @return
	 */
	private String getWebHookConfigDataId(WebHookConfig webHookConfig) {
		return MD5Utils.md5Hex(webHookConfig.getCallbackPath(), "UTF_8") + DATA_ID_EXTENSION;
	}

	private String getManuGroupId(WebHookConfig webHookConfig) {
		return GROUP_PREFIX + webHookConfig.getManufacturerName();
	}

	private ManufacturerObject getManufacturersInfo() throws NacosException {
		String manufacturersContent = configService.getConfig(MANUFACTURERS_DATA_ID, "webhook", TIMEOUT_MS);
		return StringUtil.isNullOrEmpty(manufacturersContent) ?
				new ManufacturerObject():
				JsonUtils.deserialize(manufacturersContent, ManufacturerObject.class);
	}

}
