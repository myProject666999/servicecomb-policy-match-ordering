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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Captures the log messages of one logger, so that tests can assert on configuration warnings and
 * on the policy version written when a processor is created. The logger configuration is restored
 * by {@link #close()}.
 */
public final class LogCapturingAppender extends AbstractAppender {
  private final List<String> messages = new CopyOnWriteArrayList<>();

  private final Logger logger;

  private final Level previousLevel;

  private final boolean previousAdditive;

  private LogCapturingAppender(String name, Class<?> owner) {
    super(name, null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    this.logger = context.getLogger(owner.getName());
    this.previousLevel = logger.getLevel();
    this.previousAdditive = logger.isAdditive();
  }

  public static LogCapturingAppender attach(Class<?> owner) {
    LogCapturingAppender appender = new LogCapturingAppender("test-capture-" + owner.getSimpleName(), owner);
    appender.start();
    appender.logger.addAppender(appender);
    // info and warnings are usually below the configured threshold of the test run, and a captured
    // event must not be printed to the console as well
    appender.logger.setLevel(Level.INFO);
    appender.logger.setAdditive(false);
    return appender;
  }

  @Override
  public void append(LogEvent event) {
    messages.add(event.getMessage().getFormattedMessage());
  }

  public List<String> messages() {
    return messages;
  }

  public void close() {
    logger.removeAppender(this);
    logger.setLevel(previousLevel);
    logger.setAdditive(previousAdditive);
    stop();
  }
}
