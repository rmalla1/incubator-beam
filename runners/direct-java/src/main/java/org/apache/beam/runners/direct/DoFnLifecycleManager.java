/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.runners.direct;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import org.apache.beam.sdk.runners.PipelineRunner;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.Setup;
import org.apache.beam.sdk.transforms.DoFn.Teardown;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.util.SerializableUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages {@link DoFn} setup, teardown, and serialization.
 *
 * <p>{@link DoFnLifecycleManager} is similar to a {@link ThreadLocal} storing a {@link DoFn}, but
 * calls the {@link DoFn} {@link Setup @Setup} method the first time the {@link DoFn} is obtained
 * and {@link Teardown @Teardown} whenever the {@link DoFn} is removed, and provides a method for
 * clearing all cached {@link DoFn DoFns}.
 */
class DoFnLifecycleManager {
  private static final Logger LOG = LoggerFactory.getLogger(DoFnLifecycleManager.class);

  public static DoFnLifecycleManager of(DoFn<?, ?> original) {
    return new DoFnLifecycleManager(original);
  }

  private final LoadingCache<Thread, DoFn<?, ?>> outstanding;

  private DoFnLifecycleManager(DoFn<?, ?> original) {
    this.outstanding = CacheBuilder.newBuilder().build(new DeserializingCacheLoader(original));
  }

  public DoFn<?, ?> get() throws Exception {
    Thread currentThread = Thread.currentThread();
    return outstanding.get(currentThread);
  }

  public void remove() throws Exception {
    Thread currentThread = Thread.currentThread();
    DoFn<?, ?> fn = outstanding.asMap().remove(currentThread);
    DoFnInvokers.INSTANCE.invokerFor(fn).invokeTeardown();
  }

  /**
   * Remove all {@link DoFn DoFns} from this {@link DoFnLifecycleManager}. Returns all exceptions
   * that were thrown while calling the remove methods.
   *
   * <p>If the returned Collection is nonempty, an exception was thrown from at least one {@link
   * DoFn.Teardown @Teardown} method, and the {@link PipelineRunner} should throw an exception.
   */
  public Collection<Exception> removeAll() throws Exception {
    Iterator<DoFn<?, ?>> fns = outstanding.asMap().values().iterator();
    Collection<Exception> thrown = new ArrayList<>();
    while (fns.hasNext()) {
      DoFn<?, ?> fn = fns.next();
      fns.remove();
      try {
        DoFnInvokers.INSTANCE.invokerFor(fn).invokeTeardown();
      } catch (Exception e) {
        thrown.add(e);
      }
    }
    return thrown;
  }

  private class DeserializingCacheLoader extends CacheLoader<Thread, DoFn<?, ?>> {
    private final byte[] original;

    public DeserializingCacheLoader(DoFn<?, ?> original) {
      this.original = SerializableUtils.serializeToByteArray(original);
    }

    @Override
    public DoFn<?, ?> load(Thread key) throws Exception {
      DoFn<?, ?> fn = (DoFn<?, ?>) SerializableUtils.deserializeFromByteArray(original,
          "DoFn Copy in thread " + key.getName());
      DoFnInvokers.INSTANCE.invokerFor(fn).invokeSetup();
      return fn;
    }
  }
}
