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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.servicecomb.governance.marker.GovernanceRequest;
import org.apache.servicecomb.governance.policy.RateLimitingPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Ordering rules are part of the governance configuration contract. These tests do not need a
 * Spring context and pin down compareTo as well as the policy finally selected by MatchersManager.
 */
public class MatchersManagerOrderingTest {
  private static final GovernanceRequest REQUEST = new GovernanceRequest();

  private final MatchersManager matchersManager = new MatchersManager((request, key) -> true);

  private static RateLimitingPolicy policy(String name, int order) {
    RateLimitingPolicy policy = new RateLimitingPolicy();
    policy.setName(name);
    policy.setOrder(order);
    return policy;
  }

  private static Map<String, RateLimitingPolicy> policies(RateLimitingPolicy... values) {
    Map<String, RateLimitingPolicy> map = new LinkedHashMap<>();
    for (RateLimitingPolicy value : values) {
      map.put(value.getName(), value);
    }
    return map;
  }

  @Test
  public void test_compare_to_does_not_overflow() {
    RateLimitingPolicy min = policy("min", Integer.MIN_VALUE);
    RateLimitingPolicy one = policy("one", 1);
    RateLimitingPolicy max = policy("max", Integer.MAX_VALUE);
    RateLimitingPolicy minusOne = policy("minus-one", -1);

    Assertions.assertTrue(min.compareTo(one) < 0, "Integer.MIN_VALUE must sort before 1");
    Assertions.assertTrue(one.compareTo(min) > 0, "1 must sort after Integer.MIN_VALUE");
    Assertions.assertTrue(minusOne.compareTo(max) < 0, "-1 must sort before Integer.MAX_VALUE");
    Assertions.assertTrue(max.compareTo(minusOne) > 0, "Integer.MAX_VALUE must sort after -1");
  }

  @Test
  public void test_min_order_wins_over_one() {
    Assertions.assertEquals("min",
        matchersManager.match(REQUEST, policies(policy("one", 1), policy("min", Integer.MIN_VALUE))).getName());
  }

  @Test
  public void test_minus_one_wins_over_max() {
    Assertions.assertEquals("minus-one",
        matchersManager.match(REQUEST, policies(policy("max", Integer.MAX_VALUE), policy("minus-one", -1))).getName());
  }

  @Test
  public void test_same_order_is_resolved_by_name() {
    Assertions.assertEquals("rule-a",
        matchersManager.match(REQUEST, policies(policy("rule-a", 0), policy("rule-b", 0))).getName());
    Assertions.assertEquals("rule-a",
        matchersManager.match(REQUEST, policies(policy("rule-b", 0), policy("rule-a", 0))).getName());
  }

  @Test
  public void test_selection_does_not_depend_on_map_iteration_order() {
    List<List<RateLimitingPolicy>> permutations = Arrays.asList(
        Arrays.asList(policy("p1", 1), policy("p2", Integer.MIN_VALUE), policy("p3", 0)),
        Arrays.asList(policy("p2", Integer.MIN_VALUE), policy("p3", 0), policy("p1", 1)),
        Arrays.asList(policy("p1", 1), policy("p3", 0), policy("p2", Integer.MIN_VALUE)),
        Arrays.asList(policy("p3", 0), policy("p2", Integer.MIN_VALUE), policy("p1", 1)),
        Arrays.asList(policy("p3", 0), policy("p1", 1), policy("p2", Integer.MIN_VALUE)),
        Arrays.asList(policy("p2", Integer.MIN_VALUE), policy("p1", 1), policy("p3", 0)));

    for (List<RateLimitingPolicy> permutation : permutations) {
      Map<String, RateLimitingPolicy> map = new LinkedHashMap<>();
      permutation.forEach(policy -> map.put(policy.getName(), policy));
      Assertions.assertEquals("p2", matchersManager.match(REQUEST, map).getName(),
          "order 1, Integer.MIN_VALUE, 0 must always select p2");
    }
  }

  @Test
  public void test_default_order_is_zero() {
    RateLimitingPolicy defaultOrder = new RateLimitingPolicy();
    defaultOrder.setName("default-order");

    Assertions.assertEquals(0, defaultOrder.getOrder());
    // same order, the name decides the precedence and keeps the comparison total
    Assertions.assertTrue(defaultOrder.compareTo(policy("z-explicit-zero", 0)) < 0);
    Assertions.assertTrue(defaultOrder.compareTo(policy("a-explicit-zero", 0)) > 0);
  }

  @Test
  public void test_tie_warning_is_logged_once_per_tie_group() {
    LogCapturingAppender appender = LogCapturingAppender.attach(MatchersManager.class);
    try {
      Map<String, RateLimitingPolicy> tied = policies(policy("rule-b", 0), policy("rule-a", 0));
      for (int i = 0; i < 5; i++) {
        Assertions.assertEquals("rule-a", matchersManager.match(REQUEST, tied).getName());
      }

      long warnings = appender.messages().stream()
          .filter(message -> message.contains("same order"))
          .count();
      Assertions.assertEquals(1, warnings, "a tie group must not be reported on every request");
      Assertions.assertTrue(appender.messages().stream()
          .anyMatch(message -> message.contains("rule-a") && message.contains("rule-b")));
    } finally {
      appender.close();
    }
  }

  @Test
  public void test_tie_warning_is_reported_again_after_the_group_changes() {
    LogCapturingAppender appender = LogCapturingAppender.attach(MatchersManager.class);
    try {
      matchersManager.match(REQUEST, policies(policy("rule-b", 0), policy("rule-a", 0)));
      matchersManager.match(REQUEST, policies(policy("rule-c", 0), policy("rule-a", 0)));

      long warnings = appender.messages().stream()
          .filter(message -> message.contains("same order"))
          .count();
      Assertions.assertEquals(2, warnings, "a different tie group is a new warning");
    } finally {
      appender.close();
    }
  }
}
