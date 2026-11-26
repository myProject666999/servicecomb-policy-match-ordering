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

package org.apache.servicecomb.governance.handler;

import org.apache.servicecomb.governance.MatchersManager;
import org.apache.servicecomb.governance.event.GovernanceConfigurationChangedEvent;
import org.apache.servicecomb.governance.event.GovernanceEventManager;
import org.apache.servicecomb.governance.marker.GovernanceRequestExtractor;
import org.apache.servicecomb.governance.policy.AbstractPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.eventbus.Subscribe;

import io.micrometer.core.instrument.MeterRegistry;

public abstract class AbstractGovernanceHandler<PROCESSOR, POLICY extends AbstractPolicy> {
  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractGovernanceHandler.class);

  protected final DisposableMap<PROCESSOR> processors;

  private final Object lock = new Object();

  protected MatchersManager matchersManager;

  protected MeterRegistry meterRegistry;

  protected AbstractGovernanceHandler() {
    GovernanceEventManager.register(this);
    processors = new DisposableMap<>(this::onConfigurationChanged);
  }

  @Autowired
  public void setMatchersManager(MatchersManager matchersManager) {
    this.matchersManager = matchersManager;
  }

  @Autowired(required = false)
  public void setMeterRegistry(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public PROCESSOR getActuator(GovernanceRequestExtractor requestExtractor) {
    Disposable<PROCESSOR> processor = getOrCreateProcessor(requestExtractor);
    return processor == null ? null : processor.get();
  }

  private Disposable<PROCESSOR> getOrCreateProcessor(GovernanceRequestExtractor requestExtractor) {
    POLICY policy = matchPolicy(requestExtractor);
    if (policy == null) {
      return null;
    }

    String key = createKey(requestExtractor, policy);
    if (key == null) {
      return null;
    }

    Disposable<PROCESSOR> processor = processors.get(key);
    if (processor != null && isCurrent(processor, policy)) {
      return processor;
    }

    synchronized (lock) {
      // Resolve the policy again under the same lock used to invalidate processors. A request may
      // have matched a policy right before a refresh, while the refresh has already replaced the
      // configuration. Publishing a processor built from that stale policy would keep the previous
      // parameters until the next refresh, so the decision is taken again inside the lock.
      POLICY currentPolicy = matchPolicy(requestExtractor);
      if (currentPolicy == null) {
        return null;
      }
      String currentKey = createKey(requestExtractor, currentPolicy);
      if (currentKey == null) {
        return null;
      }

      processor = processors.get(currentKey);
      if (processor != null && isCurrent(processor, currentPolicy)) {
        return processor;
      }

      // Dispose the processor built from the previous policy version before replacing it.
      Disposable<PROCESSOR> stale = processors.remove(currentKey);
      if (stale != null) {
        stale.dispose();
      }

      processor = createProcessor(currentKey, requestExtractor, currentPolicy);
      processor.setPolicyVersion(currentPolicy.getVersion());
      processors.put(currentKey, processor);
      return processor;
    }
  }

  /**
   * The policy version is assigned by the properties class for every parsed entity. A cached
   * processor built from another version must not be reused: the configuration was replaced after
   * the policy was matched and the processor holds the parameters of the previous configuration.
   */
  private boolean isCurrent(Disposable<PROCESSOR> processor, POLICY policy) {
    return processor.getPolicyVersion() == policy.getVersion();
  }

  protected abstract String createKey(GovernanceRequestExtractor requestExtractor, POLICY policy);

  protected abstract POLICY matchPolicy(GovernanceRequestExtractor requestExtractor);

  protected abstract Disposable<PROCESSOR> createProcessor(String key, GovernanceRequestExtractor requestExtractor,
      POLICY policy);

  /**
   * Removes a processor under the same lock used to create one, so that a processor can not be
   * re-added while the configuration change that invalidated it is still being applied.
   */
  protected void removeProcessor(String key) {
    synchronized (lock) {
      Disposable<PROCESSOR> processor = processors.remove(key);
      if (processor != null) {
        LOGGER.info("remove governance processor {}", key);
        processor.dispose();
      }
    }
  }

  protected void onConfigurationChanged(String key) {
    removeProcessor(key);
  }

  @Subscribe
  public void onDynamicConfigurationListener(GovernanceConfigurationChangedEvent event) {
    event.getChangedConfigurations().forEach(this::onConfigurationChanged);
  }
}
