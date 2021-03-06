/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.tools.chromeinspector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import com.oracle.truffle.api.InstrumentInfo;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import com.oracle.truffle.tools.chromeinspector.commands.Params;
import com.oracle.truffle.tools.chromeinspector.domains.ProfilerDomain;
import com.oracle.truffle.tools.chromeinspector.instrument.Enabler;
import com.oracle.truffle.tools.chromeinspector.instrument.TypeProfileInstrument;
import com.oracle.truffle.tools.chromeinspector.server.ConnectionWatcher;
import com.oracle.truffle.tools.chromeinspector.types.CoverageRange;
import com.oracle.truffle.tools.chromeinspector.types.FunctionCoverage;
import com.oracle.truffle.tools.chromeinspector.types.Profile;
import com.oracle.truffle.tools.chromeinspector.types.ProfileNode;
import com.oracle.truffle.tools.chromeinspector.types.RuntimeCallFrame;
import com.oracle.truffle.tools.chromeinspector.types.Script;
import com.oracle.truffle.tools.chromeinspector.types.ScriptCoverage;
import com.oracle.truffle.tools.chromeinspector.types.ScriptTypeProfile;
import com.oracle.truffle.tools.chromeinspector.types.TypeObject;
import com.oracle.truffle.tools.chromeinspector.types.TypeProfileEntry;

import com.oracle.truffle.tools.profiler.CPUSampler;
import com.oracle.truffle.tools.profiler.CPUTracer;
import com.oracle.truffle.tools.profiler.ProfilerNode;
import com.oracle.truffle.tools.profiler.impl.CPUSamplerInstrument;
import com.oracle.truffle.tools.profiler.impl.CPUTracerInstrument;

public final class TruffleProfiler extends ProfilerDomain {

    private CPUSampler sampler;
    private CPUTracer tracer;
    private TypeHandler typeHandler;
    private ScriptsHandler slh;
    private long startTimestamp;
    private boolean oldGatherSelfHitTimes;

    private final TruffleExecutionContext context;
    private final ConnectionWatcher connectionWatcher;

    public TruffleProfiler(TruffleExecutionContext context, ConnectionWatcher connectionWatcher) {
        this.context = context;
        this.connectionWatcher = connectionWatcher;
    }

    private void doEnable() {
        slh = context.getScriptsHandler();
        sampler = context.getEnv().lookup(context.getEnv().getInstruments().get(CPUSamplerInstrument.ID), CPUSampler.class);
        tracer = context.getEnv().lookup(context.getEnv().getInstruments().get(CPUTracerInstrument.ID), CPUTracer.class);
        InstrumentInfo instrumentInfo = context.getEnv().getInstruments().get(TypeProfileInstrument.ID);
        context.getEnv().lookup(instrumentInfo, Enabler.class).enable();
        typeHandler = context.getEnv().lookup(instrumentInfo, TypeHandler.Provider.class).getTypeHandler();
    }

    @Override
    public void enable() {
        if (slh == null) {
            doEnable();
        }
    }

    @Override
    public void disable() {
        if (slh != null) {
            context.releaseScriptsHandler();
            slh = null;
            sampler = null;
            tracer = null;
            typeHandler = null;
            context.getEnv().lookup(context.getEnv().getInstruments().get(TypeProfileInstrument.ID), Enabler.class).disable();
        }
    }

    @Override
    public void setSamplingInterval(long interval) {
        sampler.setPeriod(Math.max(1, TimeUnit.MICROSECONDS.toMillis(interval)));
    }

    @Override
    public void start() {
        connectionWatcher.setWaitForClose();
        synchronized (sampler) {
            oldGatherSelfHitTimes = sampler.isGatherSelfHitTimes();
            sampler.setGatherSelfHitTimes(true);
            sampler.setMode(CPUSampler.Mode.ROOTS);
            sampler.setFilter(SourceSectionFilter.newBuilder().includeInternal(false).build());
            sampler.setCollecting(true);
        }
        startTimestamp = System.currentTimeMillis();
    }

    @Override
    public Params stop() {
        long time = System.currentTimeMillis();
        synchronized (sampler) {
            sampler.setCollecting(false);
            sampler.setGatherSelfHitTimes(oldGatherSelfHitTimes);
            long idleHitCount = (time - startTimestamp) / sampler.getPeriod() - sampler.getSampleCount();
            Params profile = getProfile(sampler.getRootNodes(), idleHitCount, startTimestamp, time);
            sampler.clearData();
            return profile;
        }
    }

    @Override
    public void startPreciseCoverage(boolean callCount, boolean detailed) {
        connectionWatcher.setWaitForClose();
        synchronized (tracer) {
            tracer.setFilter(SourceSectionFilter.newBuilder().tagIs(detailed ? StandardTags.StatementTag.class : StandardTags.RootTag.class).includeInternal(false).build());
            tracer.setCollecting(true);
        }
    }

    @Override
    public void stopPreciseCoverage() {
        synchronized (tracer) {
            tracer.setCollecting(false);
            tracer.clearData();
        }
    }

    @Override
    public Params takePreciseCoverage() {
        synchronized (tracer) {
            Params coverage = getCoverage(tracer.getPayloads());
            tracer.clearData();
            return coverage;
        }
    }

    @Override
    public Params getBestEffortCoverage() {
        synchronized (tracer) {
            Params coverage = getCoverage(tracer.getPayloads());
            tracer.clearData();
            return coverage;
        }
    }

    @Override
    public void startTypeProfile() {
        connectionWatcher.setWaitForClose();
        typeHandler.start();
    }

    @Override
    public void stopTypeProfile() {
        synchronized (typeHandler) {
            typeHandler.stop();
            typeHandler.clearData();
        }
    }

    @Override
    public Params takeTypeProfile() {
        synchronized (typeHandler) {
            Params typeProfile = getTypeProfile(typeHandler.getSectionTypeProfiles());
            typeHandler.clearData();
            return typeProfile;
        }
    }

    private Params getCoverage(Collection<CPUTracer.Payload> payloads) {
        JSONObject json = new JSONObject();
        Map<Source, Map<String, Collection<CPUTracer.Payload>>> sourceToRoots = new HashMap<>();
        payloads.forEach(payload -> {
            Map<String, Collection<CPUTracer.Payload>> rootsToPayloads = sourceToRoots.computeIfAbsent(payload.getSourceSection().getSource(), s -> new LinkedHashMap<>());
            Collection<CPUTracer.Payload> pls = rootsToPayloads.computeIfAbsent(payload.getRootName(), t -> new LinkedList<>());
            pls.add(payload);
        });
        JSONArray result = new JSONArray();
        sourceToRoots.entrySet().stream().map(sourceEntry -> {
            List<FunctionCoverage> functions = new ArrayList<>();
            sourceEntry.getValue().entrySet().forEach(rootEntry -> {
                boolean isBlockCoverage = false;
                List<CoverageRange> ranges = new ArrayList<>();
                for (CPUTracer.Payload payload : rootEntry.getValue()) {
                    isBlockCoverage |= payload.getTags().contains(StandardTags.StatementTag.class);
                    ranges.add(new CoverageRange(payload.getSourceSection().getCharIndex(), payload.getSourceSection().getCharEndIndex(), payload.getCount()));
                }
                functions.add(new FunctionCoverage(rootEntry.getKey(), isBlockCoverage, ranges.toArray(new CoverageRange[ranges.size()])));
            });
            int scriptId = slh.getScriptId(sourceEntry.getKey());
            Script script = scriptId < 0 ? null : slh.getScript(scriptId);
            return new ScriptCoverage(script != null ? script.getId() : 0, script != null ? script.getUrl() : "", functions.toArray(new FunctionCoverage[functions.size()]));
        }).forEachOrdered(scriptCoverage -> {
            result.put(scriptCoverage.toJSON());
        });
        json.put("result", result);
        return new Params(json);
    }

    private Params getProfile(Collection<ProfilerNode<CPUSampler.Payload>> rootProfilerNodes, long idleHitCount, long startTime, long endTime) {
        Map<ProfilerNode<CPUSampler.Payload>, Integer> node2id = new HashMap<>();
        List<ProfileNode> nodes = new ArrayList<>();
        List<Profile.TimeLineItem> timeLine = new ArrayList<>();
        int[] counter = {1};
        ProfileNode root = new ProfileNode(counter[0]++, new RuntimeCallFrame("(root)", 0, "", 0, 0), idleHitCount);
        nodes.add(root);
        fillChildren(root, rootProfilerNodes, node2id, nodes, timeLine, counter);
        Collections.sort(timeLine, (item1, item2) -> Long.compare(item1.getTimestamp(), item2.getTimestamp()));
        JSONObject json = new JSONObject();
        json.put("profile", new Profile(nodes.toArray(new ProfileNode[nodes.size()]), startTime, endTime, timeLine.toArray(new Profile.TimeLineItem[timeLine.size()])).toJSON());
        return new Params(json);
    }

    private void fillChildren(ProfileNode node, Collection<ProfilerNode<CPUSampler.Payload>> childProfilerNodes, Map<ProfilerNode<CPUSampler.Payload>, Integer> node2id,
                    List<ProfileNode> nodes, List<Profile.TimeLineItem> timeLine, int[] counter) {
        childProfilerNodes.stream().map(childProfilerNode -> {
            Integer id = node2id.get(childProfilerNode);
            if (id == null) {
                id = counter[0]++;
                int scriptId = slh.getScriptId(childProfilerNode.getSourceSection().getSource());
                Script script = scriptId < 0 ? null : slh.getScript(scriptId);
                SourceSection sourceSection = childProfilerNode.getSourceSection();
                ProfileNode childNode = new ProfileNode(id, new RuntimeCallFrame(childProfilerNode.getRootName(), script != null ? script.getId() : 0, script != null ? script.getUrl() : "",
                                sourceSection.getStartLine(), sourceSection.getStartColumn()), childProfilerNode.getPayload().getSelfHitCount());
                nodes.add(childNode);
                for (Long timestamp : childProfilerNode.getPayload().getSelfHitTimes()) {
                    timeLine.add(new Profile.TimeLineItem(timestamp, id));
                }
                node2id.put(childProfilerNode, id);
                fillChildren(childNode, childProfilerNode.getChildren(), node2id, nodes, timeLine, counter);
            }
            return id;
        }).forEachOrdered(id -> {
            node.addChild(id);
        });
    }

    private Params getTypeProfile(Collection<TypeHandler.SectionTypeProfile> profiles) {
        JSONObject json = new JSONObject();
        Map<Source, Collection<TypeHandler.SectionTypeProfile>> sourceToProfiles = new HashMap<>();
        profiles.forEach(profile -> {
            Collection<TypeHandler.SectionTypeProfile> pfs = sourceToProfiles.computeIfAbsent(profile.getSourceSection().getSource(), t -> new LinkedList<>());
            pfs.add(profile);
        });
        JSONArray result = new JSONArray();
        sourceToProfiles.entrySet().forEach(entry -> {
            List<TypeProfileEntry> entries = new ArrayList<>();
            entry.getValue().forEach(sectionProfile -> {
                List<TypeObject> types = new ArrayList<>();
                sectionProfile.getTypes().forEach(type -> {
                    types.add(new TypeObject(type));
                });
                if (!types.isEmpty()) {
                    entries.add(new TypeProfileEntry(sectionProfile.getSourceSection().getCharEndIndex(), types.toArray(new TypeObject[types.size()])));
                }
            });
            int scriptId = slh.getScriptId(entry.getKey());
            Script script = scriptId < 0 ? null : slh.getScript(scriptId);
            result.put(new ScriptTypeProfile(script != null ? script.getId() : 0, script != null ? script.getUrl() : "", entries.toArray(new TypeProfileEntry[entries.size()])).toJSON());
        });
        json.put("result", result);
        return new Params(json);
    }
}
