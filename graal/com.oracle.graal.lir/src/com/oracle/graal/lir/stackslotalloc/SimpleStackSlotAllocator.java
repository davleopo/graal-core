/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.stackslotalloc;

import static com.oracle.graal.lir.LIRValueUtil.asVirtualStackSlot;
import static com.oracle.graal.lir.LIRValueUtil.isVirtualStackSlot;
import static com.oracle.graal.lir.stackslotalloc.StackSlotAllocatorUtil.allocatedFramesize;
import static com.oracle.graal.lir.stackslotalloc.StackSlotAllocatorUtil.allocatedSlots;
import static com.oracle.graal.lir.stackslotalloc.StackSlotAllocatorUtil.virtualFramesize;

import java.util.List;

import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.common.JVMCIError;

import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.Indent;
import com.oracle.graal.lir.LIRInstruction;
import com.oracle.graal.lir.ValueProcedure;
import com.oracle.graal.lir.VirtualStackSlot;
import com.oracle.graal.lir.framemap.FrameMapBuilderTool;
import com.oracle.graal.lir.framemap.SimpleVirtualStackSlot;
import com.oracle.graal.lir.framemap.VirtualStackSlotRange;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.phases.AllocationPhase;

public class SimpleStackSlotAllocator extends AllocationPhase {

    @Override
    protected <B extends AbstractBlockBase<B>> void run(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder, AllocationContext context) {
        allocateStackSlots((FrameMapBuilderTool) lirGenRes.getFrameMapBuilder(), lirGenRes);
        lirGenRes.buildFrameMap();
    }

    public void allocateStackSlots(FrameMapBuilderTool builder, LIRGenerationResult res) {
        StackSlot[] mapping = new StackSlot[builder.getNumberOfStackSlots()];
        long currentFrameSize = allocatedFramesize.isEnabled() ? builder.getFrameMap().currentFrameSize() : 0;
        for (VirtualStackSlot virtualSlot : builder.getStackSlots()) {
            final StackSlot slot;
            if (virtualSlot instanceof SimpleVirtualStackSlot) {
                slot = mapSimpleVirtualStackSlot(builder, (SimpleVirtualStackSlot) virtualSlot);
                virtualFramesize.add(builder.getFrameMap().spillSlotSize(virtualSlot.getLIRKind()));
            } else if (virtualSlot instanceof VirtualStackSlotRange) {
                VirtualStackSlotRange slotRange = (VirtualStackSlotRange) virtualSlot;
                slot = mapVirtualStackSlotRange(builder, slotRange);
                virtualFramesize.add(builder.getFrameMap().spillSlotRangeSize(slotRange.getSlots()));
            } else {
                throw JVMCIError.shouldNotReachHere("Unknown VirtualStackSlot: " + virtualSlot);
            }
            allocatedSlots.increment();
            mapping[virtualSlot.getId()] = slot;
        }
        updateLIR(res, mapping);
        if (allocatedFramesize.isEnabled()) {
            allocatedFramesize.add(builder.getFrameMap().currentFrameSize() - currentFrameSize);
        }
    }

    @SuppressWarnings("try")
    protected void updateLIR(LIRGenerationResult res, StackSlot[] mapping) {
        try (Scope scope = Debug.scope("StackSlotMappingLIR")) {
            ValueProcedure updateProc = (value, mode, flags) -> {
                if (isVirtualStackSlot(value)) {
                    StackSlot stackSlot = mapping[asVirtualStackSlot(value).getId()];
                    Debug.log("map %s -> %s", value, stackSlot);
                    return stackSlot;
                }
                return value;
            };
            for (AbstractBlockBase<?> block : res.getLIR().getControlFlowGraph().getBlocks()) {
                try (Indent indent0 = Debug.logAndIndent("block: %s", block)) {
                    for (LIRInstruction inst : res.getLIR().getLIRforBlock(block)) {
                        try (Indent indent1 = Debug.logAndIndent("Inst: %d: %s", inst.id(), inst)) {
                            inst.forEachAlive(updateProc);
                            inst.forEachInput(updateProc);
                            inst.forEachOutput(updateProc);
                            inst.forEachTemp(updateProc);
                            inst.forEachState(updateProc);
                        }
                    }
                }
            }
        }
    }

    protected StackSlot mapSimpleVirtualStackSlot(FrameMapBuilderTool builder, SimpleVirtualStackSlot virtualStackSlot) {
        return builder.getFrameMap().allocateSpillSlot(virtualStackSlot.getLIRKind());
    }

    protected StackSlot mapVirtualStackSlotRange(FrameMapBuilderTool builder, VirtualStackSlotRange virtualStackSlot) {
        return builder.getFrameMap().allocateStackSlots(virtualStackSlot.getSlots(), virtualStackSlot.getObjects());
    }
}
