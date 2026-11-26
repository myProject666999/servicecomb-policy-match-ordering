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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.servicecomb.governance.LogCapturingAppender;
import org.apache.servicecomb.governance.MatchersManager;
import org.apache.servicecomb.governance.MockMicroserviceMeta;
import org.apache.servicecomb.governance.event.GovernanceConfigurationChangedEvent;
import org.apache.servicecomb.governance.event.GovernanceEventManager;
import org.apache.servicecomb.governance.marker.GovernanceRequest;
import org.apache.servicecomb.governance.marker.GovernanceRequestExtractor;
import org.apache.servicecomb.governance.policy.AbstractPolicy;
import org.apache.servicecomb.governance.policy.RateLimitingPolicy;
import org.apache.servicecomb.governance.properties.RateLimitProperties;
import org.apache.servicecomb.governance.service.MatchersService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.github.resilience4j.ratelimiter.RateLimiter;

/**
 * Covers the interleaving in which a request thread matches the old policy, the refresh replaces the
 * policy and deletes the processor, and the request thread then caches a processor built from the
 * old policy. The matcher is fenced so that the interleaving is deterministic, without sleeps.
 */
public class AbstractGovernanceHandlerRefreshTest {
  /**
   * A dedicated prefix keeps the shared static EventBus from notifying governance properties and
   * handlers created by other tests.
   */
  private static final String KEY = "test.governanceRefresh.rateLimiting";

  private static final String NAME = "rule-a";

  private static final long TIMEOUT_SECONDS = 10;

  /**
   * A properties instance whose parse can be frozen, used when a test needs the refreshed map to
   * stay at a stable value while the change is in flight.
   */
  private static final class FrozenParseRateLimitProperties extends RateLimitProperties {
    private final CyclicBarrier fence = new CyclicBarrier(2);

    private final CountDownLatch fenceReached = new CountDownLatch(1);

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private volatile boolean fenceEnabled;

    FrozenParseRateLimitProperties() {
      super(KEY);
    }

    @Override
    protected RateLimitingPolicy parseEntityItem(String key, String value) {
      if (fenceEnabled) {
        fenceReached.countDown();
        try {
          fence.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
          failure.compareAndSet(null, e);
          Thread.currentThread().interrupt();
        }
      }
      return super.parseEntityItem(key, value);
    }
  }

  /**
   * Blocks the first match call, so that the test can run a refresh between matching a policy and
   * creating the processor. Later calls, including the one made inside the handler lock, pass
   * through: blocking them would deadlock the fix under test.
   */
  private static final class OnceFencedMatchersManager extends MatchersManager {
    private final AtomicBoolean fenceNextMatch = new AtomicBoolean();

    private final CyclicBarrier matchFence = new CyclicBarrier(2);

    private final CountDownLatch matchFenceReached = new CountDownLatch(1);

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    OnceFencedMatchersManager() {
      super(new AllMatchService());
    }

    @Override
    public <T extends AbstractPolicy> T match(GovernanceRequestExtractor requestExtractor,
        Map<String, T> policies) {
      T result = super.match(requestExtractor, policies);
      if (fenceNextMatch.compareAndSet(true, false)) {
        matchFenceReached.countDown();
        try {
          matchFence.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
          failure.compareAndSet(null, e);
          Thread.currentThread().interrupt();
        }
      }
      return result;
    }
  }

  private static final class AllMatchService implements MatchersService {
    @Override
    public boolean checkMatch(GovernanceRequestExtractor governanceRequest, String key) {
      return true;
    }
  }

  private static final class TestRateLimitingHandler extends RateLimitingHandler {
    private final AtomicReference<RateLimitingPolicy> lastCreatedPolicy = new AtomicReference<>();

    TestRateLimitingHandler(RateLimitProperties properties) {
      super(properties);
    }

    @Override
    public Disposable<RateLimiter> createProcessor(String key, GovernanceRequestExtractor requestExtractor,
        RateLimitingPolicy policy) {
      lastCreatedPolicy.set(policy);
      return super.createProcessor(key, requestExtractor, policy);
    }
  }

  private final MockEnvironment environment = new MockEnvironment();

  private RateLimitProperties properties;

  private OnceFencedMatchersManager matchersManager;

  private TestRateLimitingHandler handler;

  private void start(String rate) {
    start(new RateLimitProperties(KEY), rate);
  }

  private void start(RateLimitProperties propertiesToUse, String rate) {
    environment.setProperty(KEY + "." + NAME, "rate: " + rate);
    properties = propertiesToUse;
    properties.setEnvironment(environment);
    properties.setMicroserviceMeta(new MockMicroserviceMeta());
    properties.afterPropertiesSet();

    matchersManager = new OnceFencedMatchersManager();
    handler = new TestRateLimitingHandler(properties);
    handler.setMatchersManager(matchersManager);
  }

  @AfterEach
  public void tearDown() {
    if (properties != null) {
      // The EventBus has no unregister operation and the instances stay subscribed. Disable any
      // fence and remove the entities so a later event can not block this test's properties.
      if (properties instanceof FrozenParseRateLimitProperties) {
        ((FrozenParseRateLimitProperties) properties).fenceEnabled = false;
      }
      environment.setProperty(KEY + "." + NAME, "");
      environment.setProperty(KEY + ".rule-z", "");
      postConfigurationChanged(KEY + "." + NAME);
      postConfigurationChanged(KEY + ".rule-z");
    }
  }

  private static void postConfigurationChanged(String key) {
    GovernanceEventManager.post(new GovernanceConfigurationChangedEvent(Collections.singleton(key)));
  }

  private static GovernanceRequest helloRequest() {
    GovernanceRequest request = new GovernanceRequest();
    request.setApiPath("/hello");
    return request;
  }

  private static int rateOf(RateLimiter rateLimiter) {
    return rateLimiter.getRateLimiterConfig().getLimitForPeriod();
  }

  @Test
  public void test_processor_created_after_refresh_uses_the_new_policy() throws Exception {
    start("10");
    Assertions.assertEquals(10, rateOf(handler.getActuator(helloRequest())));

    matchersManager.fenceNextMatch.set(true);
    AtomicReference<RateLimiter> requestResult = new AtomicReference<>();
    Thread requestThread = new Thread(() -> requestResult.set(handler.getActuator(helloRequest())));
    requestThread.start();

    // The request thread has matched the policy with rate 10 and is stopped right after that.
    Assertions.assertTrue(matchersManager.matchFenceReached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "the request thread must reach the match fence");

    // The refresh replaces the policy with rate 20 and deletes the cached processor.
    environment.setProperty(KEY + "." + NAME, "rate: 20");
    postConfigurationChanged(KEY + "." + NAME);

    // Let the request thread continue: it has a stale policy in its hand.
    matchersManager.matchFence.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    requestThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    Assertions.assertFalse(requestThread.isAlive());
    Assertions.assertNull(matchersManager.failure.get(), "the match fence must not time out");

    // The request that overlapped the refresh may have used the old or the new value, but the next
    // request must use the processor built from the new policy.
    Assertions.assertEquals(20, rateOf(handler.getActuator(helloRequest())),
        "a processor built from the stale policy must not stay in the cache");
    Assertions.assertEquals(20, handler.lastCreatedPolicy.get().getRate());

    RateLimiter current = handler.getActuator(helloRequest());
    Assertions.assertEquals(20, rateOf(current), "the cache must keep serving the new policy");
  }

  @Test
  public void test_broken_update_keeps_the_running_processor_until_a_valid_update() {
    start("10");
    Assertions.assertEquals(10, rateOf(handler.getActuator(helloRequest())));

    // an illegal yaml must keep both the policy and the processor built from it
    environment.setProperty(KEY + "." + NAME, "rate: [1, 2");
    postConfigurationChanged(KEY + "." + NAME);
    Assertions.assertEquals(10, rateOf(handler.getActuator(helloRequest())),
        "a broken update must keep the previous rate limiter");
    Assertions.assertEquals(10, handler.lastCreatedPolicy.get().getRate());

    // the next valid update switches to the new parameters
    environment.setProperty(KEY + "." + NAME, "rate: 20");
    postConfigurationChanged(KEY + "." + NAME);
    Assertions.assertEquals(20, rateOf(handler.getActuator(helloRequest())));
    Assertions.assertEquals(20, handler.lastCreatedPolicy.get().getRate());
  }

  @Test
  public void test_cached_processor_is_replaced_when_the_policy_changes() {
    start("10");
    RateLimiter first = handler.getActuator(helloRequest());
    Assertions.assertEquals(10, rateOf(first));

    environment.setProperty(KEY + "." + NAME, "rate: 20");
    postConfigurationChanged(KEY + "." + NAME);

    RateLimiter second = handler.getActuator(helloRequest());
    Assertions.assertEquals(20, rateOf(second));
    Assertions.assertNotSame(first, second);
    // calls already holding the replaced processor keep working with the parameters it was built from
    Assertions.assertTrue(first.acquirePermission(), "a replaced processor must remain usable in flight");
    Assertions.assertEquals(10, rateOf(first));
  }

  @Test
  public void test_lower_priority_policy_is_not_selected_during_refresh() throws Exception {
    FrozenParseRateLimitProperties frozen = new FrozenParseRateLimitProperties();
    start(frozen, "10");
    environment.setProperty(KEY + ".rule-z", "rate: 5\norder: 5");
    postConfigurationChanged(KEY + ".rule-z");
    Assertions.assertEquals(10, rateOf(handler.getActuator(helloRequest())),
        "rule-a with the default order 0 must win over rule-z with order 5");

    // Freeze the refresh of rule-a inside its parse. The configuration is replaced as one snapshot,
    // so during the window a request may only see the old or the new rule-a, never rule-z.
    frozen.fenceEnabled = true;
    environment.setProperty(KEY + "." + NAME, "rate: 20");
    Thread refresh = new Thread(() -> frozen.onConfigurationChangedEvent(
        new GovernanceConfigurationChangedEvent(Collections.singleton(KEY + "." + NAME))));
    refresh.start();
    Assertions.assertTrue(frozen.fenceReached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "the refresh must reach the parse fence");

    Map<String, RateLimitingPolicy> duringRefresh = frozen.getParsedEntity();
    Assertions.assertNotNull(duringRefresh.get(NAME),
        "rule-a must stay visible while its new version is being parsed");
    Assertions.assertEquals(10, duringRefresh.get(NAME).getRate(),
        "the visible rule-a must be the previous version while the new one is not published");
    Assertions.assertEquals(5, duringRefresh.get("rule-z").getRate());
    // the request must resolve rule-a, not fall through to rule-z
    Assertions.assertEquals(10, rateOf(handler.getActuator(helloRequest())),
        "a request in the refresh window must not be served by rule-z");

    frozen.fence.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    refresh.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    Assertions.assertNull(frozen.failure.get(), "the parse fence must not time out");
    Assertions.assertEquals(20, frozen.getParsedEntity().get(NAME).getRate());
  }

  @Test
  public void test_applying_new_policy_log_contains_the_policy_version() {
    start("10");
    LogCapturingAppender appender = LogCapturingAppender.attach(RateLimitingHandler.class);
    try {
      handler.getActuator(helloRequest());
      RateLimitingPolicy applied = handler.lastCreatedPolicy.get();
      Assertions.assertTrue(applied.getVersion() > 0, "parsed policies carry a version");
      Assertions.assertTrue(appender.messages().stream()
              .anyMatch(message -> message.contains("policy version " + applied.getVersion())),
          "the applying log must show which policy version the processor was built from, got: "
              + appender.messages());
    } finally {
      appender.close();
    }
  }

}
