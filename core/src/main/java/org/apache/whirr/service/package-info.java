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
 * 
 */ 
/** 
 * <p>
 * The Whirr Service API.
 * </p> 
 * 
 * <h3>Terminology</h3>
 * <p>Because "instance" in the cloud means a virtual machine, and has a more generic meaning in OOP, we will
 * use "machine" in these definitions for clarity. </p>
 * <p>
 * A <i>service</i> is an instance of a coherent system running on one or more
 * machines (example: an ensemble of zookeeper processes), and is composed of machines filling roles. One or more
 * services can run on a cluster.
 * </p>
 * <p>
 * A <i>role</i> is a part of a service running on one or more machines. A role
 * typically corresponds to a single process or daemon, although this is not
 * required.
 * </p>
 * <p>
 * A <i>role set</i> is a collection of roles that are applied together to a machine set.
 * </p>
 * <p>
 * A <i>machine set</i> is a collection of machines that fill the same role set.
 * </p>
 * <p>
 * A <i>cluster spec</i> ({@link org.apache.whirr.ClusterSpec}) is a specification for a cluster which is
 * composed of a list <i>instance templates</i>.  The Whirr command line operates on one cluster at a time, but
 * ClusterSpec is not a singleton.
 * </p>
 * <p>
 * An <i>instance template</i> ({@link org.apache.whirr.InstanceTemplate}) is a specification of a list of role sets
 * plus cardinalities for machine set size that make up a cluster. For example,
 * <tt>1 role-a+role-b,4 role-c</tt>
 * specifies a cluster with 2 instance templates (or template groups) in which one node (machine) has a role set of
 * <tt>role-a</tt> and
 * <tt>role-b</tt>, and four nodes (machine set) have the role set <tt>role-c</tt>.
 * Actual number of machines can be lower, within a specified minimum (minNumberOfInstances).
 * The property whirr.instance-templates is used to specify the cluster.
 * </p>
 * <p>
 * A <i>cluster action</i> ({@link org.apache.whirr.ClusterAction}) is an action that is performed on a set of machines
 * in a cluster. Examples of cluster actions include 'bootstrap' and 'configure'. Actions can be thought of as phases.
 * </p>
 * 
 * <h3>Orchestration</h3>
 * 
 * <p>
 * You can launch or destroy clusters using an instance of
 * {@link org.apache.whirr.ClusterController}.
 * </p>
 * 
 * <p>
 * Whirr {@link org.apache.whirr.ClusterController#launchCluster(ClusterSpec) launches a cluster} by running the bootstrap action,
 * followed by the configure/install, and start actions. For each of these actions Whirr follows these rules:
 * </p>
 * 
 * <ol>
 * <li>Instance templates (machine sets) are acted on independently.</li>
 * <li>For each instance template (or role set) Whirr will call the cluster action handlers
 * for each role in the template.</li>
 * <li>Actions are performed in phases where each action is run concurrently on all machine sets and must finish before
 * the next action is performed.</li>
 * </ol>
 * 
 * <p>
 * The first rule implies that you can't rely on one template being processed
 * first. In fact, Whirr will process them all in parallel. So to transfer
 * information from one role to another (in a different template) you should use
 * different actions. For example, use the configure phase to get information
 * about another role that was set in its bootstrap phase.
 * </p>
 * 
 * <p>
 * The second rule implies that a cluster action handler for a given role
 * will be run more than once if it appears in more than one template.
 * </p>
 * <p>
 * The third rule defines a natural synchronization barrier for information to be passed consistently from
 * one action/phase to the next.
 * </p>
 *
 * <p>
 * Waves are a way to divide role sets so that a non-intersecting subset can go through the actions
 * (bootstrap, configure/install, start) before the next subset of roles does. For example, in the first wave
 * (example: whirr.instance-templates.0=1 zookeeper),
 * a zookeeper service can be fully running before another service is configured/installed in a later wave
 * (example: whirr.instance-templates.1=myservice). This is essential when
 * services of the first wave must be running in order to configure the later wave(s). As a special case,
 * only wave 0 will perform bootstrap so that machine provisioning occurs once.
 * When waves are used, the property whirr.instance-templates (no numeric suffix) should not be defined.
 * </p>
 * 
 * <p>
 * A cluster is {@link org.apache.whirr.ClusterController#destroyCluster(ClusterSpec) destroyed} by running the destroy action.
 * </p>
 * 
 * <h3>Writing a New Service</h3>
 * 
 * <p>
 * For each role in a service you must write a
 * {@link org.apache.whirr.service.ClusterActionHandler}, which allows you to
 * run code at various points in the lifecycle. For example, you can specify a
 * script that must be run on bootstrap, and another at configuration time.
 * </p>
 * 
 * <p>
 * Roles for a service are discovered using Java's service-provider loading
 * facility defined by {@link java.util.ServiceLoader}. It's very easy to
 * register your service. Simply create a file with the following path (assuming
 * a Maven directory structure):
 * </p>
 * 
 * <p>
 * <i>src/main/resources/META-INF/services/org.apache.whirr.service.ClusterActionHandler</i>
 * </p>
 * 
 * <p>
 * Then for each {@link org.apache.whirr.service.ClusterActionHandler} 
 * implementation add its fully qualified name as a line in the file:
 * </p>
 * 
 * <pre>
 * org.example.MyClusterActionHandlerForRoleA
 * org.example.MyClusterActionHandlerForRoleB
 * </pre>
 * 
 * <p>
 * If your service is not a part of Whirr, then you can install it by first
 * installing Whirr, then dropping the JAR file for your service into
 * Whirr's <i>lib</i> directory.
 * </p>
 */
package org.apache.whirr.service;
