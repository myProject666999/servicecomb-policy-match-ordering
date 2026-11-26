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
package org.apache.servicecomb.governance.properties;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.apache.servicecomb.governance.MicroserviceMeta;
import org.apache.servicecomb.governance.entity.Configurable;
import org.apache.servicecomb.governance.event.GovernanceConfigurationChangedEvent;
import org.apache.servicecomb.governance.event.GovernanceEventManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.CollectionUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import com.google.common.eventbus.Subscribe;

public abstract class GovernanceProperties<T extends Configurable> implements InitializingBean {
  private static final Logger LOGGER = LoggerFactory.getLogger(GovernanceProperties.class);

  private final Representer representer = new Representer(new DumperOptions());

  private final String configKey;

  protected Environment environment;

  private MicroserviceMeta microserviceMeta;

  @Autowired
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Autowired
  public void setMicroserviceMeta(MicroserviceMeta microserviceMeta) {
    this.microserviceMeta = microserviceMeta;
  }

  protected volatile Map<String, T> parsedEntity;

  private final AtomicLong versionSequence = new AtomicLong();

  /**
   * Tells whether the last {@link #parseEntityItem(String, String)} invocation failed because the
   * yaml could not be parsed. Other null results (removed, blanked, invalid or bound to another
   * service) must keep their previous semantics, so the reasons can not be merged into a single null.
   */
  private final ThreadLocal<Boolean> parseFailed = ThreadLocal.withInitial(() -> Boolean.FALSE);

  protected Class<T> entityClass;

  protected GovernanceProperties(String key) {
    configKey = key;
    representer.getPropertyUtils().setSkipMissingProperties(true);
    GovernanceEventManager.register(this);
    entityClass = getEntityClass();
  }

  public String getConfigKey() {
    return this.configKey;
  }

  @Override
  public void afterPropertiesSet() {
    parsedEntity = parseEntity(readPropertiesFromPrefix());
  }

  @Subscribe
  public void onConfigurationChangedEvent(GovernanceConfigurationChangedEvent event) {
    final Map<String, T> current = parsedEntity;
    if (current == null) {
      // afterPropertiesSet has not run yet, the initial load picks up the new values
      return;
    }

    // The copy is built and published as a whole. Request threads therefore either see the previous
    // snapshot or the complete new one, and never a state in which an already removed key is missing
    // while the replacement is still being parsed. An alternative would be a ConcurrentHashMap, but
    // it is updated key by key and still exposes the gap between remove and put.
    Map<String, T> updated = new HashMap<>(current);
    for (String key : event.getChangedConfigurations()) {
      if (!key.startsWith(configKey + ".")) {
        continue;
      }
      String mapKey = key.substring((configKey + ".").length());
      String value = environment.getProperty(key);
      parseFailed.remove();
      if (StringUtils.isEmpty(value)) {
        // the configuration was removed or blanked: the entity is intentionally dropped
        updated.remove(mapKey);
        continue;
      }

      T entityItem = parseEntityItem(mapKey, value);
      if (entityItem != null) {
        assignVersion(entityItem);
        updated.put(mapKey, entityItem);
      } else if (Boolean.TRUE.equals(parseFailed.get()) && updated.containsKey(mapKey)) {
        // The yaml could not be parsed at all. Keep the last valid version instead of letting a typo
        // remove a working policy, so the handler keeps running with the previous parameters. The
        // key has already been reported by parseEntityItem.
        LOGGER.warn("Governance configuration [{}] was not applied, the previous value is kept.", key);
      } else {
        // removed, blanked, invalid or bound to another service: the entity is dropped. This is the
        // existing behavior, intentionally different from a parse failure.
        updated.remove(mapKey);
      }
    }

    parsedEntity = updated;
  }

  private void assignVersion(T entity) {
    entity.setVersion(versionSequence.incrementAndGet());
  }

  private Map<String, String> readPropertiesFromPrefix() {
    Set<String> allKeys = getAllKeys(environment);
    Map<String, String> result = new HashMap<>();
    allKeys.forEach(key -> {
      if (key.startsWith(configKey + ".")) {
        result.put(key.substring(configKey.length() + 1), environment.getProperty(key));
      }
    });
    return result;
  }

  private Set<String> getAllKeys(Environment environment) {
    Set<String> allKeys = new HashSet<>();

    if (!(environment instanceof ConfigurableEnvironment)) {
      return allKeys;
    }

    ConfigurableEnvironment configurableEnvironment = (ConfigurableEnvironment) environment;

    for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
      getProperties(propertySource, allKeys);
    }
    return allKeys;
  }

  private void getProperties(PropertySource<?> propertySource,
      Set<String> allKeys) {
    if (propertySource instanceof CompositePropertySource) {
      // recursively get EnumerablePropertySource
      CompositePropertySource compositePropertySource = (CompositePropertySource) propertySource;
      compositePropertySource.getPropertySources().forEach(ps -> getProperties(ps, allKeys));
      return;
    }
    if (propertySource instanceof EnumerablePropertySource) {
      EnumerablePropertySource<?> enumerablePropertySource = (EnumerablePropertySource<?>) propertySource;
      Collections.addAll(allKeys, enumerablePropertySource.getPropertyNames());
      return;
    }

    LOGGER.debug("None EnumerablePropertySource ignored in {}, propertySourceName = [{}]", this.getClass().getName(),
        propertySource.getName());
  }

  public Map<String, T> getParsedEntity() {
    return this.parsedEntity;
  }

  protected Map<String, T> parseEntity(Map<String, String> yamlEntity) {
    if (CollectionUtils.isEmpty(yamlEntity)) {
      return new HashMap<>();
    }

    Map<String, T> resultMap = new HashMap<>();
    for (Entry<String, String> entry : yamlEntity.entrySet()) {
      T marker = parseEntityItem(entry.getKey(), entry.getValue());
      if (marker != null) {
        assignVersion(marker);
        resultMap.put(entry.getKey(), marker);
      }
    }
    return resultMap;
  }

  protected abstract Class<T> getEntityClass();

  protected T parseEntityItem(String key, String value) {
    parseFailed.set(Boolean.FALSE);
    if (StringUtils.isEmpty(value)) {
      return null;
    }

    try {
      Yaml entityParser = new Yaml(new Constructor(new TypeDescription(entityClass, entityClass), new LoaderOptions()),
          representer);
      T result = entityParser.loadAs(value, entityClass);
      result.setName(key);

      if (!result.isValid()) {
        LOGGER.warn("Entity configuration is not valid and ignored. Key [{}], value [{}]", key, value);
        return null;
      }
      if (!servicesMatch(result.getServices())) {
        LOGGER.info("Configuration belongs to other service is ignored. Key [{}]", key);
        return null;
      }
      return result;
    } catch (RuntimeException e) {
      parseFailed.set(Boolean.TRUE);
      LOGGER.error("governance config yaml is illegal, configuration key [{}] : {}", key, e.getMessage());
    }
    return null;
  }

  private boolean servicesMatch(String services) {
    if (StringUtils.isEmpty(services)) {
      return true;
    }

    return Arrays.stream(services.split(",")).anyMatch(service -> {
      String[] serviceAndVersion = service.split(":");
      if (serviceAndVersion.length == 1) {
        return microserviceMeta.getName().equals(serviceAndVersion[0]);
      } else if (serviceAndVersion.length == 2) {
        return microserviceMeta.getName().equals(serviceAndVersion[0]) && microserviceMeta.getVersion()
            .equals(serviceAndVersion[1]);
      } else {
        return false;
      }
    });
  }
}
