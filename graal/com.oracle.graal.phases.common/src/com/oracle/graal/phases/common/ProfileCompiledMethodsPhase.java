/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.phases.common;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import com.oracle.graal.compiler.common.cfg.Loop;
import com.oracle.graal.graph.Node;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.AbstractEndNode;
import com.oracle.graal.nodes.AbstractMergeNode;
import com.oracle.graal.nodes.CallTargetNode;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.DeoptimizeNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.Invoke;
import com.oracle.graal.nodes.LogicNode;
import com.oracle.graal.nodes.ParameterNode;
import com.oracle.graal.nodes.ReturnNode;
import com.oracle.graal.nodes.SafepointNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.ScheduleResult;
import com.oracle.graal.nodes.UnwindNode;
import com.oracle.graal.nodes.VirtualState;
import com.oracle.graal.nodes.calc.BinaryNode;
import com.oracle.graal.nodes.calc.ConvertNode;
import com.oracle.graal.nodes.calc.DivNode;
import com.oracle.graal.nodes.calc.IntegerDivNode;
import com.oracle.graal.nodes.calc.IntegerRemNode;
import com.oracle.graal.nodes.calc.MulNode;
import com.oracle.graal.nodes.calc.NotNode;
import com.oracle.graal.nodes.calc.ReinterpretNode;
import com.oracle.graal.nodes.calc.RemNode;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.cfg.ControlFlowGraph;
import com.oracle.graal.nodes.debug.DynamicCounterNode;
import com.oracle.graal.nodes.extended.SwitchNode;
import com.oracle.graal.nodes.java.AbstractNewObjectNode;
import com.oracle.graal.nodes.java.AccessMonitorNode;
import com.oracle.graal.nodes.java.MonitorIdNode;
import com.oracle.graal.nodes.memory.Access;
import com.oracle.graal.nodes.spi.ValueProxy;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;
import com.oracle.graal.phases.Phase;
import com.oracle.graal.phases.schedule.SchedulePhase;

/**
 * This phase add counters for the dynamically executed number of nodes. Incrementing the counter
 * for each node would be too costly, so this phase takes the compromise that it trusts split
 * probabilities, but not loop frequencies. This means that it will insert counters at the start of
 * a method and at each loop header.
 *
 * A schedule is created so that floating nodes can also be taken into account. The weight of a node
 * is determined heuristically in the {@link ProfileCompiledMethodsPhase#getNodeWeight(Node)}
 * method.
 *
 * Additionally, there's a second counter that's only increased for code sections without invokes.
 */
public class ProfileCompiledMethodsPhase extends Phase {

    private static final String GROUP_NAME = "~profiled weight";
    private static final String GROUP_NAME_WITHOUT = "~profiled weight (invoke-free sections)";
    private static final String GROUP_NAME_INVOKES = "~profiled invokes";

    private static final boolean WITH_SECTION_HEADER = Boolean.parseBoolean(System.getProperty("ProfileCompiledMethodsPhase.WITH_SECTION_HEADER", "false"));
    private static final boolean WITH_INVOKE_FREE_SECTIONS = Boolean.parseBoolean(System.getProperty("ProfileCompiledMethodsPhase.WITH_FREE_SECTIONS", "false"));
    private static final boolean WITH_INVOKES = Boolean.parseBoolean(System.getProperty("ProfileCompiledMethodsPhase.WITH_INVOKES", "true"));

    @Override
    protected void run(StructuredGraph graph) {
        SchedulePhase schedule = new SchedulePhase();
        schedule.apply(graph, false);

        ControlFlowGraph cfg = ControlFlowGraph.compute(graph, true, true, true, true);
        for (Loop<Block> loop : cfg.getLoops()) {
            double loopProbability = cfg.blockFor(loop.getHeader().getBeginNode()).probability();
            if (loopProbability > (1D / Integer.MAX_VALUE)) {
                addSectionCounters(loop.getHeader().getBeginNode(), loop.getBlocks(), loop.getChildren(), graph.getLastSchedule(), cfg);
            }
        }
        // don't put the counter increase directly after the start (problems with OSR)
        FixedWithNextNode current = graph.start();
        while (current.next() instanceof FixedWithNextNode) {
            current = (FixedWithNextNode) current.next();
        }
        addSectionCounters(current, Arrays.asList(cfg.getBlocks()), cfg.getLoops(), graph.getLastSchedule(), cfg);

        if (WITH_INVOKES) {
            for (Node node : graph.getNodes()) {
                if (node instanceof Invoke) {
                    Invoke invoke = (Invoke) node;
                    DynamicCounterNode.addCounterBefore(GROUP_NAME_INVOKES, invoke.callTarget().targetName(), 1, true, invoke.asNode());

                }
            }
        }
    }

    private static void addSectionCounters(FixedWithNextNode start, Collection<Block> sectionBlocks, Collection<Loop<Block>> childLoops, ScheduleResult schedule, ControlFlowGraph cfg) {
        HashSet<Block> blocks = new HashSet<>(sectionBlocks);
        for (Loop<?> loop : childLoops) {
            blocks.removeAll(loop.getBlocks());
        }
        double weight = getSectionWeight(schedule, blocks) / cfg.blockFor(start).probability();
        DynamicCounterNode.addCounterBefore(GROUP_NAME, sectionHead(start), (long) weight, true, start.next());
        if (WITH_INVOKE_FREE_SECTIONS && !hasInvoke(blocks)) {
            DynamicCounterNode.addCounterBefore(GROUP_NAME_WITHOUT, sectionHead(start), (long) weight, true, start.next());
        }
    }

    private static String sectionHead(Node node) {
        if (WITH_SECTION_HEADER) {
            return node.toString();
        } else {
            return "";
        }
    }

    private static double getSectionWeight(ScheduleResult schedule, Collection<Block> blocks) {
        double count = 0;
        for (Block block : blocks) {
            double blockProbability = block.probability();
            for (Node node : schedule.getBlockToNodesMap().get(block)) {
                count += blockProbability * getNodeWeight(node);
            }
        }
        return count;
    }

    private static double getNodeWeight(Node node) {
        if (node instanceof AbstractMergeNode) {
            return ((AbstractMergeNode) node).phiPredecessorCount();
        } else if (node instanceof AbstractBeginNode || node instanceof AbstractEndNode || node instanceof MonitorIdNode || node instanceof ConstantNode || node instanceof ParameterNode ||
                        node instanceof CallTargetNode || node instanceof ValueProxy || node instanceof VirtualObjectNode || node instanceof ReinterpretNode) {
            return 0;
        } else if (node instanceof AccessMonitorNode) {
            return 10;
        } else if (node instanceof Access) {
            return 2;
        } else if (node instanceof LogicNode || node instanceof ConvertNode || node instanceof BinaryNode || node instanceof NotNode) {
            return 1;
        } else if (node instanceof IntegerDivNode || node instanceof DivNode || node instanceof IntegerRemNode || node instanceof RemNode) {
            return 10;
        } else if (node instanceof MulNode) {
            return 3;
        } else if (node instanceof Invoke) {
            return 5;
        } else if (node instanceof IfNode || node instanceof SafepointNode) {
            return 1;
        } else if (node instanceof SwitchNode) {
            return node.successors().count();
        } else if (node instanceof ReturnNode || node instanceof UnwindNode || node instanceof DeoptimizeNode) {
            return node.successors().count();
        } else if (node instanceof AbstractNewObjectNode) {
            return 10;
        } else if (node instanceof VirtualState) {
            return 0;
        }
        return 2;
    }

    private static boolean hasInvoke(Collection<Block> blocks) {
        boolean hasInvoke = false;
        for (Block block : blocks) {
            for (FixedNode fixed : block.getNodes()) {
                if (fixed instanceof Invoke) {
                    hasInvoke = true;
                }
            }
        }
        return hasInvoke;
    }
}
