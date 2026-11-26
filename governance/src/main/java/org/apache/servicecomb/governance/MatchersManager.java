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
package org.apache.servicecomb.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.servicecomb.governance.marker.GovernanceRequestExtractor;
import org.apache.servicecomb.governance.policy.AbstractPolicy;
import org.apache.servicecomb.governance.service.MatchersService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatchersManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(MatchersManager.class);

  private final MatchersService matchersService;

  /**
   * Signature of the last reported tie group, used to report a given group only once instead of on
   * every request. The matcher is called concurrently from request threads.
   */
  private final AtomicReference<String> lastReportedTies = new AtomicReference<>();

  public MatchersManager(MatchersService matchersService) {
    this.matchersService = matchersService;
  }

  public <T extends AbstractPolicy> T match(GovernanceRequestExtractor requestExtractor, Map<String, T> policies) {
    List<T> sortPolicies = new ArrayList<>(policies.size());
    sortPolicies.addAll(policies.values());
    sortPolicies.sort(T::compareTo);
    reportTiesOnce(sortPolicies);

    for (T policy : sortPolicies) {
      if (matchersService.checkMatch(requestExtractor, policy.getName())) {
        return policy;
      }
    }
    return null;
  }

  /**
   * Policies with the same order are applied in name order. Report such groups once, so that the
   * configuration is visible in the logs without flooding them on every request.
   */
  private <T extends AbstractPolicy> void reportTiesOnce(List<T> sortedPolicies) {
    List<String> ties = new ArrayList<>();
    for (int i = 0; i < sortedPolicies.size(); ) {
      int groupEnd = i + 1;
      while (groupEnd < sortedPolicies.size()
          && sortedPolicies.get(groupEnd).getOrder() == sortedPolicies.get(i).getOrder()) {
        groupEnd++;
      }
      if (groupEnd - i > 1) {
        List<String> names = new ArrayList<>(groupEnd - i);
        for (int index = i; index < groupEnd; index++) {
          names.add(sortedPolicies.get(index).getName());
        }
        ties.add(sortedPolicies.get(i).getClass().getSimpleName()
            + " order=" + sortedPolicies.get(i).getOrder() + " policies=" + names);
      }
      i = groupEnd;
    }

    String signature = ties.isEmpty() ? null : String.join("; ", ties);
    if (signature == null) {
      lastReportedTies.set(null);
      return;
    }
    if (!Objects.equals(signature, lastReportedTies.getAndSet(signature))) {
      LOGGER.warn("Governance policies with the same order are applied in name order, "
          + "configure distinct order values if another precedence is expected: {}", signature);
    }
  }
}
