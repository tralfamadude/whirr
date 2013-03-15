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

package org.apache.whirr.actions;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import static com.google.common.base.Predicates.not;
import com.google.common.base.Throwables;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.velocity.app.VelocityEngine;
import org.apache.whirr.Cluster;
import static org.apache.whirr.Cluster.Instance;
import org.apache.whirr.ClusterAction;
import org.apache.whirr.ClusterSpec;
import org.apache.whirr.InstanceTemplate;
import static org.apache.whirr.RolePredicates.onlyRolesIn;
import org.apache.whirr.service.ClusterActionEvent;
import org.apache.whirr.service.ClusterActionHandler;
import org.apache.whirr.service.FirewallManager;
import org.apache.whirr.service.jclouds.StatementBuilder;
import org.apache.whirr.template.TemplateUtils;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.options.RunScriptOptions;
import static org.jclouds.compute.options.RunScriptOptions.Builder.overrideLoginCredentials;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.scriptbuilder.domain.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ClusterAction} that provides the base functionality for running
 * scripts on instances in the cluster.
 */
public abstract class ScriptBasedClusterAction extends ClusterAction {

  private static final Logger LOG = LoggerFactory
      .getLogger(ScriptBasedClusterAction.class);

  private final LoadingCache<String, ClusterActionHandler> handlerMap;
  private final ImmutableSet<String> targetRoles;
  private final ImmutableSet<String> targetInstanceIds;

  protected ScriptBasedClusterAction(
      Function<ClusterSpec, ComputeServiceContext> getCompute,
      LoadingCache<String, ClusterActionHandler> handlerMap
  ) {
    this(getCompute, handlerMap, ImmutableSet.<String>of(), ImmutableSet.<String>of());
  }

  /**
   *
   * @param getCompute
   * @param handlerMap
   * @param targetRoles if non-empty, only consider these roles.
   * @param targetInstanceIds if non-empty, only consider these instanceIds
   */
  protected ScriptBasedClusterAction(
      Function<ClusterSpec, ComputeServiceContext> getCompute,
      LoadingCache<String, ClusterActionHandler> handlerMap,
      Set<String> targetRoles,
      Set<String> targetInstanceIds
  ) {
    super(getCompute);
    this.handlerMap = checkNotNull(handlerMap, "handlerMap");
    this.targetRoles = ImmutableSet.copyOf(checkNotNull(targetRoles, "targetRoles"));
    this.targetInstanceIds = ImmutableSet.copyOf(checkNotNull(targetInstanceIds, "targetInstanceIds"));
  }

  public Cluster execute(ClusterSpec clusterSpec, Cluster cluster, int wave)
      throws IOException, InterruptedException
  {
    String waveComment = (wave == -1) ? "" : (" wave="+wave);
    LOG.info("Start {} phase" + waveComment, getAction());

    // eventMap is used to hold one event per InstanceTemplate
    Map<InstanceTemplate, ClusterActionEvent> eventMap = Maps.newHashMap();
    Cluster newCluster = cluster;

    //
    //  scan InstanceTemplate list,
    //   for each InstanceTemplate that is included, compose the statements, firewall, file templates,
    //   and ActionEvents into an event object.  Then trigger the before handlers for
    //   each of the roles in the instance.
    //
    final List<InstanceTemplate> instanceTemplates = clusterSpec.getInstanceTemplates();
    for (InstanceTemplate instanceTemplate : instanceTemplates) {
      if (shouldIgnoreInstanceTemplate(instanceTemplate)) {
        continue; // skip execution if this group of instances is not in target
      }
      StatementBuilder statementBuilder = new StatementBuilder();

      ComputeServiceContext computeServiceContext = getCompute().apply(clusterSpec);
      FirewallManager firewallManager = new FirewallManager(
          computeServiceContext, clusterSpec, newCluster);

      VelocityEngine velocityEngine = TemplateUtils.newVelocityEngine();

      ClusterActionEvent event = new ClusterActionEvent(getAction(), clusterSpec,
          instanceTemplate, newCluster, statementBuilder, getCompute(), firewallManager, velocityEngine);

      eventMap.put(instanceTemplate, event);
      eventSpecificActions(instanceTemplate, event);
      // for each role in the InstanceTemplate, perform the beforeActions
      for (String role : instanceTemplate.getRoles(wave)) {
        if (roleIsInTarget(role)) {
          safeGetActionHandler(role).beforeAction(event);
        }
      }

      // cluster may have been updated by handler
      //  (Cluster is idempotent so ref is replaced if updated)
      newCluster = event.getCluster();
    }

    //
    //  perform the remote actions on the tuples <InstanceTemplate, ClusterActionEvent>
    //
    doAction(eventMap, wave);
    // remote script actions are completed at this point, effecting a barrier synchronization

    // cluster obj may have been updated by action
    //   (all the event instances hold the same cluster object)
    newCluster = Iterables.get(eventMap.values(), 0).getCluster();

    //
    //  scan InstanceTemplate list,
    //   for each InstanceTemplate that is included, trigger the after handlers for
    //   each of the roles in the instance.
    //
    for (InstanceTemplate instanceTemplate : clusterSpec.getInstanceTemplates()) {
      if (shouldIgnoreInstanceTemplate(instanceTemplate)) {
        continue;
      }
      ClusterActionEvent event = eventMap.get(instanceTemplate);
      // for each role in the InstanceTemplate, perform the afterActions
      for (String role : instanceTemplate.getRoles(wave)) {
        if (roleIsInTarget(role)) {
          event.setCluster(newCluster);
          safeGetActionHandler(role).afterAction(event);

          // cluster may have been updated by handler
          //  (Cluster is idempotent so ref is replaced if updated)
          newCluster = event.getCluster();
        }
      }
    }

    LOG.info("Finish {} phase" + waveComment, getAction());
    return newCluster;
  }

  protected void doAction(Map<InstanceTemplate, ClusterActionEvent> eventMap, int wave)
      throws InterruptedException, IOException {
    runScripts(eventMap, wave);
    postRunScriptsActions(eventMap);
  }

  protected void runScripts(Map<InstanceTemplate, ClusterActionEvent> eventMap, final int wave)
      throws InterruptedException, IOException {

    final String phaseName = getAction();
    final Collection<Future<ExecResponse>> futures = Sets.newHashSet();

    final ClusterSpec clusterSpec = eventMap.values().iterator().next().getClusterSpec();

    final RunScriptOptions options = overrideLoginCredentials(LoginCredentials.builder().user(clusterSpec.getClusterUser())
                                                              .privateKey(clusterSpec.getPrivateKey()).build());
    //
    //  for each entry <InstanceTemplate, ClusterActionEvent>, if InstanceTemplate is included,
    //    execute statements on each of the (compute host) instances bound
    //    to the InstanceTemplate obj.
    //
    for (Map.Entry<InstanceTemplate, ClusterActionEvent> entry : eventMap.entrySet()) {
      if (shouldIgnoreInstanceTemplate(entry.getKey())) {
        continue; // skip if not in the target
      }



      Cluster cluster = entry.getValue().getCluster();
      StatementBuilder statementBuilder = entry.getValue().getStatementBuilder();
      if (statementBuilder.isEmpty()) {
        continue; // skip execution if we have an empty list
      }

      Set<Instance> instances = cluster.getInstancesMatching(Predicates.<Instance> and(onlyRolesIn(entry.getKey()
          .getRoles(wave)), not(instanceIsNotInTarget())));
      LOG.info("Starting to run scripts on cluster for phase {} of wave " + wave + " on instances: {}",
          phaseName, asString(instances));
      // create a Future for each instance
      for (Instance instance : instances) {
        futures.add(runStatementOnInstanceInCluster(statementBuilder, instance, clusterSpec, options));
      }
    }
    // wait for all the Futures to finish
    for (Future<ExecResponse> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        throw new IOException(e.getCause());
      }
    }
    LOG.info("Finished running {} phase scripts on all cluster instances", phaseName);
  }
  
  public ListenableFuture<ExecResponse> runStatementOnInstanceInCluster(StatementBuilder statementBuilder,
      Instance instance, ClusterSpec clusterSpec, RunScriptOptions options) {
    Statement statement = statementBuilder.name(getAction() + "-"
          + Joiner.on('_').join(instance.getRoles())).build(clusterSpec, instance);
    ComputeService compute = getCompute().apply(clusterSpec).getComputeService();
    return compute.submitScriptOnNode(
              instance.getId(),
              statement,
              options);
  }
  
  private String asString(Set<Instance> instances) {
    return Joiner.on(", ").join(
        Iterables.transform(instances, new Function<Instance, String>() {
          @Override
          public String apply(Instance instance) {
            return instance == null ? "<null>" : instance.getId();
          }
        }));
  }

  protected boolean shouldIgnoreInstanceTemplate(InstanceTemplate template) {
    return targetRoles.size() != 0 && containsNoneOf(template.getRoles(), targetRoles);
  }

  protected boolean roleIsInTarget(String role) {
    return targetRoles.size() == 0 || targetRoles.contains(role);
  }

  protected Predicate<Instance> instanceIsNotInTarget() {
    return new Predicate<Instance>() {

      @Override
      public boolean apply(Instance arg0) {
        if (targetInstanceIds.size() != 0) {
          return !targetInstanceIds.contains(arg0.getId());
        }
        if (targetRoles.size() != 0) {
          return containsNoneOf(arg0.getRoles(), targetRoles);
        }
        return false;
      }

      @Override
      public String toString() {
        return "instanceIsNotInTarget()";
      }
    };

  }

  private boolean containsNoneOf(Set<String> querySet, final Set<String> target) {
    return !Iterables.any(querySet, new Predicate<String>() {
      @Override
      public boolean apply(String role) {
        return target.contains(role);
      }
    });
  }

  protected void eventSpecificActions(InstanceTemplate instanceTemplate, ClusterActionEvent event) throws IOException {
  }

  protected void postRunScriptsActions(
      Map<InstanceTemplate, ClusterActionEvent> eventMap) throws IOException {
  }

  /**
   * Try to get an {@see ClusterActionHandler } instance or throw an
   * IllegalArgumentException if not found for this role name
   */
  private ClusterActionHandler safeGetActionHandler(String role) {
    try {
      ClusterActionHandler handler = handlerMap.get(role);
      if (handler == null) {
        throw new IllegalArgumentException("No handler for role " + role);
      }
      return handler;
    } catch (UncheckedExecutionException e) {
       throw Throwables.propagate(e.getCause());
    } catch (ExecutionException e) {
       throw new IllegalArgumentException(e.getCause());
    }
  }

}
