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

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.servicecomb.governance.event.GovernanceConfigurationChangedEvent;
import org.apache.servicecomb.governance.policy.RateLimitingPolicy;
import org.apache.servicecomb.governance.properties.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Uses a {@link RateLimitProperties} subclass that blocks inside {@link #parseEntityItem} to pin the
 * refresh at the exact moment in which the changed entity is being re-parsed, and inspects the map
 * seen by request threads from another thread.
 */
public class GovernancePropertiesRefreshWindowTest {
  /**
   * A dedicated prefix keeps the shared static EventBus from touching the production
   * servicecomb.rateLimiting entries used by the Spring based tests.
   */
  private static final String KEY = "test.governanceRefreshWindow.rateLimiting";

  private static final String NAME = "rule-a";

  private static final long TIMEOUT_SECONDS = 10;

  private static final class FencedRateLimitProperties extends RateLimitProperties {
    private final CyclicBarrier fence = new CyclicBarrier(2);

    private final CountDownLatch fenceReached = new CountDownLatch(1);

    private final AtomicReference<Throwable> awaitFailure = new AtomicReference<>();

    private volatile boolean fenceEnabled;

    FencedRateLimitProperties() {
      super(KEY);
    }

    @Override
    protected RateLimitingPolicy parseEntityItem(String key, String value) {
      if (fenceEnabled) {
        fenceReached.countDown();
        try {
          fence.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
          awaitFailure.compareAndSet(null, e);
          Thread.currentThread().interrupt();
        }
      }
      return super.parseEntityItem(key, value);
    }
  }

  private final MockEnvironment environment = new MockEnvironment();

  private FencedRateLimitProperties properties;

  private FencedRateLimitProperties newProperties(String initialValue) {
    environment.setProperty(KEY + "." + NAME, initialValue);
    properties = new FencedRateLimitProperties();
    properties.setEnvironment(environment);
    properties.setMicroserviceMeta(new MockMicroserviceMeta());
    properties.afterPropertiesSet();
    return properties;
  }

  @AfterEach
  public void tearDown() {
    if (properties != null) {
      // The EventBus has no unregister operation, the instance stays subscribed. Disable the fence
      // and remove the entity so a later event of another test can not block or be affected.
      properties.fenceEnabled = false;
      environment.setProperty(KEY + "." + NAME, "");
      properties.onConfigurationChangedEvent(eventFor(NAME));
    }
  }

  private static GovernanceConfigurationChangedEvent eventFor(String name) {
    return new GovernanceConfigurationChangedEvent(Collections.singleton(KEY + "." + name));
  }

  @Test
  public void test_request_thread_does_not_observe_a_missing_policy_during_refresh() throws Exception {
    FencedRateLimitProperties props = newProperties("rate: 10");
    Assertions.assertEquals(10, props.getParsedEntity().get(NAME).getRate());

    props.fenceEnabled = true;
    environment.setProperty(KEY + "." + NAME, "rate: 20");
    Thread eventThread = new Thread(() -> props.onConfigurationChangedEvent(eventFor(NAME)));
    eventThread.start();

    // The event thread is now stopped inside parseEntityItem, before the new snapshot is published.
    Assertions.assertTrue(props.fenceReached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "the event thread must reach the fence");

    Map<String, RateLimitingPolicy> observedByRequestThread = props.getParsedEntity();
    Assertions.assertNotNull(observedByRequestThread.get(NAME),
        "a request thread must keep seeing the previous policy, not a map without the entity");
    Assertions.assertEquals(10, observedByRequestThread.get(NAME).getRate());
    Assertions.assertEquals(1, observedByRequestThread.size(), "no entity may be half published");

    props.fence.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    eventThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    Assertions.assertFalse(eventThread.isAlive());
    Assertions.assertNull(props.awaitFailure.get(), "the fence must not time out");

    // once the refresh completes, the new value is visible
    Assertions.assertEquals(20, props.getParsedEntity().get(NAME).getRate());
  }

  @Test
  public void test_failed_parse_keeps_the_last_valid_policy() {
    FencedRateLimitProperties props = newProperties("rate: 10");

    environment.setProperty(KEY + "." + NAME, "rate: [1, 2");
    props.onConfigurationChangedEvent(eventFor(NAME));

    RateLimitingPolicy policy = props.getParsedEntity().get(NAME);
    Assertions.assertNotNull(policy, "an illegal yaml must not make a working policy disappear");
    Assertions.assertEquals(10, policy.getRate(), "the last valid version must be kept");
  }

  @Test
  public void test_invalid_value_still_removes_the_policy() {
    FencedRateLimitProperties props = newProperties("rate: 10");

    environment.setProperty(KEY + "." + NAME, "rate: 0");
    props.onConfigurationChangedEvent(eventFor(NAME));

    Assertions.assertNull(props.getParsedEntity().get(NAME),
        "an invalid entity keeps the existing behavior and is removed");
  }

  @Test
  public void test_other_service_still_removes_the_policy() {
    FencedRateLimitProperties props = newProperties("rate: 10");

    environment.setProperty(KEY + "." + NAME, "rate: 10\nservices: other:1.0");
    props.onConfigurationChangedEvent(eventFor(NAME));

    Assertions.assertNull(props.getParsedEntity().get(NAME),
        "a value bound to another service keeps the existing behavior and is removed");
  }

  @Test
  public void test_removed_value_removes_the_policy() {
    FencedRateLimitProperties props = newProperties("rate: 10");

    environment.setProperty(KEY + "." + NAME, "");
    props.onConfigurationChangedEvent(eventFor(NAME));

    Assertions.assertNull(props.getParsedEntity().get(NAME));
  }

  @Test
  public void test_all_keys_of_one_event_are_visible_together() {
    FencedRateLimitProperties props = newProperties("rate: 10");

    environment.setProperty(KEY + ".rule-b", "rate: 20");
    Set<String> changed = new HashSet<>();
    changed.add(KEY + "." + NAME);
    changed.add(KEY + ".rule-b");
    props.onConfigurationChangedEvent(new GovernanceConfigurationChangedEvent(changed));

    Map<String, RateLimitingPolicy> snapshot = props.getParsedEntity();
    Assertions.assertEquals(2, snapshot.size());
    Assertions.assertEquals(10, snapshot.get(NAME).getRate());
    Assertions.assertEquals(20, snapshot.get("rule-b").getRate());
  }
}
