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

public abstract class Disposable<V> {
  private volatile long lastAccessed;

  /**
   * Version of the policy this disposable was created from. 0 means that no version was assigned,
   * which happens for disposables not built by a governance handler.
   */
  private volatile long policyVersion;

  protected Disposable() {
    lastAccessed = System.currentTimeMillis();
  }

  public abstract void dispose();

  public V get() {
    lastAccessed = System.currentTimeMillis();
    return getValue();
  }

  public abstract V getValue();

  public abstract String getKey();

  public long lastAccessed() {
    return lastAccessed;
  }

  public long getPolicyVersion() {
    return policyVersion;
  }

  public void setPolicyVersion(long policyVersion) {
    this.policyVersion = policyVersion;
  }
}
