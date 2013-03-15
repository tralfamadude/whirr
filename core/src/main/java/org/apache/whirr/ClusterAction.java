/**
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

package org.apache.whirr;

import java.io.IOException;

import org.jclouds.compute.ComputeServiceContext;

import com.google.common.base.Function;

/**
 * Performs an action on a cluster. Example actions include bootstrapping
 * (launching, creating), configuring, or running an arbitrary command on the
 * cluster.
 */
public abstract class ClusterAction {
  
  private final Function<ClusterSpec, ComputeServiceContext> getCompute;

  protected ClusterAction(final Function<ClusterSpec, ComputeServiceContext> getCompute) {
    this.getCompute = getCompute;
  }
  
  public Function<ClusterSpec, ComputeServiceContext> getCompute() {
    return getCompute;
  }
  
  protected abstract String getAction();

  /**
   * Use given ClusterSpec and Cluster and wave to mutate the actual cluster (create, start services, terminate)
   * and return a new Cluster object to reflect changes if necessary.
   * @param clusterSpec
   * @param cluster current cluster, null if cluster is not bootstrapped yet.
   * @param wave a selector of role subsets, -1 if all roles should be used (no wave, (equiv. to one wave)).
   * @return a new Cluster object to reflect changes if necessary (Cluster is idempotent for consistency reasons).
   * @throws IOException
   * @throws InterruptedException
   */
  public abstract Cluster execute(ClusterSpec clusterSpec, Cluster cluster, int wave)
      throws IOException, InterruptedException;
  
}
