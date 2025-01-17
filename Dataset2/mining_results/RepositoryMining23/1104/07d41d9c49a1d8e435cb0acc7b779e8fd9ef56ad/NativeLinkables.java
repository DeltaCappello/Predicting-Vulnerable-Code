/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.util.Map;

public class NativeLinkables {

  private NativeLinkables() {}

  /**
   * Find all {@link NativeLinkable} transitive roots reachable from the given {@link BuildRule}s.
   *
   * @param from the starting set of {@link BuildRule}s to begin the search from.
   * @param traverse a {@link Predicate} determining acceptable dependencies to traverse when
   *                 searching for {@link NativeLinkable}s.
   * @return all the roots found as a map from {@link BuildTarget} to {@link NativeLinkable}.
   */
  public static ImmutableMap<BuildTarget, NativeLinkable> getNativeLinkableRoots(
      Iterable<? extends BuildRule> from,
      final Predicate<Object> traverse) {

    final ImmutableMap.Builder<BuildTarget, NativeLinkable> nativeLinkables =
        ImmutableMap.builder();
    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(from) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) {

            // If this is `NativeLinkable`, we've found a root so record the rule and terminate
            // the search.
            if (rule instanceof NativeLinkable) {
              NativeLinkable nativeLinkable = (NativeLinkable) rule;
              nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
              return ImmutableSet.of();
            }

            // Otherwise, make sure this rule is marked as traversable before following it's deps.
            if (traverse.apply(rule)) {
              return rule.getDeps();
            }

            return ImmutableSet.of();
          }
        };
    visitor.start();

    return nativeLinkables.build();
  }

  public static ImmutableMap<BuildTarget, NativeLinkable> getNativeLinkables(
      final CxxPlatform cxxPlatform,
      Iterable<? extends NativeLinkable> inputs,
      final Linker.LinkableDepType linkStyle) {

    final Map<BuildTarget, NativeLinkable> nativeLinkables = Maps.newHashMap();
    for (NativeLinkable nativeLinkable : inputs) {
      nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
    }

    final MutableDirectedGraph<BuildTarget> graph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(nativeLinkables.keySet()) {
          @Override
          public ImmutableSet<BuildTarget> visit(BuildTarget target) {
            NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
            graph.addNode(target);

            // We always traverse a rule's exported native linkables.
            Iterable<? extends NativeLinkable> nativeLinkableDeps =
                nativeLinkable.getNativeLinkableExportedDeps(cxxPlatform);

            // If we're linking this dependency statically, we also need to traverse its deps.
            if (linkStyle != Linker.LinkableDepType.SHARED ||
                nativeLinkable.getPreferredLinkage(cxxPlatform) == NativeLinkable.Linkage.STATIC) {
              nativeLinkableDeps =
                  Iterables.concat(
                      nativeLinkableDeps,
                      nativeLinkable.getNativeLinkableDeps(cxxPlatform));
            }

            // Process all the traversable deps.
            ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
            for (NativeLinkable dep : nativeLinkableDeps) {
              BuildTarget depTarget = dep.getBuildTarget();
              graph.addEdge(target, depTarget);
              deps.add(depTarget);
              nativeLinkables.put(depTarget, dep);
            }
            return deps.build();
          }
        };
    visitor.start();

    // Topologically sort the rules.
    Iterable<BuildTarget> ordered =
        TopologicalSort.sort(graph, Predicates.<BuildTarget>alwaysTrue()).reverse();

    // Return a map of of the results.
    ImmutableMap.Builder<BuildTarget, NativeLinkable> result = ImmutableMap.builder();
    for (BuildTarget target : ordered) {
      result.put(target, nativeLinkables.get(target));
    }
    return result.build();
  }

  public static Function<NativeLinkable, NativeLinkableInput> getNativeLinkableInputFunction(
      final TargetGraph targetGraph,
      final CxxPlatform cxxPlatform,
      final Linker.LinkableDepType linkStyle) {
    return new Function<NativeLinkable, NativeLinkableInput>() {
      @Override
      public NativeLinkableInput apply(NativeLinkable nativeLinkable) {
        NativeLinkable.Linkage link = nativeLinkable.getPreferredLinkage(cxxPlatform);
        return nativeLinkable.getNativeLinkableInput(
            targetGraph,
            cxxPlatform,
            link == NativeLinkable.Linkage.STATIC && linkStyle == Linker.LinkableDepType.SHARED ?
                Linker.LinkableDepType.STATIC_PIC :
                linkStyle);
      }
    };
  }

  /**
   * Collect up and merge all {@link com.facebook.buck.cxx.NativeLinkableInput} objects from
   * transitively traversing all unbroken dependency chains of
   * {@link com.facebook.buck.cxx.NativeLinkable} objects found via the passed in
   * {@link com.facebook.buck.rules.BuildRule} roots.
   */
  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Linker.LinkableDepType depType,
      Predicate<Object> traverse) {

    // Get the topologically sorted native linkables.
    ImmutableMap<BuildTarget, NativeLinkable> roots = getNativeLinkableRoots(inputs, traverse);
    ImmutableMap<BuildTarget, NativeLinkable> nativeLinkables =
        getNativeLinkables(cxxPlatform, roots.values(), depType);
    return NativeLinkableInput.concat(
        FluentIterable.from(nativeLinkables.values())
            .transform(getNativeLinkableInputFunction(targetGraph, cxxPlatform, depType)));
  }

  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Linker.LinkableDepType depType) {
    return getTransitiveNativeLinkableInput(
        targetGraph,
        cxxPlatform,
        inputs,
        depType,
        Predicates.instanceOf(NativeLinkable.class));
  }

  public static ImmutableMap<BuildTarget, NativeLinkable> getTransitiveNativeLinkables(
      final CxxPlatform cxxPlatform,
      Iterable<? extends NativeLinkable> inputs) {

    final Map<BuildTarget, NativeLinkable> nativeLinkables = Maps.newHashMap();
    for (NativeLinkable nativeLinkable : inputs) {
      nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
    }

    final MutableDirectedGraph<BuildTarget> graph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(nativeLinkables.keySet()) {
          @Override
          public ImmutableSet<BuildTarget> visit(BuildTarget target) {
            NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
            graph.addNode(target);
            ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
            for (NativeLinkable dep :
                 Iterables.concat(
                     nativeLinkable.getNativeLinkableDeps(cxxPlatform),
                     nativeLinkable.getNativeLinkableExportedDeps(cxxPlatform))) {
              BuildTarget depTarget = dep.getBuildTarget();
              graph.addEdge(target, depTarget);
              deps.add(depTarget);
              nativeLinkables.put(depTarget, dep);
            }
            return deps.build();
          }
        };
    visitor.start();

    return ImmutableMap.copyOf(nativeLinkables);
  }

  /**
   * Collect all the shared libraries generated by {@link NativeLinkable}s found by transitively
   * traversing all unbroken dependency chains of {@link com.facebook.buck.cxx.NativeLinkable}
   * objects found via the passed in {@link com.facebook.buck.rules.BuildRule} roots.
   *
   * @return a mapping of library name to the library {@link SourcePath}.
   */
  public static ImmutableSortedMap<String, SourcePath> getTransitiveSharedLibraries(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Predicate<Object> traverse) {

    ImmutableMap<BuildTarget, NativeLinkable> roots = getNativeLinkableRoots(inputs, traverse);
    ImmutableMap<BuildTarget, NativeLinkable> nativeLinkables =
        getTransitiveNativeLinkables(cxxPlatform, roots.values());

    ImmutableSortedMap.Builder<String, SourcePath> libraries = ImmutableSortedMap.naturalOrder();
    for (NativeLinkable nativeLinkable : nativeLinkables.values()) {
      NativeLinkable.Linkage linkage = nativeLinkable.getPreferredLinkage(cxxPlatform);
      if (linkage != NativeLinkable.Linkage.STATIC) {
        libraries.putAll(nativeLinkable.getSharedLibraries(targetGraph, cxxPlatform));
      }
    }
    return libraries.build();
  }

}
