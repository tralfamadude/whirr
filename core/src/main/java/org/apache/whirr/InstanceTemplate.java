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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newLinkedHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.lang.StringUtils;
import org.jclouds.compute.domain.TemplateBuilderSpec;
import org.jclouds.javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.slf4j.LoggerFactory;

// Terminology: be consistent with core/src/main/java/org/apache/whirr/service/package-info.java

/**
 * This class describes the role sets of machine instances that should be in the cluster.
 * This is done by specifying the number of machine instances that fill each role set.
 * Each instance of this class specifies one machine set that files a role
 * See org.apache.whirr.service.package-info for terminology.
 */
public class InstanceTemplate {
  private static final org.slf4j.Logger LOG = LoggerFactory
        .getLogger(InstanceTemplate.class);
  public static final int MAX_WAVES = 10;

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private int numberOfInstances = -1;
    private int minNumberOfInstances = -1;
    private TemplateBuilderSpec template;
    private Float awsEc2SpotPrice;
    private ArrayList<LinkedHashSet<String>> rolesByWave = new ArrayList<LinkedHashSet<String>>();

    public Builder() {
      rolesByWave.add(new LinkedHashSet<String>());
    }

    public Builder numberOfInstance(int numberOfInstances) {
      if (this.numberOfInstances > 1 && this.numberOfInstances != numberOfInstances) {
        throw new IllegalArgumentException("Inconsistent number of instances specified in wave, "
        + this.numberOfInstances + " versus " + numberOfInstances);
      }
      this.numberOfInstances = numberOfInstances;
      return this;
    }

    public Builder minNumberOfInstances(int minNumberOfInstances) {
      if (minNumberOfInstances >= 0) {
        this.minNumberOfInstances = minNumberOfInstances;
      }
      return this;
    }

    public Builder template(@Nullable TemplateBuilderSpec template) {
      this.template = template;
      return this;
    }
    
    public Builder awsEc2SpotPrice(@Nullable Float awsEc2SpotPrice) {
      this.awsEc2SpotPrice = awsEc2SpotPrice;
      return this;
    }

    private LinkedHashSet<String> findCreateRoleSets(int wave, int setSize) {
      if (wave >= MAX_WAVES) {
        throw new IllegalArgumentException("wave value " + wave + " exceeds max " + MAX_WAVES);
      }
      if (rolesByWave.size()-1 < wave) { // expand array list
        final int n = wave + 1 - rolesByWave.size();
        for (int i=0; i<n; i++) {
          LinkedHashSet<String> hset = new LinkedHashSet<String>(setSize);
          rolesByWave.add(hset);
        }
      }
      return rolesByWave.get(wave);
    }

    public Builder roles(String... roles) {
      final LinkedHashSet<String> hset = findCreateRoleSets(0, roles.length);
      for (String r : roles) {
        hset.add(r);
      }
      return this;
    }

    /**
     * Set (accumulate) the roles for the InstanceTemplate to be built.
     * Updates var rolesByWave.
     * @param wave 0 for first or only wave, max of 9.
     * @param roles var args of individual roles, not role groups with + sep.
     * @return Builder for nice expressions.
     */
    public Builder roles(int wave, String... roles) {
      final LinkedHashSet<String> hset = findCreateRoleSets(wave, roles.length);
      for (String r : roles) {
        hset.add(r);
      }
      return this;
    }

    public Builder roles(Set<String> roles) {
      final LinkedHashSet<String> hset = findCreateRoleSets(0, roles.size());
      hset.addAll(roles);
      return this;
    }

    public Builder roles(int wave, Set<String> roles) {
      final LinkedHashSet<String> hset = findCreateRoleSets(wave, roles.size());
      hset.addAll(roles);
      return this;
    }

    public InstanceTemplate build() {
      InstanceTemplate ret;
      if (minNumberOfInstances == -1 || minNumberOfInstances > numberOfInstances) {
        minNumberOfInstances = numberOfInstances;
      }
      ret = new InstanceTemplate(numberOfInstances, minNumberOfInstances, rolesByWave,
        template, awsEc2SpotPrice);
      LOG.info(ret.toString());
      return ret;
    }
  }

  final private int numberOfInstances;
  final private int minNumberOfInstances;  // some instances may fail, at least a minimum number is required
  final private TemplateBuilderSpec template;
  final private Float awsEc2SpotPrice;
  // roles grouped by wave, if waves of instance-templates not used, then values are in [0]
  final private ArrayList<LinkedHashSet<String>> roles;
  // all the roles from all the waves for this machine set
  final private Set<String> allRoles;
  final private boolean hasWaves;

  private InstanceTemplate(int numberOfInstances, int minNumberOfInstances,
                           ArrayList<LinkedHashSet<String>> listOfRoleSet, TemplateBuilderSpec template,
                           Float awsEc2SpotPrice) {
    this.allRoles = new LinkedHashSet<String>();
    for (LinkedHashSet<String> rset : listOfRoleSet) {
      for (String role : rset) {
        allRoles.add(role);
        checkArgument(!StringUtils.contains(role, " "),
            "Role '%s' may not contain space characters.", role);
      }
    }

    this.numberOfInstances = numberOfInstances;
    this.minNumberOfInstances = minNumberOfInstances;
    this.template = template;
    this.awsEc2SpotPrice = awsEc2SpotPrice;
    this.roles = listOfRoleSet;
    if (listOfRoleSet.size() > 1) {
      hasWaves = true;
    } else {
      hasWaves = false;
    }
  }

  /** Get set of all roles.
   * @return set of roles
   */
  public Set<String> getRoles() {
    return allRoles;
  }

  /** Get set of roles.
   * @param wave wave number [0,9] or -1 to indicate all.
   * @return set of roles
   */
  public Set<String> getRoles(int wave) {
    if (wave == -1) {
      return getRoles();
    } else {
      return roles.get(wave);
    }
  }

  public int getWaveCount() {
    return roles.size();
  }

  public int getNumberOfInstances() {
    return numberOfInstances; // goal
  }

  public int getMinNumberOfInstances() {
    return minNumberOfInstances;
  }

  @Nullable
  public TemplateBuilderSpec getTemplate() {
    return template;
  }

  @Nullable
  public Float getAwsEc2SpotPrice() {
    return awsEc2SpotPrice;
  }

  public boolean equals(Object o) {
    if (o instanceof InstanceTemplate) {
      InstanceTemplate that = (InstanceTemplate) o;
      return numberOfInstances == that.numberOfInstances
        && minNumberOfInstances == that.minNumberOfInstances
        && Objects.equal(template, that.template)
        && awsEc2SpotPrice == that.awsEc2SpotPrice
        && Objects.equal(roles, that.roles);
    }
    return false;
  }

  public int hashCode() {
    return Objects.hashCode(numberOfInstances, minNumberOfInstances,
             template, awsEc2SpotPrice, roles);
  }

  public String toString() {
    final String rolesSummary;
    if (hasWaves) {
      StringBuilder sb = new StringBuilder(300);
      for (int j=0; j<getWaveCount(); j++) {
        sb.append("wave").append(j).append(" [").append(getRoles(j)).append("]  ");
      }
      rolesSummary = sb.toString();
    } else {
      rolesSummary = allRoles.toString();
    }
    return Objects.toStringHelper(this).omitNullValues()
      .add("numberOfInstances", numberOfInstances)
      .add("minNumberOfInstances", minNumberOfInstances)
      .add("template", template)
      .add("awsEc2SpotPrice", awsEc2SpotPrice)
      .add("roles", rolesSummary)
      .toString();
  }

  /**
   * Parse one or more number with role list like "2 r1+r2" into map entry (r1+r2,2).
   * @param strings one or more strings, each representing an instance template clause
   *                with like "2 r1+r2".
   * @return map of (string,string) of (role1+role2,number).
   */
  private static Map<String, String> parseIntoTemplateGroups(String... strings) {
    Set<String> roles = newLinkedHashSet(newArrayList(strings));
    Map<String, String> templates = Maps.newHashMap();
    for (String s : roles) {
      String[] parts = s.split(" ");
      checkArgument(parts.length == 2,
        "Invalid instance template syntax for '%s'. Does not match " +
          "'<number> <role1>+<role2>+<role3>...', e.g. '1 hadoop-namenode+hadoop-jobtracker'.", s);
      templates.put(parts[1], parts[0]);
    }
    return templates;
  }

  /** Parse instance templates.
   *
   * @param configuration
   * @return
   * @throws ConfigurationException
   */
  public static List<InstanceTemplate> parse(Configuration configuration)
      throws ConfigurationException {
    final String basePropKey = ClusterSpec.Property.INSTANCE_TEMPLATES.getConfigName();
    String[] instanceTemplateGroups = configuration.getStringArray(basePropKey);
    int templateGroupCount = 0;
    List<Builder> builderList = newArrayList();
    int firstWaveNumber = -1;

    if (instanceTemplateGroups.length == 0) {
      // look for instance-templates waves with .[0-9] suffix
      for (int iwave=0; iwave<MAX_WAVES; iwave++) {
        String propKey = basePropKey + "." + iwave;
        instanceTemplateGroups = configuration.getStringArray(propKey);
        if (instanceTemplateGroups.length > 0) {
          if (firstWaveNumber == -1) {
            firstWaveNumber = iwave;
            if (firstWaveNumber != 0) {
              // downstream init logic relies on first wave == 0
              throw new IllegalArgumentException("first wave of instance-template is " + propKey
                  + " but first wave must be " + basePropKey + ".0");
            }
          }
          if (templateGroupCount == 0) {
            templateGroupCount = instanceTemplateGroups.length;
          }
          // each wave must have same number of template groups
          if (instanceTemplateGroups.length != templateGroupCount) {
            // inconsistent template group count
            throw new IllegalArgumentException("inconsistent template group count " +
                templateGroupCount + " versus " +
                instanceTemplateGroups.length + " for property " +
                propKey);
          }
          parseAndBuild(configuration, instanceTemplateGroups, builderList, iwave);
        }
      }
    } else {
      templateGroupCount = instanceTemplateGroups.length;
      parseAndBuild(configuration, instanceTemplateGroups, builderList, 0);
      // verify that no-wave spec is not mixed with waves (usability)
      for (int i = 0; i < MAX_WAVES; i++) {
        String propKey = basePropKey + "." + i;
        instanceTemplateGroups = configuration.getStringArray(propKey);
        if (instanceTemplateGroups.length > 0) {
          throw new IllegalArgumentException("inconsistent instance-template properties, " +
              propKey + " cannot be defined if " +
              basePropKey + " is also defined ");
        }
      }
    }

    List<InstanceTemplate> templates = newArrayList();
    for (Builder b : builderList) {
      templates.add(b.build());
    }

    validateThatWeHaveNoOtherOverrides(templates, configuration);
    LOG.info("found " + templateGroupCount + " template groups in " + basePropKey + " settings, templates=" + templates);
    return templates;
  }

  /**
   * Parse whirr.instance-template values and hook up related minNumberOfInstances property.
   * @param configuration
   * @param instanceTemplates the template groups with number of instances as prefix, already split by comma.
   * @param builderList accumulated builders.
   * @param wave is 0 for "no wave" case.
   */
  private static void parseAndBuild(Configuration configuration, String[] instanceTemplates, List<Builder> builderList,
                                    int wave)
  {
    int j = -1;
    for (String s : instanceTemplates) {
      String[] parts = s.split(" ");
      j++;

      checkArgument(parts.length == 2, "Invalid instance template syntax for '%s'. Does not match " +
        "'<number> <role1>+<role2>+<role3>...', e.g. '1 hadoop-namenode+hadoop-jobtracker'.", s);

      int numberOfInstances = Integer.parseInt(parts[0]);
      if (numberOfInstances < 1) {
        String prop = ClusterSpec.Property.INSTANCE_TEMPLATES.getConfigName();
        throw new IllegalArgumentException("number of instances specified in " + prop + " must be >= 1");
      }
      String templateGroup = parts[1]; // role set
      Builder templateBuilder;
      if (wave == 0) { // wave==0 or "no wave" case
        templateBuilder = InstanceTemplate.builder()
            .numberOfInstance(numberOfInstances)
            .minNumberOfInstances(
                parseMinNumberOfInstances(configuration, templateGroup, numberOfInstances)
            );
        parseInstanceTemplateGroupOverrides(configuration, templateGroup, templateBuilder);
        templateBuilder.roles(wave, templateGroup.split("\\+"));
        builderList.add(templateBuilder);
      } else { // wave i where i>0
        // here we add roles to already created Builder objs for particular wave and check for min instances prop
        templateBuilder = builderList.get(j);
        templateBuilder.roles(wave, templateGroup.split("\\+"));
        templateBuilder.numberOfInstance(numberOfInstances)  // set numberOfInstances to check consistency
            .minNumberOfInstances(
                        parseMinNumberOfInstances(configuration, templateGroup, numberOfInstances)
                    );
      }
    }
  }

  private static void parseInstanceTemplateGroupOverrides(Configuration configuration, String templateGroup,
        Builder templateBuilder) {
    final List list = configuration.getList("whirr.templates." + templateGroup + ".template");
    if (list.size() > 0) {
      String specString = Joiner.on(',').join(list);
      templateBuilder.template(TemplateBuilderSpec.parse(specString));
    } else {
      // until TemplateBuilderSpec has type-safe builder
      ImmutableMap.Builder<String, String> templateParamsBuilder = ImmutableMap.<String, String> builder();
      for (String resource : ImmutableSet.of("image", "hardware")) {
        String key = String.format("whirr.templates.%s.%s-id", templateGroup, resource);
        if (configuration.getString(key) != null) {
          templateParamsBuilder.put(resource + "Id", configuration.getString(key));
        }
      }
      Map<String, String> templateParams = templateParamsBuilder.build();
      if (templateParams.size() > 0)
        templateBuilder.template(TemplateBuilderSpec.parse(Joiner.on(',').withKeyValueSeparator("=")
              .join(templateParams)));
    }
    templateBuilder.awsEc2SpotPrice(configuration.getFloat("whirr.templates." + templateGroup + ".aws-ec2-spot-price", null));
  }

  /**
   * @param configuration
   * @param templateGroup
   * @param numberOfInstances
   * @return a minimum number of instances for the given templateGroup or -1 if none found.
   */
  private static int parseMinNumberOfInstances(
    Configuration configuration, String templateGroup, int numberOfInstances
  ) {

    Map<String, String> maxPercentFailures = parseIntoTemplateGroups(configuration.getStringArray(
        ClusterSpec.Property.INSTANCE_TEMPLATES_MAX_PERCENT_FAILURES.getConfigName()));

    Map<String, String> minInstances = parseIntoTemplateGroups(configuration.getStringArray(
        ClusterSpec.Property.INSTANCE_TEMPLATES_MINIMUM_NUMBER_OF_INSTANCES.getConfigName()));

    int minNumberOfInstances = 0;
    String maxPercentFail = maxPercentFailures.get(templateGroup);
    String minNumberOfInst = minInstances.get(templateGroup);

    if (maxPercentFail == null  &&  minNumberOfInst == null) {
      return -1; // not specified
    }

    if (maxPercentFail != null) {
      // round up integer division (a + b -1) / b
      minNumberOfInstances = (Integer.parseInt(maxPercentFail) * numberOfInstances + 99) / 100;
    }

    if (minNumberOfInst != null) {
      int minExplicitlySet = Integer.parseInt(minNumberOfInst);
      if (minNumberOfInstances > 0) { // maximum between two minims
        minNumberOfInstances = Math.max(minNumberOfInstances, minExplicitlySet);
      } else {
        minNumberOfInstances = minExplicitlySet;
      }
    }

    if (minNumberOfInstances == 0 || minNumberOfInstances > numberOfInstances) {
      minNumberOfInstances = numberOfInstances;
    }

    return minNumberOfInstances;
  }

  /** throw exception with message if a template override did not match
   * a known template group.
   */
  private static void validateThatWeHaveNoOtherOverrides(
    List<InstanceTemplate> templates, Configuration configuration
  ) throws ConfigurationException {
    if (templates.size() == 0) {
      return;
    }
    int waveCount = templates.get(0).getWaveCount(); // count is same for all
    Set<String> groups = new LinkedHashSet<String>();

    for (int wave = 0; wave < waveCount; wave++) {
      groups.addAll(toRoleGroups(templates, wave));
    }
    Pattern pattern = Pattern.compile("^whirr\\.templates\\.([^.]+)\\..*$");
    Iterator iterator = configuration.getKeys("whirr.templates");

    while (iterator.hasNext()) {
      String key = String.class.cast(iterator.next());
      Matcher matcher = pattern.matcher(key);

      if (matcher.find() && !groups.contains(matcher.group(1))) {
        throw new ConfigurationException(String.format("'%s' is referencing a " +
          "template group not present in 'whirr.instance-templates'", key));
      }
    }
  }

  private static LinkedHashSet<String> toRoleGroups(List<InstanceTemplate> templates, final int wave)
  {
    return Sets.newLinkedHashSet(Iterables.transform(templates,
        new Function<InstanceTemplate, String>() {
          private final Joiner plusJoiner = Joiner.on("+");

          @Override
          public String apply(InstanceTemplate instanceTemplate)
          {
            return plusJoiner.join(instanceTemplate.getRoles(wave));
          }
        }));
  }
}
